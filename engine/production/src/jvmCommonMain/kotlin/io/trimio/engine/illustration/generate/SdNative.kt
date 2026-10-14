package io.trimio.engine.illustration.generate

import io.trimio.engine.asr.whisper.NativeLibraries

/** stable-diffusion.cpp through JNI (native/sd/trimio_sd_jni.cpp), built alone on its own pinned ggml. */
internal object SdNative {
    /** The build that loaded ("cpu" on the desktop, "" on Android); throws when the library is missing. */
    val variant: String by lazy { NativeLibraries.load("trimio_sd") }

    /** Whether the engine can load here at all (a build without it: false, and the generator stays off). */
    val available: Boolean by lazy { runCatching { variant }.isSuccess }

    /** A one-file model (SDXS-512). */
    external fun init(modelPath: String, threads: Int): Long

    /** A model in parts (FLUX.2 klein): diffusion model, LLM text encoder, VAE ("" for the one inside the model). */
    external fun initParts(diffusionPath: String, textEncoderPath: String, vaePath: String, threads: Int): Long

    /**
     * width × height × 3 RGB bytes, or null when generation failed. [init] (RGB of the same size) makes it image-to-image
     * at [strength]; [references] (RGB of the same size) are style references for models that edit.
     */
    external fun generate(
        handle: Long, prompt: String, negative: String, width: Int, height: Int, seed: Long, steps: Int, cfg: Float,
        init: ByteArray?, strength: Float, references: Array<ByteArray>?, progress: StepListener?,
    ): ByteArray?

    /** Told each denoising step of the generation in progress (called on the generating thread). */
    fun interface StepListener {
        fun onStep(step: Int, steps: Int)
    }

    external fun free(handle: Long)
}
