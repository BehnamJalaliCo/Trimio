package io.trimio.engine.assets

import io.trimio.core.model.audio.PcmAudio
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Tempo, beats and downbeats of a piece of music, for tracks that were not composed here (a
 * downloaded music pack): the edit snaps to the grid it finds exactly as it does to a composed
 * cue's. Constant tempo is assumed (library cues and loops are produced on a click).
 *
 * 1. Onset strength every 2.5 ms: half-wave-rectified log-energy flux in three bands (kick, body, air).
 * 2. Tempo: the autocorrelation of the onset curve over 60–180 bpm, weighted toward 120 (where
 *    octave errors are least likely), then refined by a least-squares fit of the onset peaks
 *    nearest each predicted beat — sub-millisecond over a whole cue.
 * 3. Downbeats: chords change on bar lines, so the beat phase (of four) where the harmony moves
 *    most (chroma novelty from Goertzel filters, 36 semitones) plus the kick is the bar's one.
 */
object BeatTracker {

    data class Analysis(val grid: BeatGrid, val beats: List<Double>, val confidence: Double)

    private const val FRAMES_PER_S = 400
    private const val MIN_BPM = 60.0
    private const val MAX_BPM = 180.0
    private const val PRIOR_BPM = 120.0
    private const val PRIOR_OCTAVES = 1.0
    private const val REFINE_PASSES = 3

    /** Analyses [audio] between [from] and [to] seconds (the whole of it by default); null when there is no pulse. */
    fun analyse(audio: PcmAudio, from: Double = 0.0, to: Double = audio.size.toDouble() / audio.sampleRate): Analysis? {
        val rate = audio.sampleRate
        val a = (from * rate).roundToInt().coerceIn(0, audio.size)
        val b = (to * rate).roundToInt().coerceIn(a, audio.size)
        val x = audio.samples.copyOfRange(a, b)
        val hop = rate / FRAMES_PER_S
        val (onset, kick) = onsets(x, rate, hop)
        if (onset.size < FRAMES_PER_S * 2) return null
        val coarse = tempo(onset) ?: return null
        var period = coarse
        var phase = phase(onset, period)
        repeat(REFINE_PASSES) { refine(onset, period, phase)?.let { (p, ph) -> period = p; phase = ph } }
        comb(onset, period, phase).let { (p, ph) -> period = p; phase = ph }
        // Off-beats (open hats, off-beat bass) can outweigh the beat in the full onset; the kick decides which half is the beat.
        if (combSum(kick, period, phase + period / 2) > combSum(kick, period, phase) * OFFBEAT_MARGIN) phase = (phase + period / 2) % period
        val periodS = period / FRAMES_PER_S
        // Frame n covers [n·hop, (n+1)·hop): a transient shows its flux at the frame it starts in.
        val firstBeat = phase / FRAMES_PER_S + from + LATENCY_S
        val count = ((to - firstBeat) / periodS).toInt() + 1
        val beats = (0 until count).map { firstBeat + it * periodS }.filter { it < to }
        val bar = downbeatPhase(audio, beats, periodS)
        val grid = BeatGrid(60.0 / periodS, offset = beats.getOrElse(bar) { firstBeat })
        return Analysis(grid, beats, confidence(onset, period, phase))
    }

    /** Small fixed delay of the flux detector (the energy rise lands in the frame after the onset sample). */
    private const val LATENCY_S = -0.5 / FRAMES_PER_S

    // ----------------------------------------------------------------------------------- onsets

    private const val OFFBEAT_MARGIN = 1.2

    private fun combSum(o: FloatArray, period: Double, phase: Double): Double {
        var sum = 0.0
        var t = phase
        while (t < o.size - 1) { sum += o[t.roundToInt()]; t += period }
        return sum
    }

    /** The combined onset strength, and the kick band's alone. */
    private fun onsets(x: FloatArray, rate: Int, hop: Int): Pair<FloatArray, FloatArray> {
        val frames = x.size / hop
        val low = Synth.Svf(rate)
        val mid = Synth.Svf(rate)
        val high = Synth.Svf(rate)
        val bands = Array(3) { DoubleArray(frames) }
        for (f in 0 until frames) {
            var l = 0.0; var m = 0.0; var h = 0.0
            for (i in f * hop until (f + 1) * hop) {
                val v = x[i]
                l += low.process(v, 150f).low.sq()
                m += mid.process(v, 1_500f, 0.6f).band.sq()
                h += high.process(v, 6_000f).high.sq()
            }
            bands[0][f] = l; bands[1][f] = m; bands[2][f] = h
        }
        val out = FloatArray(frames)
        val kick = FloatArray(frames)
        for ((b, band) in bands.withIndex()) {
            val mean = band.average().coerceAtLeast(1e-12)
            var prev = 0.0
            val flux = DoubleArray(frames)
            for (f in 0 until frames) {
                val e = ln(1.0 + band[f] / mean * 10.0)
                flux[f] = max(0.0, e - prev)
                prev = e
            }
            val norm = flux.average().coerceAtLeast(1e-9)
            for (f in 0 until frames) out[f] += (flux[f] / norm).toFloat()
            if (b == 0) for (f in 0 until frames) kick[f] = (flux[f] / norm).toFloat()
        }
        return out to kick
    }

