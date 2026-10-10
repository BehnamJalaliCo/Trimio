package io.trimio.engine.llm.local

import io.trimio.engine.llm.LanguageModel
import io.trimio.engine.models.ChatFormat
import io.trimio.engine.models.ModelCatalog
import io.trimio.engine.models.ModelKind
import io.trimio.engine.models.ModelSpec
import io.trimio.engine.models.ModelStore

/**
 * On-device director models that are installed and fit this phone. The user's choice in settings
 * wins; otherwise the largest installed model the device's RAM tier allows.
 */
class LocalModels(
    private val store: ModelStore,
    private val ramGb: Int,
    private val catalog: List<ModelSpec> = ModelCatalog.all,
) {
    fun installed(): List<ModelSpec> =
        catalog.filter { it.kind == ModelKind.Language && it.tier.minRamGb <= ramGb && store.isInstalled(it) }

    fun best(preferredId: String? = null): LanguageModel? {
        val candidates = installed()
        val spec = candidates.firstOrNull { it.id == preferredId } ?: candidates.maxByOrNull { it.sizeBytes } ?: return null
        // A multimodal director loads its own projector: one model directs and looks.
        val eyes = ModelCatalog.visionFor(spec).singleOrNull()?.takeIf { store.isInstalled(it) }
        return LlamaLanguageModel(spec.id, store.pathOf(spec).toString(), spec.chatFormat ?: ChatFormat.ChatMl, visionPath = eyes?.let { store.pathOf(it).toString() })
    }

    /**
     * Who looks at frames: the director itself when it has its projector, otherwise the light
     * looker (a small multimodal model installed next to a text-only director), or nobody.
     */
    fun eyes(director: LanguageModel?): LanguageModel? {
        if (director?.canSee == true) return director
        val looker = ModelCatalog.qwen35_08bLooker
        val projector = ModelCatalog.qwen35_08bEyes
        if (!store.isInstalled(looker) || !store.isInstalled(projector)) return null
        return LlamaLanguageModel(looker.id, store.pathOf(looker).toString(), ChatFormat.ChatMl, contextSize = 4096, visionPath = store.pathOf(projector).toString())
    }
}
