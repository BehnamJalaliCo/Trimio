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
 * least [dataHold] (3.2 s; the cold open's figure holds while the opening plays; one figure alone on a stage 2.2 s), other elements their
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
        // On a stage of its own a card replacing the one before is a slide: one leaves as the next comes in.
        f += crossing(graphics).filterNot { (a, b) -> c.footage && !a.overFootage && !b.overFootage }.map { (a, b) ->
            Bench.finding(b.at, Severity.Minor, "${b.recipe} enters over ${a.recipe}'s exit in the ${b.zone} band", "move it to the other band or wait for the exit")
        }.capped()
        f += repeats(c, graphics).map { (a, b) ->
            // The same device twice running reads as a loop, whatever its layout.
            val sev = Severity.Major
            Bench.finding(b.at, sev, "${b.recipe} twice back to back (${Bench.r1(a.at)}s, ${Bench.r1(b.at)}s)", "another device, or at least another layout of it")
        }.capped()
        f += variety(graphics)
        // The right element for the moment: a graphic shows what is being said while it is up.
        f += Relevance(c).misses().map { m ->
            val fix = if (m.saidAt != null) "land it where it is said" else "show what this line says"
            Bench.finding(m.placed.at, Severity.Major, "${m.placed.recipe} shows «${m.shown}» while something else is said", fix)
        }.capped(MAX_SHORT)
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
                // One figure filling a frame of its own («۱۰», what it counts under it) is read in a glance.
                g.recipe in STAGE_FIGURES && !g.overFootage -> STAGE_FIGURE_HOLD
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
        // A longer piece earns more devices: a third of its graphics, between three and six.
        val kinds = (graphics.size / KINDS_PER).coerceIn(MIN_KINDS, MAX_KINDS)
        // Families (titles, figures, pictures, cards…): no one kind of element carries the piece.
        val families = graphics.groupingBy { family(it.recipe) }.eachCount()
        val big = families.maxBy { it.value }
        val share = big.value / graphics.size.toFloat()
        return listOfNotNull(
            Bench.finding(0f, if (share > HEAVY_FAMILY) Severity.Major else Severity.Minor, "${big.key} are ${big.value} of ${graphics.size} graphics", "mix in other kinds of element")
                .takeIf { share > MAX_FAMILY },
            Bench.finding(0f, Severity.Minor, "only ${counts.size} treatments over ${graphics.size} graphics", "vary the treatments").takeIf { counts.size < kinds },
            Bench.finding(0f, Severity.Minor, "${top.key} is ${top.value} of ${graphics.size} graphics", "vary the treatments").takeIf { top.value > graphics.size * MAX_SHARE },
        )
    }

    /** A text graphic out of its moment, retitled with the key phrase of the line it lands on; null when it is not words or the line has none. */
    private fun retitled(c: Jury.Context, s: io.trimio.engine.motion.score.BeatScore, m: Relevance.Miss): io.trimio.engine.motion.score.BeatScore? {
        if (s.recipe !in Bench.TEXT) return null
        val line = c.lines.firstOrNull { l -> Bench.start(c.words, l.first) <= m.placed.at + LAND && Bench.start(c.words, l.last) >= m.placed.at - LAND } ?: return null
        val title = io.trimio.engine.autopilot.Phrases.title(c.words, line, alone = false).ifBlank { return null }
        // It keeps its own landing (its scene and timing stay); only what it says changes.
        return s.copy(text = title, emphasis = emptyList()).takeIf { title != s.text }
    }

    /** A counter as another figure device: a ring for a share, else a stat card of the same number and unit. */
    private fun otherFigure(b: io.trimio.engine.motion.score.BeatScore): io.trimio.engine.motion.score.BeatScore {
        val v = b.value ?: return b
        val unit = listOfNotNull(b.suffix, b.label).joinToString(" ").trim()
        val share = PERCENT.any { it in unit } && v in 0f..100f
        return if (share) {
            b.copy(recipe = "ring", label = b.label ?: unit, variant = null)
        } else {
            b.copy(recipe = "stat-stack", points = listOf(v), items = listOf(unit), variant = null)
        }
    }

    /** The kind of element a recipe is: titles, figures, pictures, names, cards, the ask. */
    private fun family(recipe: String): String = when (recipe) {
        in Bench.TEXT -> "titles"
        in FIGURE_FAMILY -> "figures"
        in PICTURE_FAMILY -> "pictures"
        "logos", "logo-mark", "chips" -> "names"
        "comment", "endcard" -> "asks"
        else -> recipe
    }

    override fun revise(c: Jury.Context, verdict: Verdict): Score? {
        var score = c.score
        val graphics = Bench.graphics(c).sortedBy { it.at }
        for ((g, need) in short(c, graphics)) {
            val at = Bench.locate(score, c.words, g) ?: continue
            // Held longer; and when the next scene leaves no room after it, it lands a little earlier instead.
            val early = (need - (g.out - g.at) + 0.1f).coerceIn(0f, MAX_EARLY)
            // Never held shorter than it was asked to be (a chat thread holds until its last message is said).
            score = Bench.mapBeat(score, at) { it.copy(hold = maxOf(need + 0.1f, it.hold ?: 0f), time = (g.at - early).coerceAtLeast(0f), at = null) }
            score = unblock(score, c, at.first, g.at + need + 0.1f)
        }
        for ((_, b) in crossing(graphics)) {
            if (!b.overFootage) continue
            Bench.locate(score, c.words, b)?.let { at -> score = Bench.mapBeat(score, at) { it.copy(place = Bench.otherBand(b.zone)) } }
        }
        for ((_, b) in repeats(c, graphics)) {
            val at = Bench.locate(score, c.words, b) ?: continue
            val variants = Recipes.named(b.recipe)?.variants ?: 1
            val swap = Bench.ALTERNATE[b.recipe]
            score = Bench.mapBeat(score, at) { s ->
                when {
                    swap != null -> s.copy(recipe = swap)
                    // A second figure right after the first: another figure device (not a title: titles carry enough).
                    s.value != null && s.recipe == "counter" -> otherFigure(s)
                    s.variant == null && variants > 1 -> s.copy(variant = (b.variant + 1) % variants)
                    else -> s
                }
            }
        }
        score = mixed(c, score, graphics)
        // Out of its moment: landed where its words are said; else it says what its own moment says (the line's key
        // phrase, so the moment keeps a graphic); else it is left out.
        for (m in Relevance(c).misses()) {
            val at = Bench.locate(score, c.words, m.placed) ?: continue
            score = Bench.mapBeat(score, at) { s -> m.saidAt?.takeIf { s.at != it }?.let { i -> s.copy(at = i, until = null, time = null) } ?: retitled(c, s, m) }
        }
        return score.takeIf { it != c.score }
    }

    /**
     * Titles carrying the piece: every other one (past the hook) says its words as another kind of
     * element — a callout pointing at the speaker, or for one or two words a stamp — until titles are
     * under half of the graphics. The words, the moment and the place stay.
     */
    private fun mixed(c: Jury.Context, score: Score, graphics: List<Compiler.Placed>): Score {
        if (graphics.size < VARIETY_FROM) return score
        val titles = graphics.filter { it.recipe in Bench.TEXT && it.at > HOOK_S && it.text.isNotBlank() }
        val excess = graphics.count { it.recipe in Bench.TEXT } - (graphics.size * TARGET_FAMILY).toInt()
        if (excess <= 0) return score
        var out = score
        titles.filterIndexed { i, _ -> i % 2 == 1 }.take(excess).forEachIndexed { k, g ->
            val at = Bench.locate(out, c.words, g) ?: return@forEachIndexed
            val short = g.text.split(' ').size <= 2
            out = Bench.mapBeat(out, at) { b ->
                if (short && k % 2 == 1) b.copy(recipe = Bench.STAMP, variant = null)
                else b.copy(recipe = "callout", variant = null, hold = maxOf(b.hold ?: 0f, CALLOUT_HOLD))
            }
        }
        return out
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
        val STAGE_FIGURES = setOf("counter", "meter")
        const val STAGE_FIGURE_HOLD = 2.2f
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
        const val MAX_KINDS = 6
        const val KINDS_PER = 3
        const val MAX_SHARE = 0.4f
        const val MAX_FAMILY = 0.5f
        /** How far from a graphic's landing its line may start or end. */
        const val LAND = 0.6f
        const val HEAVY_FAMILY = 0.65f
        /** Titles are brought under this share of the graphics when they carry the piece. */
        const val TARGET_FAMILY = 0.45f
        const val HOOK_S = 3f
        const val CALLOUT_HOLD = 2.1f
        val FIGURE_FAMILY = setOf("counter", "meter", "voucher", "stats", "countdown", "progress", "chart", "bars", "price-tag")
        val PICTURE_FAMILY = setOf("object", "objects", "icon", "animated", "live-reaction")
        val PERCENT = listOf("%", "٪", "درصد", "percent")
        /** The ask and a thread run on as one story; every other device twice running reads as a loop. */
        val REPEATABLE = setOf("comment", "message")
    }
}
