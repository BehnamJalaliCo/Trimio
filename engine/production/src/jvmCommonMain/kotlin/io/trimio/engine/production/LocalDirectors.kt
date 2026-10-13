package io.trimio.engine.production

import io.trimio.engine.asr.whisper.InstalledWhisperRecognizer
import io.trimio.engine.autopilot.ArtShelf
import io.trimio.engine.llm.LanguageModel
import io.trimio.engine.llm.local.LlamaEmbedder
import io.trimio.engine.llm.local.LocalEncoders
import io.trimio.engine.llm.local.LocalModels
import io.trimio.engine.models.DeviceFit
import io.trimio.engine.models.DeviceMemory
import io.trimio.engine.models.MediaAddOns
import io.trimio.engine.models.ModelCatalog
import io.trimio.engine.models.ModelFit
import io.trimio.engine.models.ModelKind
import io.trimio.engine.models.ModelSpec
import io.trimio.engine.models.ModelStore
import io.trimio.engine.onnx.OrtEngine
import io.trimio.engine.perception.ModelFiles
import io.trimio.engine.perception.Perception
import io.trimio.engine.retrieval.Corpora
import io.trimio.engine.retrieval.PictureRecall
import io.trimio.engine.retrieval.Retrieval

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

    /**
     * The next lighter director after the model [current] (null: the one [DirectorChoice.Auto] runs):
     * the largest installed model that runs here and is smaller than it, else the rules.
     */
    fun lighter(current: String?): DirectorChoice {
        val id = current ?: local.best(preferred())?.id ?: return DirectorChoice.Rules
        val size = catalog.firstOrNull { it.id == id }?.sizeBytes ?: ModelCatalog.byId(id)?.sizeBytes ?: return DirectorChoice.Rules
        return local.installed().filter { it.sizeBytes < size }.maxByOrNull { it.sizeBytes }?.let { DirectorChoice.Local(it.id) } ?: DirectorChoice.Rules
    }

    /** The speech model [recognizer] resolves to now (its catalogue id), for the technical report. */
    fun speechModel(preferredSpeech: () -> String? = { null }): String? = speechSpec(preferredSpeech)?.id

    private fun local(id: String?): Director {
        val model = local.best(id) ?: return Director.None
        return Director(model, local.eyes(model))
    }

    /**
     * Whisper with the installed speech model: [preferredSpeech] when installed; else, on a phone,
     * the large-v3 turbo (4 decoder layers instead of 32: several times faster to decode on a phone's
     * CPU) when it is installed; else the largest that fits. Fit is the real memory check the
     * models screen shows ([DeviceFit]), not the coarse RAM tier.
     */
    fun recognizer(preferredSpeech: () -> String? = { null }) = InstalledWhisperRecognizer(
        resolveVadPath = { vadPath() },
    ) {
        speechSpec(preferredSpeech)?.let { store.pathOf(it).toString() }
    }

    /**
     * The perception add-ons (faces, word alignment, punctuation) when at least one of them is
     * installed; null otherwise, and then the production runs exactly as without add-ons.
     */
    fun perception(): Perception? {
        val files = ModelFiles { spec -> spec.takeIf { installed(it) }?.let { store.pathOf(it).toString() } }
        if (PERCEPTION.none { files.pathOf(it) != null }) return null
        return runCatching { Perception(OrtEngine(), files) }.getOrNull()
    }

    /**
     * The picture search's retrieval add-on ([io.trimio.engine.autopilot.ArtShelf.recall]): answers
     * only while the embedder is installed (the vector pack ships; the reranker joins when installed).
     */
    fun pictureRecall(): ArtShelf.Recall = PictureRecall {
        val encoders = LocalEncoders(store, device)
        (encoders.embedder() as? LlamaEmbedder)?.let { e ->
            runCatching {
                e.load()
                Retrieval.open(Corpora.pictures(ArtShelf.indexJson()), e, encoders.reranker())
            }.onFailure { e.close() }.getOrNull()
        }
    }.let { r -> ArtShelf.Recall(r::search) }

    private fun installed(spec: ModelSpec): Boolean = runCatching { store.isInstalled(spec) }.getOrDefault(false)

    /**
     * Silero VAD's file when that add-on is installed AND `-Dtrimio.whisper.vad=on`; else null (unchanged).
     * Off by default: on a 60 s Persian proof (long.mp4) whisper with VAD lost 43 of 152 words (a whole passage).
     */
    private fun vadPath(): String? =
        MediaAddOns.vad.takeIf { System.getProperty("trimio.whisper.vad") == "on" && installed(it) }?.let { store.pathOf(it).toString() }

    private fun speechSpec(preferredSpeech: () -> String?): ModelSpec? {
        val installed = catalog.filter { it.kind == ModelKind.Speech && store.isInstalled(it) }
        val fits = installed.filter { DeviceFit.of(it, device) <= ModelFit.Experimental }
        return installed.firstOrNull { it.id == preferredSpeech() }
            ?: PHONE_SPEECH.takeUnless { device.desktop }?.firstNotNullOfOrNull { id -> fits.firstOrNull { it.id == id } }
            ?: fits.maxByOrNull { it.sizeBytes }
            ?: installed.minByOrNull { it.sizeBytes }
    }

    private companion object {
        /** The perception add-ons the production uses ([AddOnStages]). */
        val PERCEPTION = listOf(MediaAddOns.faces, MediaAddOns.aligner, MediaAddOns.punctuation)

        /** The phone's default speech models, best first: turbo decodes fast enough for a phone's CPU. */
        val PHONE_SPEECH = listOf(ModelCatalog.whisperTurboQ8.id, ModelCatalog.whisperTurboQ5.id)
    }
}
