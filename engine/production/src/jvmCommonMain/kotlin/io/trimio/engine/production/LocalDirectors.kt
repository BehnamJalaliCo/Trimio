package io.trimio.engine.production

import io.trimio.engine.asr.whisper.InstalledWhisperRecognizer
import io.trimio.engine.llm.LanguageModel
import io.trimio.engine.llm.local.LocalModels
import io.trimio.engine.models.DeviceFit
import io.trimio.engine.models.DeviceMemory
import io.trimio.engine.models.ModelCatalog
import io.trimio.engine.models.ModelFit
import io.trimio.engine.models.ModelKind
import io.trimio.engine.models.ModelSpec
import io.trimio.engine.models.ModelStore

/**
 * Directors from the installed on-device models (llama.cpp through JNI), shared by Android and
 * desktop: [DirectorChoice.Auto] is the user's pick in settings ([preferred]) or the largest that
 * fits the device comfortably ([LocalModels], [io.trimio.engine.models.DeviceFit]); the eyes are
 * the director's own projector or the light looker. [cloud] answers [DirectorChoice.Cloud].
 */
class LocalDirectors(
    private val store: ModelStore,
    private val device: DeviceMemory,
    private val catalog: List<ModelSpec> = ModelCatalog.all,
    private val preferred: () -> String? = { null },
    private val cloud: suspend () -> LanguageModel? = { null },
) {
    private val local = LocalModels(store, device.ramGb, catalog, device)

    suspend fun director(choice: DirectorChoice): Director = when (choice) {
        DirectorChoice.Rules -> Director.None
        DirectorChoice.Cloud -> cloud()?.let { Director(it, local.eyes(it)) } ?: director(DirectorChoice.Auto)
        DirectorChoice.Auto -> local(preferred())
        is DirectorChoice.Local -> local(choice.modelId)
    }

    private fun local(id: String?): Director {
        val model = local.best(id) ?: return Director.None
        return Director(model, local.eyes(model))
    }

    /**
     * Whisper with the installed speech model: [preferredSpeech] when installed, else the largest that fits.
     * Fit is the real memory check the models screen shows ([DeviceFit]), not the coarse RAM tier.
     */
    fun recognizer(preferredSpeech: () -> String? = { null }) = InstalledWhisperRecognizer {
        val installed = catalog.filter { it.kind == ModelKind.Speech && store.isInstalled(it) }
        (
            installed.firstOrNull { it.id == preferredSpeech() }
                ?: installed.filter { DeviceFit.of(it, device) <= ModelFit.Experimental }.maxByOrNull { it.sizeBytes }
                ?: installed.minByOrNull { it.sizeBytes }
            )?.let { store.pathOf(it).toString() }
    }
}
