package io.trimio.engine.models

/**
 * The picture generator add-ons of «تقویت کیفیت»: on-device text-to-image for the explainer films'
 * hero illustrations, run by stable-diffusion.cpp on its own pinned ggml (no cloud, no new runtime
 * beyond its JNI library). The bundled illustration library needs none of them; a generator only draws
 * what the library cannot show (FLUX.2 klein from the library's own sketch of the scene; SDXS from the prompt
 * alone, as it ignores an init picture), and every picture is restyled into the film's look (engine/illustration).
 *
 * Licences are the model cards' and permit commercial use: Apache-2.0 (FLUX.2 [klein] 4B, Qwen3-4B,
 * the FLUX.2 small decoder), MIT (TCD LoRA, TAESD), CreativeML OpenRAIL-M / OpenRAIL++-M (SD 1.5,
 * SDXS-512: commercial use allowed; the use-based restrictions pass on to users in the app's terms).
 * Excluded: SD-Turbo, SDXL-Turbo, FLUX.2 [klein] 9B, FLUX.1-dev (non-commercial). Sizes and sha256 are
 * the upstream LFS values read from the Hugging Face API; [ModelSpec.workBytes] are estimates from the
 * models' latent sizes and stable-diffusion.cpp's buffers (docs/research/ILLUSTRATION.md §3); SDXS-512 was
 * downloaded, re-hashed and run here (peak 1.18 GB on x86 CPU), the others not yet; none on the target devices.
 */
object IllustrationAddOns {
    private const val HF = "https://huggingface.co"
    private const val MB = 1_000_000L

    @Suppress("LongParameterList")
    private fun file(
        id: String, repo: String, file: String, size: Long, sha: String, license: String, fa: String, en: String,
        addOn: AddOn, work: Long, companions: List<String> = emptyList(), experimental: Boolean = false,
    ) = ModelSpec(
        id = id, kind = ModelKind.AddOn, titleFa = fa, titleEn = en, fileName = file,
        urls = listOf("${ModelCatalog.CDN}/addons/$file", "$HF/$repo/resolve/main/$file"),
        sizeBytes = size, sha256 = sha, tier = DeviceTier.Standard, license = license, workBytes = work,
        companions = companions, addOn = addOn, experimental = experimental,
    )

    private fun picture(tier: AddOnTier, fa: String, en: String, devices: Set<DeviceClass>) =
        AddOn(tier, AddOnGroup.Picture, AddOnRuntime.Diffusion, fa, en, devices, stage = "illustrate")

    private val BOTH = setOf(DeviceClass.Phone, DeviceClass.Laptop)

    /** SDXS-512 (one step, tiny U-Net and tiny VAE): text-to-image fast enough for a phone, in the pencil look from the prompt. */
    val sdxs = file(
        "sdxs-512", "concedo/sdxs-512-tinySDdistilled-GGUF", "sdxs-512-tinySDdistilled_Q8_0.gguf",
        682_847_200, "409ab23582ee074c6b9d5395784fc0741b0599fb9d138686c69087c71678eb6a", "OpenRAIL++-M",
        "تصویرساز سریع", "Fast illustrator",
        picture(AddOnTier.Pro, "تصویر هر صحنه را روی گوشی در چند ثانیه می‌کشد", "Draws each scene's picture on the phone in seconds", BOTH),
        work = 600 * MB,
    )

    /** FLUX.2 [klein] 4B (Apache-2.0): four steps, reads Persian through Qwen3, keeps a film's style from a reference picture. */
    val klein = file(
        "flux2-klein-4b", "leejet/FLUX.2-klein-4B-GGUF", "flux-2-klein-4b-Q4_0.gguf",
        2_460_378_560, "d1023499ef3f2f82ff7c50e6778495195c1b6cc34835741778868428111f9ff4", "Apache-2.0",
        "تصویرساز حرفه‌ای", "Studio illustrator",
        // Phones too: its weights are memory-mapped (measured: 4.3 of 6.8 GB resident were the file's pages), so a 12 GB
        // phone holds it with its encoder and decoder (about 8 GB, the director freed first), a 16 GB one with room.
        picture(AddOnTier.Ultra, "تصویرهای دقیق و حرفه‌ای با یک سبک ثابت در کل فیلم", "Detailed, professional pictures in one steady style across the film", BOTH),
        work = 1_200 * MB, companions = listOf("flux2-klein-qwen3-4b", "flux2-small-decoder"),
    )

    /** The text encoder of [klein] (Qwen3-4B, Apache-2.0). */
    val kleinText = file(
        "flux2-klein-qwen3-4b", "unsloth/Qwen3-4B-GGUF", "Qwen3-4B-Q4_K_M.gguf",
        2_497_281_312, "f6f851777709861056efcdad3af01da38b31223a3ba26e61a4f8bf3a2195813a", "Apache-2.0",
        "فهم متن تصویرساز", "Illustrator text encoder", klein.addOn!!.copy(helper = true), work = 300 * MB,
    )

    /** The VAE of [klein] (FLUX.2 small decoder with the full encoder, for image-to-image; Apache-2.0). */
    val kleinDecoder = file(
        "flux2-small-decoder", "black-forest-labs/FLUX.2-small-decoder", "full_encoder_small_decoder.safetensors",
        249_519_092, "ea4273f02d1fafbf8e1d1c2cf6018ed8748652eb0bf34f2dd91171f16f15ab62", "Apache-2.0",
        "رمزگشای تصویر", "Picture decoder", klein.addOn!!.copy(helper = true), work = 400 * MB,
    )

    val all: List<ModelSpec> = listOf(sdxs, klein, kleinText, kleinDecoder)
}
