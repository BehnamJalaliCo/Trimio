package io.trimio.shared.player

import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine

/** Where the player's sound goes: 16-bit little-endian PCM, written as fast as it is heard. */
interface PcmSink : AutoCloseable {
    /** Blocks until [n] bytes of [bytes] are queued. */
    fun write(bytes: ByteArray, n: Int)

    /** Sample frames actually played since open: the player's clock. */
    val playedFrames: Long

    /** Waits until what was written has been heard. */
    fun drain()
}

/**
 * The default sound device through javax.sound (WASAPI/DirectSound on Windows, ALSA/Pulse on
 * Linux, CoreAudio on macOS), with a fifth of a second of buffer so the playhead stays close to
 * what is heard. Null from [open] when the machine has no output line (the player stays silent).
 */
class JavaSoundSink private constructor(private val line: SourceDataLine) : PcmSink {
    override fun write(bytes: ByteArray, n: Int) {
        var off = 0
        // A write blocks until the device takes it; closing the line (pause) ends it.
        while (off < n && line.isOpen) off += line.write(bytes, off, n - off)
    }

    override val playedFrames: Long get() = line.longFramePosition

    override fun drain() = line.drain()

    override fun close() {
        runCatching {
            line.stop()
            line.flush()
            line.close()
        }
    }

    companion object {
        private const val BITS = 16
        private const val BUFFERS = 5

        fun open(rate: Int, channels: Int): PcmSink? = runCatching {
            val format = AudioFormat(rate.toFloat(), BITS, channels, true, false)
            val line = AudioSystem.getSourceDataLine(format)
            line.open(format, rate * channels * 2 / BUFFERS)
            line.start()
            JavaSoundSink(line)
        }.getOrNull()
    }
}
