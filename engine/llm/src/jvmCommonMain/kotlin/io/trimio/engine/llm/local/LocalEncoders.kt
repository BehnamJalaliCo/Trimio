package io.trimio.engine.llm.local

import io.trimio.engine.llm.EmbeddingFamily
import io.trimio.engine.llm.TextEmbedder
import io.trimio.engine.llm.TextReranker
import io.trimio.engine.models.DeviceClass
import io.trimio.engine.models.DeviceMemory
import io.trimio.engine.models.ModelSpec
import io.trimio.engine.models.ModelStore
import io.trimio.engine.models.TextAddOns

/**
 * The retrieval add-ons installed on this device, opened as llama.cpp encoders: the embedder
 * (resident while an edit is made) and the reranker (loaded for the pictures, released before
 * rendering). Preference: the add-on the vector packs are built for, then the larger laptop
 * models, then the alternatives — only what this device class is offered and has installed.
 */
class LocalEncoders(
    private val store: ModelStore,
    private val device: DeviceMemory,
    private val threads: Int = LlamaLanguageModel.defaultThreads(),
) {
    private fun usable(spec: ModelSpec): Boolean {
        val family = if (device.desktop) DeviceClass.Laptop else DeviceClass.Phone
        return spec.addOn?.devices?.contains(family) != false && store.isInstalled(spec)
    }

    /** The embedder to use, or null when none is installed. */
    fun embedder(): TextEmbedder? {
        val order = listOf(TextAddOns.embedder, TextAddOns.embedding4b, TextAddOns.embeddingGemma2, TextAddOns.bgeM3).distinct()
        val spec = order.firstOrNull(::usable) ?: return null
        return LlamaEmbedder(spec.id, store.pathOf(spec).toString(), familyOf(spec), threads = threads)
    }

    /** The reranker to use, or null when none is installed (the visual one judges text too). */
    fun reranker(): TextReranker? {
        val order = listOf(TextAddOns.rerankerFor(device), TextAddOns.reranker4b, TextAddOns.reranker06b, TextAddOns.vlReranker2b).distinct()
        val spec = order.firstOrNull(::usable) ?: return null
        return LlamaReranker(spec.id, store.pathOf(spec).toString(), threads = threads)
    }

    companion object {
        /** How each embedder of the catalogue wants its inputs written. */
        fun familyOf(spec: ModelSpec): EmbeddingFamily = when {
            spec.id.startsWith("embeddinggemma") -> EmbeddingFamily.EmbeddingGemma
            spec.id.startsWith("qwen3-embedding") -> EmbeddingFamily.Qwen3Embedding
            else -> EmbeddingFamily.BgeM3
        }
    }
}
