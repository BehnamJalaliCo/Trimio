package io.trimio.engine.autopilot.explainer.judges

import io.trimio.engine.autopilot.Finding
import io.trimio.engine.autopilot.Jury
import io.trimio.engine.autopilot.Severity
import io.trimio.engine.autopilot.Verdict
import io.trimio.engine.autopilot.explainer.FilmCheck
import io.trimio.engine.autopilot.explainer.Narration
import io.trimio.engine.motion.explainer.ArtDirection
import io.trimio.engine.motion.explainer.ExplainerFilm

/** One expert on the explainer panel: pure and fast, it reads the film and may offer a fixed one. */
interface FilmJudge {
    val name: String
    val dimension: String

    fun score(c: FilmJury.Context): Verdict

    /** The film with this judge's findings fixed, when it can fix them; null otherwise. */
    fun revise(c: FilmJury.Context, verdict: Verdict): ExplainerFilm? = null
}

/**
 * The jury of an explainer film: metaphor (each picture says its sentence), continuity (the stage
 * builds, no hard cuts), variety (the whole vocabulary, no device three times running, a look of
 * its own), timing (every beat long enough to read, on its words), spelling and script, and the
 * brief. Like the edit's [Jury], a film is sent back with the judges' own fixes until every judge
 * gives 10 or the [budget] is spent, then other seeds are tried; the best panel's film goes on.
 * Verdicts and panels are the edit jury's, so screens and reports read them the same way.
 */
class FilmJury(val judges: List<FilmJudge> = standard(), val budget: Jury.Budget = Jury.Budget()) {

    /** What the judges read: the film, its narration, the checks, and the looks of films already made of the same narration. */
    data class Context(
        val film: ExplainerFilm,
        val narration: Narration,
        val check: FilmCheck = FilmCheck(narration),
        /** Looks of other films of this narration (earlier seeds): this one should not repeat them. */
        val others: List<ArtDirection> = emptyList(),
    )

    fun deliberate(c: Context): Jury.Panel = Jury.Panel(
        judges.map { j ->
            runCatching { j.score(c) }.getOrElse { e -> Verdict(j.name, j.dimension, Verdict.PERFECT, listOf(Finding(0f, Severity.Note, "abstained: ${e.message}", "-"))) }
        },
    )

    /** What a round may try: every failing judge's fix together, then each alone (lowest score first). */
    fun options(c: Context, panel: Jury.Panel): List<Pair<String, ExplainerFilm>> {
        val failing = panel.verdicts.filter { !it.perfect }.sortedBy { it.score }
        var together = c.film
        for (v in failing) {
            val judge = judges.firstOrNull { it.name == v.judge } ?: continue
            together = runCatching { judge.revise(c.copy(film = together), v) }.getOrNull() ?: together
        }
        val alone = failing.mapNotNull { v ->
            val judge = judges.firstOrNull { it.name == v.judge }
            judge?.let { j -> runCatching { j.revise(c, v) }.getOrNull()?.let { v.judge to it } }
        }
        val all = listOfNotNull(("all fixes" to together).takeIf { together != c.film }) + alone.filter { a -> a.second != together && a.second != c.film }
        return all.take(MAX_TRIES)
    }

    /**
     * Score [first]; while a judge is below 10, apply the fixes (up to [Jury.Budget.rounds]
     * rounds, keeping a revision only when the panel prefers it); then try [vary] k = 1, 2, … up to
     * [Jury.Budget.variations]. The best panel wins.
     */
    suspend fun loop(
        first: ExplainerFilm,
        context: (ExplainerFilm) -> Context,
        vary: suspend (Int) -> ExplainerFilm?,
        log: (String) -> Unit = {},
    ): Jury.Outcome<ExplainerFilm> {
        var best = first
        var bestPanel: Jury.Panel? = null
        var rounds = 0
        var variation = 0
        var candidate: ExplainerFilm? = first
        while (candidate != null) {
            val (film, panel, spent) = polish(candidate, if (variation == 0) "first film" else "variation $variation", context, log)
            rounds += spent
            if (panel.betterThan(bestPanel)) {
                best = film
                bestPanel = panel
            }
            if (panel.unanimous || variation >= budget.variations) break
            variation++
            candidate = vary(variation)
        }
        return Jury.Outcome(best, bestPanel ?: deliberate(context(best)), rounds, variation)
    }

    private fun polish(first: ExplainerFilm, name: String, context: (ExplainerFilm) -> Context, log: (String) -> Unit): Triple<ExplainerFilm, Jury.Panel, Int> {
        var current = first
        var panel = deliberate(context(current))
        log("jury $name round 0: ${panel.summary()}")
        panel.details().forEach(log)
        val tried = mutableSetOf(current)
        var round = 0
        while (!panel.unanimous && round < budget.rounds) {
            val options = options(context(current), panel).filter { it.second !in tried }
            val step = options.firstNotNullOfOrNull { (what, film) ->
                tried += film
                val p = deliberate(context(film))
                log("jury $name round ${round + 1} ($what): ${p.summary()}")
                (film to p).takeIf { p.betterThan(panel) }
            } ?: break
            round++
            current = step.first
            panel = step.second
            panel.details().forEach(log)
        }
        return Triple(current, panel, round)
    }

    companion object {
        private const val MAX_TRIES = 4

        fun standard(): List<FilmJudge> = listOf(MetaphorJudge(), ContinuityJudge(), VarietyJudge(), TimingJudge(), ScriptJudge(), FilmBriefJudge())

        /** A verdict: 10 less what the findings cost. */
        fun verdict(judge: FilmJudge, findings: List<Finding>) =
            Verdict(judge.name, judge.dimension, (Verdict.PERFECT - findings.sumOf { it.severity.cost.toDouble() }.toFloat()).coerceIn(0f, Verdict.PERFECT), findings)

        fun finding(at: Double, severity: Severity, what: String, fix: String) = Finding((kotlin.math.round(at * 10) / 10).toFloat(), severity, what, fix)
    }
}
