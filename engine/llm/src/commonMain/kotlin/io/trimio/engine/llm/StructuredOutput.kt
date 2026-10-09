package io.trimio.engine.llm

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/**
 * Asks [this] model for a value of type [T] described by [GenerationRequest.schema].
 *
 * Grammar-constrained local output and cloud structured output are valid JSON by construction,
 * but a model may still be cut off by the token limit, so a parse failure gets one corrective
 * retry that shows the model its own broken output before giving up with
 * [LanguageModelException.Kind.InvalidOutput]. Refusals are surfaced as their own error and never retried.
 */
suspend fun <T> LanguageModel.generateStructured(
    request: GenerationRequest,
    serializer: KSerializer<T>,
    onText: (String) -> Unit = {},
    /** Told which model actually answered (a refusal fallback may differ from [LanguageModel.id]). */
    onServed: (String) -> Unit = {},
): T {
    requireNotNull(request.schema) { "Structured generation needs a schema" }
    var current = request
    repeat(2) { attempt ->
        val generation = generate(current, onText)
        onServed(generation.servedBy)
        when (generation.stopReason) {
            StopReason.Refusal -> throw LanguageModelException(LanguageModelException.Kind.Refused, "The model declined this request")
            StopReason.Cancelled -> throw LanguageModelException(LanguageModelException.Kind.Other, "Generation was cancelled")
            else -> Unit
        }
        try {
            return StructuredJson.decode(serializer, generation.text)
        } catch (e: SerializationException) {
            if (attempt == 1) throw LanguageModelException(LanguageModelException.Kind.InvalidOutput, "Unparseable reply: ${e.message}", e)
            current = request.copy(
                messages = request.messages +
                    ChatMessage(ChatRole.Assistant, generation.text.take(2_000)) +
                    ChatMessage(ChatRole.User, "That reply was not valid JSON for the schema (${e.message?.take(200)}). Reply again with the complete JSON only."),
            )
        } catch (e: IllegalArgumentException) {
            if (attempt == 1) throw LanguageModelException(LanguageModelException.Kind.InvalidOutput, "Unparseable reply: ${e.message}", e)
            current = request.copy(
                messages = request.messages + ChatMessage(ChatRole.User, "Reply with the complete JSON object only."),
            )
        }
    }
    error("unreachable")
}

object StructuredJson {
    val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
    }

    /** Decodes the first top-level JSON object in [text], tolerating fences or chatter around it. */
    fun <T> decode(serializer: KSerializer<T>, text: String): T {
        val start = text.indexOf('{')
        require(start >= 0) { "No JSON object in reply" }
        var depth = 0
        var inString = false
        var escaped = false
        for (i in start until text.length) {
            val c = text[i]
            when {
                escaped -> escaped = false
                c == '\\' && inString -> escaped = true
                c == '"' -> inString = !inString
                !inString && c == '{' -> depth++
                !inString && c == '}' -> if (--depth == 0) return json.decodeFromString(serializer, text.substring(start, i + 1))
            }
        }
        throw SerializationException("Truncated JSON object")
    }
}
