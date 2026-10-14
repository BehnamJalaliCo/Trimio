package io.trimio.engine.retrieval

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The picture search's retrieval add-on: hybrid search (words and meaning, then the reranker
 * when installed) over the picture catalogue, keeping only hits above the relevance floor.
 * [open] builds the [Retrieval] once it can (null: the embedder is not installed, asked again
 * next time, and the answer is null, so the caller keeps its own word search).
 */
class PictureRecall(private val open: suspend () -> Retrieval?) {
    private val lock = Mutex()
    private var retrieval: Retrieval? = null

    /** Picture ids ("source:key") of [sources] with their relevance 0..1, best first; null when there is no semantic answer. */
    suspend fun search(query: String, line: String, limit: Int, sources: Set<String>): List<Pair<String, Float>>? {
        val r = lock.withLock {
            if (retrieval == null) retrieval = open()
            retrieval
        }?.takeIf { it.semantic } ?: return null
        val list = r.visuals(query, hint = line, kinds = setOf(Corpora.ANIMATION, Corpora.ICON), limit = limit * 2)
        return list.passing.filter { it.doc.id.substringBefore(':') in sources }.take(limit).map { it.doc.id to it.relevance.coerceIn(0f, 1f) }
    }

    /** The music bed (a mood id, "tense") whose description best matches [moment], above the relevance floor; null otherwise. */
    suspend fun music(moment: String): String? {
        val r = lock.withLock {
            if (retrieval == null) retrieval = open()
            retrieval
        }?.takeIf { it.semantic } ?: return null
        return r.sounds(moment, Corpora.MUSIC, limit = 1).passing.firstOrNull()?.doc?.id?.substringAfter(':')
    }
}
