package io.trimio.engine.models

import io.trimio.core.model.text.Language
import kotlinx.serialization.Serializable

@Serializable
enum class ModelKind {
    Speech,
    Language,
    Embedding,

    /** Sight: a vision projector for a director that can see, or a small model that only looks. */
    Vision,
}

/**
 * Where a device may run a model, by RAM (high-end only: the app requires 8 GB). A coarse label
 * kept for the server catalogue and old settings screens; [DeviceFit] is the real memory check.
 */
@Serializable
enum class DeviceTier(val minRamGb: Int) {
    Standard(8),
    High(12),
    Ultra(16),

    /** 24 GB phones and desktops. */
    Max(24),
}

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
    /**
     * Files installed with this one: a director's vision projector (the "eyes" that let the same
     * model look at frames), or a light vision model's projector.
     */
    val companions: List<String> = emptyList(),
    /**
     * Language models: bytes of KV cache per context token (f16 K+V of the full-attention layers
     * only: hybrid models keep a fixed-size state in the others). 0 when unknown ([DeviceFit] then
     * assumes a dense model's cost).
     */
    val kvBytesPerToken: Long = 0,
    /** Language models: vocabulary size; llama.cpp reserves logits for a whole micro-batch of it. */
    val vocabSize: Int = 0,
    /**
     * Fixed runtime memory beyond weights, KV cache and logits: recurrent state, sliding-window
     * cache, or a projector's encoder activations for one frame.
     */
    val workBytes: Long = 0,
    /**
     * Weights that are only looked up row by row (untied input embeddings, Gemma's per-layer
     * embeddings): they stay memory-mapped from the file and only the rows of tokens in use take RAM.
     */
    val mappedBytes: Long = 0,
    /** Not yet calibrated in the pipeline: offered to curious users, never rated above [ModelFit.Experimental]. */
    val experimental: Boolean = false,
)

/** Prompt layouts of the model families in the catalogue. */
@Serializable
enum class ChatFormat {
    ChatMl,

    /** Gemma 2/3 (`<start_of_turn>`). */
    Gemma,
    Lfm,

