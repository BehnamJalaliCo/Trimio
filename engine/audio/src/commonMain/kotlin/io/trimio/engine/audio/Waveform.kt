package io.trimio.engine.audio

import io.trimio.core.model.audio.PcmAudio
import kotlin.math.abs
import kotlin.math.log10

object Waveform {
    /**
     * Peak level of [bars] equal slices between [startIndex] and [endIndex], mapped to 0..1 on a
     * dB scale (-48 dB..0 dB) so quiet speech is still visible in the UI strip.
     */
    fun bars(audio: PcmAudio, bars: Int, startIndex: Int = 0, endIndex: Int = audio.size): List<Float> {
        require(bars > 0)
        val from = startIndex.coerceIn(0, audio.size)
        val to = endIndex.coerceIn(from, audio.size)
        val span = to - from
        if (span == 0) return List(bars) { 0f }
        return List(bars) { b ->
            val s = from + (span.toLong() * b / bars).toInt()
            val e = (from + (span.toLong() * (b + 1) / bars).toInt()).coerceAtLeast(s + 1).coerceAtMost(to)
            var peak = 0f
            for (i in s until e) peak = maxOf(peak, abs(audio.samples[i]))
            if (peak <= 0f) 0f else ((20 * log10(peak) + 48f) / 48f).coerceIn(0f, 1f)
        }
    }
}
