package io.trimio.engine.llm

import kotlinx.serialization.json.JsonObject

enum class ChatRole { User, Assistant }

/** An image for a model that can see: packed RGB bytes, [width]×[height]. */
class RgbImage(val width: Int, val height: Int, val rgb: ByteArray) {
    init {
        require(rgb.size == width * height * 3) { "RGB size ${rgb.size} != $width×$height×3" }
    }
}

/** One turn; a user turn may carry [images], which models that see place before the text. */
data class ChatMessage(val role: ChatRole, val text: String, val images: List<RgbImage> = emptyList())

/**
 * One request to any director model, local or cloud.
 *
 * When [schema] is set the model must answer with a single JSON value matching it: cloud models
 * use their native structured-output mode, local models are constrained token by token with a
 * GBNF grammar compiled from the same schema ([JsonSchemaGrammar]).
 */
data class GenerationRequest(
    val system: String,
    val messages: List<ChatMessage>,
    val schema: JsonObject? = null,
    val maxTokens: Int = 4096,
    /** Local models only; cloud reasoning models choose their own sampling. */
    val temperature: Float = 0.3f,
    val seed: Int = 0,
)

enum class StopReason {
    EndTurn,
    MaxTokens,

    /** The model (or its safety system) declined. Never treat the partial text as an answer. */
    Refusal,
    Cancelled,
}

data class Generation(
    val text: String,
    val stopReason: StopReason,
    /** Model that actually produced the answer (differs from the requested one after a fallback). */
    val servedBy: String,
    /** Tokens and time of this call, where the engine measures them (on-device models). */
    val usage: Usage? = null,
)

/** What one or more calls cost on the device: tokens read and written, and the time each took. */
data class Usage(
    val calls: Int = 1,
    val promptTokens: Int = 0,
    /** Prompt tokens not read again: a head shared with the previous call, kept in the model's memory. */
    val reusedTokens: Int = 0,
    val generatedTokens: Int = 0,
    val promptMs: Long = 0,
    val generateMs: Long = 0,
) {
    /** Prompt tokens actually read per second (reused ones cost nothing). */
    val promptTokensPerSecond: Float get() = perSecond(promptTokens - reusedTokens, promptMs)
    val generatedTokensPerSecond: Float get() = perSecond(generatedTokens, generateMs)

    operator fun plus(other: Usage) = Usage(
        calls + other.calls, promptTokens + other.promptTokens, reusedTokens + other.reusedTokens,
        generatedTokens + other.generatedTokens, promptMs + other.promptMs, generateMs + other.generateMs,
    )

    /** "12 calls, read 9800 tokens at 85/s (6100 reused), wrote 3100 at 9.2/s". */
    fun summary(): String =
        "$calls calls, read $promptTokens tokens at ${one(promptTokensPerSecond)}/s ($reusedTokens reused), " +
            "wrote $generatedTokens at ${one(generatedTokensPerSecond)}/s"

    private fun perSecond(tokens: Int, ms: Long) = if (ms <= 0) 0f else tokens * MS / ms

    private companion object {
        const val MS = 1000f
        fun one(v: Float) = (kotlin.math.round(v * 10) / 10).toString()
    }
}

/**
 * Where an on-device model runs: the engine build ([backend]: "vulkan", "opencl", "cpu"), the GPU
 * and how many of the model's layers it took, the CPU threads, the cache, and the calls so far.
 * [facts] keeps every raw detail the engine reported (devices, buffers) for the technical report.
 */
data class ModelRuntime(
    val backend: String,
    val gpu: String?,
    val gpuLayers: Int,
    val totalLayers: Int,
    val threads: Int,
    val kvCache: String,
    /** Why the GPU was not used although one exists ("gpu-coverage 0.65", "fell back"), else null. */
    val cpuReason: String? = null,
    val usage: Usage = Usage(calls = 0),
    val facts: List<Pair<String, String>> = emptyList(),
) {
    /** One line for logs and the report: "llm vulkan NVIDIA GeForce RTX 3050 Ti 20/33 layers, 4 threads, kv q8_0; …". */
    fun summary(): String = buildString {
        append("llm ").append(backend).append(' ')
        append(gpu?.let { "$it $gpuLayers/$totalLayers layers" } ?: "cpu only")
        cpuReason?.let { append(" (").append(it).append(')') }
        append(", ").append(threads).append(" threads, kv ").append(kvCache)
        if (usage.calls > 0) append("; ").append(usage.summary())
    }
}

interface LanguageModel {
    /** Stable id, e.g. "claude-opus-5-5" or a catalogue id such as "qwen3.5-4b-q4km". */
    val id: String

    /** Runs on the phone: private, offline, free; slower and smaller than cloud models. */
    val isLocal: Boolean

    /** Whether this model looks at [ChatMessage.images] (otherwise they are ignored). */
    val canSee: Boolean get() = false

    /** On-device models once loaded: where they run and how fast they have been; null otherwise. */
    val runtime: ModelRuntime? get() = null

    /**
     * Generates a reply, streaming text through [onText] as it is produced. Cancelling the
     * coroutine stops generation. Throws [LanguageModelException] on failures the caller can explain.
     */
    suspend fun generate(request: GenerationRequest, onText: (String) -> Unit = {}): Generation

    /** Frees heavy resources (a local model's weights) until the next request. */
    suspend fun release() {}
}

class LanguageModelException(val kind: Kind, message: String, cause: Throwable? = null) : Exception(message, cause) {
    enum class Kind {
        /** Missing, invalid or revoked API key. */
        Auth,
        RateLimited,
        Network,

        /** The prompt does not fit the model's context window. */
        ContextTooLong,

        /** Model file missing or failed to load. */
        Unavailable,

        /** The reply could not be parsed into the requested structure. */
        InvalidOutput,

        /** The model or its safety system declined; the user is told, the rules engine takes over. */
        Refused,
        Other,
    }
}
