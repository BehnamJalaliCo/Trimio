package io.trimio.engine.retrieval

import io.trimio.engine.llm.EmbedRole
import io.trimio.engine.llm.TextEmbedder
import kotlinx.serialization.Serializable

/**
 * One searchable thing: a picture, a primitive, a card family, a music mood, a sound cue, an
 * exemplar, a glossary term, a memory. [title] names it, [text] describes it in English (what it
 * shows, its metaphors, what it is good for), [fa] in Persian, [tags] are extra keywords. [meta]
 * carries what the caller needs back (a set, a payload).
 */
@Serializable
data class Doc(
    val id: String,
    val kind: String,
    val title: String,
    val text: String = "",
    val fa: String = "",
    val tags: List<String> = emptyList(),
    val meta: Map<String, String> = emptyMap(),
) {
    /** Everything BM25 reads. */
    val searchText: String get() = listOf(title, text, fa, tags.joinToString(" ")).filter { it.isNotBlank() }.joinToString(" ")

    /** What the embedder reads (bilingual, compact); the title goes to the family's document prompt. */
    val embedText: String get() = listOf(text, fa, tags.take(MAX_TAGS).joinToString(", ")).filter { it.isNotBlank() }.joinToString(" | ").ifEmpty { title }

    /** What the reranker reads. */
    val rerankText: String get() = listOf(title, text, fa).filter { it.isNotBlank() }.joinToString(" — ")

    /** Identifies the embedded text: a pack vector is reused only for a document that still reads the same. */
    val fingerprint: Int get() = fnv("$title\u0000$embedText")

    private companion object {
        const val MAX_TAGS = 12
    }
}

/** 32-bit FNV-1a of [s]'s UTF-16 units: stable on every platform (unlike [String.hashCode]'s contract). */
fun fnv(s: String): Int {
    var h = 0x811C9DC5.toInt()
    for (c in s) {
        h = h xor c.code
        h *= 0x01000193
    }
    return h
}

/**
 * A corpus ready to search: BM25 always, dense vectors once [attach]ed (from a [VectorPack] built
 * with the same embedder, and the documents it does not cover embedded on the device).
 */
class CorpusIndex(val name: String, val docs: List<Doc>) {
    private val byId = docs.withIndex().associate { it.value.id to it.index }
    val bm25 = Bm25(docs.map(Doc::searchText))

    var vectors: Int8Vectors? = null
        private set

    /** The vector space of [vectors] ([TextEmbedder.space]). */
    var space: String? = null
        private set

    fun indexOf(id: String): Int? = byId[id]

    /**
     * Dense vectors for every document in [embedder]'s space: those [pack] holds (same space, same
     * fingerprint) are reused, the rest are embedded now. Returns how many were embedded here.
     */
    suspend fun attach(embedder: TextEmbedder, pack: VectorPack? = null, onProgress: (Int, Int) -> Unit = { _, _ -> }): Int {
        val usable = pack?.takeIf { it.space == embedder.space && it.dims == embedder.dims }
        val known = usable?.entries?.associateBy { it.id }.orEmpty()
        val missing = docs.indices.filter { i -> known[docs[i].id]?.fingerprint != docs[i].fingerprint }
        val fresh = HashMap<Int, FloatArray>()
        var done = 0
        for (chunk in missing.chunked(EMBED_CHUNK)) {
            val got = embedder.embed(chunk.map { docs[it].embedText }, EmbedRole.Document, chunk.map { docs[it].title })
            chunk.forEachIndexed { k, i -> fresh[i] = got[k] }
            done += chunk.size
            onProgress(done, missing.size)
        }
        val index = Int8Vectors(embedder.dims, docs.size.coerceAtLeast(1))
        docs.forEachIndexed { i, d ->
            val f = fresh[i]
            if (f != null) index.add(f) else known.getValue(d.id).let { index.addQuantised(it.bytes, it.scale) }
        }
        vectors = index
        space = embedder.space
        return missing.size
    }

    /** This corpus's vectors as a pack (for the cache or the offline builder); null before [attach]. */
    fun pack(): VectorPack? {
        val v = vectors ?: return null
        val s = space ?: return null
        return VectorPack(s, name, v.dims, docs.mapIndexed { i, d -> VectorPack.Entry(d.id, d.fingerprint, v.scale(i), v.bytes(i)) })
    }

    private companion object {
        const val EMBED_CHUNK = 64
    }
}
