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
        return LlamaLanguageModel(spec.id, store.pathOf(spec).toString(), spec.chatFormat ?: ChatFormat.ChatMl)
    }
}
