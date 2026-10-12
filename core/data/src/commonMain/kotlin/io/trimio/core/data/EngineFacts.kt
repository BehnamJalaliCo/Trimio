package io.trimio.core.data

/**
 * The engines as the latest build on this device measured them: the native build ([backend]:
 * "vulkan", "opencl", "cpu"…), the GPU and how many of the model's layers it took, the CPU
 * threads, and each model's generation speed so far ([speeds], tokens per second by model id),
 * for the models screen's hardware line and for choosing fast / balanced / pro.
 */
data class EngineSummary(
    val backend: String? = null,
    val gpu: String? = null,
    val gpuLayers: Int? = null,
    val totalLayers: Int? = null,
    val threads: Int? = null,
    /** The director model the numbers were measured with. */
    val model: String? = null,
    val speeds: Map<String, Float> = emptyMap(),
    /** Whisper's seconds of work per second of sound (below 1: faster than real time). */
    val speechRealTime: Float? = null,
    val renderFps: Float? = null,
    val measuredAtMs: Long = 0,
) {
    /** The model ran (at least partly) on a GPU. */
    val onGpu: Boolean get() = gpu != null && (gpuLayers ?: 0) > 0
}

/** The latest [EngineSummary] on this device; null before the first build that used a model. */
fun interface EngineFacts {
    fun latest(): EngineSummary?

    companion object {
        val None = EngineFacts { null }
    }
}
