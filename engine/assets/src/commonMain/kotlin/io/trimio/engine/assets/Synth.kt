package io.trimio.engine.assets

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.tan
import kotlin.math.tanh
import kotlin.random.Random

/**
 * Small, deterministic DSP toolkit for the procedural sound library. Everything is generated from
 * code and a seed: no sample files to license, ship or download, and identical output on every
 * platform (the same edit always sounds the same).
 */
internal class Synth(val rate: Int) {

    fun buffer(ms: Int) = FloatArray(rate * ms / 1000)

    fun seconds(i: Int) = i.toFloat() / rate

    /** Exponential decay envelope reaching -60 dB after [decayMs]. */
    fun decay(t: Float, decayMs: Float): Float = exp(-6.9f * t * 1000f / decayMs)

    /** Linear attack, exponential release. */
    fun ar(t: Float, attackMs: Float, decayMs: Float): Float {
        val ms = t * 1000f
        return if (ms < attackMs) ms / attackMs else exp(-6.9f * (ms - attackMs) / decayMs)
    }

    /** Raised-cosine swell between 0 and 1 over the buffer, peaking at [peak] (0..1). */
    fun swell(i: Int, n: Int, peak: Float): Float {
        val x = i.toFloat() / n
        return if (x < peak) 0.5f - 0.5f * kotlin.math.cos(PI.toFloat() * x / peak)
        else 0.5f + 0.5f * kotlin.math.cos(PI.toFloat() * (x - peak) / (1f - peak))
    }

    /** Phase-accumulating oscillator; frequency may change every sample. */
    class Osc(private val rate: Int) {
        private var phase = 0.0
        fun sine(freq: Float): Float {
            phase = (phase + freq / rate) % 1.0
            return sin(2 * PI * phase).toFloat()
        }

        /** Band-limited sawtooth (polyBLEP), so bright synths do not alias. */
        fun saw(freq: Float): Float {
            val dt = freq / rate
            phase = (phase + dt) % 1.0
            var v = 2.0 * phase - 1.0
            v -= blep(phase, dt.toDouble())
            return v.toFloat()
        }

        fun square(freq: Float): Float {
            val dt = freq / rate
            phase = (phase + dt) % 1.0
            var v = if (phase < 0.5) 1.0 else -1.0
            v += blep(phase, dt.toDouble())
            v -= blep((phase + 0.5) % 1.0, dt.toDouble())
            return v.toFloat()
        }

        fun triangle(freq: Float): Float {
            phase = (phase + freq / rate) % 1.0
            return (4.0 * abs(phase - 0.5) - 1.0).toFloat()
        }

        private fun blep(t: Double, dt: Double): Double = when {
            t < dt -> { val x = t / dt; x + x - x * x - 1.0 }
            t > 1.0 - dt -> { val x = (t - 1.0) / dt; x * x + x + x + 1.0 }
            else -> 0.0
        }
    }

    /** White noise and Paul Kellet's economical pink noise, seeded. */
    class Noise(seed: Int) {
        private val rnd = Random(seed)
        private var b0 = 0f
        private var b1 = 0f
        private var b2 = 0f
        fun white(): Float = rnd.nextFloat() * 2f - 1f
        fun pink(): Float {
            val w = white()
            b0 = 0.99765f * b0 + w * 0.0990460f
            b1 = 0.96300f * b1 + w * 0.2965164f
            b2 = 0.57000f * b2 + w * 1.0526913f
            return (b0 + b1 + b2 + w * 0.1848f) * 0.25f
        }
    }

    /** Topology-preserving state-variable filter (Simper); cutoff may be modulated per sample. */
    class Svf(private val rate: Int) {
        private var ic1 = 0f
        private var ic2 = 0f
        var low = 0f; private set
        var band = 0f; private set
        var high = 0f; private set

        fun process(x: Float, cutoff: Float, q: Float = 0.707f): Svf {
            val g = tan(PI * (cutoff.coerceIn(20f, rate * 0.45f) / rate)).toFloat()
            val k = 1f / q
            val a1 = 1f / (1f + g * (g + k))
            val a2 = g * a1
            val a3 = g * a2
            val v3 = x - ic2
            val v1 = a1 * ic1 + a2 * v3
            val v2 = ic2 + a2 * ic1 + a3 * v3
            ic1 = 2f * v1 - ic1
            ic2 = 2f * v2 - ic2
            low = v2
            band = v1
            high = x - k * v1 - v2
            return this
        }
    }

    /** Compact Schroeder reverb (4 combs + 2 allpasses) for shimmer and cinematic tails. */
    class Reverb(rate: Int, private val size: Float = 0.8f, private val damp: Float = 0.3f) {
        private val combs = intArrayOf(1557, 1617, 1491, 1422).map { FloatArray(it * rate / 44_100) }
        private val combIdx = IntArray(4)
        private val combLp = FloatArray(4)
        private val alls = intArrayOf(556, 441).map { FloatArray(it * rate / 44_100) }
        private val allIdx = IntArray(2)

        fun process(x: Float): Float {
            var out = 0f
            for (c in 0 until 4) {
                val buf = combs[c]
                val y = buf[combIdx[c]]
                combLp[c] = y * (1f - damp) + combLp[c] * damp
                buf[combIdx[c]] = x + combLp[c] * size
                combIdx[c] = (combIdx[c] + 1) % buf.size
                out += y
            }
            out *= 0.25f
            for (a in 0 until 2) {
                val buf = alls[a]
                val y = buf[allIdx[a]]
                buf[allIdx[a]] = out + y * 0.5f
                out = y - out
                allIdx[a] = (allIdx[a] + 1) % buf.size
            }
            return out
        }
    }

    companion object {
        fun softClip(x: Float, drive: Float = 1f): Float = tanh(x * drive) / tanh(drive)

        fun midiToHz(note: Int): Float = 440f * 2f.pow((note - 69) / 12f)

        /** Scales [buffer] so its peak sits at [peakDb] dBFS. */
        fun normalize(buffer: FloatArray, peakDb: Float = -1f): FloatArray {
            val peak = buffer.maxOfOrNull { abs(it) } ?: 0f
            if (peak <= 1e-6f) return buffer
            val gain = 10f.pow(peakDb / 20f) / peak
            for (i in buffer.indices) buffer[i] *= gain
            return buffer
        }
    }
}
