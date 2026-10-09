package io.trimio.engine.media

import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.input.AudioTrackInfo
import kotlin.math.roundToInt

/**
 * RIFF/WAVE reader and writer. Reads PCM 16/24/32-bit and IEEE float 32-bit, any channel count
 * (downmixed to mono). Writes mono PCM16 — the format whisper.cpp and test fixtures use.
 */
object WavCodec {

    class Header(val channels: Int, val sampleRate: Int, val bitsPerSample: Int, val isFloat: Boolean, val dataOffset: Int, val dataSize: Int) {
        val frameBytes: Int get() = channels * bitsPerSample / 8
        val frames: Int get() = dataSize / frameBytes
        val durationMs: Long get() = frames * 1000L / sampleRate
        fun trackInfo() = AudioTrackInfo(sampleRate, channels, if (isFloat) "pcm_f32le" else "pcm_s${bitsPerSample}le")
    }

    fun readHeader(bytes: ByteArray): Header {
        if (bytes.size < 12 || bytes.ascii(0, 4) != "RIFF" || bytes.ascii(8, 4) != "WAVE") throw MediaFormatException("Not a WAVE file")
        var pos = 12
        var format: IntArray? = null // formatTag, channels, rate, bits
        while (pos + 8 <= bytes.size) {
            val id = bytes.ascii(pos, 4)
            val size = bytes.u32(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    var tag = bytes.u16(body)
                    if (tag == WAVE_FORMAT_EXTENSIBLE && size >= 26) tag = bytes.u16(body + 24) // sub-format GUID starts with the tag
                    format = intArrayOf(tag, bytes.u16(body + 2), bytes.u32(body + 4), bytes.u16(body + 14))
                }
                "data" -> {
                    val f = format ?: throw MediaFormatException("data chunk before fmt chunk")
                    val isFloat = f[0] == WAVE_FORMAT_FLOAT
                    if (f[0] != WAVE_FORMAT_PCM && !isFloat) throw MediaFormatException("Unsupported WAVE format tag ${f[0]}")
                    if (isFloat && f[3] != 32) throw MediaFormatException("Only 32-bit float is supported")
                    if (!isFloat && f[3] !in setOf(16, 24, 32)) throw MediaFormatException("Unsupported bit depth ${f[3]}")
                    val dataSize = minOf(size, bytes.size - body)
                    return Header(f[1], f[2], f[3], isFloat, body, dataSize)
                }
            }
            pos = body + size + (size and 1) // chunks are word aligned
        }
        throw MediaFormatException("No data chunk")
    }

    fun decodeMono(bytes: ByteArray): PcmAudio {
        val h = readHeader(bytes)
        val bytesPerSample = h.bitsPerSample / 8
        val out = FloatArray(h.frames)
        for (frame in 0 until h.frames) {
            var sum = 0f
            val base = h.dataOffset + frame * h.frameBytes
            for (c in 0 until h.channels) {
                val o = base + c * bytesPerSample
                sum += when {
                    h.isFloat -> Float.fromBits(bytes.u32(o))
                    h.bitsPerSample == 16 -> bytes.s16(o) / 32768f
                    h.bitsPerSample == 24 -> bytes.s24(o) / 8388608f
                    else -> bytes.u32(o) / 2147483648f
                }
            }
            out[frame] = sum / h.channels
        }
        return PcmAudio(out, h.sampleRate)
    }

    /** Mono PCM16, clipped to full scale. */
    fun encodePcm16(audio: PcmAudio): ByteArray {
        val dataSize = audio.size * 2
        val b = ByteArray(44 + dataSize)
        b.putAscii(0, "RIFF"); b.putU32(4, 36 + dataSize); b.putAscii(8, "WAVE")
        b.putAscii(12, "fmt "); b.putU32(16, 16); b.putU16(20, WAVE_FORMAT_PCM); b.putU16(22, 1)
        b.putU32(24, audio.sampleRate); b.putU32(28, audio.sampleRate * 2); b.putU16(32, 2); b.putU16(34, 16)
        b.putAscii(36, "data"); b.putU32(40, dataSize)
        for (i in 0 until audio.size) {
            val v = (audio.samples[i].coerceIn(-1f, 1f) * 32767f).roundToInt()
            b.putU16(44 + i * 2, v and 0xFFFF)
        }
        return b
    }

    private const val WAVE_FORMAT_PCM = 1
    private const val WAVE_FORMAT_FLOAT = 3
    private const val WAVE_FORMAT_EXTENSIBLE = 0xFFFE

    private fun ByteArray.ascii(at: Int, n: Int) = (at until at + n).map { this[it].toInt().toChar() }.joinToString("")
    private fun ByteArray.u16(at: Int) = (this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8)
    private fun ByteArray.s16(at: Int) = u16(at).toShort().toInt()
    private fun ByteArray.s24(at: Int) = ((this[at].toInt() and 0xFF) or ((this[at + 1].toInt() and 0xFF) shl 8) or (this[at + 2].toInt() shl 16))
    private fun ByteArray.u32(at: Int) = u16(at) or (u16(at + 2) shl 16)
    private fun ByteArray.putAscii(at: Int, s: String) = s.forEachIndexed { i, c -> this[at + i] = c.code.toByte() }
    private fun ByteArray.putU16(at: Int, v: Int) { this[at] = v.toByte(); this[at + 1] = (v shr 8).toByte() }
    private fun ByteArray.putU32(at: Int, v: Int) { putU16(at, v and 0xFFFF); putU16(at + 2, (v ushr 16) and 0xFFFF) }
}
