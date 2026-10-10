package io.trimio.engine.autopilot

import io.trimio.core.model.audio.PcmAudio
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

/**
 * Speech-like test audio at 48 kHz: a room-tone floor (about -65 dBFS) and voiced bursts at exact
 * source times, so cut points and joins can be checked against known truth. Deterministic.
 */
internal class SyntheticVoice(seconds: Float, val rate: Int = 48_000) {
    val samples = FloatArray((seconds * rate).toInt())

    init {
        var seed = 11
        for (i in samples.indices) {
            seed = seed * 1_103_515_245 + 12_345
            samples[i] = (((seed ushr 16) and 0x7FFF) / 32_768f - 0.5f) * 0.002f
        }
    }

    /** A voiced burst between [a] and [b] seconds (8 ms attack and release), pitch [hz]. */
    fun word(a: Float, b: Float, amp: Float = 0.3f, hz: Float = 150f): SyntheticVoice {
        for (i in (a * rate).toInt() until minOf(samples.size, (b * rate).toInt())) {
            val t = i / rate.toFloat()
            val env = minOf(1f, (t - a) / EDGE, (b - t) / EDGE)
            val p = 2f * PI.toFloat() * hz * t
            samples[i] += amp * env * (0.7f * sin(p) + 0.3f * sin(2.02f * p))
        }
        return this
    }

    fun pcm() = PcmAudio(samples, rate)

    companion object {
        private const val EDGE = 0.008f

        /**
         * A click detector: the largest sample-to-sample jump in [x] around each of [at] (output
         * seconds), relative to the largest jump of the signal anywhere else. A spliced click shows
         * as a jump far above anything the material itself does.
         */
        fun worstJump(x: FloatArray, rate: Int, at: List<Float>, window: Float = 0.03f): Float {
            fun jump(i: Int) = abs(x[i] - x[i - 1])
            val near = at.flatMap { t -> ((t - window) * rate).toInt().coerceAtLeast(1) until ((t + window) * rate).toInt().coerceAtMost(x.size) }.toSet()
            val inside = near.maxOfOrNull(::jump) ?: 0f
            val elsewhere = (1 until x.size).filter { it !in near }.maxOfOrNull(::jump) ?: 0f
            return inside / elsewhere.coerceAtLeast(1e-6f)
        }
    }
}
