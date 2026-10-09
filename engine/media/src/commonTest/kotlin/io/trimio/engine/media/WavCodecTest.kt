package io.trimio.engine.media

import io.trimio.core.model.audio.PcmAudio
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class WavCodecTest {

    @Test
    fun pcm16RoundTripWithinQuantisation() {
        val audio = PcmAudio(FloatArray(1_600) { (0.8 * sin(2 * PI * 440 * it / 16_000)).toFloat() }, 16_000)
        val decoded = WavCodec.decodeMono(WavCodec.encodePcm16(audio))
        assertEquals(16_000, decoded.sampleRate)
        assertEquals(audio.size, decoded.size)
        for (i in 0 until audio.size) assertEquals(audio.samples[i], decoded.samples[i], 1f / 16_000)
    }

    @Test
    fun stereoFloatIsDownmixed() {
        // Hand-built 2-frame stereo IEEE-float file: L=0.5/R=-0.5 then L=1/R=0.
        val samples = floatArrayOf(0.5f, -0.5f, 1f, 0f)
        val data = ByteArray(samples.size * 4)
        samples.forEachIndexed { i, f -> val b = f.toRawBits(); for (k in 0..3) data[i * 4 + k] = (b shr (8 * k)).toByte() }
        val header = wavHeader(format = 3, channels = 2, rate = 48_000, bits = 32, dataSize = data.size)
        val decoded = WavCodec.decodeMono(header + data)
        assertEquals(listOf(0f, 0.5f), decoded.samples.toList())
        assertEquals(48_000, decoded.sampleRate)
    }

    @Test
    fun rejectsNonWave() {
        assertFailsWith<MediaFormatException> { WavCodec.readHeader("hello world, not a wav".encodeToByteArray()) }
    }

    private fun wavHeader(format: Int, channels: Int, rate: Int, bits: Int, dataSize: Int): ByteArray {
        val b = ByteArray(44)
        fun ascii(at: Int, s: String) = s.forEachIndexed { i, c -> b[at + i] = c.code.toByte() }
        fun u16(at: Int, v: Int) { b[at] = v.toByte(); b[at + 1] = (v shr 8).toByte() }
        fun u32(at: Int, v: Int) { u16(at, v and 0xFFFF); u16(at + 2, v ushr 16) }
        ascii(0, "RIFF"); u32(4, 36 + dataSize); ascii(8, "WAVE"); ascii(12, "fmt "); u32(16, 16)
        u16(20, format); u16(22, channels); u32(24, rate); u32(28, rate * channels * bits / 8)
        u16(32, channels * bits / 8); u16(34, bits); ascii(36, "data"); u32(40, dataSize)
        return b
    }
}