    private fun Float.sq() = this.toDouble() * this

    // ------------------------------------------------------------------------------------ tempo

    /** The beat period in frames from the onset autocorrelation (and its multiples), weighted by a tempo prior. */
    private fun tempo(o: FloatArray): Double? {
        val mean = o.average().toFloat()
        // Onset peaks are a frame wide: smoothed (±3 frames) so a fractional period still lines them up at an integer lag.
        val c = FloatArray(o.size) { i -> var v = 0f; for (d in -3..3) v += o.getOrElse(i + d) { mean } * (4 - kotlin.math.abs(d)); v / 16f - mean }
        val minLag = (60.0 / MAX_BPM * FRAMES_PER_S).toInt()
        val maxLag = min(o.size / 2, (60.0 / MIN_BPM * FRAMES_PER_S).toInt() + 1)
        val longest = min(o.size - 1, 4 * maxLag + 1)
        val ac = DoubleArray(longest + 2)
        for (lag in minLag - 1..longest) {
            if (lag >= o.size) continue
            var sum = 0.0
            for (i in 0 until o.size - lag) sum += c[i] * c[i + lag]
            ac[lag] = sum / (o.size - lag)
        }
        var best = -1
        var bestScore = 0.0
        for (lag in minLag..maxLag) {
            val bpm = 60.0 * FRAMES_PER_S / lag
            val octaves = ln(bpm / PRIOR_BPM) / ln(2.0)
            val weight = exp(-0.5 * (octaves / PRIOR_OCTAVES) * (octaves / PRIOR_OCTAVES))
            // Metrical consistency: a true beat period repeats at the half bar and the bar too (a dotted figure does not).
            val score = (ac[lag] + 0.5 * ac.getOrElse(2 * lag) { 0.0 } + 0.75 * ac.getOrElse(4 * lag) { 0.0 }) * weight
            if (score > bestScore) { bestScore = score; best = lag }
        }
        if (best < 0) return null
        // Parabolic interpolation around the peak: a fractional lag.
        val y0 = ac[best - 1]; val y1 = ac[best]; val y2 = ac[best + 1]
        val d = (y0 - 2 * y1 + y2).let { if (abs(it) < 1e-12) 0.0 else 0.5 * (y0 - y2) / it }
        return best + d.coerceIn(-0.5, 0.5)
    }

    /** The phase (frames) whose comb collects the most onset energy. */
    private fun phase(o: FloatArray, period: Double): Double {
        var best = 0.0
        var bestScore = -1.0
        var p = 0.0
        while (p < period) {
            var sum = 0.0
            var t = p
            while (t < o.size - 1) { sum += o[t.roundToInt()]; t += period }
            if (sum > bestScore) { bestScore = sum; best = p }
            p += 1.0
        }
        return best
    }

    /** Least-squares line through the strongest onset near each predicted beat: period and phase, refined. */
    private fun refine(o: FloatArray, period: Double, phase: Double): Pair<Double, Double>? {
        val window = (period / 8).toInt().coerceAtLeast(2)
        val ks = ArrayList<Double>()
        val ts = ArrayList<Double>()
        var k = 0
        while (true) {
            val predicted = phase + k * period
            if (predicted >= o.size - window) break
            val c = predicted.roundToInt()
            var bi = -1
            var bv = 0f
            for (i in max(1, c - window)..min(o.size - 2, c + window)) if (o[i] > bv) { bv = o[i]; bi = i }
            // Only clear onsets vote (above the curve's typical level).
            if (bi >= 0 && bv > STRONG) {
                // Sub-frame peak position.
                val d = (o[bi - 1] - 2 * o[bi] + o[bi + 1]).let { if (abs(it) < 1e-9f) 0.0 else 0.5 * (o[bi - 1] - o[bi + 1]) / it }
                ks += k.toDouble(); ts += bi + d.coerceIn(-0.5, 0.5)
            }
            k++
        }
        if (ks.size < 4) return null
        val mk = ks.average(); val mt = ts.average()
        var num = 0.0; var den = 0.0
        for (i in ks.indices) { num += (ks[i] - mk) * (ts[i] - mt); den += (ks[i] - mk) * (ks[i] - mk) }
        if (den <= 0.0) return null
        val p = num / den
        if (abs(p / period - 1.0) > 0.05) return null
        val ph = mt - p * mk
        return p to (ph - kotlin.math.floor(ph / p) * p)
    }

