package io.trimio.engine.llm

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Claude over plain HTTP, for platforms without the official SDK (web/Wasm and iOS/Native); Android
 * and desktop use the Java SDK ([io.trimio.engine.llm.cloud.ClaudeLanguageModel]). Same request:
 * native structured output, effort `medium`, and the server-side refusal fallback, whose serving
 * model is reported in [Generation.servedBy].
 */
class ClaudeHttpLanguageModel(
    private val http: HttpClient,
    private val apiKey: String,
    private val model: String = CloudProvider.Anthropic.defaultModel,
    private val endpoint: String = "https://api.anthropic.com/v1/messages",
) : LanguageModel {
    override val id: String = model
    override val isLocal = false

    @Suppress("ThrowsCount")
    override suspend fun generate(request: GenerationRequest, onText: (String) -> Unit): Generation {
        val body = buildJsonObject {
            put("model", model)
            put("max_tokens", request.maxTokens)
            put("system", request.system)
            put(
                "messages",
                buildJsonArray {
                    for (m in request.messages) {
                        add(buildJsonObject { put("role", if (m.role == ChatRole.User) "user" else "assistant"); put("content", m.text) })
                    }
                },
            )
            put(
                "output_config",
                buildJsonObject {
                    put("effort", "medium")
                    request.schema?.let { schema ->
                        put("format", buildJsonObject { put("type", "json_schema"); put("schema", JsonSchemas.forCloud(schema)) })
                    }
                },
            )
            put("fallbacks", "default")
        }

        val (status, text) = try {
            val response = http.post(endpoint) {
                header("x-api-key", apiKey)
                header("anthropic-version", API_VERSION)
                header("anthropic-beta", FALLBACK_BETA)
                // The key is the user's own and never leaves their device except to Anthropic, so the
                // browser build may call the API directly (CORS opt-in; ignored by native clients).
                header("anthropic-dangerous-direct-browser-access", "true")
                setBody(TextContent(body.toString(), ContentType.Application.Json))
            }
            response.status.value to response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (@Suppress("TooGenericExceptionCaught") e: Exception) {
            throw LanguageModelException(LanguageModelException.Kind.Network, "Claude unreachable: ${e.message}", e)
        }

        val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
        if (status !in 200..299) throw error(status, json)
        json ?: throw LanguageModelException(LanguageModelException.Kind.InvalidOutput, "Claude returned no JSON")

        val content = json["content"]?.jsonArray.orEmpty()
            .map { it.jsonObject }
            .filter { it["type"]?.jsonPrimitive?.contentOrNull == "text" }
            .joinToString("") { it["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
        val stop = when (json["stop_reason"]?.jsonPrimitive?.contentOrNull) {
            "refusal" -> StopReason.Refusal
            "max_tokens" -> StopReason.MaxTokens
            else -> StopReason.EndTurn
        }
        if (stop != StopReason.Refusal) onText(content)
        return Generation(content, stop, servedBy = json["model"]?.jsonPrimitive?.contentOrNull ?: model)
    }

    private fun error(status: Int, json: JsonObject?): LanguageModelException {
        val message = json?.get("error")?.jsonObject?.get("message")?.jsonPrimitive?.contentOrNull ?: "HTTP $status"
        val kind = when (status) {
            401, 403 -> LanguageModelException.Kind.Auth
            429 -> LanguageModelException.Kind.RateLimited
            413 -> LanguageModelException.Kind.ContextTooLong
            in 500..599 -> LanguageModelException.Kind.Network
            else -> LanguageModelException.Kind.Other
        }
        return LanguageModelException(kind, "Claude: $message")
    }

    private companion object {
        const val API_VERSION = "2023-06-01"
        const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
    }
}
