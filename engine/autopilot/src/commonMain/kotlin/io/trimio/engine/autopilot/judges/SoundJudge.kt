package io.trimio.engine.autopilot.judges

import io.trimio.core.model.transcript.Word
import io.trimio.engine.autopilot.Finding
import io.trimio.engine.autopilot.Judge
import io.trimio.engine.autopilot.Jury
import io.trimio.engine.autopilot.Severity
import io.trimio.engine.autopilot.Soundtrack
import io.trimio.engine.autopilot.Verdict
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * The re-recording mixer. Measured on the mastered mix when there is one: integrated loudness
 * −14 LUFS ±[lufsTolerance], true peak (4× oversampled) at most [ceilingDbtp], the bed never
 * louder in the pauses than the voice is while it speaks ([gapBelowDb]), no clicks where the voice is
 * spliced. From the plan, always: the music ducks under words by the levels the mix uses, and
 * sound cues stay an accent — at most [cuesPerMinute] a minute and [cuesPer2s] in any two seconds.
 */
class SoundJudge(
    private val targetLufs: Double = -14.0,
    private val lufsTolerance: Double = 1.0,
    private val ceilingDbtp: Double = -1.0,
    private val gapBelowDb: Double = 0.0,
    private val cuesPerMinute: Int = 45,
    private val cuesPer2s: Int = 4,
) : Judge {
    override val name = "sound"
    override val dimension = "mix"

    override fun score(c: Jury.Context): Verdict {
        val f = mutableListOf<Finding>()
        val lv = c.levels
        if (c.music != null && (lv.musicUnderDb > MAX_UNDER_DB || lv.musicUnderDb > lv.musicGapDb - MIN_DUCK_DB)) {
            f += Bench.finding(0f, Severity.Major, "music ducks only to ${lv.musicUnderDb} dB under the voice", "duck the bed to −20 dB under words")
        }
        f += cues(c)
        val sound = c.sound
        if (sound == null) {
            f += Bench.finding(0f, Severity.Note, "not mastered yet: loudness judged after mixing", "-")
            return Verdict.of(this, f)
        }
        if (abs(sound.lufs - targetLufs) > lufsTolerance) {
            f += Bench.finding(0f, Severity.Major, "integrated loudness ${r1(sound.lufs)} LUFS", "master to $targetLufs LUFS")
        }
        val tp = truePeakDb(sound.audio.samples)
        if (tp > ceilingDbtp) f += Bench.finding(0f, Severity.Major, "true peak ${r1(tp)} dBTP", "limit to $ceilingDbtp dBTP")
        val words = c.prelude + c.words
        gapLevel(sound.audio.samples, sound.audio.sampleRate, words, c.duration)?.let { (speech, gap) ->
            if (gap > speech - gapBelowDb) f += Bench.finding(0f, Severity.Minor, "the bed swells to ${r1(gap - speech)} dB of the voice in pauses", "keep the bed under the voice")
        }
        f += clicks(c, sound)
        return Verdict.of(this, f)
    }

    private fun cues(c: Jury.Context): List<Finding> {
        val at = c.compiled.sfx.map { it.at }.sorted()
        val out = mutableListOf<Finding>()
        val minutes = (c.duration / 60f).coerceAtLeast(MIN_MINUTES)
        if (at.size / minutes > cuesPerMinute) out += Bench.finding(0f, Severity.Minor, "${at.size} sound cues in ${r1(c.duration.toDouble())}s", "sound only the moments that matter")
        at.firstOrNull { t -> at.count { it >= t && it < t + 2f } > cuesPer2s }?.let {
            out += Bench.finding(it, Severity.Minor, "more than $cuesPer2s sound cues in two seconds", "thin the cues")
        }
        return out
    }

    /** Clicks at the voice's splices: a sample step far sharper than anything around it. */
    private fun clicks(c: Jury.Context, sound: Soundtrack.Result): List<Finding> {
        val x = sound.audio.samples
        val rate = sound.audio.sampleRate
        val joins = c.edit?.played.orEmpty().map { it.outStart }.filter { it > 0f }
        return joins.mapNotNull { t ->
            val i = (t * rate).toInt()
            val near = rate * NEAR_MS / 1000
            val wide = rate * WIDE_MS / 1000
            val jump = (i - near..i + near).filter { it in 1 until x.size }.maxOfOrNull { abs(x[it] - x[it - 1]) } ?: return@mapNotNull null
            // The neighbourhood's own sharpest steps (its 99th percentile): noise and consonants are not clicks.
            val around = ((i - wide..i - near) + (i + near..i + wide)).filter { it in 1 until x.size }.map { abs(x[it] - x[it - 1]) }.sorted()
            val sharp = around.getOrNull((around.size * SHARP_PERCENTILE).toInt()) ?: 0f
            Bench.finding(t, Severity.Minor, "a click at the cut", "a short fade at the splice").takeIf { jump > CLICK_ABS && jump > sharp * CLICK_RATIO }
        }.capped()
    }

    /** RMS of the mix while words are said and in pauses of [MIN_GAP_S] or more, in dB; null when there are no pauses. */
    private fun gapLevel(x: FloatArray, rate: Int, words: List<Word>, duration: Float): Pair<Double, Double>? {
        val speech = BooleanArray(x.size)
        for (w in words) {
            val a = ((w.range.startMs / 1000f - PAD) * rate).toInt().coerceAtLeast(0)
            val b = ((w.range.endMs / 1000f + PAD) * rate).toInt().coerceAtMost(x.size)
            for (i in a until b) speech[i] = true
        }
        val head = (HEAD_S * rate).toInt()
        val tail = ((duration - TAIL_S) * rate).toInt().coerceAtMost(x.size)
        var gapSum = 0.0
        var gapN = 0
        var run = 0
        var speechSum = 0.0
        var speechN = 0
        for (i in head until tail) {
            val v = x[i].toDouble() * x[i]
            if (speech[i]) {
                speechSum += v; speechN++; run = 0
            } else {
                run++
                if (run > MIN_GAP_S * rate) { gapSum += v; gapN++ }
            }
        }
        if (gapN == 0 || speechN == 0) return null
        return db(sqrt(speechSum / speechN)) to db(sqrt(gapSum / gapN))
    }

    private fun r1(v: Double) = kotlin.math.round(v * 10.0) / 10.0

    companion object {
        private const val MAX_UNDER_DB = -15f
        private const val MIN_DUCK_DB = 4f
        private const val MIN_MINUTES = 0.25f
        private const val NEAR_MS = 4
        private const val WIDE_MS = 30
        private const val CLICK_ABS = 0.05f
        private const val CLICK_RATIO = 2.5f
        private const val SHARP_PERCENTILE = 0.99f
        private const val PAD = 0.15f
        private const val HEAD_S = 0.3f
        private const val TAIL_S = 1.0f
        private const val MIN_GAP_S = 0.25f

        private fun db(v: Double) = if (v <= 0.0) SILENT else 20.0 * log10(v)

        /**
         * True peak (ITU-R BS.1770 style): the signal 4× oversampled with a windowed-sinc
         * interpolator ([TAPS] taps a side), where a reconstruction overshoots between samples the
         * way a DAC does. Cheap: only the stretches near the sample peak are interpolated.
         */
        fun truePeakDb(x: FloatArray): Double {
            val peak = x.maxOfOrNull { abs(it) } ?: return SILENT
            var best = peak
            val near = peak * NEAR_PEAK
            for (i in TAPS until x.size - TAPS) {
                if (abs(x[i]) < near && abs(x[i + 1]) < near) continue
                for (k in 1 until OVERSAMPLE) best = maxOf(best, abs(between(x, i, k.toFloat() / OVERSAMPLE)))
            }
            return db(best.toDouble())
        }

        /** The band-limited value at [i] + [frac] (Hann-windowed sinc). */
        private fun between(x: FloatArray, i: Int, frac: Float): Float {
            var sum = 0f
            for (j in -TAPS + 1..TAPS) {
                val d = j - frac
                val w = 0.5f + 0.5f * kotlin.math.cos(PI_F * d / TAPS)
                sum += x[i + j] * sinc(d) * w
            }
            return sum
        }

        private fun sinc(d: Float) = if (abs(d) < 1e-6f) 1f else kotlin.math.sin(PI_F * d) / (PI_F * d)

        private const val TAPS = 8
        private const val OVERSAMPLE = 4
        private const val NEAR_PEAK = 0.7f
        private const val PI_F = kotlin.math.PI.toFloat()
        private const val SILENT = -120.0

        /**
         * Mastering levels that bring a mix's true peak under the ceiling: the limiter's ceiling
         * lowered by the overshoot (and a little more); null when the peak is fine.
         */
        fun remaster(sound: Soundtrack.Result, levels: Soundtrack.Levels, ceilingDbtp: Double = -1.0): Soundtrack.Levels? {
            val over = truePeakDb(sound.audio.samples) - ceilingDbtp
            return if (over > 0.0) levels.copy(ceilingDb = levels.ceilingDb - over.toFloat() - MARGIN_DB) else null
        }

        private const val MARGIN_DB = 0.1f

    }
}
