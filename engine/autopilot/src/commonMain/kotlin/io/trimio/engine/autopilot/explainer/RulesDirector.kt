package io.trimio.engine.autopilot.explainer

import io.trimio.engine.autopilot.Rng
import io.trimio.engine.motion.explainer.Chapter
import io.trimio.engine.motion.explainer.ExplainerFilm

/**
 * Who writes the film from the narration: a language model ([LlmDirector]) or the rules
 * ([RulesDirector]). Both return a whole [ExplainerFilm]; the [Screenwriter] checks and repairs it.
 */
interface FilmDirector {
    /** "rules", or the model's id, for the report. */
    val name: String

    /** The film of [n] for [seed]; [log] hears what the director decided (and a model's streamed text, briefly). */
    suspend fun direct(n: Narration, seed: Long, log: (String) -> Unit = {}): ExplainerFilm
}

/**
 * The film from the understanding alone, deterministic for a seed: chapters where the subject
 * turns ([Chapters]), a look from the topic and the seed ([ArtDirector]), and every clause drawn as
 * its idea on a stage that builds ([StageWriter]). It is the fallback when no model can direct,
 * and what a model's film is repaired with when it fails the checks.
 */
class RulesDirector : FilmDirector {
    override val name: String = "rules"

    override suspend fun direct(n: Narration, seed: Long, log: (String) -> Unit): ExplainerFilm = film(n, seed)

    fun film(n: Narration, seed: Long, plans: List<Chapters.Plan> = Chapters.find(n)): ExplainerFilm {
        val art = ArtDirector.choose(n, seed)
        val accents = ArtDirector.accents(plans.size, seed)
        val memory = FilmMemory()
        val rng = Rng(seed).fork(SALT)
        val bounds = bounds(n, plans)
        val chapters = plans.indices.map { i ->
            val (start, end) = bounds[i]
            val beats = StageWriter(n, plans, i, rng.fork(i), memory, start, end).write()
            Chapter(plans[i].title, start, end, accents[i], beats)
        }
        val captions = if (art.captionStyle == ArtDirector.NO_CAPTIONS) emptyList() else Captions.of(n.words, n.persian)
        return ExplainerFilm(art, chapters, captions, n.language)
    }

    /** Chapter [i] alone, as the rules would write it inside [film] (a judge's fix for one chapter). */
    fun chapter(n: Narration, seed: Long, plans: List<Chapters.Plan>, i: Int, film: ExplainerFilm): Chapter {
        val memory = FilmMemory()
        plans.take(i).mapNotNull { it.subject }.forEach { memory.subjects += it }
        film.chapters.take(i).flatMap { c -> c.beats.mapNotNull { it.primitive } }.forEach { memory.mains += it }
        val old = film.chapters[i]
        val beats = StageWriter(n, plans, i, Rng(seed).fork(SALT).fork(i), memory, old.start, old.end).write()
        return old.copy(beats = beats)
    }

    companion object {
        private const val SALT = 7

        /** Chapters cover the narration end to end: each from its first clause to the next one's (the first from 0). */
        fun bounds(n: Narration, plans: List<Chapters.Plan>): List<Pair<Double, Double>> = plans.indices.map { i ->
            val start = if (i == 0) 0.0 else n.clauses[plans[i].clauses.first].start - EDGE
            val end = plans.getOrNull(i + 1)?.let { n.clauses[it.clauses.first].start - EDGE } ?: n.duration
            StageWriter.round2(start) to StageWriter.round2(maxOf(end, start + MIN_CHAPTER))
        }

        /** A chapter's heading arrives a breath before its first words. */
        private const val EDGE = 0.15
        private const val MIN_CHAPTER = 1.0
    }
}
