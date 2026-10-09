package io.trimio.engine.audio

import io.trimio.core.model.audio.PcmAudio
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

/** Deterministic test signals with known loudness, pitch and timing. */
object Synth {
    fun dbToAmp(db: Double) = 10.0.pow(db / 20).toFloat()

    fun sine(hz: Double, peakDb: Double, ms: Int, rate: Int): FloatArray {
        val a = dbToAmp(peakDb)
        return FloatArray(rate * ms / 1000) { (a * sin(2 * PI * hz * it / rate)).toFloat() }
    }

    /** Voice-like tone: harmonics of [f0] with 1/k roll-off, like a glottal source. */
    fun voice(f0: Double, peakDb: Double, ms: Int, rate: Int): FloatArray {
        val n = rate * ms / 1000
        val raw = FloatArray(n) { i ->
            var v = 0.0
            var k = 1
            while (k * f0 < rate / 2.5 && k <= 20) {
                v += sin(2 * PI * k * f0 * i / rate) / k
                k++
            }
            v.toFloat()
        }
        val peak = raw.maxOf { kotlin.math.abs(it) }
        val a = dbToAmp(peakDb) / peak
        // 10 ms fades so word edges don't click.
        val fade = rate / 100
        return FloatArray(n) { i ->
            val env = minOf(1f, i.toFloat() / fade, (n - 1 - i).toFloat() / fade)
            raw[i] * a * env
        }
    }

    /** Low-level white noise from a fixed-seed LCG (room tone). */
    fun noise(peakDb: Double, ms: Int, rate: Int, seed: Int = 1): FloatArray {
        val a = dbToAmp(peakDb)
        var x = seed.toLong()
        return FloatArray(rate * ms / 1000) {
            x = (x * 1103515245 + 12345) and 0x7FFFFFFF
            a * ((x / 1073741823.5f) - 1f)
        }
    }

    fun concat(rate: Int, vararg parts: FloatArray): PcmAudio {
        val out = FloatArray(parts.sumOf { it.size })
        var pos = 0
        for (p in parts) {
            p.copyInto(out, pos)
            pos += p.size
        }
        return PcmAudio(out, rate)
    }

    /** Adds [b] onto [a] in place (same length or shorter). */
    fun mix(a: FloatArray, b: FloatArray): FloatArray {
        for (i in b.indices) if (i < a.size) a[i] += b[i]
        return a
    }
}
