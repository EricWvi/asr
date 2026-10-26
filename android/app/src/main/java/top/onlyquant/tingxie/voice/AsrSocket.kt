package top.onlyquant.tingxie.voice

import kotlinx.coroutines.CompletableDeferred
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.util.Timer
import kotlin.concurrent.schedule

/** How a recognition ended: all of it, or the sentences finished before it failed. */
sealed interface AsrOutcome {
    val text: String

    data class Ok(override val text: String) : AsrOutcome
    data class Failed(override val text: String, val message: String) : AsrOutcome
}

@Serializable
private data class ServerMessage(val type: String, val sentence: Int = 0, val text: String = "", val message: String = "")

private val json = Json { ignoreUnknownKeys = true }

/**
 * One recognition over /api/asr (internal/server/asr.go), as
 * frontend/src/voice/asr-socket.ts does it: PCM16 frames go out as they are
 * captured, results come back per sentence. Interim results repeat the whole
 * sentence and replace the previous one with the same ID.
 *
 * Callbacks arrive on OkHttp's thread; [onText] gets everything heard so far.
 */
class AsrSocket(http: OkHttpClient, url: String, private val onText: (String) -> Unit) {
    private val lock = Any()
    private val sentences = sortedMapOf<Int, Pair<String, Boolean>>()
    private val pending = mutableListOf<ByteString>()
    private var opened = false
    private var finishing = false
    private var settled = false
    private var timer: Timer? = null
    val outcome = CompletableDeferred<AsrOutcome>()

    private val ws: WebSocket = http.newWebSocket(Request.Builder().url(url).build(), object : WebSocketListener() {
        override fun onOpen(webSocket: WebSocket, response: Response) {
            synchronized(lock) {
                opened = true
                for (b in pending) webSocket.send(b)
                pending.clear()
                if (finishing) webSocket.send(FINISH)
            }
        }

        override fun onMessage(webSocket: WebSocket, text: String) {
            val m = runCatching { json.decodeFromString<ServerMessage>(text) }.getOrNull() ?: return
            receive(m)
        }

        override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = fail(if (opened) "识别中断" else "连不上语音识别服务")

        override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
            val msg = when {
                response?.code == 401 -> "登录已失效，请重新登录"
                opened -> "识别中断"
                else -> "连不上语音识别服务"
            }
            fail(msg)
        }
    })

    fun send(pcm: ByteArray) {
        synchronized(lock) {
            if (settled || finishing) return
            val b = pcm.toByteString()
            if (opened) ws.send(b) else pending.add(b)
        }
    }

    /** No more audio; [outcome] completes with the final results. */
    fun finish() {
        synchronized(lock) {
            if (settled || finishing) return
            finishing = true
            if (opened) ws.send(FINISH)
            // The server gives up 10 s after finish; this only covers a silent socket.
            timer = Timer(true).apply { schedule(FINISH_TIMEOUT) { fail("语音识别超时") } }
        }
    }

    /** Drops the recognition; [outcome] never completes. */
    fun abort() {
        synchronized(lock) {
            settled = true
            timer?.cancel()
            ws.cancel()
        }
    }

    private fun receive(m: ServerMessage) {
        val heard: String
        synchronized(lock) {
            if (settled) return
            when (m.type) {
                "partial", "final" -> {
                    sentences[m.sentence] = m.text to (m.type == "final")
                    heard = text(finalOnly = false)
                }
                "done" -> {
                    settle(AsrOutcome.Ok(text(finalOnly = true)))
                    return
                }
                "error" -> {
                    failLocked(m.message.ifEmpty { "语音识别失败" })
                    return
                }
                else -> return
            }
        }
        onText(heard)
    }

    private fun text(finalOnly: Boolean) =
        sentences.values.filter { !finalOnly || it.second }.joinToString("") { it.first }

    private fun fail(message: String) {
        synchronized(lock) { failLocked(message) }
    }

    private fun failLocked(message: String) {
        if (settled) return
        val unfinished = sentences.values.any { !it.second && it.first.isNotEmpty() }
        settle(AsrOutcome.Failed(text(finalOnly = true), if (unfinished) "识别中断，最后一句没有识别完" else message))
    }

    private fun settle(o: AsrOutcome) {
        settled = true
        timer?.cancel()
        ws.close(1000, null)
        outcome.complete(o)
    }

    companion object {
        private const val FINISH = """{"type":"finish"}"""
        private const val FINISH_TIMEOUT = 15_000L

        /** ws(s)://…/api/asr for a server at http(s)://… */
        fun url(server: String) = server.replaceFirst(Regex("^http"), "ws") + "/api/asr"
    }
}
