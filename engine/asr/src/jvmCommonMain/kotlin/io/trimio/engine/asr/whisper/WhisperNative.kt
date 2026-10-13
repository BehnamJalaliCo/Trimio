package io.trimio.engine.asr.whisper

import java.io.File

/** Receives decoder output from native code, on the decoding thread. */
interface WhisperCallback {
    fun onProgress(percent: Int)

    /** One decoded segment: per-token UTF-8 bytes, start/end in ms and probability. */
    fun onSegment(texts: Array<ByteArray>, startMs: LongArray, endMs: LongArray, probabilities: FloatArray)

    /** Polled by the decoder; returning true aborts transcription. */
    fun isCancelled(): Boolean
}

/** JNI entry points of `libtrimio_whisper` (native/whisper/trimio_whisper_jni.cpp). */
internal object WhisperNative {
    /** The build that loaded: "vulkan", "cpu", … ("" for the flat directory or the APK). */
    val variant: String = NativeLibraries.load("trimio_whisper")

    external fun init(modelPath: String, useGpu: Boolean, flashAttention: Boolean): Long
    external fun free(handle: Long)
    external fun isMultilingual(handle: Long): Boolean
    external fun transcribe(handle: Long, pcm: FloatArray, language: String, initialPrompt: String?, threads: Int, beamSize: Int, callback: WhisperCallback): Int

    /** How the last model loaded: devices, GPU, flash attention, as key=value lines. */
    external fun runtimeInfo(): String
    external fun detectedLanguage(handle: Long): String
    external fun systemInfo(): String

    const val ABORTED = -100
}

/**
 * Loads bundled native libraries. Android finds them in the APK; desktop and tests point
 * `-Dtrimio.native.dir` at the host build output.
 *
 * The packaged desktop app ships variants side by side (`<dir>/vulkan`, `<dir>/cpu`) and lists
 * them in `-Dtrimio.native.variants`, best first: a GPU build fails to load where its runtime
 * (vulkan-1.dll) is missing, and the next variant is tried. The flat directory comes last.
 */
object NativeLibraries {
    private val loaded = mutableMapOf<String, String>()

    /** Loads [name]; returns the variant that loaded ("" for the flat directory or the system path). */
    @Synchronized
    fun load(name: String): String {
        loaded[name]?.let { return it }
        val dir = System.getProperty("trimio.native.dir")
        val variants = System.getProperty("trimio.native.variants").orEmpty().split(',').map(String::trim).filter(String::isNotEmpty) + ""
        var failure: Throwable? = null
        for (variant in variants) {
            val file = dir?.let { d -> fileNames(name).map { File(File(d, variant), it) }.firstOrNull { it.isFile } } ?: continue
            val ok = runCatching { System.load(file.absolutePath) }.onFailure { failure = it }.isSuccess
            if (ok) return variant.also { loaded[name] = it }
        }
        failure?.takeIf { dir != null }?.let { throw it }
        System.loadLibrary(name)
        return "".also { loaded[name] = it }
    }

    private fun fileNames(name: String) = listOf("lib$name.so", "lib$name.dylib", "$name.dll")
}