    /** Gemma 4 (`<|turn>`): a new layout that llama.cpp's built-in templates do not recognise. */
    Gemma4,
}

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
            // Self/cross attention caches and compute buffers grow with the model (about 0.45 GB for large).
            workBytes = (size * 2 / 5).coerceAtLeast(WHISPER_MIN_WORK),
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

    private fun vision(
        id: String, repo: String, remote: String, local: String, size: Long, sha: String, tier: DeviceTier, fa: String, en: String,
        license: String, default: Boolean = false, companions: List<String> = emptyList(), format: ChatFormat? = null,
        work: Long = ENCODER_WORK, kv: Long = 0, vocab: Int = 0,
    ) = ModelSpec(
        id = id, kind = ModelKind.Vision, titleFa = fa, titleEn = en, fileName = local,
        urls = listOf("$CDN/vision/$local", "https://huggingface.co/$repo/resolve/main/$remote"),
        sizeBytes = size, sha256 = sha, tier = tier, license = license, isDefault = default, companions = companions, chatFormat = format,
        workBytes = work, kvBytesPerToken = kv, vocabSize = vocab,
    )

    // --- Sight. Qwen3.5 and Gemma 4 are natively multimodal: with their projector the director
    // itself looks at frames (one model, two jobs). Text-only directors get a light looker instead.

    val qwen35_4bEyes = vision(
        "qwen3.5-4b-mmproj", "unsloth/Qwen3.5-4B-GGUF", "mmproj-F16.gguf", "Qwen3.5-4B-mmproj-F16.gguf", 672_423_616,
        "cd88edcf8d031894960bb0c9c5b9b7e1fea6ebee02b9f7ce925a00d12891f864", DeviceTier.Standard,
        "بینایی کارگردان پیش‌فرض", "Default director's vision", "Apache-2.0", default = true,
    )
    val qwen35_9bEyes = vision(
        "qwen3.5-9b-mmproj", "unsloth/Qwen3.5-9B-GGUF", "mmproj-F16.gguf", "Qwen3.5-9B-mmproj-F16.gguf", 918_166_080,
        "f70dc3509053962b0d0d3ee8a7eacebf5d60aa560cad78254ae8698516ae029f", DeviceTier.Ultra,
        "بینایی کارگردان حرفه‌ای", "Pro director's vision", "Apache-2.0",
    )
    val gemma4E4bEyes = vision(
        "gemma-4-e4b-mmproj", "google/gemma-4-E4B-it-qat-q4_0-gguf", "gemma-4-E4B-it-mmproj.gguf", "gemma-4-E4B-it-mmproj.gguf", 991_552_256,
        "7498a37cb619e55f2fcf87eb931f56e99389ed6d432e4c5c66110694c0d65578", DeviceTier.High,
        "بینایی کارگردان خلاق", "Creative director's vision", "Apache-2.0",
        // Gemma's encoder sees ~9 patches per token (280-token budget): larger activations than Qwen's.
        work = 350_000_000,
    )
    val qwen36_35bA3bEyes = vision(
        "qwen3.6-35b-a3b-mmproj", "unsloth/Qwen3.6-35B-A3B-GGUF", "mmproj-F16.gguf", "Qwen3.6-35B-A3B-mmproj-F16.gguf", 899_283_680,
        "8971ee4f331ff0a4c609374f32984b3d4e6dc086c0aa35f1d637fad1829e887f", DeviceTier.Max,
        "بینایی کارگردان آزمایشی", "Experimental director's vision", "Apache-2.0",
    )

    /** A small model that only looks (frames → what is in them), for directors without eyes. */
    val qwen35_08bLooker = vision(
        "qwen3.5-0.8b-looker", "unsloth/Qwen3.5-0.8B-GGUF", "Qwen3.5-0.8B-Q4_K_M.gguf", "Qwen3.5-0.8B-Q4_K_M.gguf", 532_517_120,
        "bd258782e35f7f458f8aced1adc053e6e92e89bc735ba3be89d38a06121dc517", DeviceTier.Standard,
        "بینایی سبک", "Light vision", "Apache-2.0", companions = listOf("qwen3.5-0.8b-mmproj"), format = ChatFormat.ChatMl,
        // A small language model of its own: 6 full-attention layers of KV, 18 layers of recurrent state.
        work = 20_000_000, kv = 12_288, vocab = QWEN_VOCAB,
    )
    val qwen35_08bEyes = vision(
        "qwen3.5-0.8b-mmproj", "unsloth/Qwen3.5-0.8B-GGUF", "mmproj-F16.gguf", "Qwen3.5-0.8B-mmproj-F16.gguf", 204_987_232,
        "56e4c6cfe73b0c82e3e82bc518d7591997e61d81f723fc41a586f4fa69ea2453", DeviceTier.Standard,
        "چشم بینایی سبک", "Light vision projector", "Apache-2.0", work = 150_000_000,
    )

    /** How a model spends memory beyond its weights (numbers from the GGUF headers and HF configs). */
    private class RuntimeCost(val kvPerToken: Long, val vocab: Int, val work: Long, val mapped: Long = 0)

    private fun llm(
        id: String, repo: String, file: String, size: Long, sha: String, tier: DeviceTier, fa: String, en: String,
        license: String, format: ChatFormat, runtime: RuntimeCost, activeB: Float? = null, default: Boolean = false, eyes: String? = null,
        experimental: Boolean = false,
    ) = ModelSpec(
        id = id, kind = ModelKind.Language, titleFa = fa, titleEn = en, fileName = file,
        urls = listOf("$CDN/llm/$file", "https://huggingface.co/$repo/resolve/main/$file"),
        sizeBytes = size, sha256 = sha, tier = tier, license = license, isDefault = default,
        chatFormat = format, activeParamsB = activeB, companions = listOfNotNull(eyes),
        kvBytesPerToken = runtime.kvPerToken, vocabSize = runtime.vocab, workBytes = runtime.work, mappedBytes = runtime.mapped,
        experimental = experimental,
    )

    // Qwen3.5/3.6 are hybrid: one layer in four is full attention (KV cache), the rest Gated DeltaNet
    // with a fixed ~2.2 MB state each. Gemma 4 E4B keeps KV for 24 of 42 layers (the rest share it),
    // 20 of them over a 512-token sliding window. LFM2.5 has 6 attention layers among 18 convolutions.
    private val qwen35Small = RuntimeCost(kvPerToken = 32_768, vocab = QWEN_VOCAB, work = 24 * QWEN_LINEAR_STATE)
    private val qwen35Nine = RuntimeCost(kvPerToken = 32_768, vocab = QWEN_VOCAB, work = 24 * QWEN_LINEAR_STATE, mapped = 572_129_280)

    /** Default director: 4B dense, 2.7 GB, strong Persian and reliable JSON. */
    val qwen35_4b = llm(
        "qwen3.5-4b-q4km", "unsloth/Qwen3.5-4B-GGUF", "Qwen3.5-4B-Q4_K_M.gguf", 2_740_937_888,
        "00fe7986ff5f6b463e62455821146049db6f9313603938a70800d1fb69ef11a4", DeviceTier.Standard,
        "کارگردان پیش\u200Cفرض (۴ میلیارد)", "Default director (4B)", "Apache-2.0", ChatFormat.ChatMl, qwen35Small, default = true, eyes = "qwen3.5-4b-mmproj",
    )

    /** Google's quantisation-aware 4-bit build; best multilingual phrasing in its class. */
    val gemma4E4b = llm(
        "gemma-4-e4b-qat-q4", "google/gemma-4-E4B-it-qat-q4_0-gguf", "gemma-4-E4B_q4_0-it.gguf", 5_154_941_280,
        "676c35070db6dbe52f93e9c864ee0fba4eddea94b9c875d9cb10daff453fbaee", DeviceTier.High,
        "کارگردان خلاق (Gemma 4)", "Creative director (Gemma 4)", "Apache-2.0", ChatFormat.Gemma4,
        RuntimeCost(kvPerToken = 16_384, vocab = 262_144, work = 41_943_040, mapped = 2_312_110_080), eyes = "gemma-4-e4b-mmproj",
    )

    /** Mixture-of-Experts: 8B knowledge at ~1B-per-token speed, the fastest strong option on phones. */
    val lfm25_8bA1b = llm(
        "lfm2.5-8b-a1b-q4km", "LiquidAI/LFM2.5-8B-A1B-GGUF", "LFM2.5-8B-A1B-Q4_K_M.gguf", 5_155_564_768,
        "4923ec14f06b968b74d663e5949867d2d9c3bf13a20b8be1a9f9af39989b2bb0", DeviceTier.High,
        "کارگردان سریع MoE (۸ میلیارد)", "Fast MoE director (8B-A1B)", "LFM-Open-1.0", ChatFormat.Lfm,
        RuntimeCost(kvPerToken = 12_288, vocab = 128_000, work = 1_000_000), activeB = 1.5f,
    )

    /** Largest on-device director, for 16 GB phones. */
    val qwen35_9b = llm(
        "qwen3.5-9b-q4km", "unsloth/Qwen3.5-9B-GGUF", "Qwen3.5-9B-Q4_K_M.gguf", 5_680_522_464,
        "03b74727a860a56338e042c4420bb3f04b2fec5734175f4cb9fa853daf52b7e8", DeviceTier.Ultra,
        "کارگردان حرفه\u200Cای (۹ میلیارد)", "Pro director (9B)", "Apache-2.0", ChatFormat.ChatMl, qwen35Nine, eyes = "qwen3.5-9b-mmproj",
    )

    /**
     * The 9B for 12 GB phones: Unsloth's dynamic 3-bit (sensitive tensors kept at 4-6 bits), 0.6 GB
     * lighter than Q4_K_M, so the director, its eyes and the renderer leave Android room to breathe.
     */
    val qwen35_9bLight = llm(
        "qwen3.5-9b-ud-q3kxl", "unsloth/Qwen3.5-9B-GGUF", "Qwen3.5-9B-UD-Q3_K_XL.gguf", 5_053_834_464,
        "aae0879e1be99ce93f0d56217f8185a399e25ad68a8ebbc095f362706283062f", DeviceTier.High,
        "کارگردان حرفه\u200Cای سبک (۹ میلیارد)", "Pro director, light (9B)", "Apache-2.0", ChatFormat.ChatMl, qwen35Nine, eyes = "qwen3.5-9b-mmproj",
    )

    /**
     * Qwen3.6 35B-A3B (llama.cpp arch `qwen35moe`): 256 experts, 8 active (3B per token), so it runs
     * at roughly a 3B model's speed but needs all 12 GB of 2-bit weights resident. 24 GB phones and
     * desktops only, and experimental until the calibration harness has scored it.
     */
    val qwen36_35bA3b = llm(
        "qwen3.6-35b-a3b-ud-q2kxl", "unsloth/Qwen3.6-35B-A3B-GGUF", "Qwen3.6-35B-A3B-UD-Q2_K_XL.gguf", 12_290_628_576,
        "96b9c0af5c77a4ecaabe3983175112b5ece763261c1ece12b2494b692a70dad7", DeviceTier.Max,
        "کارگردان آزمایشی (Qwen3.6 MoE)", "Experimental director (Qwen3.6 35B-A3B)", "Apache-2.0", ChatFormat.ChatMl,
        RuntimeCost(kvPerToken = 20_480, vocab = QWEN_VOCAB, work = 30 * QWEN_LINEAR_STATE, mapped = 349_634_560),
        activeB = 3f, eyes = "qwen3.6-35b-a3b-mmproj", experimental = true,
    )

    val all: List<ModelSpec> = listOf(
        whisperBaseQ8, whisperSmallQ8, whisperTurboQ5, whisperTurboQ8, whisperLargeV3Q5,
        qwen35_4b, gemma4E4b, lfm25_8bA1b, qwen35_9bLight, qwen35_9b, qwen36_35bA3b,
        qwen35_4bEyes, qwen35_9bEyes, gemma4E4bEyes, qwen36_35bA3bEyes, qwen35_08bLooker, qwen35_08bEyes,
    )

    /**
     * What a [director] needs to see: its own projector when it is multimodal (one model, two
     * jobs), otherwise the light looker and its projector, installed separately.
     */
    fun visionFor(director: ModelSpec): List<ModelSpec> {
        val eyes = director.companions.mapNotNull(::byId).filter { it.kind == ModelKind.Vision }
        return eyes.ifEmpty { listOf(qwen35_08bLooker, qwen35_08bEyes) }
    }

    fun byId(id: String): ModelSpec? = all.firstOrNull { it.id == id }

    /** Best model of a kind the device's tier allows (experimental models are never picked for the user). */
    fun bestFor(kind: ModelKind, ramGb: Int): ModelSpec? =
        all.filter { it.kind == kind && it.tier.minRamGb <= ramGb && !it.experimental }.maxByOrNull { it.sizeBytes }

    fun defaults(): List<ModelSpec> = all.filter { it.isDefault }

    /** Every model of [kind] with how well it fits [device], for the settings list and its badges. */
    fun fitsOn(device: DeviceMemory, kind: ModelKind = ModelKind.Language, catalog: List<ModelSpec> = all): List<FitReport> =
        catalog.filter { it.kind == kind }.map { DeviceFit.report(it, device) }

    private const val QWEN_VOCAB = 248_320

    /** One Gated DeltaNet layer's state: 32 value heads of 128×128 f32, plus the short convolution's. */
    private const val QWEN_LINEAR_STATE = 2_200_000L

    /** A vision projector's activations while it encodes one frame (≈256 tokens). */
    private const val ENCODER_WORK = 200_000_000L
    private const val WHISPER_MIN_WORK = 100_000_000L
}
