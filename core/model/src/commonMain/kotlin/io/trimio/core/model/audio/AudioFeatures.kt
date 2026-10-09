package io.trimio.core.model.audio

import io.trimio.core.model.time.TimeRange

/**
 * Frame-level description of the soundtrack, computed once and shared by the Director
 * (emphasis, pacing, cut points) and the renderer (audio-reactive backgrounds and visualisers).
 */
class AudioFeatures(
    /** Distance between frames. */
    val hopMs: Int,
    /** Short-term loudness per frame in dBFS (RMS), floored at [SILENCE_DB]. */
    val energyDb: FloatArray,
    /** Fundamental frequency per frame in Hz, 0 where unvoiced. */
    val pitchHz: FloatArray,
    /** Integrated programme loudness (EBU R128) before normalisation. */
    val integratedLufs: Float,
    /** Gain applied to reach the delivery target. */
    val appliedGainDb: Float,
    /** Detected speech regions; everything else is silence or noise. */
    val speech: List<TimeRange>,
) {
    init {
        require(hopMs > 0)
        require(energyDb.size == pitchHz.size) { "energy and pitch tracks must align" }
    }

    val frameCount: Int get() = energyDb.size

    fun frameAt(timeMs: Long): Int = (timeMs / hopMs).toInt().coerceIn(0, (frameCount - 1).coerceAtLeast(0))

    /** Energy mapped to 0..1 over a speech-friendly range, for driving visuals. */
    fun energy01(timeMs: Long): Float {
        if (frameCount == 0) return 0f
        return ((energyDb[frameAt(timeMs)] - SILENCE_DB) / -SILENCE_DB).coerceIn(0f, 1f)
    }

    companion object {
        const val SILENCE_DB = -60f
    }
}
