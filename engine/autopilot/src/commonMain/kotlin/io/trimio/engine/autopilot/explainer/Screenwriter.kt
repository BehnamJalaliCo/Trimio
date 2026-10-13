package io.trimio.engine.autopilot.explainer

import io.trimio.engine.autopilot.Jury
import io.trimio.engine.autopilot.explainer.judges.FilmJury
import io.trimio.engine.motion.explainer.ArtDirection
import io.trimio.engine.motion.explainer.ExplainerFilm
import kotlinx.coroutines.CancellationException

/**
 * From the narration to a film the jury passes. The [director] writes it (a model, or the rules);
 * [FilmCheck] repairs what it got wrong, and when a model's film is still unsound the rules write
 * it instead; then the [jury] sends it back with its fixes until every judge gives 10, and tries
 * other seeds when fixes run out (the rules', or the model's when it is cheap to ask again). The
 * best panel's film goes on, with an honest report.
 */
class Screenwriter(
    private val director: FilmDirector = RulesDirector(),
    private val jury: FilmJury? = FilmJury(),
    private val rules: RulesDirector = RulesDirector(),
    /** Variations are asked of the director itself (a cloud model); else the rules write them. */
    private val askAgain: Boolean = director is RulesDirector,
) {
    class Result(val film: ExplainerFilm, val panel: Jury.Panel?, val report: List<String>)

    suspend fun write(n: Narration, seed: Long, others: List<ArtDirection> = emptyList(), log: (String) -> Unit = {}): Result {
        val report = mutableListOf<String>()
        val say: (String) -> Unit = { report += it; log(it) }
        val check = FilmCheck(n)
        val first = draft(director, n, seed, check, say)
        val look = with(first.art) { "$grammar $palette/$layout/$chapterMark/$captionStyle" }
        say("screenwriter: ${first.chapters.size} chapters, ${first.chapters.sumOf { it.beats.size }} beats, $look")
        val j = jury ?: return Result(first, null, report)
        val outcome = j.loop(
            first,
            context = { f -> FilmJury.Context(f, n, check, others) },
            vary = { k -> draft(if (askAgain) director else rules, n, seed * VARIATION_STRIDE + k, check) { report += "variation $k: $it" } },
            log = { report += it },
        )
        val p = outcome.panel
        val verdict = if (p.unanimous) "every judge gives 10" else "not unanimous — the best panel within the budget"
        say("jury: $verdict after ${outcome.rounds} rounds and ${outcome.variations} variations | ${p.summary()}")
        p.findings().forEach { report += it }
        return Result(outcome.best, p, report)
    }

    /** [d]'s film, repaired; the rules' when [d] fails or its film stays unsound. */
    private suspend fun draft(d: FilmDirector, n: Narration, seed: Long, check: FilmCheck, log: (String) -> Unit): ExplainerFilm {
        val written = try {
            d.direct(n, seed, log)
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            log("screenwriter: ${d.name} failed (${e.message}); the rules write the film")
            rules.film(n, seed)
        }
        val repaired = check.repair(written) { log("${d.name} $it") }
        val left = check.problems(repaired).filter { it.kind in UNSOUND }
        if (left.isEmpty() || d is RulesDirector) return repaired
        log("screenwriter: ${d.name}'s film is unsound after repair (${left.first().detail}); the rules write it")
        return check.repair(rules.film(n, seed))
    }

    private companion object {
        const val VARIATION_STRIDE = 1000L
        val UNSOUND = setOf(FilmCheck.Kind.Time, FilmCheck.Kind.Reference, FilmCheck.Kind.Empty)
    }
}
