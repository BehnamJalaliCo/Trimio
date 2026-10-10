package io.trimio.engine.autopilot.judges

import io.trimio.engine.autopilot.Finding
import io.trimio.engine.autopilot.Judge
import io.trimio.engine.autopilot.Jury
import io.trimio.engine.autopilot.Severity
import io.trimio.engine.autopilot.Verdict
import io.trimio.engine.motion.score.Compiler
import io.trimio.engine.motion.score.Score

/**
 * The editor's sense of pace. Over the speaker, no stretch longer than [maxDead] (2.6 s) passes
 * without a visual change — a graphic landing or leaving, a scene cut, a jump cut (the critic's
 * notion: a graphic on screen is alive); no more than two graphics share the screen; no cut
 * falls inside a word; and jump cuts stay humane (no flash shots under [minShot], no more than
 * [maxCutsPer10s] cuts in ten seconds).
 */
class RhythmJudge(
    private val maxDead: Float = 2.6f,
    private val majorDead: Float = 3.6f,
    private val minShot: Float = 0.3f,
    private val maxCutsPer10s: Int = 7,
) : Judge {
    override val name = "rhythm"
    override val dimension = "edit pace"

    override fun score(c: Jury.Context): Verdict {
        val graphics = Bench.graphics(c)
        val f = mutableListOf<Finding>()
        f += dead(c, graphics).map { (a, b) ->
            val sev = if (b - a > majorDead) Severity.Major else Severity.Minor
            Bench.finding(a, sev, "${Bench.r1(b - a)}s over the speaker without a visual change", "punch in or land a graphic in the middle")
        }
        f += clutter(graphics).map { g -> Bench.finding(g.at, Severity.Major, "three graphics share the screen around ${g.recipe}", "drop or shorten one of them") }
        f += midWord(c)
        f += density(c)
        return Verdict.of(this, f)
    }

    /** Stretches over footage with no change and no graphic on screen. */
    private fun dead(c: Jury.Context, graphics: List<Compiler.Placed>): List<Pair<Float, Float>> {
        val duration = c.duration
        val cuts = c.edit?.played.orEmpty().map { it.outStart }.filter { it > 0.05f }
        val changes = (graphics.flatMap { listOf(it.at, it.out) } + Bench.sceneStarts(c.score, c.words) + cuts + 0f + duration)
            .map { it.coerceIn(0f, duration) }.distinct().sorted()
        return changes.zipWithNext().filter { (a, b) ->
            b - a > maxDead && graphics.none { g -> g.at < b - 0.05f && g.out > a + 0.05f } && Bench.overFootageAt(c, (a + b) / 2f)
        }
    }

    /** One graphic of each cluster of three or more on screen together. */
    private fun clutter(graphics: List<Compiler.Placed>): List<Compiler.Placed> {
        val crowded = graphics.filter { g -> graphics.count { o -> o !== g && Bench.overlap(o.at, o.out, g.at, g.out) > OVERLAP } >= 2 }
        val out = mutableListOf<Compiler.Placed>()
        for (g in crowded.sortedBy { it.at }) if (out.none { kotlin.math.abs(it.at - g.at) < 1f }) out += g
        return out
    }

    /** Cuts of the body that fall inside a word (on the source clock, judged on the voice when it is here). */
    private fun midWord(c: Jury.Context): List<Finding> {
        val segments = c.edit?.segments ?: return emptyList()
        return segments.flatMapIndexed { i, s ->
            listOfNotNull(
                s.takeIf { i > 0 }?.let { Bench.cutInto(c, it.sourceStart, entering = true)?.let { w -> it.outStart to w } },
                s.takeIf { i < segments.lastIndex }?.let { Bench.cutInto(c, it.sourceEnd, entering = false)?.let { w -> it.outEnd to w } },
            )
        }.map { (out, w) -> Bench.finding(out, Severity.Major, "a cut falls inside «$w»", "move the cut to the pause before or after the word") }
            .distinctBy { it.what }.capped()
    }

    private fun density(c: Jury.Context): List<Finding> {
        val shots = c.edit?.played.orEmpty()
        val out = mutableListOf<Finding>()
        shots.firstOrNull { it.length < minShot }?.let {
            out += Bench.finding(it.outStart, Severity.Minor, "a ${Bench.r1(it.length)}s shot flashes between jump cuts", "merge it into its neighbour")
        }
        val starts = shots.map { it.outStart }.filter { it > 0f }
        starts.firstOrNull { t -> starts.count { it >= t && it < t + 10f } > maxCutsPer10s }?.let {
            out += Bench.finding(it, Severity.Minor, "more than $maxCutsPer10s jump cuts in ten seconds", "keep a breath at some pauses")
        }
        return out
    }

    override fun revise(c: Jury.Context, verdict: Verdict): Score? {
        var score = c.score
        val graphics = Bench.graphics(c)
        // Dead stretches: punch-in cuts that split them evenly (the cheapest, most natural change).
        for ((a, b) in dead(c, graphics)) {
            // Aim under the limit: cuts snap to word starts.
            val parts = kotlin.math.ceil((b - a) / (maxDead * SPLIT)).toInt().coerceAtLeast(2)
            for (k in 1 until parts) score = Bench.punchAt(score, c.words, a + (b - a) * k / parts) ?: score
        }
        // Clutter: the latest arrival of each crowd leaves, unless it is the call to action or a figure the piece hangs on.
        for (g in clutter(graphics)) {
            val crowd = graphics.filter { o -> Bench.overlap(o.at, o.out, g.at, g.out) > OVERLAP }.filter { it.recipe !in PROTECTED }
            val leaving = crowd.maxByOrNull { it.at } ?: continue
            Bench.locate(score, c.words, leaving)?.let { at -> score = Bench.mapBeat(score, at) { null } }
        }
        return score.takeIf { it != c.score }
    }

    private companion object {
        const val OVERLAP = 0.1f
        const val SPLIT = 0.8f
        val PROTECTED = setOf("comment", "message", "voucher", "stats", "countdown", "progress")
    }
}
