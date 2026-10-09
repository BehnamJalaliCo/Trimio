package io.trimio.engine.models

import io.trimio.core.model.text.Language
import kotlinx.serialization.Serializable

@Serializable
enum class ModelKind { Speech, Language, Embedding }

/** Where a device may run a model, by RAM (high-end only: the app requires 8 GB). */
@Serializable
enum class DeviceTier(val minRamGb: Int) { Standard(8), High(12), Ultra(16) }

/**
 * A downloadable model. [urls] are tried in order: our own CDN mirror first (fast inside Iran,
 * required for Cafe Bazaar users where Hugging Face is slow or blocked), then the upstream source.
 */
@Serializable
data class ModelSpec(
    val id: String,
    val kind: ModelKind,
    val titleFa: String,
    val titleEn: String,
    val fileName: String,
    val urls: List<String>,
    val sizeBytes: Long,
    val sha256: String,
    val tier: DeviceTier,
    val languages: Set<Language> = setOf(Language.Persian, Language.English),
    val license: String,
    /** Shipped/downloaded on first launch; the app works offline with only default models. */
    val isDefault: Boolean = false,
)

/**
 * Built-in catalogue. Hashes and sizes are the upstream LFS values, so a corrupted or tampered
 * download is rejected before it is ever loaded into native code. The server catalogue (phase 10)
 * can add models without an app update; these are the offline fallback.
 */
object ModelCatalog {
    const val CDN = "https://cdn.trimio.app/models"
    private const val HF_WHISPER = "https://huggingface.co/ggerganov/whisper.cpp/resolve/main"

    private fun whisper(id: String, file: String, size: Long, sha: String, tier: DeviceTier, fa: String, en: String, default: Boolean = false) =
        ModelSpec(
            id = id, kind = ModelKind.Speech, titleFa = fa, titleEn = en, fileName = file,
            urls = listOf("$CDN/whisper/$file", "$HF_WHISPER/$file"),
            sizeBytes = size, sha256 = sha, tier = tier, license = "MIT", isDefault = default,
        )

    val whisperBaseQ8 = whisper(
        "whisper-base-q8", "ggml-base-q8_0.bin", 81_768_585,
        "c577b9a86e7e048a0b7eada054f4dd79a56bbfa911fbdacf900ac5b567cbb7d9", DeviceTier.Standard,
        "گفتار سریع (پایه)", "Fast speech (base)",
    )
    val whisperSmallQ8 = whisper(
        "whisper-small-q8", "ggml-small-q8_0.bin", 264_464_607,
        "49c8fb02b65e6049d5fa6c04f81f53b867b5ec9540406812c643f177317f779f", DeviceTier.Standard,
        "گفتار متعادل", "Balanced speech", default = true,
    )
    val whisperTurboQ5 = whisper(
        "whisper-large-v3-turbo-q5", "ggml-large-v3-turbo-q5_0.bin", 574_041_195,
        "394221709cd5ad1f40c46e6031ca61bce88931e6e088c188294c6d5a55ffa7e2", DeviceTier.High,
        "گفتار دقیق (Turbo)", "Accurate speech (Turbo)",
    )
    val whisperTurboQ8 = whisper(
        "whisper-large-v3-turbo-q8", "ggml-large-v3-turbo-q8_0.bin", 874_188_075,
        "317eb69c11673c9de1e1f0d459b253999804ec71ac4c23c17ecf5fbe24e259a1", DeviceTier.Ultra,
        "گفتار بسیار دقیق", "Most accurate speech",
    )

    val all: List<ModelSpec> = listOf(whisperBaseQ8, whisperSmallQ8, whisperTurboQ5, whisperTurboQ8)

    fun byId(id: String): ModelSpec? = all.firstOrNull { it.id == id }

    /** Best model of a kind the device can hold. */
    fun bestFor(kind: ModelKind, ramGb: Int): ModelSpec? =
        all.filter { it.kind == kind && it.tier.minRamGb <= ramGb }.maxByOrNull { it.sizeBytes }

    fun defaults(): List<ModelSpec> = all.filter { it.isDefault }
}
