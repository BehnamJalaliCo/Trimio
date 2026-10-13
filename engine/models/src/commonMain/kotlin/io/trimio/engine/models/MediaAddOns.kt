package io.trimio.engine.models

/**
 * The audio and vision add-ons of «تقویت کیفیت»: perception networks on ONNX Runtime, a Persian
 * speech upgrade and second opinions. Sizes and hashes were checked by downloading each file
 * (they equal the upstream LFS values); [ModelSpec.workBytes] is the runtime memory beyond the
 * file, measured on a 4-core x86 server CPU with ONNX Runtime 1.29 (phones differ; each add-on
 * runs alone as its own stage).
 *
 * Licences are the model cards' (commercial use allowed); non-commercial, AGPL and unclear ones
 * are excluded (see docs/research/ADDONS.md §8.3).
 */
object MediaAddOns {
    private const val HF = "https://huggingface.co"

    @Suppress("LongParameterList")
    private fun onnx(
        id: String, repo: String, remote: String, local: String, size: Long, sha: String, license: String, fa: String, en: String,
        addOn: AddOn, work: Long, companions: List<String> = emptyList(), languages: Set<io.trimio.core.model.text.Language>? = null,
    ) = ModelSpec(
        id = id, kind = ModelKind.AddOn, titleFa = fa, titleEn = en, fileName = local,
        urls = listOf("${ModelCatalog.CDN}/addons/$local", "$HF/$repo/resolve/main/$remote"),
        sizeBytes = size, sha256 = sha, tier = DeviceTier.Standard, license = license, workBytes = work,
        companions = companions, addOn = addOn,
    ).let { spec -> languages?.let { spec.copy(languages = it) } ?: spec }

    private fun pro(group: AddOnGroup, fa: String, en: String, stage: String, runtime: AddOnRuntime = AddOnRuntime.Onnx) =
        AddOn(AddOnTier.Pro, group, runtime, fa, en, stage = stage)

    private fun ultra(group: AddOnGroup, fa: String, en: String, stage: String, devices: Set<DeviceClass> = BOTH, runtime: AddOnRuntime = AddOnRuntime.Onnx) =
        AddOn(AddOnTier.Ultra, group, runtime, fa, en, devices, stage)

    private fun helper(of: AddOn) = of.copy(helper = true)

    private val BOTH = setOf(DeviceClass.Phone, DeviceClass.Laptop)

    // --- Picture -------------------------------------------------------------------------------

    /** YuNet 2023mar (OpenCV Zoo): faces and landmarks, so text never covers the speaker. */
    val faces = onnx(
        "yunet-2023mar", "opencv/face_detection_yunet", "face_detection_yunet_2023mar.onnx", "face_detection_yunet_2023mar.onnx",
        232_589, "8f2383e4dd3cfbb4553ea8718107fc0423210dc964f9f4280604804ed2552fa4", "MIT",
        "تشخیص چهره", "Face detection",
        pro(AddOnGroup.Picture, "متن و گرافیک هیچ‌وقت روی صورت نمی‌افتد", "Text and graphics never cover a face", "layout"),
        work = 60_000_000,
    )

    /** TransNetV2 (ONNX export of the MIT weights): shot boundaries for cuts and B-roll. */
    val shots = onnx(
        "transnetv2", "elya5/transnetv2", "transnetv2.onnx", "transnetv2.onnx",
        31_250_929, "c4d54a682bace32f25136ef83ca2c9d403e8f8193775efeb995172a0d95a8e0c", "MIT",
        "تشخیص برش نما", "Shot cut detection",
        pro(AddOnGroup.Picture, "مرز نماها برای برش و بی‌رول", "Shot boundaries for cuts and B-roll", "layout"),
        work = 150_000_000,
    )

