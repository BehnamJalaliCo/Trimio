package io.trimio.engine.llm

import io.ktor.client.HttpClient

/** Cloud providers the user can connect with their own key (BYOK); Trimio runs no AI server. */
enum class CloudProvider(val secretName: String, val defaultModel: String, val title: String) {
    Anthropic("anthropic-api-key", "claude-opus-5-5", "Claude"),
    OpenAI("openai-api-key", "gpt-5", "OpenAI"),
}

/**
 * Builds cloud director models from stored keys. On Android/desktop [claude] is the official Java
 * SDK; elsewhere (web, iOS) Claude goes over plain HTTP. OpenAI is plain HTTP everywhere.
 */
class CloudModels(
    private val secrets: SecretStore,
    private val http: HttpClient,
    private val claude: ((apiKey: String, model: String) -> LanguageModel)? = null,
) {
    suspend fun hasKey(provider: CloudProvider): Boolean = !secrets.read(provider.secretName).isNullOrBlank()

    suspend fun saveKey(provider: CloudProvider, key: String) = secrets.write(provider.secretName, key.trim())

    suspend fun removeKey(provider: CloudProvider) = secrets.delete(provider.secretName)

    /** The model for [provider], or null without a key. */
    suspend fun create(provider: CloudProvider, model: String = provider.defaultModel): LanguageModel? {
        val key = secrets.read(provider.secretName)?.takeIf { it.isNotBlank() } ?: return null
        return when (provider) {
            CloudProvider.Anthropic -> claude?.invoke(key, model) ?: ClaudeHttpLanguageModel(http, key, model)
            CloudProvider.OpenAI -> OpenAiLanguageModel(http, key, model)
        }
    }

    /** First connected provider, Claude preferred. */
    suspend fun firstAvailable(): LanguageModel? = CloudProvider.entries.firstNotNullOfOrNull { create(it) }
}
