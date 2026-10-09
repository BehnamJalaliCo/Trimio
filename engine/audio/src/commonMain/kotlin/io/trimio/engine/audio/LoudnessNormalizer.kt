package io.trimio.engine.audio

import io.trimio.core.model.audio.PcmAudio
import kotlin.math.pow

/**
 * Brings speech to a delivery loudness. Social platforms normalise playback to about -14 LUFS,
 * so mastering there means the voice is neither turned down nor crushed by the platform.
 *
 * The gain is capped so the sample peak stays under [ceilingDb]; quiet-but-peaky recordings
 * therefore end below target instead of clipping. (A true-peak limiter comes with the mixer.)
 */
class LoudnessNormalizer(
    private val targetLufs: Double = -14.0,
    private val ceilingDb: Double = -1.0,
    private val maxBoostDb: Double = 24.0,
) {
    data class Result(val audio: PcmAudio, val measuredLufs: Double, val appliedGainDb: Double)

    fun normalize(audio: PcmAudio): Result {
        val measured = LoudnessMeter.measure(audio)
        if (measured.isSilent) return Result(audio, measured.integratedLufs, 0.0)

        val wanted = (targetLufs - measured.integratedLufs).coerceAtMost(maxBoostDb)
        val headroom = ceilingDb - measured.samplePeakDb
        val gainDb = minOf(wanted, headroom)
        val gain = 10.0.pow(gainDb / 20).toFloat()
        val out = FloatArray(audio.size) { audio.samples[it] * gain }
        return Result(PcmAudio(out, audio.sampleRate), measured.integratedLufs, gainDb)
    }
}
