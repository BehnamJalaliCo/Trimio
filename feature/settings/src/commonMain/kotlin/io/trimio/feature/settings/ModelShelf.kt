package io.trimio.feature.settings

import io.trimio.engine.models.AddOnAdvice
import io.trimio.engine.models.AddOnTier
import io.trimio.engine.models.DeviceClass
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
    /** A director the creator added from their own disk (its file stays theirs: removing only forgets it). */
    val own: Boolean = false,
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
    /** «تقویت کیفیت»: the add-on tiers offered on this device, Essential first. */
    val boosts: List<BoostTier> = emptyList(),
    /** «پیشنهاد برای این دستگاه»: the one-tap set; null when nothing is offered. */
    val boostPlan: BoostPlan? = null,
) {
    val installedBytes: Long get() = (directors + speech).filter { it.installed }.sumOf { it.sizeBytes }
}

val ModelRow.installed: Boolean get() = state == ModelState.Installed || state is ModelState.UpdateAvailable

/** One quality add-on as the «تقویت کیفیت» section lists it: the model row and what it brings. */
data class BoostRow(
    val row: ModelRow,
    val purposeFa: String,
    val purposeEn: String,
    /** In the one-tap set for this device. */
    val recommended: Boolean,
    /** Laptop only, or best on one; shown as a note. */
    val laptopOnly: Boolean,
)

/** One tier of add-ons (ضروری / حرفه‌ای / فوق‌حرفه‌ای). */
data class BoostTier(val tier: AddOnTier, val rows: List<BoostRow>) {
    val titleFa: String get() = when (tier) {
        AddOnTier.Essential -> "ضروری"
        AddOnTier.Pro -> "حرفه\u200Cای"
        AddOnTier.Ultra -> "فوق\u200Cحرفه\u200Cای"
    }
    val titleEn: String get() = tier.titleEn
}

/** The one-tap download: the ids still to fetch and their total size (companions included). */
data class BoostPlan(val ids: List<String>, val missingIds: List<String>, val downloadBytes: Long, val ramBytes: Long) {
    val done: Boolean get() = missingIds.isEmpty()
}

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
                // An own model's name already says its class ("Qwen3-32B-Q4_K_M").
                spec.id, fa, en, if (spec.localPath != null) "" else tierOf(spec), spec.sizeBytes + companions, report.footprintBytes, report.fit,
                states[spec.id] ?: ModelState.NotInstalled, spec.id == active, spec.experimental, speeds[spec.id], own = spec.localPath != null,
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
        val director = catalog.firstOrNull { it.id == active } ?: recommended?.let { r -> catalog.firstOrNull { it.id == r.id } }
        val (boosts, plan) = boosts(catalog, states, device, director, ::row)
        return ModelsUi(device.ramGb, device.desktop, directors, speechRows, recommended, freeBytes, hardware, boosts, plan)
    }

    /**
     * The add-on tiers: every add-on this device is offered ([AddOnAdvice.offers]: laptop-only ones
     * left out on a phone, each rated by memory), grouped by tier, with the one-tap set beside the
     * [director] in use.
     */
    private fun boosts(
        catalog: List<ModelSpec>, states: Map<String, ModelState>, device: DeviceMemory, director: ModelSpec?, row: (ModelSpec, String?) -> ModelRow,
    ): Pair<List<BoostTier>, BoostPlan?> {
        val offers = AddOnAdvice.offers(device, director, catalog)
        if (offers.isEmpty()) return emptyList<BoostTier>() to null
        val tiers = AddOnTier.entries.mapNotNull { tier ->
            val rows = offers.mapNotNull { o ->
                val a = o.spec.addOn?.takeIf { it.tier == tier } ?: return@mapNotNull null
                BoostRow(row(o.spec, null).copy(footprintBytes = o.footprintBytes, fit = o.fit), a.purposeFa, a.purposeEn, o.recommended, DeviceClass.Phone !in a.devices)
            }.sortedByDescending { it.recommended }
            rows.takeIf { it.isNotEmpty() }?.let { BoostTier(tier, it) }
        }
        val picked = offers.filter { it.recommended }.map { it.spec }
        val (files, _) = AddOnAdvice.download(picked, catalog)
        val missing = files.filter { (states[it.id] ?: ModelState.NotInstalled).let { s -> s != ModelState.Installed && s !is ModelState.UpdateAvailable } }
        val plan = BoostPlan(picked.map { it.id }, missing.map { it.id }, missing.sumOf { it.sizeBytes }, offers.filter { it.recommended }.maxOfOrNull { it.footprintBytes } ?: 0)
        return tiers to plan.takeIf { picked.isNotEmpty() }
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
        // An own model's class is in its file name ("Qwen3-32B-Q4_K_M.gguf" → 32B).
        val m = TIER.find(if (spec.localPath != null) spec.fileName else spec.id) ?: return ""
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
    /** Downloads several models at once (the one-tap «پیشنهاد برای این دستگاه»). */
    val onDownloadAll: (List<String>) -> Unit = { ids -> ids.forEach(onDownload) },
    /** «سنجش سرعت این دستگاه»; null hides it (no models run here). */
    val onSpeedTest: (() -> Unit)? = null,
    /** The speed test's rows and state. */
    val speed: SpeedUi = SpeedUi(),
    /** «افزودن مدل از روی سیستم» (Windows): a GGUF from the creator's disk becomes a director; null hides it. */
    val onAddOwn: (() -> Unit)? = null,
    /** What the last add said (added, or why not). */
    val ownNote: OwnNote? = null,
)

/** The models screen's line after «افزودن مدل از روی سیستم»: the model added, or why the file was refused. */
data class OwnNote(val added: String? = null, val refusal: io.trimio.engine.models.OwnModels.Refusal? = null)
