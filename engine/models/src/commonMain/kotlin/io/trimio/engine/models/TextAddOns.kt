package io.trimio.engine.models

/**
 * The text add-ons of «تقویت کیفیت», all run by llama.cpp (no new engine): embedders that let the
 * director search the picture catalogue, sounds, examples and the glossary by meaning, rerankers
 * that judge whether a candidate makes a sentence instantly clear, and their multimodal sides.
 * Sizes and hashes are the upstream LFS values, checked by downloading each file (sha256 of the
 * bytes); licences are the model cards'. [ModelSpec.workBytes] is the runtime memory beyond the
 * file on the CPU (context, compute buffers), from the encoder's own settings.
 *
 * The embedder in use is [embedder] (picked by the Persian retrieval check, docs/research/RETRIEVAL_EVAL.md);
 * the others are alternatives a curious user may install. Vector packs are built for [embedder].
 */
object TextAddOns {
    private const val HF = "https://huggingface.co"

    @Suppress("LongParameterList")
    private fun gguf(
        id: String, kind: ModelKind, repo: String, file: String, size: Long, sha: String, license: String, fa: String, en: String,
        addOn: AddOn, work: Long, companions: List<String> = emptyList(), experimental: Boolean = false,
    ) = ModelSpec(
        id = id, kind = kind, titleFa = fa, titleEn = en, fileName = file,
        urls = listOf("${ModelCatalog.CDN}/addons/$file", "$HF/$repo/resolve/main/$file"),
        sizeBytes = size, sha256 = sha, tier = DeviceTier.Standard, license = license, workBytes = work,
        companions = companions, addOn = addOn, experimental = experimental,
    )

    private val LAPTOP = setOf(DeviceClass.Laptop)

    private fun retrieval(tier: AddOnTier, fa: String, en: String, stage: String, devices: Set<DeviceClass> = setOf(DeviceClass.Phone, DeviceClass.Laptop)) =
        AddOn(tier, AddOnGroup.Retrieval, AddOnRuntime.Llama, fa, en, devices, stage)

    // --- Essential -----------------------------------------------------------------------------

    /**
     * EmbeddingGemma 2 (Google, 2026-10), text side, Q8_0: 270M parameters, 768-d (Matryoshka),
     * 100+ languages. Mean pooling; never f16 (NaNs, per its card).
     */
    val embeddingGemma2 = gguf(
        "embeddinggemma-2-q8", ModelKind.Embedding, "ggml-org/embeddinggemma-2-GGUF", "embeddinggemma-2-Q8_0.gguf",
        309_855_456, "2188ac1deca4b77dffefd603c2776a9d76d9d74ec01841392982ebb840b09135", "Apache-2.0",
        "جست‌وجوی معنایی سبک (EmbeddingGemma 2)", "Light meaning search (EmbeddingGemma 2)",
        retrieval(AddOnTier.Essential, "جایگزین سبک‌تر: نصف حجم، در جست‌وجوی فارسی کمی ضعیف‌تر", "Lighter alternative: half the size, a little weaker on Persian search", "plan"),
        work = 160_000_000,
    )

    /** BAAI bge-m3 (XLM-R, MIT), dense head, Q8_0: 568M parameters, 1024-d; strong measured Persian retrieval. */
    val bgeM3 = gguf(
        "bge-m3-q8", ModelKind.Embedding, "ggml-org/bge-m3-Q8_0-GGUF", "bge-m3-q8_0.gguf",
        634_553_760, "aa473d51f451a22f0fcf39ba3330c14bed38a385712b1113440f69df4047a173", "MIT",
        "جست‌وجوی معنایی (bge-m3)", "Meaning search (bge-m3)",
        retrieval(
            AddOnTier.Essential, "برای هر جمله تصویر، صدا و مثالی پیدا می‌کند که معنایش را دارد",
            "Finds the picture, sound and example that mean what each sentence says", "plan",
        ),
        work = 220_000_000,
    )

    /** Qwen3-Reranker-0.6B, Q8_0: P(yes) that a candidate fits a sentence under our own instruction. */
    val reranker06b = gguf(
        "qwen3-reranker-0.6b-q8", ModelKind.AddOn, "ggml-org/Qwen3-Reranker-0.6B-Q8_0-GGUF", "qwen3-reranker-0.6b-q8_0.gguf",
        639_153_184, "22c9979ce4fbcdc5acdc310c6641c32797eff1aa980b8f7a2db8a8ea23429a48", "Apache-2.0",
        "داور ارتباط (Qwen3 Reranker)", "Relevance judge (Qwen3 Reranker)",
        retrieval(
            AddOnTier.Essential, "از بین گزینه‌ها آن را برمی‌دارد که جمله را فوراً روشن کند؛ تصویر بی‌ربط حذف می‌شود",
            "Keeps the candidate that makes the sentence instantly clear; irrelevant pictures are dropped", "plan",
        ),
        work = 300_000_000,
    )

