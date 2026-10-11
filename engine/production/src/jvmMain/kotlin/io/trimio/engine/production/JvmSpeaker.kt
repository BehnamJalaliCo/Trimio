package io.trimio.engine.production

import io.trimio.core.model.audio.PcmAudio
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.thread

/**
 * The editor's sound on the desktop: the mix as 16-bit PCM through the default output line
 * (javax.sound), written from its own thread. Without an output device it stays silent and the
 * player keeps time on its own clock ([position] is null).
 */
class JvmSpeaker : Speaker {
    @Volatile private var line: SourceDataLine? = null
    @Volatile private var writer: Thread? = null
    @Volatile private var from = 0f
    @Volatile private var rate = 0

    override fun play(audio: PcmAudio, from: Float) {
        pause()
        val format = AudioFormat(audio.sampleRate.toFloat(), BITS, 1, true, false)
        val out = runCatching { AudioSystem.getSourceDataLine(format).apply { open(format, audio.sampleRate * 2 / BUFFERS) } }.getOrNull() ?: return
        this.from = from
        rate = audio.sampleRate
        line = out
        out.start()
        writer = thread(name = "trimio-speaker", isDaemon = true) {
            val samples = audio.samples
            val chunk = ByteArray(CHUNK * 2)
            var i = (from * audio.sampleRate).toInt().coerceIn(0, samples.size)
            while (i < samples.size && line === out && !Thread.currentThread().isInterrupted) {
                val n = minOf(CHUNK, samples.size - i)
                for (k in 0 until n) {
                    val v = (samples[i + k].coerceIn(-1f, 1f) * Short.MAX_VALUE).toInt()
                    chunk[k * 2] = v.toByte()
                    chunk[k * 2 + 1] = (v shr BYTE).toByte()
                }
                out.write(chunk, 0, n * 2)
                i += n
            }
            if (line === out) out.drain()
        }
    }

    override fun pause() {
        val out = line ?: return
        line = null
        writer?.interrupt()
        writer = null
        runCatching {
            out.stop()
            out.flush()
            out.close()
        }
    }

    override val position: Float?
        get() = line?.let { from + it.longFramePosition / rate.toFloat() }

    private companion object {
        const val BITS = 16
        const val BYTE = 8
        const val CHUNK = 2048
        /** A tenth of a second of buffer: the playhead stays close to what is heard. */
        const val BUFFERS = 10
    }
}