    private const val STRONG = 1.5f

    /**
     * The last step: the period (±0.3 %) and phase (±3 frames) whose comb of beats collects the
     * most onset energy, sampled between frames. Where the line fit is pulled by onsets of mixed
     * kinds (a swung groove), the comb still peaks where every beat lands.
     */
    private fun comb(o: FloatArray, period: Double, phase: Double): Pair<Double, Double> {
        var best = period to phase
        var bestScore = -1.0
        for (pi in -COMB_STEPS..COMB_STEPS) {
            val p = period * (1.0 + pi * COMB_SPAN / COMB_STEPS)
            for (qi in -12..12) {
                val ph = phase + qi * 0.25
                if (ph < 0) continue
                var sum = 0.0
                var t = ph
                while (t < o.size - 2) {
                    val i = t.toInt()
                    val f = (t - i).toFloat()
                    sum += o[i] * (1 - f) + o[i + 1] * f
                    t += p
                }
                if (sum > bestScore) { bestScore = sum; best = p to ph }
            }
        }
        return best
    }

    private const val COMB_STEPS = 30
    private const val COMB_SPAN = 0.003

    private fun confidence(o: FloatArray, period: Double, phase: Double): Double {
        var on = 0.0
        var t = phase
        var n = 0
        while (t < o.size - 1) { on += o[t.roundToInt()]; n++; t += period }
        val mean = o.average()
        return if (n == 0 || mean <= 0.0) 0.0 else (on / n / mean / 10.0).coerceIn(0.0, 1.0)
    }

    // -------------------------------------------------------------------------------- downbeats

    /**
     * Index (0..3) of the first beat that is a downbeat. Each boundary is scored by how different
     * the harmony of the four beats after it is from the four before: only bar-aligned blocks hold
     * one chord each, while a backbeat or a hat pattern is the same in every four-beat block.
     */
    private fun downbeatPhase(audio: PcmAudio, beats: List<Double>, periodS: Double): Int {
        if (beats.size < 12) return 0
        val chroma = beats.map { chromaAt(audio, it + periodS * 0.1, periodS * 0.85) }
        val score = DoubleArray(4)
        for (k in 4..beats.size - 4) {
            val before = DoubleArray(12).also { acc -> for (j in k - 4 until k) for (c in 0 until 12) acc[c] += chroma[j][c] }
            val after = DoubleArray(12).also { acc -> for (j in k until k + 4) for (c in 0 until 12) acc[c] += chroma[j][c] }
            score[k % 4] += 1.0 - correlation(before, after)
        }
        return score.indices.maxBy { score[it] }
    }

    private const val CHROMA_LOW_MIDI = 40
    private const val CHROMA_NOTES = 36

    private fun chromaAt(audio: PcmAudio, at: Double, length: Double): DoubleArray {
        val rate = audio.sampleRate
        val a = (at * rate).roundToInt().coerceIn(0, audio.size)
        val n = min((length * rate).roundToInt(), MAX_WINDOW).coerceAtMost(audio.size - a)
        val out = DoubleArray(12)
        if (n < 256) return out
        for (note in 0 until CHROMA_NOTES) {
            val midi = CHROMA_LOW_MIDI + note
            val f = 440.0 * Math2.pow2((midi - 69) / 12.0)
            out[midi % 12] += sqrt(goertzel(audio.samples, a, n, f / rate))
        }
        return out
    }

    private const val MAX_WINDOW = 16_384

    private fun goertzel(x: FloatArray, from: Int, n: Int, cycles: Double): Double {
        val w = 2.0 * PI * cycles
        val coeff = 2.0 * cos(w)
        var s1 = 0.0; var s2 = 0.0
        for (i in 0 until n) {
            // Hann window: less leakage between neighbouring semitones.
            val win = 0.5 - 0.5 * cos(2.0 * PI * i / (n - 1))
            val s0 = x[from + i] * win + coeff * s1 - s2
            s2 = s1; s1 = s0
        }
        return (s1 * s1 + s2 * s2 - coeff * s1 * s2) / n
    }

    /** Pearson correlation: a flat (noise) component in both vectors does not count as similarity. */
    private fun correlation(a: DoubleArray, b: DoubleArray): Double {
        val ma = a.average(); val mb = b.average()
        var ab = 0.0; var aa = 0.0; var bb = 0.0
        for (i in a.indices) { ab += (a[i] - ma) * (b[i] - mb); aa += (a[i] - ma) * (a[i] - ma); bb += (b[i] - mb) * (b[i] - mb) }
        return if (aa <= 0.0 || bb <= 0.0) 1.0 else ab / sqrt(aa * bb)
    }

    private object Math2 {
        fun pow2(x: Double): Double = exp(x * ln(2.0))
    }
}
