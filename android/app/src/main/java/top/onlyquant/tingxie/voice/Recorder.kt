package top.onlyquant.tingxie.voice

import android.annotation.SuppressLint
import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import top.onlyquant.tingxie.BuildConfig
import java.io.File
import java.io.IOException
import kotlin.concurrent.thread

/**
 * The microphone as 16 kHz mono PCM16, handed out in 100 ms frames on a
 * background thread. Opens at once; the caller checks the permission.
 *
 * Debug builds read files/fake-mic.wav instead when it exists, in real time,
 * so dictation can be tried on an emulator (adb push it with run-as).
 */
class Recorder(context: Context, private val onFrame: (ByteArray) -> Unit) {
    @Volatile private var running = true
    private val worker: Thread

    init {
        val fake = File(context.filesDir, "fake-mic.wav").takeIf { BuildConfig.DEBUG && it.exists() }
        worker = if (fake != null) fromFile(fake) else fromMic()
    }

    @SuppressLint("MissingPermission")
    private fun fromMic(): Thread {
        val min = AudioRecord.getMinBufferSize(RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION, RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, FRAME * 4),
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            rec.release()
            throw IOException("打不开麦克风")
        }
        rec.startRecording()
        return thread(name = "recorder") {
            val buf = ByteArray(FRAME)
            try {
                while (running) {
                    var n = 0
                    while (n < FRAME && running) {
                        val r = rec.read(buf, n, FRAME - n)
                        if (r <= 0) break
                        n += r
                    }
                    if (n > 0 && running) onFrame(buf.copyOf(n))
                }
            } finally {
                rec.stop()
                rec.release()
            }
        }
    }

    private fun fromFile(f: File) = thread(name = "fake-mic") {
        val pcm = f.readBytes().let { it.copyOfRange(minOf(44, it.size), it.size) }
        var at = 0
        while (running) {
            val end = minOf(at + FRAME, pcm.size)
            onFrame(if (at < end) pcm.copyOfRange(at, end) else ByteArray(FRAME))
            at = end
            Thread.sleep(100)
        }
    }

    /** Stops capturing; frames already read have been handed out when this returns. */
    fun stop() {
        running = false
        worker.join(500)
    }

    companion object {
        const val RATE = 16_000
        /** 100 ms of 16-bit mono samples. */
        const val FRAME = RATE / 10 * 2
    }
}
