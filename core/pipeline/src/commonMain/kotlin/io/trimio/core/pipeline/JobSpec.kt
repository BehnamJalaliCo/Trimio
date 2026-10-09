package io.trimio.core.pipeline

import io.trimio.core.model.input.InputSource
import io.trimio.core.model.text.Language

/** Everything the user asked for in one job. */
data class JobSpec(
    val id: String,
    val input: InputSource,
    /** Free-form instruction, simple ("make it energetic") or long ChatGPT/Claude-style briefs. */
    val prompt: String,
    /** Explicit style pick; null lets the Director choose from the prompt. */
    val styleId: String? = null,
    /** Spoken language hint; null means auto-detect. */
    val language: Language? = null,
    val director: DirectorBackend = DirectorBackend.OnDevice,
    val seed: Long = 0,
)

enum class DirectorBackend {
    /** Bundled/downloaded GGUF model through llama.cpp. */
    OnDevice,

    /** User's own Claude or OpenAI key. Falls back to [OnDevice] when offline. */
    Cloud,
}
