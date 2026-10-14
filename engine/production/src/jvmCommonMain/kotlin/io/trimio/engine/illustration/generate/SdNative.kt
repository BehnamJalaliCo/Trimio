package io.trimio.engine.illustration.generate

import io.trimio.engine.asr.whisper.NativeLibraries

/** stable-diffusion.cpp through JNI (native/sd/trimio_sd_jni.cpp), on the app's own ggml. */
internal object SdNative {
    /** The build that loaded ("cpu" on the desktop, "" on Android); throws when the library is missing. */
    val variant: String by lazy { NativeLibraries.load("trimio_sd") }

    /** Whether the engine can load here at all (a build without it: false, and the generator stays off). */
    val available: Boolean by lazy { runCatching { variant }.isSuccess }

    external fun init(modelPath: String, threads: Int): Long

    /** width × height × 3 RGB bytes, or null when generation failed. */
    external fun generate(handle: Long, prompt: String, negative: String, width: Int, height: Int, seed: Long, steps: Int, cfg: Float): ByteArray?

    external fun free(handle: Long)
}
