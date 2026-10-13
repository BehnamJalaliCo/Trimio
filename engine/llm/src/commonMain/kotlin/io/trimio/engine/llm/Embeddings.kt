package io.trimio.engine.llm

/** What a text is to an asymmetric embedder: the thing searched for, or a thing searched. */
enum class EmbedRole { Query, Document }

/**
 * How a text-embedding model family wants its inputs written, from each model card. The same
 * prompts must be used when the offline vector packs are built and when the device embeds a
 * query, so [promptVersion] is part of every vector space's identity ([TextEmbedder.space]).
 */
enum class EmbeddingFamily(val promptVersion: Int) {
    /**
     * EmbeddingGemma 1 and 2 (google/embeddinggemma-300m, google/embeddinggemma-2): task prefixes,
     * `task: search result | query: …` for queries, `title: … | text: …` for documents (`none`
     * without a title). Mean pooling; run in bf16 or f32, never f16 (NaNs).
     */
    EmbeddingGemma(1) {
        override fun format(text: String, role: EmbedRole, title: String?, task: String?): String = when (role) {
            EmbedRole.Query -> "task: ${task ?: "search result"} | query: $text"
            EmbedRole.Document -> "title: ${title?.takeIf { it.isNotBlank() } ?: "none"} | text: $text"
        }
    },

    /** BAAI/bge-m3 (XLM-R): no instruction on either side; CLS pooling. */
    BgeM3(1) {
        override fun format(text: String, role: EmbedRole, title: String?, task: String?): String =
            if (title.isNullOrBlank() || role == EmbedRole.Query) text else "$title: $text"
    },

    /**
     * Qwen3-Embedding (0.6B/4B/8B): `Instruct: {task}\nQuery:{query}` on the query side, documents
     * plain; last-token pooling (the GGUF says so).
     */
    Qwen3Embedding(1) {
        override fun format(text: String, role: EmbedRole, title: String?, task: String?): String = when (role) {
            EmbedRole.Query -> "Instruct: ${task ?: QWEN_TASK}\nQuery:$text"
            EmbedRole.Document -> if (title.isNullOrBlank()) text else "$title: $text"
        }
    },
    ;

    /** [text] as this family expects it for [role]; [title] names a document, [task] overrides the query task. */
    abstract fun format(text: String, role: EmbedRole, title: String? = null, task: String? = null): String

    private companion object {
        const val QWEN_TASK = "Given a web search query, retrieve relevant passages that answer the query"
    }
}

/**
 * A text embedder: texts in, L2-normalised vectors out (one per text, in order). Implementations
 * batch internally and stay resident (an embedding call is milliseconds once loaded).
 */
interface TextEmbedder {
    /** Catalogue id of the model ("embeddinggemma-2-q8"). */
    val id: String
    val family: EmbeddingFamily

    /** Output dimensions (after any Matryoshka truncation the embedder applies). */
    val dims: Int

    /** The vector space: vectors from different spaces are never compared ([io.trimio.engine.llm.EmbeddingFamily.promptVersion]). */
    val space: String get() = "$id/$dims/p${family.promptVersion}"

    /** Embeds [texts] (already raw: the family's prompt is applied here, with [title] for documents). */
    suspend fun embed(texts: List<String>, role: EmbedRole, titles: List<String?> = emptyList()): List<FloatArray>

    /** Frees the model's memory; it reloads on the next call. */
    suspend fun release() {}
}

/**
 * A cross-encoder reranker: how well each document answers the query under an [instruction],
 * as a calibrated probability 0..1 (Qwen3-Reranker's P(yes)). Loaded on first use, released
 * before rendering.
 */
interface TextReranker {
    val id: String

    suspend fun rerank(query: String, documents: List<String>, instruction: String = RerankPrompt.DEFAULT_INSTRUCTION): List<Float>

    suspend fun release() {}
}

/** The Qwen3-Reranker prompt (model card), with our own instruction in place of the default one. */
object RerankPrompt {
    const val DEFAULT_INSTRUCTION = "Given a web search query, retrieve relevant passages that answer the query"

    /** For pictures: would this visual make the sentence instantly clear. */
    const val VISUAL = "Judge whether this visual (icon, animation, diagram or primitive) would make a viewer instantly grasp " +
        "the meaning or the metaphor of the sentence, in a short motion-graphics video"

    /** For music and sound cues: does this sound suit the moment. */
    const val SOUND = "Judge whether this music or sound effect suits the mood and energy of the moment described"

    /** For few-shot examples: is this past decision a good model for the new line. */
    const val EXEMPLAR = "Judge whether this example of how a line was pictured is a useful model for picturing the new line"

    /** For glossary terms: is this term what the text talks about. */
    const val TERM = "Judge whether this glossary term is mentioned or meant in the text"

    private const val SYSTEM = "Judge whether the Document meets the requirements based on the Query and the Instruct provided. " +
        "Note that the answer can only be \"yes\" or \"no\"."

    /** The full prompt, ChatML with an empty thinking block, ending where the model says yes or no. */
    fun qwen3(instruction: String, query: String, document: String): String = head(instruction, query) + tail(document)

    /**
     * What every document of one query shares, up to `<Document>:`. It ends before the space so the
     * split falls on a pre-token boundary: head and tail tokenize exactly as the whole prompt does.
     */
    fun head(instruction: String, query: String): String =
        "<|im_start|>system\n$SYSTEM<|im_end|>\n<|im_start|>user\n<Instruct>: $instruction\n<Query>: $query\n<Document>:"

    /** One document and the end of the prompt. */
    fun tail(document: String): String = " $document<|im_end|>\n<|im_start|>assistant\n<think>\n\n</think>\n\n"
}
