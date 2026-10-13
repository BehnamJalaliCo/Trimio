package io.trimio.engine.models

import kotlinx.serialization.Serializable

/** The quality tiers of «تقویت کیفیت»: what to download beyond the director and speech model. */
@Serializable
enum class AddOnTier(val titleFa: String, val titleEn: String) {
    /** Small, broad gains; no extra engine needed beyond what the app ships. */
    Essential("ضروری", "Essential"),

    /** Perception networks on ONNX Runtime and a Persian speech upgrade. */
    Pro("حرفه‌ای", "Pro"),

    /** Big second opinions and exact alignment; best on a laptop, one stage at a time on a phone. */
    Ultra("فوق حرفه‌ای", "Ultra"),
}

/** What an add-on improves, for grouping rows on the tiers screen. */
@Serializable
enum class AddOnGroup(val titleFa: String, val titleEn: String) {
    Retrieval("جست‌وجو و معنا", "Search and meaning"),
    Speech("گفتار و زمان‌بندی", "Speech and timing"),
    Picture("تصویر و چیدمان", "Picture and layout"),
    Music("موسیقی و ریتم", "Music and rhythm"),
    Judge("داوری", "Judging"),
    Director("کارگردان", "Director"),
}

/** The engine that runs an add-on. */
@Serializable
enum class AddOnRuntime { Llama, Whisper, Onnx, Kotlin }

/** Device families an add-on is offered on. */
@Serializable
enum class DeviceClass { Phone, Laptop }

/**
 * Tier and group of an add-on model, for the «تقویت کیفیت» screen and the device-fit rule.
 * Add-ons run as pipeline stages one at a time (never all resident): [ModelSpec.workBytes] holds the
 * runtime memory beyond the file, measured, so [DeviceFit] rates each add-on on its own.
 */
@Serializable
data class AddOn(
    val tier: AddOnTier,
    val group: AddOnGroup,
    val runtime: AddOnRuntime,
    /** What it does, one line each language, for the row's subtitle. */
    val purposeFa: String,
    val purposeEn: String,
    val devices: Set<DeviceClass> = setOf(DeviceClass.Phone, DeviceClass.Laptop),
    /** Pipeline stage that loads it ("listen", "proofread", "layout", "music", "jury"), for the scheduler. */
    val stage: String = "",
    /** Files installed with it that are not models themselves (a tokenizer, a dictionary) are its companions. */
    val helper: Boolean = false,
)

/** The device-fit rule for add-ons: offered on this device class at all, then rated alone by memory. */
object AddOnFit {
    fun of(spec: ModelSpec, device: DeviceMemory, byId: (String) -> ModelSpec? = ModelCatalog::byId): ModelFit {
        val addOn = spec.addOn ?: return DeviceFit.of(spec, device, byId)
        val family = if (device.desktop) DeviceClass.Laptop else DeviceClass.Phone
        if (family !in addOn.devices) return ModelFit.TooBig
        return DeviceFit.of(spec, device, byId)
    }

    /** Every add-on of [tier] offered on [device], helpers (tokenizers, dictionaries) left out. */
    fun offered(tier: AddOnTier, device: DeviceMemory, catalog: List<ModelSpec> = ModelCatalog.all): List<ModelSpec> =
        catalog.filter { spec -> spec.addOn?.let { it.tier == tier && !it.helper } == true && of(spec, device).runnable }
}
