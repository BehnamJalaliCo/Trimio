package io.trimio.engine.llm

import kotlinx.serialization.json.JsonObject

enum class ChatRole { User, Assistant }

data class ChatMessage(val role: ChatRole, val text: String)

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
)

interface LanguageModel {
    /** Stable id, e.g. "claude-opus-5-5" or a catalogue id such as "qwen3.5-4b-q4km". */
    val id: String

    /** Runs on the phone: private, offline, free; slower and smaller than cloud models. */
    val isLocal: Boolean

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
