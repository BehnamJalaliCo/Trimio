package io.trimio.engine.retrieval

import io.trimio.engine.llm.RerankPrompt
import io.trimio.engine.llm.TextEmbedder
import io.trimio.engine.llm.TextReranker

/**
 * What the director asks while it works, answered from the corpora:
 *  - [visuals]: the pictures, primitives and card families that make a sentence instantly clear,
 *    above the relevance floor (below it: typography or a primitive, never an irrelevant picture);
 *  - [sounds]: music moods and sound cues that suit a moment;
 *  - [examples]: few-shot lines pictured well, from the golden archive and this creator's taste;
 *  - [terms]: the glossary terms a text names (the creator's own first), to spell and explain;
 *  - [hints]: all of it as a prompt block for a director model.
 *
 * Built once per edit session by [open]; with no embedder installed it still answers by words
 * (BM25 over the same documents) and every [Shortlist] says which [Signal] made it.
 */
class Retrieval(
    val retriever: Retriever,
    val visualIndex: CorpusIndex,
    val soundIndex: CorpusIndex,
    val exemplarIndex: CorpusIndex,
    val termIndex: CorpusIndex,
    val glossary: Glossary,
    private val exemplarsById: Map<String, Exemplar>,
    private val memory: UserMemory? = null,
) {
    /** True when meaning is searched (an embedder is installed and the corpora have vectors). */
    val semantic: Boolean get() = retriever.embedder != null && visualIndex.space == retriever.embedder.space

    suspend fun visuals(
        line: String,
        hint: String = "",
        context: String = "",
        kinds: Set<String> = Corpora.PICTURES,
        exclude: Set<String> = emptySet(),
        limit: Int = Retriever.DEFAULT_LIMIT,
    ): Shortlist = retriever.search(visualIndex, Query(line, hint, context, kinds, exclude, RerankPrompt.VISUAL), limit)

    /** Sounds for a moment described in words (the line, the piece's mood and domain); [kind]: music or sfx. */
    suspend fun sounds(moment: String, kind: String = Corpora.MUSIC, limit: Int = Retriever.DEFAULT_LIMIT): Shortlist =
        retriever.search(soundIndex, Query(moment, kinds = setOf(kind), instruction = RerankPrompt.SOUND), limit)

    /** [k] varied examples for a line (golden and taste), best first. */
    suspend fun examples(line: String, k: Int = EXAMPLES, domain: String = ""): List<Exemplar> {
        val hint = domain.takeIf { it.isNotBlank() }?.let { "domain: $it" }.orEmpty()
        val list = retriever.search(exemplarIndex, Query(line, hint, instruction = RerankPrompt.EXEMPLAR), k * 3)
        val good = if (list.signal == Signal.Lexical) list.hits else list.passing
        return retriever.diverse(exemplarIndex, good, k).mapNotNull { exemplarsById[it.doc.id] }
    }

    /**
     * The terms [text] names: the creator's own glossary and the bundled one matched by spelling
     * (exact, any variant), then — with an embedder — terms the text means without naming them
     * (above the floor).
     */
    suspend fun terms(text: String, limit: Int = TERMS): List<Term> {
        val own = memory?.glossary() ?: Glossary.Empty
        val all = own + glossary
        val named = all.find(text).map { it.term }.distinctBy { it.term }
        if (named.size >= limit || !semantic) return named.take(limit)
        val meant = retriever.search(termIndex, Query(text, instruction = RerankPrompt.TERM), limit).passing
            .mapNotNull { h -> glossary.lookup(h.doc.title) }
        return (named + meant).distinctBy { it.term }.take(limit)
    }

    /** A prompt block for a director model: the terms to spell exactly, the creator's pinned memory, and examples. */
    suspend fun hints(lines: List<String>, domain: String = "", examplesPerLine: Int = 1, maxExamples: Int = EXAMPLES * 2): String {
        val text = lines.joinToString(" ")
        val terms = terms(text)
        val pinned = memory?.pinned().orEmpty()
        val examples = lines.flatMap { examples(it, examplesPerLine, domain) }.distinctBy { it.id }.take(maxExamples)
        return buildString {
            if (terms.isNotEmpty()) {
                append("Terms (spell exactly as written):\n")
                terms.forEach { t ->
                    append("- ").append(t.term)
                    if (t.en.isNotBlank()) append(" (").append(t.en).append(')')
                    if (t.note.isNotBlank()) append(": ").append(t.note)
                    append('\n')
                }
            }
            if (pinned.isNotEmpty()) {
                append("The creator's rules:\n")
                pinned.forEach { m -> append("- ").append(m.kind.name.lowercase()).append(": ").append(m.text).append(if (m.note.isBlank()) "" else " → ${m.note}").append('\n') }
            }
            val block = Exemplar.block(examples)
            if (block.isNotEmpty()) append(block).append('\n')
        }.trimEnd()
    }

    companion object {
        const val EXAMPLES = 3
        const val TERMS = 12

        /**
         * Builds the indexes: [pictures] (the caller's catalogue) with the bundled primitives and
         * families; sounds (bundled plus [extraSounds], e.g. installed music and sound packs);
         * golden exemplars plus the creator's [taste]; glossary terms. With an [embedder], vectors
         * come from [packs] where they match and are embedded here otherwise ([onProgress]: done, total).
         */
        suspend fun open(
            pictures: List<Corpora.Picture>,
            embedder: TextEmbedder? = null,
            reranker: TextReranker? = null,
            config: RetrievalConfig = RetrievalConfig(),
            extraSounds: List<Doc> = emptyList(),
            memory: UserMemory? = null,
            taste: TasteMemory? = null,
            bundled: Corpora.Bundled? = null,
            onProgress: (Int, Int) -> Unit = { _, _ -> },
        ): Retrieval {
            val b = bundled ?: Corpora.bundled(setOfNotNull(embedder?.space))
            val exemplars = b.exemplars + taste?.all().orEmpty()
            val memoryDocs = memory?.docs().orEmpty()
            val visuals = CorpusIndex(Corpora.VISUALS, Corpora.art(pictures) + b.primitives + b.families + memoryDocs.filter { it.kind == "memory-asset" })
            val sounds = CorpusIndex(Corpora.SOUNDS, b.sounds + extraSounds)
            val examples = CorpusIndex(Corpora.EXEMPLARS, exemplars.map { it.doc() })
            val terms = CorpusIndex(Corpora.TERMS, b.glossary.docs())
            if (embedder != null) {
                val all = listOf(visuals, sounds, examples, terms)
                val total = all.sumOf { it.docs.size }
                var before = 0
                for (index in all) {
                    val pack = b.packs.firstOrNull { it.corpus == index.name && it.space == embedder.space }
                    val offset = before
                    index.attach(embedder, pack) { done, _ -> onProgress(offset + done, total) }
                    before += index.docs.size
                }
            }
            return Retrieval(
                Retriever(embedder, reranker, config), visuals, sounds, examples, terms, b.glossary,
                exemplars.associateBy { "exemplar:${it.id}" }, memory,
            )
        }
    }
}
