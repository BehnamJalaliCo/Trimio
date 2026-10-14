package io.trimio.engine.illustration.generate

import io.trimio.engine.asr.whisper.NativeLibraries

/** stable-diffusion.cpp through JNI (native/sd/trimio_sd_jni.cpp), built alone on its own pinned ggml. */
internal object SdNative {
    /**
     * Ask for the GPU build before the first call (Android: libtrimio_sd_gpu, OpenCL for Adreno, chosen by the speed
     * test); the processor build loads when it is not asked for or does not load (no OpenCL driver). One per process:
     * both export the same entry points.
     */
    @Volatile
    var wantGpu: Boolean = false

    /** The build that loaded ("opencl" the phone's GPU build, "cpu"/"vulkan" on the desktop, "" on Android); throws when missing. */
    val variant: String by lazy {
        if (wantGpu && runCatching { NativeLibraries.load(GPU_LIBRARY) }.isSuccess) return@lazy OPENCL
        NativeLibraries.load("trimio_sd")
    }

    /** Whether the engine can load here at all (a build without it: false, and the generator stays off). */
    val available: Boolean by lazy { runCatching { variant }.isSuccess }

    /**
     * A one-file model (SDXS-512). [backend]: where each part runs ("" the default device, "cpu", or sd.cpp's assignments
     * such as "all=GPUOpenCL,te=cpu"); [maxVram]: the GPU budget in GiB ("" the live free memory decides).
     */
    external fun init(modelPath: String, threads: Int, backend: String, maxVram: String): Long

    /** A model in parts (FLUX.2 klein): diffusion model, LLM text encoder, VAE ("" for the one inside the model). */
    external fun initParts(diffusionPath: String, textEncoderPath: String, vaePath: String, threads: Int, backend: String, maxVram: String): Long

    /** The devices this build runs on, one "name<TAB>description" per line. */
    external fun devices(): String

    const val OPENCL = "opencl"
    private const val GPU_LIBRARY = "trimio_sd_gpu"

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
