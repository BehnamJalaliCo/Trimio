package io.trimio.engine.autopilot.judges

import io.trimio.engine.autopilot.Finding
import io.trimio.engine.autopilot.Judge
import io.trimio.engine.autopilot.Jury
import io.trimio.engine.autopilot.Severity
import io.trimio.engine.autopilot.Verdict
import io.trimio.engine.motion.recipe.Recipes
import io.trimio.engine.motion.score.Compiler
import io.trimio.engine.motion.score.Score

/**
 * The motion designer's eye on choreography. Every graphic holds long enough to be read: text at
 * least its reading time (the compiler's 0.35 s + 0.055 s a letter, at least 1 s), figures at
 * least [dataHold] (3.2 s; the cold open's figure holds while the opening plays), other elements their
 * recipe's own minimum. An entrance never plays over an exit in the same band; a treatment is not
 * used twice back to back within [repeatWindow]; and a longer piece uses a range of treatments.
 */
class MotionJudge(
    private val dataHold: Float = 3.2f,
    private val repeatWindow: Float = 6f,
) : Judge {
    override val name = "motion"
    override val dimension = "choreography"

    override fun score(c: Jury.Context): Verdict {
        val graphics = Bench.graphics(c).sortedBy { it.at }
        val f = mutableListOf<Finding>()
        f += short(c, graphics).map { (g, need) ->
            val held = g.out - g.at
            val sev = if (held >= need * MAJOR_SHARE) Severity.Minor else Severity.Major
            Bench.finding(g.at, sev, "${g.recipe} holds ${Bench.r1(held)}s of the ${Bench.r1(need)}s it needs", "hold it to ${Bench.r1(need)}s")
        }.capped(MAX_SHORT)
        f += crossing(graphics).map { (a, b) ->
            Bench.finding(b.at, Severity.Minor, "${b.recipe} enters over ${a.recipe}'s exit in the ${b.zone} band", "move it to the other band or wait for the exit")
        }.capped()
        f += repeats(c, graphics).map { (a, b) -> Bench.finding(b.at, Severity.Minor, "${b.recipe} twice back to back (${Bench.r1(a.at)}s, ${Bench.r1(b.at)}s)", "vary the second treatment") }.capped()
        f += variety(graphics)
        return Verdict.of(this, f)
    }

    /** Graphics on screen for less than they need, with what they need. */
    private fun short(c: Jury.Context, graphics: List<Compiler.Placed>): List<Pair<Compiler.Placed, Float>> {
        val prelude = c.edit?.preludeLength ?: 0f
        val end = c.duration - 0.05f
        return graphics.mapNotNull { g ->
            // The cold open's figure is up for the opening; a graphic cut by the end of the piece has nowhere to go.
            val opening = prelude > 0f && g.at < prelude
            val need = when {
                g.recipe in Bench.DATA && opening -> (prelude - OPENING_SLACK).coerceIn(MIN_OPENING, dataHold)
                g.recipe in Bench.DATA -> dataHold
                g.recipe in Bench.TEXT || (g.recipe == Bench.STAMP && g.text.isNotBlank()) -> maxOf(MIN_READ, 0.35f + 0.055f * g.text.length) * READ_SLACK
                else -> Recipes.named(g.recipe)?.minHold ?: MIN_READ
            }
            (g to need).takeIf { g.out - g.at < need - TOLERANCE && g.out < end }
        }
    }

    /** Pairs where the second enters while the first is still leaving the same band. */
    private fun crossing(graphics: List<Compiler.Placed>): List<Pair<Compiler.Placed, Compiler.Placed>> = graphics.flatMap { a ->
        graphics.filter { b -> b.at > a.at && sameBand(a, b) && b.at < a.out - CROSS_TOL && b.at > a.out - EXIT }.map { a to it }
    }

    private fun sameBand(a: Compiler.Placed, b: Compiler.Placed) = a.zone == b.zone || a.zone == "full" || b.zone == "full"

    /** The same treatment twice running; the cold open's figure is a flash-forward, not the first of a pair. */
    private fun repeats(c: Jury.Context, graphics: List<Compiler.Placed>) = graphics.zipWithNext().filter { (a, b) ->
        a.recipe == b.recipe && a.recipe !in REPEATABLE && b.at - a.out < repeatWindow && a.at >= (c.edit?.preludeLength ?: 0f)
    }

    private fun variety(graphics: List<Compiler.Placed>): List<Finding> {
        if (graphics.size < VARIETY_FROM) return emptyList()
        val counts = graphics.groupingBy { it.recipe }.eachCount()
        val top = counts.maxBy { it.value }
        return listOfNotNull(
            Bench.finding(0f, Severity.Minor, "only ${counts.size} treatments over ${graphics.size} graphics", "vary the treatments").takeIf { counts.size < MIN_KINDS },
            Bench.finding(0f, Severity.Minor, "${top.key} is ${top.value} of ${graphics.size} graphics", "vary the treatments").takeIf { top.value > graphics.size * MAX_SHARE },
        )
    }

    override fun revise(c: Jury.Context, verdict: Verdict): Score? {
        var score = c.score
        val graphics = Bench.graphics(c).sortedBy { it.at }
        for ((g, need) in short(c, graphics)) {
            val at = Bench.locate(score, c.words, g) ?: continue
            // Held longer; and when the next scene leaves no room after it, it lands a little earlier instead.
            val early = (need - (g.out - g.at) + 0.1f).coerceIn(0f, MAX_EARLY)
            score = Bench.mapBeat(score, at) { it.copy(hold = need + 0.1f, time = (g.at - early).coerceAtLeast(0f), at = null) }
            score = unblock(score, c, at.first, g.at + need + 0.1f)
        }
        for ((_, b) in crossing(graphics)) {
            if (!b.overFootage) continue
            Bench.locate(score, c.words, b)?.let { at -> score = Bench.mapBeat(score, at) { it.copy(place = Bench.otherBand(b.zone)) } }
        }
        for ((_, b) in repeats(c, graphics)) {
            val swap = Bench.ALTERNATE[b.recipe] ?: continue
            Bench.locate(score, c.words, b)?.let { at -> score = Bench.mapBeat(score, at) { it.copy(recipe = swap) } }
        }
        return score.takeIf { it != c.score }
    }

    /** A bare punch-in scene that would cut a held graphic short is folded into the scene before it. */
    private fun unblock(score: Score, c: Jury.Context, k: Int, until: Float): Score {
        val starts = Bench.sceneStarts(score, c.words)
        val next = k + 1
        val scene = score.scenes.getOrNull(next) ?: return score
        val bare = scene.beats.isEmpty() && scene.bg == null
        return if (bare && starts[next] < until) score.copy(scenes = score.scenes.filterIndexed { i, _ -> i != next }) else score
    }

    private companion object {
        const val MIN_READ = 1.0f
        const val MAX_EARLY = 0.6f
        const val READ_SLACK = 0.9f
        const val MIN_OPENING = 1.2f
        const val OPENING_SLACK = 0.1f
        /** A tenth of a second short is not seen; more is. */
        const val TOLERANCE = 0.15f
        const val MAJOR_SHARE = 0.75f
        const val MAX_SHORT = 4
        const val CROSS_TOL = 0.1f
        const val EXIT = 0.6f
        const val VARIETY_FROM = 6
        const val MIN_KINDS = 3
        const val MAX_SHARE = 0.6f
        /** Each figure is its own news: two counters in a row show two different numbers. */
        val REPEATABLE = setOf("comment", "message", "counter")
    }
}
