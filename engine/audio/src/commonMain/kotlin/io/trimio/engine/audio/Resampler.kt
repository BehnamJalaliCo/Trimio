package io.trimio.engine.audio

import io.trimio.core.model.audio.PcmAudio
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * Band-limited sample-rate conversion with a Blackman-windowed sinc kernel.
 * When downsampling, the cutoff drops to the new Nyquist so speech does not alias
 * (48 kHz phone recordings -> 16 kHz for Whisper).
 *
 * Common rate pairs reduce to a small fraction L/M (48k→16k is 1/3, 44.1k→16k is 160/441), so the
 * output only ever needs L distinct fractional positions. Their normalised kernels are precomputed
 * once ("polyphase"), leaving a plain multiply-add inner loop. Unusual ratios fall back to a
 * tabulated kernel with interpolation.
 */
class Resampler(
    /** Kernel half-width in zero crossings; 16 gives > 70 dB stop-band rejection. */
    private val zeroCrossings: Int = 16,
    /** Fraction of the target Nyquist kept; the rest is the transition band. */
    private val rolloff: Double = 0.94,
) {
    fun resample(input: PcmAudio, targetRate: Int): PcmAudio {
        require(targetRate > 0)
        if (input.sampleRate == targetRate || input.size == 0) return PcmAudio(input.samples.copyOf(), targetRate)

        val g = gcd(targetRate, input.sampleRate)
        val up = targetRate / g // L
        val down = input.sampleRate / g // M
        val cutoff = minOf(1.0, up.toDouble() / down) * rolloff
        val outSize = (input.size.toLong() * up / down).toInt()

        val out = if (up <= MAX_PHASES) polyphase(input.samples, up, down, cutoff, outSize) else interpolated(input.samples, up.toDouble() / down, cutoff, outSize)
        return PcmAudio(out, targetRate)
    }

    private fun polyphase(src: FloatArray, up: Int, down: Int, cutoff: Double, outSize: Int): FloatArray {
        val halfWidth = zeroCrossings / cutoff
        // For phase p the output sits at base + p/up; taps are base + first[p] + j.
        val first = IntArray(up)
        val weights = Array(up) { p ->
            val frac = p.toDouble() / up
            val lo = ceil(frac - halfWidth).toInt()
            val hi = floor(frac + halfWidth).toInt()
            first[p] = lo
            val w = DoubleArray(hi - lo + 1) { j -> kernel((lo + j - frac) * cutoff) }
            val sum = w.sum()
            FloatArray(w.size) { (w[it] / sum).toFloat() }
        }

        val out = FloatArray(outSize)
        for (n in 0 until outSize) {
            val pos = n.toLong() * down
            val base = (pos / up).toInt()
            val phase = (pos % up).toInt()
            val w = weights[phase]
            val start = base + first[phase]
            if (start >= 0 && start + w.size <= src.size) {
                // Four independent accumulators break the add dependency chain so the JIT/ART
                // can pipeline (and on capable CPUs vectorise) the multiply-adds.
                var a0 = 0f; var a1 = 0f; var a2 = 0f; var a3 = 0f
                var j = 0
                val unrolled = w.size - 3
                while (j < unrolled) {
                    a0 += src[start + j] * w[j]
                    a1 += src[start + j + 1] * w[j + 1]
                    a2 += src[start + j + 2] * w[j + 2]
                    a3 += src[start + j + 3] * w[j + 3]
                    j += 4
                }
                while (j < w.size) {
                    a0 += src[start + j] * w[j]
                    j++
                }
                out[n] = (a0 + a1) + (a2 + a3)
            } else {
                // Edges: use the taps that exist and renormalise so the level stays correct.
                var acc = 0f
                var norm = 0f
                for (j in w.indices) {
                    val k = start + j
                    if (k in src.indices) {
                        acc += src[k] * w[j]
                        norm += w[j]
                    }
                }
                out[n] = if (norm != 0f) acc / norm else 0f
            }
        }
        return out
    }

    private fun interpolated(src: FloatArray, ratio: Double, cutoff: Double, outSize: Int): FloatArray {
        val table = FloatArray(zeroCrossings * TABLE_RESOLUTION + 2) { i -> kernel(i.toDouble() / TABLE_RESOLUTION).toFloat() }
        val halfWidth = zeroCrossings / cutoff
        val scale = cutoff * TABLE_RESOLUTION
        return FloatArray(outSize) { n ->
            val center = n / ratio
            val lo = ceil(center - halfWidth).toInt().coerceAtLeast(0)
            val hi = floor(center + halfWidth).toInt().coerceAtMost(src.size - 1)
            var acc = 0.0
            var norm = 0.0
            for (k in lo..hi) {
                val pos = abs(k - center) * scale
                val i = pos.toInt()
                val w = table[i] + (table[i + 1] - table[i]) * (pos - i)
                acc += src[k] * w
                norm += w
            }
            if (norm != 0.0) (acc / norm).toFloat() else 0f
        }
    }

    /** Windowed sinc at [u] zero crossings from the centre. */
    private fun kernel(u: Double): Double {
        val a = abs(u)
        if (a >= zeroCrossings) return 0.0
        val sinc = if (a == 0.0) 1.0 else sin(PI * a) / (PI * a)
        val phase = PI * (a / zeroCrossings + 1.0) // Blackman, centred
        return sinc * (0.42 - 0.5 * cos(phase) + 0.08 * cos(2 * phase))
    }

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)

    private companion object {
        const val TABLE_RESOLUTION = 512
        const val MAX_PHASES = 2048
    }
}
