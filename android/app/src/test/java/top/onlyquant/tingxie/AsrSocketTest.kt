package top.onlyquant.tingxie

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import top.onlyquant.tingxie.voice.AsrOutcome
import top.onlyquant.tingxie.voice.AsrSocket
import java.io.File
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Streams a recording to a running dev server, in real time, the way the
 * recorder does. Skipped unless TINGXIE_TOKEN and TINGXIE_WAV are set
 * (TINGXIE_SERVER defaults to http://127.0.0.1:36750).
 */
class AsrSocketTest {
    @Test
    fun recognizesRecording() = runBlocking {
        val token = System.getenv("TINGXIE_TOKEN")
        val wav = System.getenv("TINGXIE_WAV")
        assumeTrue(token != null && wav != null)
        val server = System.getenv("TINGXIE_SERVER") ?: "http://127.0.0.1:36750"
        val http = OkHttpClient.Builder()
            .addInterceptor { it.proceed(it.request().newBuilder().header("Authorization", "Bearer $token").build()) }
            .build()
        val heard = mutableListOf<String>()
        val socket = AsrSocket(http, AsrSocket.url(server)) { heard.add(it) }
        val pcm = File(wav!!).readBytes().drop(44).toByteArray()
        for (chunk in pcm.toList().chunked(3200)) {
            socket.send(chunk.toByteArray())
            Thread.sleep(100)
        }
        socket.finish()
        val outcome = withTimeout(20_000) { socket.outcome.await() }
        println("interim: ${heard.size} updates, last: ${heard.lastOrNull()}")
        println("final: $outcome")
        assertIs<AsrOutcome.Ok>(outcome)
        assertTrue(outcome.text.isNotEmpty())
    }
}