    /** PP-OCRv5 mobile text detector (PaddlePaddle). */
    val ocrDetector = onnx(
        "ppocrv5-mobile-det", "PaddlePaddle/PP-OCRv5_mobile_det_onnx", "inference.onnx", "ppocrv5_mobile_det.onnx",
        4_826_518, "a431985659dc921974177a95adcfbb90fd9e51989a5e04d70d0b75f597b6e61d", "Apache-2.0",
        "خواندن متن تصویر", "Text in the picture",
        pro(AddOnGroup.Picture, "متن موجود در فیلم و متن خودمان را می‌خواند", "Reads text in the footage and checks our own", "jury"),
        work = 120_000_000, companions = listOf("ppocrv5-arabic-rec", "ppocrv5-arabic-dict"),
    )

    /** PP-OCRv5 mobile recogniser for Arabic-script languages (Persian letters پ چ ژ گ ک ی included). */
    val ocrRecognizer = onnx(
        "ppocrv5-arabic-rec", "PaddlePaddle/arabic_PP-OCRv5_mobile_rec_onnx", "inference.onnx", "arabic_ppocrv5_mobile_rec.onnx",
        7_998_947, "799113ebf267fbe742deb99eb36e8d42c9ddc5291ceacf92add41b4d52a59110", "Apache-2.0",
        "تشخیص حروف فارسی", "Persian letter recognition", helper(ocrDetector.addOn!!), work = 60_000_000,
    )

    /** The recogniser's character table (its inference.yml). */
    val ocrDictionary = onnx(
        "ppocrv5-arabic-dict", "PaddlePaddle/arabic_PP-OCRv5_mobile_rec_onnx", "inference.yml", "arabic_ppocrv5_mobile_rec.yml",
        6_165, "21368419e6c016c31db55d316d59e11c128e1913e6e6fe10287084710043d3a6", "Apache-2.0",
        "جدول حروف", "Character table", helper(ocrDetector.addOn!!), work = 0,
    )

    // --- Music ---------------------------------------------------------------------------------

    /** Beat This! final0 (CPJKU, MIT code and weights; ONNX export with parity ≤ 6e-6): beats and downbeats of imported music. */
    val beats = onnx(
        "beat-this-final0", "aaatmy/beat-this-onnx", "beat-this-final0.onnx", "beat-this-final0.onnx",
        82_098_846, "e5e37b7d1802895e42559c5a1ced7619b1601d257a5c8e167c9d45de37c1b078", "MIT",
        "ضرب و میزان موسیقی", "Music beats and downbeats",
        pro(AddOnGroup.Music, "برش‌ها و حرکت‌ها روی ضرب موسیقی خودتان", "Cuts and motion on the beat of your own music", "music"),
        work = 400_000_000, companions = listOf("beat-this-frontend"),
    )

    /** Its frontend: the reference Hann window and 128 mel filters. */
    val beatsFrontend = onnx(
        "beat-this-frontend", "aaatmy/beat-this-onnx", "frontend.json", "beat-this-frontend.json",
        298_186, "7b2ba71db92b79cb33f45feb21854161de329b86a7e6ea686c3fec51ce85eb9f", "MIT",
        "فیلترهای ضرب", "Beat filters", helper(beats.addOn!!), work = 0,
    )

    // --- Speech --------------------------------------------------------------------------------

    /**
     * Punctuation, true-casing and sentence ends in 47 languages including Persian (6-layer
     * transformer, 64k SentencePiece). On our 26 s benchmark it restored 13 of 15 gold marks.
     */
    val punctuation = onnx(
        "punct-cap-seg-47", "1-800-BAD-CODE/punct_cap_seg_47_language", "punct_cap_seg_47lang.onnx", "punct_cap_seg_47lang.onnx",
        232_986_305, "640d91c06b7cc5b3e065c12a7097188378aad3bc11568ff1d72c4c0a2acb0df4", "Apache-2.0",
        "نقطه‌گذاری فارسی", "Persian punctuation",
        pro(AddOnGroup.Speech, "نقطه، ویرگول و علامت سؤال درست در زیرنویس", "Correct full stops, commas and question marks in captions", "proofread"),
        work = 200_000_000, companions = listOf("punct-cap-seg-47-spm"),
    )

