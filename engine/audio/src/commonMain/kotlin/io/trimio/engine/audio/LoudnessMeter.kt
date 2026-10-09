package io.trimio.engine.audio

import io.trimio.core.model.audio.PcmAudio
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan

/**
 * Programme loudness per ITU-R BS.1770-4 / EBU R128, for a single (mono) channel.
 *
 * K-weighting (high shelf + RLB high-pass) is derived for any sample rate from the analogue
 * prototype, then gated: 400 ms blocks with 75% overlap, absolute gate at -70 LUFS and a
 * relative gate 10 LU below the ungated level.
 */
object LoudnessMeter {
    const val ABSOLUTE_GATE_LUFS = -70.0
    private const val RELATIVE_GATE_LU = -10.0
    private const val BLOCK_MS = 400
    private const val STEP_MS = 100

    data class Measurement(val integratedLufs: Double, val samplePeakDb: Double) {
        val isSilent: Boolean get() = integratedLufs == Double.NEGATIVE_INFINITY
    }

    fun measure(audio: PcmAudio): Measurement {
        val blockLen = audio.sampleRate * BLOCK_MS / 1000
        val step = audio.sampleRate * STEP_MS / 1000
        var peak = 0f
        for (v in audio.samples) peak = maxOf(peak, abs(v))
        val peakDb = if (peak > 0f) 20 * log10(peak.toDouble()) else Double.NEGATIVE_INFINITY
        if (audio.size < blockLen) return Measurement(Double.NEGATIVE_INFINITY, peakDb)

        // Energy of each 100 ms step; a 400 ms gating block is four consecutive steps.
        val steps = kWeightedStepEnergies(audio.samples, audio.sampleRate, step)
        val stepsPerBlock = blockLen / step
        val blockPowers = ArrayList<Double>(steps.size)
        for (b in 0..steps.size - stepsPerBlock) {
            var sum = 0.0
            for (k in b until b + stepsPerBlock) sum += steps[k]
            blockPowers += sum / blockLen
        }

        val absoluteThreshold = lufsToPower(ABSOLUTE_GATE_LUFS)
        val aboveAbsolute = blockPowers.filter { it > absoluteThreshold }
        if (aboveAbsolute.isEmpty()) return Measurement(Double.NEGATIVE_INFINITY, peakDb)

        val relativeThreshold = lufsToPower(powerToLufs(aboveAbsolute.average()) + RELATIVE_GATE_LU)
        val gated = aboveAbsolute.filter { it > relativeThreshold }
        return Measurement(powerToLufs(gated.average()), peakDb)
    }

    private fun powerToLufs(power: Double) = -0.691 + 10 * log10(power)
    private fun lufsToPower(lufs: Double) = 10.0.pow((lufs + 0.691) / 10)

    /**
     * K-weighting as two cascaded biquads (coefficients per BS.1770 / libebur128, derived for any rate),
     * streamed into per-step sums of squares: one pass, no full-length buffers.
     */
    internal fun kWeightedStepEnergies(x: FloatArray, rate: Int, step: Int): DoubleArray {
        // Stage 1: high shelf (+4 dB above ~1.7 kHz, models the head).
        var f0 = 1681.974450955533
        val g = 3.999843853973347
        var q = 0.7071752369554196
        var k = tan(PI * f0 / rate)
        val vh = 10.0.pow(g / 20)
        val vb = vh.pow(0.4996667741545416)
        var a0 = 1 + k / q + k * k
        val sb0 = (vh + vb * k / q + k * k) / a0
        val sb1 = 2 * (k * k - vh) / a0
        val sb2 = (vh - vb * k / q + k * k) / a0
        val sa1 = 2 * (k * k - 1) / a0
        val sa2 = (1 - k / q + k * k) / a0

        // Stage 2: RLB high-pass (~38 Hz).
        f0 = 38.13547087602444
        q = 0.5003270373238773
        k = tan(PI * f0 / rate)
        a0 = 1 + k / q + k * k
        val ha1 = 2 * (k * k - 1) / a0
        val ha2 = (1 - k / q + k * k) / a0

        // Direct form II transposed state for both stages.
        var s1 = 0.0; var s2 = 0.0; var h1 = 0.0; var h2 = 0.0
        val energies = DoubleArray(x.size / step)
        for (i in 0 until energies.size * step) {
            val v = x[i].toDouble()
            val y = sb0 * v + s1
            s1 = sb1 * v - sa1 * y + s2
            s2 = sb2 * v - sa2 * y
            val z = y + h1
            h1 = -2.0 * y - ha1 * z + h2
            h2 = y - ha2 * z
            energies[i / step] += z * z
        }
        return energies
    }
}
