package io.trimio.engine.models

/** The create screen's three speeds: how much model the director brings to one edit. */
enum class SpeedMode {
    /** A mixture of experts (about a billion parameters per word): the quickest strong director. */
    Fast,

    /** The 4B: comfortable on every supported phone, the default. */
    Balanced,

    /** The 9B: the best calibrated director, for devices with room for it. */
    Pro,
}

/** Dedicated graphics memory llama.cpp offloads director layers to (a desktop's cards, pooled). */
data class GraphicsMemory(val names: List<String>, val totalBytes: Long) {
    val cards: Int get() = names.size.coerceAtLeast(1)
}

/** The directors a platform runs (the desktop leaves some phone models out); every director by default. */
fun interface DirectorOffer {
    fun directors(catalog: List<ModelSpec>): List<ModelSpec>

    companion object {
        val All = DirectorOffer { catalog -> catalog.filter { it.kind == ModelKind.Language } }
    }
}

/** The machine's dedicated graphics memory, asked once (a helper process on desktop); null when unknown. */
fun interface GraphicsProbe {
    suspend fun memory(): GraphicsMemory?
}

/** One speed on one device. */
data class SpeedOption(
    val mode: SpeedMode,
    /** The installed director this speed runs; null when none is installed for it. */
    val model: ModelSpec?,
    /** How [model] fits this device (null without a model). */
    val fit: ModelFit?,
    /** What to download for this speed when [model] is null; null when this device has nothing for it. */
    val suggested: ModelSpec?,
    /** Share of [model]'s layers the graphics card holds (desktop with a known card); null otherwise. */
    val gpuShare: Float?,
) {
    val available: Boolean get() = model != null
}

/** The three speeds on this device, the one to start on, and what the device has to run them. */
data class SpeedPlan(
    val options: List<SpeedOption>,
    val default: SpeedMode,
    val desktop: Boolean,
    val gpu: GraphicsMemory?,
) {
    fun of(mode: SpeedMode): SpeedOption = options.first { it.mode == mode }

    /**
     * A phone running [mode]'s model with less than a comfortable margin («جا می‌شود» or
     * experimental): Android may close other apps, or Trimio itself mid-run.
     */
    fun tight(mode: SpeedMode): Boolean {
        val o = of(mode)
        return !desktop && o.model != null && o.fit != ModelFit.Recommended
    }

    companion object {
        /** No installed director anywhere (previews, tests). */
        val None = SpeedPlan(SpeedMode.entries.map { SpeedOption(it, null, null, null, null) }, SpeedMode.Balanced, desktop = false, gpu = null)
    }
}

/**
 * Maps «سریع / متعادل / حرفه‌ای» to the best installed directors for this device ([DeviceFit]):
 * Fast is the mixture of experts, Balanced the 4B, Pro the 9B (the 3-bit build when the full one
 * would only just fit). The default is the speed that runs comfortably: Balanced, or the creator's
 * own pick from the models screen when the device holds it with room to spare; on a desktop whose
 * graphics card holds the whole 9B, Pro.
 */
object SpeedModes {
    /** Mixtures of experts this light per word count as the fast director. */
    private const val FAST_ACTIVE_B = 2f

    private val BALANCED = listOf(ModelCatalog.qwen35_4b.id, ModelCatalog.gemma4E4b.id)
    private val PRO = listOf(ModelCatalog.qwen35_9b.id, ModelCatalog.qwen35_9bLight.id)

    /** A card holding this share of the layers runs the model at its speed (the rest is a few layers on the CPU). */
    const val WHOLE_ON_GPU = 0.9f

    /** Per card: compute buffers and what the desktop keeps for itself (as the native fit reserves). */
    private const val CARD_RESERVE = 768L shl 20

    /** Context cache per token the native fit counts for the whole model. */
    private const val CACHE_PER_TOKEN = 160L shl 10

    /** Part of a card's memory that is free while the desktop runs. */
    private const val FREE_SHARE = 0.92

    fun plan(
        directors: List<ModelSpec>,
        installed: (ModelSpec) -> Boolean,
        device: DeviceMemory,
        gpu: GraphicsMemory? = null,
        saved: String? = null,
        byId: (String) -> ModelSpec? = ModelCatalog::byId,
    ): SpeedPlan {
        val language = directors.filter { it.kind == ModelKind.Language && !it.experimental }
        fun fit(spec: ModelSpec) = DeviceFit.of(spec, device) { id -> directors.firstOrNull { it.id == id } ?: byId(id) }
        val runnable = language.filter { fit(it).runnable }
        val fast = runnable.filter { (it.activeParamsB ?: Float.MAX_VALUE) <= FAST_ACTIVE_B }
        val balanced = BALANCED.mapNotNull { id -> runnable.firstOrNull { it.id == id } }
        val pro = PRO.mapNotNull { id -> runnable.firstOrNull { it.id == id } }
        fun option(mode: SpeedMode, candidates: List<ModelSpec>): SpeedOption {
            val model = best(candidates.filter(installed), ::fit)
            val suggested = if (model == null) best(candidates, ::fit)?.takeIf { fit(it).comfortable } else null
            return SpeedOption(mode, model, model?.let(::fit), suggested, if (gpu != null) model?.let { gpuShare(it, gpu) } else null)
        }
        val options = listOf(option(SpeedMode.Fast, fast), option(SpeedMode.Balanced, balanced), option(SpeedMode.Pro, pro))
        return SpeedPlan(options, defaultOf(options, device, gpu, saved), device.desktop, gpu)
    }

    /** The first Recommended in the speed's own order, then the smallest that only fits, then the smallest experimental. */
    private fun best(candidates: List<ModelSpec>, fit: (ModelSpec) -> ModelFit): ModelSpec? =
        candidates.firstOrNull { fit(it) == ModelFit.Recommended }
            ?: candidates.filter { fit(it) == ModelFit.Fits }.minByOrNull { it.sizeBytes }
            ?: candidates.filter { fit(it) == ModelFit.Experimental }.minByOrNull { it.sizeBytes }

    private fun defaultOf(options: List<SpeedOption>, device: DeviceMemory, gpu: GraphicsMemory?, saved: String?): SpeedMode {
        // A phone starts only on a model it holds with room to spare; a desktop on any comfortable one.
        fun safe(o: SpeedOption) = o.fit != null && if (device.desktop) o.fit.comfortable else o.fit == ModelFit.Recommended
        val pick = options.firstOrNull { it.model?.id == saved && safe(it) }
            ?: options.firstOrNull { it.mode == SpeedMode.Pro && gpu != null && safe(it) && (it.gpuShare ?: 0f) >= WHOLE_ON_GPU }
            ?: options.firstOrNull { it.mode == SpeedMode.Balanced && safe(it) }
            ?: options.firstOrNull(::safe)
            ?: options.firstOrNull { it.available }
        return pick?.mode ?: SpeedMode.Balanced
    }

    /**
     * Share of [spec]'s layers that [gpu] holds, the way the native loader decides ("as many as
     * fit"): free card memory less a compute reserve per card, over the weights plus the context
     * cache. The rest runs on the CPU.
     */
    fun gpuShare(spec: ModelSpec, gpu: GraphicsMemory): Float {
        val free = (gpu.totalBytes * FREE_SHARE).toLong() - CARD_RESERVE * gpu.cards
        val need = spec.sizeBytes + DirectorProfile.of(spec).contextSize * CACHE_PER_TOKEN
        if (free <= 0 || need <= 0) return 0f
        return (free.toDouble() / need).coerceIn(0.0, 1.0).toFloat()
    }
}
