package io.trimio.engine.retrieval

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One line and how it was pictured well: the sentence ([line], as said), what it means ([gist]),
 * the kind of graphic ([show]: a Planner show or an explainer [primitive]), the [metaphor] behind
 * the picture, the picture query ([visual]) and, when one was used, its catalogue id ([art]).
 * [score] is the jury's mean (0–10) for the edit it came from; [source] says where it was learnt:
 * "golden" (the bundled archive) or "taste" (this creator's own approved edits).
 */
@Serializable
data class Exemplar(
    val id: String,
    val line: String,
    val language: String = "fa",
    val domain: String = "",
    val gist: String = "",
    val show: String = "",
    val primitive: String = "",
    val metaphor: String = "",
    val visual: String = "",
    val art: String = "",
    val score: Float = 10f,
    val source: String = "golden",
) {
    fun doc(): Doc = Doc(
        "exemplar:$id", "exemplar", gist.ifBlank { line.take(TITLE) },
        listOf(gist, metaphor, visual).filter { it.isNotBlank() }.joinToString("; "), line,
        listOfNotNull(show.ifBlank { null }, primitive.ifBlank { null }, domain.ifBlank { null }),
        mapOf("source" to source, "score" to score.toString()),
    )

    /** One few-shot line for a director prompt. */
    fun prompt(): String = buildString {
        append("- line: «").append(line).append('»')
        if (gist.isNotBlank()) append(" | gist: ").append(gist)
        if (show.isNotBlank()) append(" | show: ").append(show)
        if (primitive.isNotBlank()) append(" | primitive: ").append(primitive)
        if (metaphor.isNotBlank()) append(" | metaphor: ").append(metaphor)
        if (visual.isNotBlank()) append(" | visual: ").append(visual)
    }

    companion object {
        private const val TITLE = 60
        private val json = Json { ignoreUnknownKeys = true }

        fun parse(text: String): List<Exemplar> = json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(serializer()), text)

        fun write(list: List<Exemplar>): String = json.encodeToString(kotlinx.serialization.builtins.ListSerializer(serializer()), list)

        /** A few-shot block: "Examples of lines pictured well:" and one line each. */
        fun block(list: List<Exemplar>): String =
            if (list.isEmpty()) "" else "Examples of lines pictured well (follow the idea, not the words):\n" + list.joinToString("\n") { it.prompt() }
    }
}

/**
 * The taste memory: this creator's approved decisions, learnt from every delivered edit the jury
 * scored well (and kept or changed by the creator in the editor). It replaces fine-tuning: the
 * director is shown the creator's own best past lines as examples, and the [TasteHead] learns
 * which pictures this creator's edits score with. Bounded; the best and newest stay.
 */
class TasteMemory(private val store: MemoryStore, private val capacity: Int = CAPACITY) {
    private val lock = Mutex()
    private var cached: List<Exemplar>? = null

    suspend fun all(): List<Exemplar> = lock.withLock { load() }

    private suspend fun load(): List<Exemplar> =
        cached ?: (store.read()?.let { runCatching { Exemplar.parse(it) }.getOrNull() }.orEmpty()).also { cached = it }

    /** Keeps [made] (decisions from one edit) that reached [minScore]; the weakest and oldest fall out past [capacity]. */
    suspend fun record(made: List<Exemplar>, minScore: Float = GOOD) = lock.withLock {
        val good = made.filter { it.score >= minScore }.map { it.copy(source = "taste") }
        if (good.isEmpty()) return@withLock
        val ids = good.map { it.id }.toSet()
        val merged = load().filter { it.id !in ids } + good
        val kept = if (merged.size <= capacity) merged else merged.withIndex().sortedByDescending { (i, e) -> e.score * SCORE_WEIGHT + i }.take(capacity)
            .sortedBy { it.index }.map { it.value }
        cached = kept
        store.write(Exemplar.write(kept))
    }

    private companion object {
        const val CAPACITY = 1500
        const val GOOD = 9f

        /** A point of jury score outweighs this many newer entries. */
        const val SCORE_WEIGHT = 200f
    }
}