    // --- Pro (laptop) --------------------------------------------------------------------------

    /** Qwen3-Reranker-4B, Q4_K_M (mradermacher's GGUF of the Apache-2.0 weights; tied head, judged by its yes/no logits). */
    val reranker4b = gguf(
        "qwen3-reranker-4b-q4km", ModelKind.AddOn, "mradermacher/Qwen3-Reranker-4B-GGUF", "Qwen3-Reranker-4B.Q4_K_M.gguf",
        2_496_714_144, "143cbc3a4b2ebbd0b4022931c22a257c1c501cf20eeae0e65b1131183506ce65", "Apache-2.0",
        "داور ارتباط بزرگ (4B)", "Large relevance judge (4B)",
        retrieval(AddOnTier.Pro, "داوری دقیق‌تر ارتباط تصویر و جمله، روی لپ‌تاپ", "Sharper picture-to-sentence judging, on a laptop", "plan", LAPTOP),
        work = 400_000_000,
    )

    /** Qwen3-Embedding-4B, Q4_K_M: the best open Apache-2.0 Persian retriever (MIRACL-fa 62.2); 2560-d, last-token pooling. */
    val embedding4b = gguf(
        "qwen3-embedding-4b-q4km", ModelKind.Embedding, "Qwen/Qwen3-Embedding-4B-GGUF", "Qwen3-Embedding-4B-Q4_K_M.gguf",
        2_496_703_776, "2b0cf8f17b4c723c27303015383c27ec4bf2d8314bb677d05e920dd70bb0f16b", "Apache-2.0",
        "جست‌وجوی معنایی بزرگ (4B)", "Large meaning search (4B)",
        retrieval(AddOnTier.Pro, "دقیق‌ترین جست‌وجوی معنایی فارسی، روی لپ‌تاپ", "The most accurate Persian meaning search, on a laptop", "plan", LAPTOP),
        work = 450_000_000,
    )

    // --- Ultra ---------------------------------------------------------------------------------

    /** Qwen3-VL-Reranker-2B, Q4_K_M, with its vision projector: judges a sentence against a candidate's text (and, later, its thumbnail). */
    val vlReranker2b = gguf(
        "qwen3-vl-reranker-2b-q4km", ModelKind.AddOn, "mradermacher/Qwen3-VL-Reranker-2B-GGUF", "Qwen3-VL-Reranker-2B.Q4_K_M.gguf",
        1_107_410_784, "e4c2472ca5a85b815ecbcb1f74acd7e8930951f738c4e9d501153b6b4315d642", "Apache-2.0",
        "داور ارتباط تصویری (VL 2B)", "Visual relevance judge (VL 2B)",
        retrieval(AddOnTier.Ultra, "داور ارتباط چندرسانه‌ای؛ یک مرحله در هر زمان", "Multimodal relevance judge; one stage at a time", "plan"),
        work = 350_000_000, companions = listOf("qwen3-vl-reranker-2b-mmproj"),
    )

    val vlReranker2bEyes = gguf(
        "qwen3-vl-reranker-2b-mmproj", ModelKind.AddOn, "mradermacher/Qwen3-VL-Reranker-2B-GGUF", "Qwen3-VL-Reranker-2B.mmproj-Q8_0.gguf",
        445_053_600, "528a7f4c39851c2fc5bf128d73437c1b73e86fb4df41442781c804806a687980", "Apache-2.0",
        "چشم داور تصویری", "Visual judge's projector",
        retrieval(AddOnTier.Ultra, "", "", "plan").copy(helper = true),
        work = 200_000_000,
    )

    /**
     * The embedder the vector packs are built for: bge-m3, by the measured Persian check
     * (docs/research/RETRIEVAL_EVAL.md: nDCG@10 0.374 against EmbeddingGemma 2's 0.293, hybrid with
     * the director's picture idea; 0.321 against 0.208 on the sentence alone).
     */
    val embedder: ModelSpec get() = bgeM3

    /** The reranker offered first on each device class. */
    fun rerankerFor(device: DeviceMemory): ModelSpec = if (device.desktop && device.ramGb >= LAPTOP_PRO_RAM) reranker4b else reranker06b

    private const val LAPTOP_PRO_RAM = 16

    /** Every text add-on, in the order the tiers screen lists them. */
    val all: List<ModelSpec> = listOf(bgeM3, reranker06b, embeddingGemma2, reranker4b, embedding4b, vlReranker2b, vlReranker2bEyes)
}
