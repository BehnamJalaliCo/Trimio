package io.trimio.feature.settings

import io.trimio.engine.models.DeviceFit
import io.trimio.engine.models.DeviceMemory
import io.trimio.engine.models.ModelFit
import io.trimio.engine.models.ModelKind
import io.trimio.engine.models.ModelSpec
import io.trimio.engine.models.ModelState

/** One model as the manager lists it: what it is, how it fits this device, and where it stands. */
data class ModelRow(
    val id: String,
    val nameFa: String,
    val nameEn: String,
    /** Parameter class ("4B", "9B", "35B-A3B"), set apart in the row. */
    val tier: String,
    /** Download size including the files installed with it (a director's eyes). */
    val sizeBytes: Long,
    /** Memory it holds while working. */
    val footprintBytes: Long,
    val fit: ModelFit,
    val state: ModelState,
    val active: Boolean,
    val experimental: Boolean,
    /** Generated tokens per second this model measured on this device (latest build), when it ran here. */
    val measuredTokensPerSecond: Float? = null,
)

/**
 * The «موتور: …» line: where the models run on this device, as the latest build measured it (the
 * GPU, the engine build, the layers it took, the CPU threads); before any build only the threads.
 */
data class HardwareLine(
    val gpu: String? = null,
    /** "vulkan", "opencl", "cpu"… */
    val backend: String? = null,
    val gpuLayers: Int? = null,
    val totalLayers: Int? = null,
    val threads: Int? = null,
    /** False until a build has run a model here (the GPU is not known yet). */
    val measured: Boolean = false,
)

/** The model manager's whole view: the device, the recommendation, and the two shelves. */
data class ModelsUi(
    val ramGb: Int,
    val desktop: Boolean,
    val directors: List<ModelRow>,
    val speech: List<ModelRow>,
    /** The best director this device runs comfortably (null when none fits). */
    val recommended: ModelRow?,
    /** Free storage, when the platform reports it. */
    val freeBytes: Long? = null,
    /** Where models run here ([HardwareLine]); null hides the line. */
    val hardware: HardwareLine? = null,
) {
    val installedBytes: Long get() = (directors + speech).filter { it.installed }.sumOf { it.sizeBytes }
}

val ModelRow.installed: Boolean get() = state == ModelState.Installed || state is ModelState.UpdateAvailable

/**
 * Builds the shelves from the catalogue: directors (4B for every phone, 9B for strong phones,
 * 35B-A3B for desktops) and speech models, each badged with [DeviceFit] for this device.
 */
object ModelShelf {
    fun build(
        catalog: List<ModelSpec>,
        states: Map<String, ModelState>,
        device: DeviceMemory,
        activeDirector: String?,
        activeSpeech: String?,
        freeBytes: Long? = null,
        hardware: HardwareLine? = null,
        /** Model id → generated tokens per second measured on this device. */
        speeds: Map<String, Float> = emptyMap(),
    ): ModelsUi {
        val byId = catalog.associateBy { it.id }
        fun row(spec: ModelSpec, active: String?): ModelRow {
            val report = DeviceFit.report(spec, device) { byId[it] }
            val companions = spec.companions.mapNotNull { byId[it] }.sumOf { it.sizeBytes }
            val (fa, en) = NAMES[spec.id] ?: (spec.titleFa to spec.titleEn)
            return ModelRow(
                spec.id, fa, en, tierOf(spec), spec.sizeBytes + companions, report.footprintBytes, report.fit,
                states[spec.id] ?: ModelState.NotInstalled, spec.id == active, spec.experimental, speeds[spec.id],
            )
        }
        val speechRows = catalog.filter { it.kind == ModelKind.Speech }.map { row(it, activeSpeech) }.sortedBy { it.sizeBytes }
        val directorRows = catalog.filter { it.kind == ModelKind.Language }.map { row(it, null) }.sortedBy { it.sizeBytes }
        // With no choice saved, the best installed comfortable director is the one in use.
        val active = activeDirector ?: directorRows.filter { it.installed && it.fit.runnable }.maxByOrNull { it.sizeBytes }?.id
        val directors = directorRows.map { it.copy(active = it.id == active) }
        // The product ladder: 4B for every phone, 9B for strong phones, 35B-A3B for desktops. The
        // highest tier rated Recommended wins (so the card and the row's badge agree), else the
        // highest that fits; memory decides, not the experimental flag (the row still shows it).
        fun pick(ok: (ModelFit) -> Boolean) = LADDER.reversed().firstNotNullOfOrNull { id ->
            directors.firstOrNull { it.id == id }?.takeIf { (!it.experimental || device.desktop) && ok(DeviceFit.rate(it.footprintBytes, device)) }
        }
        val recommended = pick { it == ModelFit.Recommended } ?: pick { it.comfortable }
        return ModelsUi(device.ramGb, device.desktop, directors, speechRows, recommended, freeBytes, hardware)
    }

    /** "4B", "9B", "8B-A1B", "35B-A3B" from the catalogue id, or the size class for speech models. */
    fun tierOf(spec: ModelSpec): String {
        if (spec.kind == ModelKind.Speech) {
            return when {
                "turbo" in spec.id -> "Turbo"
                "large" in spec.id -> "Large"
                else -> spec.id.removePrefix("whisper-").substringBefore('-').replaceFirstChar { it.uppercase() }
            }
        }
        val m = TIER.find(spec.id) ?: return ""
        return m.value.uppercase()
    }

    private val LADDER = listOf("qwen3.5-4b-q4km", "qwen3.5-9b-ud-q3kxl", "qwen3.5-9b-q4km", "qwen3.6-35b-a3b-ud-q2kxl")

    private val TIER = Regex("(?i)(e?\\d+(?:\\.\\d+)?b)(?:-a\\d+b)?")

    /** The shelf's names: what the tier is for, not the file it ships as. */
    private val NAMES = mapOf(
        "qwen3.5-4b-q4km" to ("استاندارد" to "Standard"),
        "qwen3.5-9b-ud-q3kxl" to ("حرفه‌ای سبک" to "Pro, light"),
        "qwen3.5-9b-q4km" to ("حرفه‌ای" to "Pro"),
        "qwen3.6-35b-a3b-ud-q2kxl" to ("دسکتاپ" to "Desktop"),
        "gemma-4-e4b-qat-q4" to ("جما" to "Gemma"),
        "lfm2.5-8b-a1b-q4km" to ("سریع MoE" to "Fast MoE"),
    )
}

/** What the model manager can do with one model (by catalogue id). */
data class ModelActions(
    val onBack: () -> Unit = {},
    val onDownload: (String) -> Unit = {},
    val onCancel: (String) -> Unit = {},
    val onDelete: (String) -> Unit = {},
    val onUpdate: (String) -> Unit = {},
    /** Makes an installed director the one that runs. */
    val onUse: (String) -> Unit = {},
)
