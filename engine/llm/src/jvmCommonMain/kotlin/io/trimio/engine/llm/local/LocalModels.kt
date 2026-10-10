package io.trimio.engine.llm.local

import io.trimio.engine.llm.LanguageModel
import io.trimio.engine.models.ChatFormat
import io.trimio.engine.models.DeviceFit
import io.trimio.engine.models.DeviceMemory
import io.trimio.engine.models.DirectorProfile
import io.trimio.engine.models.ModelCatalog
import io.trimio.engine.models.ModelKind
import io.trimio.engine.models.ModelSpec
import io.trimio.engine.models.ModelStore

/**
 * On-device director models that are installed and fit this device. The user's choice in settings
 * wins, even an experimental one, as long as the device can hold it; otherwise the largest
 * installed model that fits comfortably ([DeviceFit]). Each model is built with its family's
 * [DirectorProfile]: context, image budget and the reasoning switch.
 */
class LocalModels(
    private val store: ModelStore,
    ramGb: Int,
    private val catalog: List<ModelSpec> = ModelCatalog.all,
    private val device: DeviceMemory = DeviceMemory.phone(ramGb),
) {
    private fun byId(id: String) = catalog.firstOrNull { it.id == id } ?: ModelCatalog.byId(id)

    fun installed(): List<ModelSpec> =
        catalog.filter { it.kind == ModelKind.Language && DeviceFit.of(it, device, ::byId).runnable && store.isInstalled(it) }

    fun best(preferredId: String? = null): LanguageModel? {
        val candidates = installed()
        val spec = candidates.firstOrNull { it.id == preferredId }
            ?: candidates.filter { DeviceFit.of(it, device, ::byId).comfortable }.maxByOrNull { it.sizeBytes }
            ?: candidates.filterNot { it.experimental }.minByOrNull { it.sizeBytes }
            ?: return null
        val profile = DirectorProfile.of(spec)
        // A multimodal director loads its own projector: one model directs and looks.
        val eyes = ModelCatalog.visionFor(spec).singleOrNull()?.takeIf { store.isInstalled(it) }
        return LlamaLanguageModel(
            spec.id, store.pathOf(spec).toString(), spec.chatFormat ?: ChatFormat.ChatMl, contextSize = profile.contextSize,
            visionPath = eyes?.let { store.pathOf(it).toString() }, maxImageTokens = profile.maxImageTokens, assistantPrefix = profile.assistantPrefix,
        )
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
        val profile = DirectorProfile.of(looker)
        return LlamaLanguageModel(
            looker.id, store.pathOf(looker).toString(), ChatFormat.ChatMl, contextSize = profile.contextSize,
            visionPath = store.pathOf(projector).toString(), maxImageTokens = profile.maxImageTokens, assistantPrefix = profile.assistantPrefix,
        )
    }
}
