package io.trimio.engine.llm.cloud

import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.errors.AnthropicIoException
import com.anthropic.errors.AnthropicServiceException
import com.anthropic.helpers.BetaMessageAccumulator
import com.anthropic.models.beta.messages.BetaJsonOutputFormat
import com.anthropic.models.beta.messages.BetaOutputConfig
import com.anthropic.models.beta.messages.BetaStopReason
import com.anthropic.models.beta.messages.MessageCreateParams
import io.trimio.engine.llm.ChatRole
import io.trimio.engine.llm.Generation
import io.trimio.engine.llm.GenerationRequest
import io.trimio.engine.llm.JsonSchemas
import io.trimio.engine.llm.LanguageModel
import io.trimio.engine.llm.LanguageModelException
import io.trimio.engine.llm.StopReason
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Claude as the director, through the official Anthropic Java SDK with the user's own key.
 *
 * - Thinking is always on for this model; [effort] trades depth for latency and cost.
 * - The schema goes to native structured output, so the reply is valid JSON by construction.
 * - Server-side refusal fallback is enabled (`fallbacks: "default"`): if the request is declined,
 *   Anthropic re-runs it on its recommended fallback model and [Generation.servedBy] names that model.
 */
class ClaudeLanguageModel(
    apiKey: String,
    private val model: String = DEFAULT_MODEL,
    private val effort: BetaOutputConfig.Effort = BetaOutputConfig.Effort.MEDIUM,
    private val client: AnthropicClient = AnthropicOkHttpClient.builder().apiKey(apiKey).build(),
) : LanguageModel {
    override val id: String = model
    override val isLocal = false

    override suspend fun generate(request: GenerationRequest, onText: (String) -> Unit): Generation = withContext(Dispatchers.IO) {
        val output = BetaOutputConfig.builder().effort(effort)
        request.schema?.let { output.format(BetaJsonOutputFormat.builder().schema(schemaOf(JsonSchemas.forCloud(it))).build()) }

        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(request.maxTokens.toLong())
            .system(request.system)
            .outputConfig(output.build())
            .addBeta(FALLBACK_BETA)
            .putAdditionalBodyProperty("fallbacks", JsonValue.from("default"))
            .apply {
                for (m in request.messages) {
                    if (m.role == ChatRole.User) addUserMessage(m.text) else addAssistantMessage(m.text)
                }
            }
            .build()

        try {
            val accumulator = BetaMessageAccumulator.create()
            client.beta().messages().createStreaming(params).use { stream ->
                val context = currentCoroutineContext()
                stream.stream().forEach { event ->
                    context.ensureActive()
                    accumulator.accumulate(event)
                    event.contentBlockDelta().flatMap { it.delta().text() }.ifPresent { onText(it.text()) }
                }
            }
            val message = accumulator.message()
            val text = message.content().mapNotNull { block -> block.text().orElse(null)?.text() }.joinToString("")
            val stop = when (message.stopReason().orElse(null)) {
                BetaStopReason.REFUSAL -> StopReason.Refusal
                BetaStopReason.MAX_TOKENS -> StopReason.MaxTokens
                else -> StopReason.EndTurn
            }
            Generation(text, stop, servedBy = message.model().asString())
        } catch (e: CancellationException) {
            throw e
        } catch (e: AnthropicServiceException) {
            throw LanguageModelException(kindOf(e.statusCode()), "Claude: ${e.message}", e)
        } catch (e: AnthropicIoException) {
            throw LanguageModelException(LanguageModelException.Kind.Network, "Claude unreachable: ${e.message}", e)
        }
    }

    private fun kindOf(status: Int) = when (status) {
        401, 403 -> LanguageModelException.Kind.Auth
        429 -> LanguageModelException.Kind.RateLimited
        413 -> LanguageModelException.Kind.ContextTooLong
        in 500..599 -> LanguageModelException.Kind.Network
        else -> LanguageModelException.Kind.Other
    }

    private fun schemaOf(schema: JsonObject): BetaJsonOutputFormat.Schema =
        BetaJsonOutputFormat.Schema.builder().apply { schema.forEach { (k, v) -> putAdditionalProperty(k, JsonValue.from(plain(v))) } }.build()

    /** kotlinx JSON → plain Java values the SDK's Jackson layer understands. */
    private fun plain(e: JsonElement): Any? = when (e) {
        is JsonNull -> null
        is JsonObject -> e.mapValues { plain(it.value) }
        is JsonArray -> e.map(::plain)
        is JsonPrimitive -> when {
            e.isString -> e.content
            e.booleanOrNull != null -> e.booleanOrNull
            e.longOrNull != null -> e.longOrNull
            else -> e.doubleOrNull
        }
    }

    companion object {
        const val DEFAULT_MODEL = "claude-opus-5-5"
        private const val FALLBACK_BETA = "server-side-fallback-2026-07-01"
    }
}
