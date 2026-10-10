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
    /** Language models only: prompt format used when the GGUF carries no template llama.cpp knows. */
    val chatFormat: ChatFormat? = null,
    /** Language models only: Mixture-of-Experts with this many active parameters per token, in billions. */
    val activeParamsB: Float? = null,
    /**
     * Release of this model id. The server catalogue raises it when a better build ships (new
     * weights, quantisation or fine-tune); installed copies with a lower version are offered an
     * update, independently of app updates.
     */
    val version: Int = 1,
    /** What changed in this [version], shown with the update. */
    val notesFa: String? = null,
    val notesEn: String? = null,
)

/** Prompt layouts of the model families in the catalogue. */
@Serializable
enum class ChatFormat { ChatMl, Gemma, Lfm }

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

    /** Full large-v3 (not turbo): all 32 decoder layers; clearly better Persian spelling and punctuation. */
    val whisperLargeV3Q5 = whisper(
        "whisper-large-v3-q5", "ggml-large-v3-q5_0.bin", 1_081_140_203,
        "d75795ecff3f83b5faa89d1900604ad8c780abd5739fae406de19f23ecd98ad1", DeviceTier.Ultra,
        "گفتار دقیق‌ترین (فارسی)", "Most accurate speech (Persian)",
    )

    private fun llm(
        id: String, repo: String, file: String, size: Long, sha: String, tier: DeviceTier, fa: String, en: String,
        license: String, format: ChatFormat, activeB: Float? = null, default: Boolean = false,
    ) = ModelSpec(
        id = id, kind = ModelKind.Language, titleFa = fa, titleEn = en, fileName = file,
        urls = listOf("$CDN/llm/$file", "https://huggingface.co/$repo/resolve/main/$file"),
        sizeBytes = size, sha256 = sha, tier = tier, license = license, isDefault = default,
        chatFormat = format, activeParamsB = activeB,
    )

    /** Default director: 4B dense, 2.7 GB, strong Persian and reliable JSON. */
    val qwen35_4b = llm(
        "qwen3.5-4b-q4km", "unsloth/Qwen3.5-4B-GGUF", "Qwen3.5-4B-Q4_K_M.gguf", 2_740_937_888,
        "00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4", DeviceTier.Standard,
        "کارگردان پیش\u200Cفرض (۴ میلیارد)", "Default director (4B)", "Apache-2.0", ChatFormat.ChatMl, default = true,
    )

    /** Google's quantisation-aware 4-bit build; best multilingual phrasing in its class. */
    val gemma4E4b = llm(
        "gemma-4-e4b-qat-q4", "google/gemma-4-E4B-it-qat-q4_0-gguf", "gemma-4-E4B_q4_0-it.gguf", 5_154_941_280,
        "676c35070db6dbe52f93e9c864ee0fba4eddea94b9c875d9cb10daff453fbaee", DeviceTier.High,
        "کارگردان خلاق (Gemma 4)", "Creative director (Gemma 4)", "Apache-2.0", ChatFormat.Gemma,
    )

    /** Mixture-of-Experts: 8B knowledge at ~1B-per-token speed, the fastest strong option on phones. */
    val lfm25_8bA1b = llm(
        "lfm2.5-8b-a1b-q4km", "LiquidAI/LFM2.5-8B-A1B-GGUF", "LFM2.5-8B-A1B-Q4_K_M.gguf", 5_155_564_768,
        "4923ec14f06b968b74d663e5949867d2d9c3bf13a20b8be1a9f9af39989b2bb0", DeviceTier.High,
        "کارگردان سریع MoE (۸ میلیارد)", "Fast MoE director (8B-A1B)", "LFM-Open-1.0", ChatFormat.Lfm, activeB = 1.5f,
    )

    /** Largest on-device director, for 16 GB phones. */
    val qwen35_9b = llm(
        "qwen3.5-9b-q4km", "unsloth/Qwen3.5-9B-GGUF", "Qwen3.5-9B-Q4_K_M.gguf", 5_680_522_464,
        "03b74727a860a56338e042c4420bb3f04b2fec5734175f4cb9fa853daf52b7e8", DeviceTier.Ultra,
        "کارگردان حرفه\u200Cای (۹ میلیارد)", "Pro director (9B)", "Apache-2.0", ChatFormat.ChatMl,
    )

    val all: List<ModelSpec> = listOf(
        whisperBaseQ8, whisperSmallQ8, whisperTurboQ5, whisperTurboQ8, whisperLargeV3Q5,
        qwen35_4b, gemma4E4b, lfm25_8bA1b, qwen35_9b,
    )

    fun byId(id: String): ModelSpec? = all.firstOrNull { it.id == id }

    /** Best model of a kind the device can hold. */
    fun bestFor(kind: ModelKind, ramGb: Int): ModelSpec? =
        all.filter { it.kind == kind && it.tier.minRamGb <= ramGb }.maxByOrNull { it.sizeBytes }

    fun defaults(): List<ModelSpec> = all.filter { it.isDefault }
}
