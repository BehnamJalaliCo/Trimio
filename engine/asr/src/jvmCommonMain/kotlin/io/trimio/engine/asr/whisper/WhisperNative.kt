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
    init {
        NativeLibraries.load("trimio_whisper")
    }

    external fun init(modelPath: String, useGpu: Boolean, flashAttention: Boolean): Long
    external fun free(handle: Long)
    external fun isMultilingual(handle: Long): Boolean
    external fun transcribe(handle: Long, pcm: FloatArray, language: String, initialPrompt: String?, threads: Int, callback: WhisperCallback): Int
    external fun detectedLanguage(handle: Long): String
    external fun systemInfo(): String

    const val ABORTED = -100
}

/**
 * Loads bundled native libraries. Android finds them in the APK; desktop and tests point
 * `-Dtrimio.native.dir` at the host build output.
 */
object NativeLibraries {
    private val loaded = mutableSetOf<String>()

    @Synchronized
    fun load(name: String) {
        if (name in loaded) return
        val dir = System.getProperty("trimio.native.dir")
        val file = dir?.let { d -> listOf("lib$name.so", "lib$name.dylib", "$name.dll").map { File(d, it) }.firstOrNull { it.isFile } }
        if (file != null) System.load(file.absolutePath) else System.loadLibrary(name)
        loaded += name
    }
}
