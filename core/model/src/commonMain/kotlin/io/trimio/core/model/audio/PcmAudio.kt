package io.trimio.core.model.audio

import kotlin.math.roundToLong

/**
 * Mono floating-point audio, samples in -1..1. The working format of every audio engine:
 * decoders downmix to it, analysers read it, Whisper consumes it at 16 kHz.
 */
class PcmAudio(val samples: FloatArray, val sampleRate: Int) {
    init {
        require(sampleRate > 0) { "sampleRate must be positive" }
    }

    val size: Int get() = samples.size
    val durationMs: Long get() = (samples.size * 1000.0 / sampleRate).roundToLong()

    fun indexAt(timeMs: Long): Int = (timeMs * sampleRate / 1000).toInt().coerceIn(0, samples.size)

    /** Copy of the samples between two times. */
    fun slice(startMs: Long, endMs: Long): PcmAudio =
        PcmAudio(samples.copyOfRange(indexAt(startMs), indexAt(endMs)), sampleRate)

    companion object {
        const val WHISPER_RATE = 16_000
    }
}
