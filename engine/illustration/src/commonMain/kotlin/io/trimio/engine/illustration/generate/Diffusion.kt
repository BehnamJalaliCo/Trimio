package io.trimio.engine.illustration.generate

import io.trimio.engine.illustration.Raster

/**
 * One picture to generate. [init] (with [strength] 0..1) turns it into image-to-image from a sketch
 * template — the library's own composition of the scene — so a generated picture keeps the layout the
 * library would have drawn and only gains detail. [references] are pictures whose style to keep (models
 * with reference editing, e.g. FLUX.2 klein: the film's first illustration keeps the rest in its hand).
 */
data class DiffusionJob(
    val prompt: String,
    val negative: String,
    val width: Int,
    val height: Int,
    val seed: Long,
    val steps: Int,
    val cfg: Float,
    val init: Raster? = null,
    val strength: Float = DEFAULT_STRENGTH,
    val references: List<Raster> = emptyList(),
) {
    companion object {
        const val DEFAULT_STRENGTH = 0.6f
    }
}

/**
 * An on-device image generator (stable-diffusion.cpp over the app's ggml, through JNI, with a
 * downloaded add-on model). The app provides it only when the model is installed and fits the device;
 * the illustration layer works without it.
 */
interface DiffusionEngine {
    /** The add-on model id ([io.trimio.engine.models.ModelSpec.id]). */
    val modelId: String

    /** What the loaded model can do. */
    val recipe: DiffusionRecipe

    /** The picture, or null when generation failed or was cancelled. */
    suspend fun generate(job: DiffusionJob): Raster?
}

/**
 * How a model wants to be driven: its native size, steps and guidance, and whether it takes an init
 * image or style references. One per catalogue entry ([Recipes]).
 */
data class DiffusionRecipe(
    val size: Int,
    val steps: Int,
    val cfg: Float,
    val img2img: Boolean,
    val references: Boolean,
    /** The model's text encoder reads only English (CLIP): Persian words are translated first. */
    val englishOnly: Boolean = true,
)

/** Recipes of the generator add-ons (see IllustrationAddOns in engine/models). */
object Recipes {
    /** SDXS-512 (one-step distilled SD 1.x, tiny U-Net, tiny VAE): fast enough for a phone. */
    val sdxs = DiffusionRecipe(size = 512, steps = 1, cfg = 1f, img2img = true, references = false)

    /** SD 1.5 with the LCM LoRA: four steps, img2img from the sketch template. */
    val sd15Lcm = DiffusionRecipe(size = 512, steps = 4, cfg = 1.5f, img2img = true, references = false)

    /** FLUX.2 [klein] 4B (Apache-2.0): four steps, multi-reference editing keeps a film's style; reads Persian through Qwen3. */
    val klein4b = DiffusionRecipe(size = 768, steps = 4, cfg = 1f, img2img = true, references = true, englishOnly = false)

    fun of(modelId: String): DiffusionRecipe? = when {
        modelId.startsWith("sdxs") -> sdxs
        modelId.startsWith("sd15") -> sd15Lcm
        modelId.startsWith("flux2-klein") -> klein4b
        else -> null
    }
}