    /** Its SentencePiece vocabulary. */
    val punctuationTokenizer = onnx(
        "punct-cap-seg-47-spm", "1-800-BAD-CODE/punct_cap_seg_47_language", "spe_unigram_64k_lowercase_47lang.model", "pcs47_spe_unigram_64k.model",
        1_186_519, "1bc15b6e5fd80dfac9999582ce3efcad2ac1f7cf4e0e9769b329f5de9ca5af47", "Apache-2.0",
        "واژگان نقطه‌گذاری", "Punctuation vocabulary", helper(punctuation.addOn!!), work = 0,
    )

    /**
     * Omnilingual ASR CTC-300M (Meta, Apache-2.0), dynamic-int8 ONNX: emissions for forced
     * alignment of the proofread words, so each word's time is the audio's, not the recogniser's.
     */
    val aligner = onnx(
        "omniasr-ctc-300m-int8", "OpenVoiceOS/omnilingual-asr-ctc-300m-onnx", "model.int8.onnx", "omniasr_ctc_300m_int8.onnx",
        328_094_283, "38a6cc569d46eb382f27d85b3208b883e834ff281b2057a606ec79e4ae501545", "Apache-2.0",
        "هم‌ترازی دقیق کلمه‌ها", "Exact word timing",
        ultra(AddOnGroup.Speech, "زمان دقیق هر کلمه برای زیرنویس و برش", "Exact word times for captions and cuts", "listen"),
        work = 700_000_000, companions = listOf("omniasr-ctc-300m-tokens"),
    )

    /** Its character vocabulary. */
    val alignerTokens = onnx(
        "omniasr-ctc-300m-tokens", "OpenVoiceOS/omnilingual-asr-ctc-300m-onnx", "tokens.txt", "omniasr_ctc_300m_tokens.txt",
        90_630, "7d99997ef207ff14c2cfe825f2aa037528ea250113cc3c6392bfe49326884ba6", "Apache-2.0",
        "حروف هم‌ترازی", "Alignment characters", helper(aligner.addOn!!), work = 0,
    )

    /** pyannote segmentation-3.0 (ONNX community export): who speaks when, up to three local speakers per 10 s. */
    val speakerTurns = onnx(
        "pyannote-seg-3.0", "onnx-community/pyannote-segmentation-3.0", "onnx/model.onnx", "pyannote_seg3.onnx",
        5_986_908, "057ee564753071c0b09b5b611648b50ac188d50846bff5f01e9f7bbf1591ea25", "MIT",
        "تشخیص گوینده", "Speaker turns",
        ultra(AddOnGroup.Speech, "گفت‌وگو و ویدیوی دونفره", "Dialogue and two-speaker videos", "listen"),
        work = 80_000_000, companions = listOf("wespeaker-campplus-lm"),
    )

    /** WeSpeaker CAM++ (VoxCeleb, large-margin fine-tuned): one 512-d voice print per stretch of speech. */
    val speakerVoices = onnx(
        "wespeaker-campplus-lm", "Wespeaker/wespeaker-voxceleb-campplus-LM", "voxceleb_CAM++_LM.onnx", "wespeaker_campplus_lm.onnx",
        29_292_449, "1068e4ac3a76bb9c769e6816ef30bf89363f6e966f1d938210cb8ed4038f8e93", "Apache-2.0",
        "صدای گوینده‌ها", "Speaker voices", helper(speakerTurns.addOn!!), work = 100_000_000,
    )

    /** Every media add-on, in the order the tiers screen lists them. */
    val all: List<ModelSpec> = listOf(
        faces, shots, ocrDetector, ocrRecognizer, ocrDictionary, beats, beatsFrontend, punctuation, punctuationTokenizer,
        aligner, alignerTokens, speakerTurns, speakerVoices,
    )
}
