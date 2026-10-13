package io.trimio.engine.autopilot.explainer

import io.trimio.engine.llm.ChatMessage
import io.trimio.engine.llm.ChatRole
import io.trimio.engine.llm.GenerationRequest
import io.trimio.engine.llm.LanguageModel
import io.trimio.engine.llm.generateStructured
import io.trimio.engine.motion.explainer.ExplainerFilm
import kotlinx.coroutines.CancellationException
import kotlin.time.Duration
import kotlin.time.TimeSource

/**
 * A language model writes the film. A cloud model (the owner's Claude key, [io.trimio.engine.llm.CloudModels])
 * writes it whole in one call (a few for a long narration, each a run of chapters), its reply
 * streamed; an on-device model writes it chapter by chapter, each reply constrained by the JSON
 * schema's grammar, so it fits a small context and a phone. Whatever a call cannot give (it
 * failed, it ran past [budget]) the rules write ([RulesDirector]), chapter by chapter.
 */
class LlmDirector(
    private val model: LanguageModel,
    private val rules: RulesDirector = RulesDirector(),
    /** Model time for one film; the rest is written by the rules (a slow phone still ends). */
    private val budget: Duration? = null,
    /** A small model also cuts the chapters (one short call) instead of taking the rules' cut. */
    private val outline: Boolean = true,
    private val temperature: Float = TEMPERATURE,
) : FilmDirector {
    override val name: String get() = model.id

    override suspend fun direct(n: Narration, seed: Long, log: (String) -> Unit): ExplainerFilm =
        if (model.isLocal) local(n, seed, log) else cloud(n, seed, log)

    /** The cloud: the whole film per call, a run of the rules' chapters at a time when the narration is long. */
    private suspend fun cloud(n: Narration, seed: Long, log: (String) -> Unit): ExplainerFilm {
        val plans = Chapters.find(n)
        val runs = runs(n, plans)
        val written = mutableListOf<Pair<Chapters.Plan, List<DraftBeat>>>()
        for (run in runs) {
            val reply = attempt(log) {
                model.generateStructured(
                    GenerationRequest(DirectorPrompts.SYSTEM, listOf(ChatMessage(ChatRole.User, DirectorPrompts.film(n, run))), Drafts.filmSchema, CLOUD_TOKENS, temperature, seed.toInt()),
                    DraftFilm.serializer(), onText = streamed(log),
                )
            }
            val chapters = reply?.let { cut(n, it, run) }
            if (chapters == null) {
                log("director: ${model.id} gave no usable chapters for c${run.first().clauses.first}–c${run.last().clauses.last}; the rules write them")
                written += run.map { p -> p to emptyList() }
            } else {
                written += chapters
            }
        }
        return fill(n, seed, written, log)
    }

    /** On the device: the outline (or the rules' cut), then one call per chapter. */
    private suspend fun local(n: Narration, seed: Long, log: (String) -> Unit): ExplainerFilm {
        val clock = TimeSource.Monotonic.markNow()
        val plans = (if (outline) outline(n, seed, log) else null) ?: Chapters.find(n)
        val written = plans.mapIndexed { i, plan ->
            if (budget != null && clock.elapsedNow() > budget) {
                log("director: past the model's budget; the rules write chapter ${i + 1}")
                return@mapIndexed plan to emptyList()
            }
            val scene = attempt(log) {
                model.generateStructured(
                    GenerationRequest(
                        DirectorPrompts.SYSTEM, listOf(ChatMessage(ChatRole.User, DirectorPrompts.scene(n, plans, i))),
                        Drafts.sceneSchema(plan.clauses.count()), LOCAL_TOKENS_PER_CLAUSE * plan.clauses.count() + LOCAL_TOKENS, temperature, seed.toInt() + i,
                    ),
                    DraftScene.serializer(), onText = streamed(log),
                )
            }
            log("director: chapter ${i + 1} «${scene?.title ?: plan.title}» ${scene?.beats?.size ?: 0} beats from ${model.id}")
            plan.copy(title = scene?.title?.trim()?.ifBlank { null } ?: plan.title) to scene?.beats.orEmpty()
        }
        return fill(n, seed, written, log)
    }

    /** The model's chapter cut, kept when it is sound (starts at 0, rising, 2+ clauses each), else null. */
    private suspend fun outline(n: Narration, seed: Long, log: (String) -> Unit): List<Chapters.Plan>? {
        if (n.clauses.size < MIN_OUTLINE) return null
        val reply = attempt(log) {
            model.generateStructured(
                GenerationRequest(DirectorPrompts.OUTLINE, listOf(ChatMessage(ChatRole.User, DirectorPrompts.outline(n))), Drafts.outlineSchema, OUTLINE_TOKENS, temperature, seed.toInt()),
                DraftOutline.serializer(), onText = streamed(log),
            )
        } ?: return null
        val starts = reply.chapters.map { it.first }
        val sound = starts.firstOrNull() == 0 && starts.zipWithNext().all { (a, b) -> b - a >= 2 } && n.clauses.size - (starts.lastOrNull() ?: 0) >= 2
        if (!sound) {
            log("director: the model's outline ${starts} is not sound; the rules cut the chapters")
            return null
        }
        val rules = Chapters.find(n)
        return reply.chapters.mapIndexed { i, e ->
            val range = e.first until (reply.chapters.getOrNull(i + 1)?.first ?: n.clauses.size)
            val subject = rules.firstOrNull { it.clauses.first == range.first }?.subject
            Chapters.Plan(range, e.title.trim().ifBlank { rules.firstOrNull { range.first in it.clauses }?.title ?: "…" }, subject)
        }.also { log("director: outline ${it.joinToString(" | ") { p -> "c${p.clauses.first} ${p.title}" }}") }
    }

    /** The film of what was written; chapters the model left empty are written by the rules. */
    private fun fill(n: Narration, seed: Long, written: List<Pair<Chapters.Plan, List<DraftBeat>>>, log: (String) -> Unit): ExplainerFilm {
        val film = Drafts.toFilm(n, written, seed)
        val plans = written.map { it.first }
        val empty = written.indices.filter { written[it].second.isEmpty() }
        if (empty.isEmpty()) return film
        log("director: the rules write chapters ${empty.map { it + 1 }}")
        return film.copy(chapters = film.chapters.mapIndexed { i, c -> if (i in empty) rules.chapter(n, seed, plans, i, film) else c })
    }

    /** A cloud reply's chapters as plans, when they cover the run's clauses in order; else null. */
    private fun cut(n: Narration, film: DraftFilm, run: List<Chapters.Plan>): List<Pair<Chapters.Plan, List<DraftBeat>>>? {
        val from = run.first().clauses.first
        val to = run.last().clauses.last
        val chapters = film.chapters.sortedBy { it.first }
        val sound = chapters.isNotEmpty() && chapters.first().first == from && chapters.last().last == to &&
            chapters.zipWithNext().all { (a, b) -> b.first == a.last + 1 } && chapters.all { it.last >= it.first }
        if (!sound) return null
        return chapters.map { c ->
            val range = c.first..c.last
            val subject = run.firstOrNull { it.clauses.first == c.first }?.subject
            Chapters.Plan(range, c.title.trim().ifBlank { run.firstOrNull { c.first in it.clauses }?.title ?: "…" }, subject) to c.beats
        }.takeIf { it.all { (p, _) -> p.clauses.last < n.clauses.size } }
    }

    /** Runs of chapters small enough for one reply. */
    private fun runs(n: Narration, plans: List<Chapters.Plan>): List<List<Chapters.Plan>> {
        val out = mutableListOf<MutableList<Chapters.Plan>>()
        for (p in plans) {
            val last = out.lastOrNull()
            if (last != null && last.sumOf { it.clauses.count() } + p.clauses.count() <= CLAUSES_PER_CALL) last += p else out += mutableListOf(p)
        }
        return if (n.clauses.isEmpty()) emptyList() else out
    }

    /** [block]'s reply, or null when the model failed (said in the log); cancellation goes through. */
    private suspend fun <T> attempt(log: (String) -> Unit, block: suspend () -> T): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
        log("director: ${model.id} failed (${e.message?.take(ERROR_CHARS)})")
        null
    }

    /** The model's streamed text, told now and then (a long reply shows it is alive). */
    private fun streamed(log: (String) -> Unit): (String) -> Unit {
        var chars = 0
        var next = STREAM_EVERY
        return { s ->
            chars += s.length
            if (chars >= next) {
                log("director: … ${chars} characters written")
                next += STREAM_EVERY
            }
        }
    }

    private companion object {
        const val TEMPERATURE = 0.45f
        const val CLOUD_TOKENS = 24_000
        const val LOCAL_TOKENS = 200
        const val LOCAL_TOKENS_PER_CLAUSE = 260
        const val OUTLINE_TOKENS = 400
        const val CLAUSES_PER_CALL = 60
        const val MIN_OUTLINE = 6
        const val ERROR_CHARS = 200
        const val STREAM_EVERY = 2000
    }
}
