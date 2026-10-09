package io.trimio.engine.audio

import io.trimio.core.model.audio.PcmAudio

/**
 * Fundamental frequency by the YIN algorithm (de Cheveigné & Kawahara, 2002):
 * cumulative-mean-normalised difference, absolute threshold, parabolic refinement.
 *
 * Runs on an 8 kHz copy of the signal: speech F0 (70-400 Hz) is far below its Nyquist,
 * and halving the rate quarters the cost of the difference function.
 */
class PitchTracker(
    private val minHz: Float = 70f,
    private val maxHz: Float = 400f,
    private val windowMs: Int = 25,
    private val threshold: Float = 0.15f,
    private val resampler: Resampler = Resampler(zeroCrossings = 8),
) {
    /**
     * Returns [frames] values spaced [hopMs] apart, 0 where unvoiced. Frames for which [analyse]
     * is false (silence) are skipped. Pitch is estimated every [stride] frames and held in between:
     * prosody needs the contour (a syllable lasts 100-250 ms), not 10 ms precision.
     */
    fun track(audio: PcmAudio, hopMs: Int, frames: Int, stride: Int = 3, analyse: (Int) -> Boolean = { true }): FloatArray {
        val s = (if (audio.sampleRate == ANALYSIS_RATE) audio else resampler.resample(audio, ANALYSIS_RATE)).samples
        val window = ANALYSIS_RATE * windowMs / 1000
        val hop = ANALYSIS_RATE * hopMs / 1000
        val tauMin = (ANALYSIS_RATE / maxHz).toInt()
        val tauMax = (ANALYSIS_RATE / minHz).toInt()
        val diff = FloatArray(tauMax + 1)
        val out = FloatArray(frames)

        for (f in 0 until frames step stride) {
            val start = f * hop
            val value = if (analyse(f) && start + window + tauMax < s.size) estimate(s, start, window, tauMin, tauMax, diff) else 0f
            for (k in f until minOf(f + stride, frames)) out[k] = value
        }
        return out
    }

    private fun estimate(s: FloatArray, start: Int, window: Int, tauMin: Int, tauMax: Int, d: FloatArray): Float {
        // Difference function d(tau).
        for (tau in 1..tauMax) {
            var sum = 0f
            for (j in start until start + window) {
                val delta = s[j] - s[j + tau]
                sum += delta * delta
            }
            d[tau] = sum
        }
        // Cumulative mean normalisation d'(tau).
        d[0] = 1f
        var running = 0f
        for (tau in 1..tauMax) {
            running += d[tau]
            d[tau] = if (running > 0f) d[tau] * tau / running else 1f
        }
        // First dip under the threshold, followed down to its local minimum.
        var tau = tauMin
        while (tau <= tauMax && d[tau] >= threshold) tau++
        if (tau > tauMax) return 0f
        while (tau + 1 <= tauMax && d[tau + 1] < d[tau]) tau++
        return ANALYSIS_RATE / refine(d, tau, tauMax)
    }

    /** Parabolic interpolation around the minimum for sub-sample period accuracy. */
    private fun refine(d: FloatArray, tau: Int, tauMax: Int): Float {
        if (tau <= 1 || tau >= tauMax) return tau.toFloat()
        val a = d[tau - 1]
        val b = d[tau]
        val c = d[tau + 1]
        val denom = a - 2 * b + c
        return if (denom == 0f) tau.toFloat() else tau + 0.5f * (a - c) / denom
    }

    companion object {
        const val ANALYSIS_RATE = 8_000
    }
}
