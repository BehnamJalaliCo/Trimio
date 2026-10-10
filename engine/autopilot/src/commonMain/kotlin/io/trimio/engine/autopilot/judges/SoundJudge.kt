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
 * −14 LUFS ±[lufsTolerance], true peak (4× interpolated) at most [ceilingDbtp], the bed below the
 * voice in the pauses (at least [gapBelowDb] under speech level), no clicks where the voice is
 * spliced. From the plan, always: the music ducks under words by the levels the mix uses, and
 * sound cues stay an accent — at most [cuesPerMinute] a minute and [cuesPer2s] in any two seconds.
 */
class SoundJudge(
    private val targetLufs: Double = -14.0,
    private val lufsTolerance: Double = 1.0,
    private val ceilingDbtp: Double = -1.0,
    private val gapBelowDb: Double = 4.0,
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

    /** Clicks at the voice's splices: a sample jump far above the local slope. */
    private fun clicks(c: Jury.Context, sound: Soundtrack.Result): List<Finding> {
        val x = sound.audio.samples
        val rate = sound.audio.sampleRate
        val joins = c.edit?.played.orEmpty().map { it.outStart }.filter { it > 0f }
        return joins.mapNotNull { t ->
            val i = (t * rate).toInt()
            val near = rate * NEAR_MS / 1000
            val wide = rate * WIDE_MS / 1000
            val jump = (i - near..i + near).filter { it in 1 until x.size }.maxOfOrNull { abs(x[it] - x[it - 1]) } ?: return@mapNotNull null
            val around = ((i - wide..i - near) + (i + near..i + wide)).filter { it in 1 until x.size }
            val slope = if (around.isEmpty()) 0f else around.sumOf { abs(x[it] - x[it - 1]).toDouble() }.toFloat() / around.size
            Bench.finding(t, Severity.Minor, "a click at the cut", "a short fade at the splice").takeIf { jump > CLICK_ABS && jump > slope * CLICK_RATIO }
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
        private const val CLICK_RATIO = 8f
        private const val PAD = 0.15f
        private const val HEAD_S = 0.3f
        private const val TAIL_S = 1.0f
        private const val MIN_GAP_S = 0.25f

        private fun db(v: Double) = if (v <= 0.0) -120.0 else 20.0 * log10(v)

        /**
         * True peak, approximated by 4× Catmull-Rom interpolation around the loud samples (a
         * reconstruction overshoots between samples the way a DAC does). Cheap: only samples near
         * the sample peak are interpolated.
         */
        fun truePeakDb(x: FloatArray): Double {
            val peak = x.maxOfOrNull { abs(it) } ?: return -120.0
            var best = peak
            val near = peak * NEAR_PEAK
            for (i in 1 until x.size - 2) {
                if (abs(x[i]) < near && abs(x[i + 1]) < near) continue
                for (k in 1..3) best = maxOf(best, abs(catmull(x[i - 1], x[i], x[i + 1], x[i + 2], k / 4f)))
            }
            return db(best.toDouble())
        }

        private const val NEAR_PEAK = 0.7f

        private fun catmull(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
            val t2 = t * t
            val t3 = t2 * t
            return 0.5f * (2f * p1 + (-p0 + p2) * t + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 + (-p0 + 3f * p1 - 3f * p2 + p3) * t3)
        }

        /** Gentler mastering levels when the mix broke loudness or peak; null when it did not. */
        fun remaster(v: Verdict?, levels: Soundtrack.Levels): Soundtrack.Levels? {
            val broke = v?.findings?.any { "loudness" in it.what || "true peak" in it.what } == true
            return if (broke) levels.copy(ceilingDb = minOf(levels.ceilingDb, SAFER_CEILING)) else null
        }

        private const val SAFER_CEILING = -1.7f
    }
}
