package top.onlyquant.tingxie.voice

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient

/** Where dictated text goes: a document, or the send-to-computer list. */
interface DictationTarget {
    /** Recognition started: remember the insertion point. */
    fun begin()

    /** Everything heard so far, interim sentences included. */
    fun interim(text: String)

    /** The final text, or "" for nothing; [error] is for the user when it failed. */
    fun commit(text: String, error: String?)

    /** Dropped: nothing is written. */
    fun cancel()
}

enum class Phase { Idle, Pressing, Recording, Finishing }

/**
 * Push to talk, as frontend/src/voice/controller.ts: pressing opens the
 * microphone at once and buffers the audio; a press shorter than 300 ms is a
 * tap and nothing is recognized. Past that the buffered audio and what
 * follows stream to the server while the target shows interim text.
 * Letting go waits for the final results. Sliding up past the threshold and
 * letting go drops the recording.
 */
class Voice(private val context: Context, private val scope: CoroutineScope, private val toast: (String) -> Unit) {
    var phase by mutableStateOf(Phase.Idle)
        private set

    /** Seconds left, shown during the last 30. */
    var left by mutableStateOf<Int?>(null)
        private set

    /** The finger slid up: letting go now cancels. */
    var cancelling by mutableStateOf(false)

    private class Run(val target: DictationTarget, val http: OkHttpClient, val url: String) {
        val pressedAt = System.currentTimeMillis()
        var recorder: Recorder? = null
        var socket: AsrSocket? = null
        val buffer = mutableListOf<ByteArray>()
        val jobs = mutableListOf<Job>()
    }

    private var run: Run? = null

    fun press(http: OkHttpClient, server: String, target: DictationTarget) {
        if (run != null) return
        val r = Run(target, http, AsrSocket.url(server))
        run = r
        cancelling = false
        left = null
        phase = Phase.Pressing
        try {
            r.recorder = Recorder(context) { pcm ->
                synchronized(r) { r.socket?.send(pcm) ?: r.buffer.add(pcm) }
            }
        } catch (e: Exception) {
            run = null
            phase = Phase.Idle
            toast(e.message ?: "打不开麦克风")
            return
        }
        r.jobs += scope.launch {
            delay(HOLD_MS)
            start(r)
        }
    }

    private fun start(r: Run) {
        if (run !== r) return
        r.target.begin()
        val socket = AsrSocket(r.http, r.url) { text -> scope.launch { if (run === r) r.target.interim(text) } }
        synchronized(r) {
            for (pcm in r.buffer) socket.send(pcm)
            r.buffer.clear()
            r.socket = socket
        }
        // A failure while the user is still speaking ends the recording.
        r.jobs += scope.launch {
            val o = socket.outcome.await()
            if (run === r && phase == Phase.Recording) settle(r, o)
        }
        r.jobs += scope.launch {
            while (isActive) {
                val rest = MAX_MS - (System.currentTimeMillis() - r.pressedAt)
                if (rest <= 0) {
                    toast("已到 5 分钟上限，自动结束")
                    cancelling = false
                    release()
                    break
                }
                left = if (rest <= COUNTDOWN_MS) ((rest + 999) / 1000).toInt() else null
                delay(250)
            }
        }
        phase = Phase.Recording
    }

    /** The finger lifted (or the app left the screen). */
    fun release() {
        val r = run ?: return
        if (cancelling) {
            abort()
            return
        }
        when (phase) {
            Phase.Pressing -> {
                clear(r)
                run = null
                r.recorder?.stop()
                phase = Phase.Idle
            }
            Phase.Recording -> {
                clear(r)
                phase = Phase.Finishing
                left = null
                scope.launch {
                    r.recorder?.stop()
                    val socket = r.socket ?: return@launch
                    socket.finish()
                    settle(r, socket.outcome.await())
                }
            }
            else -> Unit
        }
    }

    private fun settle(r: Run, o: AsrOutcome) {
        if (run !== r) return
        clear(r)
        run = null
        r.recorder?.stop()
        r.socket?.abort()
        phase = Phase.Idle
        left = null
        cancelling = false
        r.target.commit(o.text, (o as? AsrOutcome.Failed)?.message)
    }

    /** Drops the recording and whatever was heard. */
    fun abort() {
        val r = run ?: return
        clear(r)
        run = null
        r.recorder?.stop()
        r.socket?.abort()
        if (phase != Phase.Pressing) r.target.cancel()
        phase = Phase.Idle
        left = null
        cancelling = false
    }

    /** Whether [target] is the one being dictated into. */
    fun busyWith(target: DictationTarget) = phase != Phase.Idle && run?.target === target

    private fun clear(r: Run) {
        r.jobs.forEach { it.cancel() }
        r.jobs.clear()
    }

    companion object {
        const val HOLD_MS = 300L
        const val MAX_MS = 5 * 60_000L
        const val COUNTDOWN_MS = 30_000L
    }
}
