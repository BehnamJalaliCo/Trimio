package io.trimio.engine.llm

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.content.TextContent
import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/** OpenAI Chat Completions with strict JSON-schema output, using the user's own key. */
class OpenAiLanguageModel(
    private val http: HttpClient,
    private val apiKey: String,
    private val model: String,
    private val endpoint: String = "https://api.openai.com/v1/chat/completions",
) : LanguageModel {
    override val id: String = model
    override val isLocal = false

    override suspend fun generate(request: GenerationRequest, onText: (String) -> Unit): Generation {
        val body = buildJsonObject {
            put("model", model)
            put("max_completion_tokens", request.maxTokens)
            put(
                "messages",
                buildJsonArray {
                    add(buildJsonObject { put("role", "developer"); put("content", request.system) })
                    for (m in request.messages) {
                        add(buildJsonObject { put("role", if (m.role == ChatRole.User) "user" else "assistant"); put("content", m.text) })
                    }
                },
            )
            request.schema?.let { schema ->
                put(
                    "response_format",
                    buildJsonObject {
                        put("type", "json_schema")
                        put(
                            "json_schema",
                            buildJsonObject {
                                put("name", "trimio_output")
                                put("strict", true)
                                put("schema", JsonSchemas.forCloud(schema))
                            },
                        )
                    },
                )
            }
        }

        val (status, text) = try {
            val response = http.post(endpoint) {
                header(HttpHeaders.Authorization, "Bearer $apiKey")
                setBody(TextContent(body.toString(), ContentType.Application.Json))
            }
            response.status.value to response.bodyAsText()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw LanguageModelException(LanguageModelException.Kind.Network, "OpenAI unreachable: ${e.message}", e)
        }

        val json = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull()
        if (status !in 200..299) throw error(status, json)

        val choice = json?.get("choices")?.jsonArray?.firstOrNull()?.jsonObject
            ?: throw LanguageModelException(LanguageModelException.Kind.InvalidOutput, "OpenAI returned no choices")
        val message = choice["message"]?.jsonObject
        val refusal = message?.get("refusal")?.jsonPrimitive?.contentOrNull
        val content = message?.get("content")?.jsonPrimitive?.contentOrNull.orEmpty()
        val stop = when {
            refusal != null -> StopReason.Refusal
            choice["finish_reason"]?.jsonPrimitive?.contentOrNull == "length" -> StopReason.MaxTokens
            choice["finish_reason"]?.jsonPrimitive?.contentOrNull == "content_filter" -> StopReason.Refusal
            else -> StopReason.EndTurn
        }
        if (stop != StopReason.Refusal) onText(content)
        return Generation(content, stop, servedBy = (json["model"] as? JsonPrimitive)?.contentOrNull ?: model)
    }

    private fun error(status: Int, json: JsonObject?): LanguageModelException {
        val err = json?.get("error")?.jsonObject
        val code = err?.get("code")?.jsonPrimitive?.contentOrNull
        val message = err?.get("message")?.jsonPrimitive?.contentOrNull ?: "HTTP $status"
        val kind = when {
            status == 401 || status == 403 -> LanguageModelException.Kind.Auth
            status == 429 -> LanguageModelException.Kind.RateLimited
            code == "context_length_exceeded" -> LanguageModelException.Kind.ContextTooLong
            status >= 500 -> LanguageModelException.Kind.Network
            else -> LanguageModelException.Kind.Other
        }
        return LanguageModelException(kind, "OpenAI: $message")
    }
}
