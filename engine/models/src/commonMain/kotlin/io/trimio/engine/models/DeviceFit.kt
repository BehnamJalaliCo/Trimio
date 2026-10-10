package io.trimio.engine.models

import kotlinx.serialization.Serializable

/** How well a model fits a device, as the settings screen badges it. */
@Serializable
enum class ModelFit {
    /** Comfortable: the OS, the keyboard and the camera keep their memory. */
    Recommended,

    /** Runs in the foreground; Android closes background apps to make room. */
    Fits,

    /** Within reach, but the OS may close Trimio mid-run (or the model is not calibrated yet). */
    Experimental,

    /** Cannot be held in memory. */
    TooBig,
    ;

    val runnable: Boolean get() = this != TooBig

    /** Safe to pick without the user asking for it. */
    val comfortable: Boolean get() = this == Recommended || this == Fits
}

/**
 * The device's memory, by its marketed RAM (what `DeviceInfo.ramGb` reports). Phones and desktops
 * differ in policy, not arithmetic: Android's low-memory killer closes even the foreground app when
 * memory runs out, a desktop OS swaps instead.
 */
@Serializable
data class DeviceMemory(val ramGb: Int, val desktop: Boolean = false) {
    val totalBytes: Long get() = ramGb.toLong() * DeviceFit.GIB

    /** The legacy tier for this much RAM (the highest whose minimum it meets). */
    val tier: DeviceTier get() = DeviceTier.entries.lastOrNull { it.minRamGb <= ramGb } ?: DeviceTier.Standard

    companion object {
        fun phone(ramGb: Int) = DeviceMemory(ramGb)
        fun desktop(ramGb: Int) = DeviceMemory(ramGb, desktop = true)
    }
}

/** One model on one device: the badge plus the numbers behind it. */
data class FitReport(
    val spec: ModelSpec,
    val fit: ModelFit,
    /** What the app holds while this model works: weights in use, caches, its eyes, the renderer. */
    val footprintBytes: Long,
    /** The same without loading the eyes (the director runs blind; the vision critic is skipped). */
    val blindFit: ModelFit,
    val blindFootprintBytes: Long,
)

/**
 * Memory budget of an on-device model, the way llama.cpp actually spends it, against what an app
 * may hold on Android.
 *
 * A director at work holds:
 * - its weights, minus [ModelSpec.mappedBytes] (embedding tables read a row at a time stay
 *   memory-mapped). The rest is touched for every token: on ARM most K-quants are repacked into
 *   anonymous memory, and even mmap'd weights that the OS drops are re-read from flash per token;
 * - the KV cache for its [DirectorProfile.contextSize], plus recurrent/sliding-window state;
 * - logits for [LOGIT_ROWS] positions (the native bridge reserves only the last one; a whole
 *   micro-batch would be 0.5 GB with Qwen's 248k vocabulary) and activations;
 * - its eyes (projector weights and encoder activations, or the light looker as a second model),
 *   because the vision critic looks at frames while the director is loaded;
 * - the app itself while rendering those frames ([APP_BYTES]).
 *
 * Rated against the device's RAM: Android keeps 2-4 GB for itself, zRAM and the low-memory killer
 * absorb the rest unevenly, so the bands are fractions of RAM ([PHONE_BANDS]) that match where
 * llama.cpp apps are reported to run fine, get other apps closed, or get closed themselves.
 */
object DeviceFit {
    const val GIB = 1L shl 30

    /** Renderer frames for the critic, video decoding, UI and ART heap. */
    const val APP_BYTES = GIB

    /**
     * Logit rows llama.cpp reserves per micro-batch (n_outputs_max defaults to n_batch = 512). The
     * JNI only ever reads the last token's logits; setting `n_outputs_max = 1` there would save
     * ~0.5 GB per Qwen/Gemma model, and this constant should then become 1.
     */
    const val LOGIT_ROWS = 1L

    /** Activations of one 512-token micro-batch on CPU. */
    const val ACTIVATION_BYTES = 64L shl 20

    /** Dense-model guesses when a server-added spec carries no runtime numbers. */
    private const val UNKNOWN_KV_PER_TOKEN = 65_536L
    private const val UNKNOWN_VOCAB = 262_144L

    /** Percent of RAM for Recommended / Fits / Experimental. */
    val PHONE_BANDS = Triple(50, 65, 80)
    val DESKTOP_BANDS = Triple(60, 75, 90)

    fun of(spec: ModelSpec, device: DeviceMemory, byId: (String) -> ModelSpec? = ModelCatalog::byId): ModelFit =
        report(spec, device, byId).fit

    fun report(spec: ModelSpec, device: DeviceMemory, byId: (String) -> ModelSpec? = ModelCatalog::byId): FitReport {
        val blind = APP_BYTES + alone(spec)
        val full = blind + eyesOf(spec, byId).sumOf { alone(it) }
        fun cap(f: ModelFit) = if (spec.experimental && f < ModelFit.Experimental) ModelFit.Experimental else f
        return FitReport(spec, cap(rate(full, device)), full, cap(rate(blind, device)), blind)
    }

    /** Bytes the app holds while [spec] (and, for a director, its eyes) works. */
    fun footprint(spec: ModelSpec, byId: (String) -> ModelSpec? = ModelCatalog::byId): Long =
        APP_BYTES + alone(spec) + eyesOf(spec, byId).sumOf { alone(it) }

    fun rate(bytes: Long, device: DeviceMemory): ModelFit {
        val (recommended, fits, experimental) = if (device.desktop) DESKTOP_BANDS else PHONE_BANDS
        val percent = bytes * 100 / device.totalBytes.coerceAtLeast(1)
        return when {
            percent <= recommended -> ModelFit.Recommended
            percent <= fits -> ModelFit.Fits
            percent <= experimental -> ModelFit.Experimental
            else -> ModelFit.TooBig
        }
    }

    /** A director's eyes; the light looker counts as a whole second model. */
    private fun eyesOf(spec: ModelSpec, byId: (String) -> ModelSpec?): List<ModelSpec> {
        val companions = spec.companions.mapNotNull(byId)
        if (spec.kind != ModelKind.Language) return companions
        return companions.filter { it.kind == ModelKind.Vision }.ifEmpty { ModelCatalog.visionFor(spec) }
    }

    /** One model's resident bytes on its own. */
    private fun alone(spec: ModelSpec): Long {
        val generates = spec.kind == ModelKind.Language || spec.vocabSize > 0
        if (!generates) return spec.sizeBytes + spec.workBytes
        val context = DirectorProfile.of(spec).contextSize.toLong()
        val kv = (spec.kvBytesPerToken.takeIf { it > 0 } ?: UNKNOWN_KV_PER_TOKEN) * context
        val vocab = spec.vocabSize.takeIf { it > 0 }?.toLong() ?: UNKNOWN_VOCAB
        return spec.sizeBytes - spec.mappedBytes + kv + LOGIT_ROWS * vocab * 4 + ACTIVATION_BYTES + spec.workBytes
    }
}
