package io.trimio.engine.llm.local

import java.io.File

/**
 * Where a model runs on this device, measured instead of assumed. A GPU build used to put every
 * model it could on the GPU; on a phone's Adreno a model the GPU only partly runs bounces between GPU
 * and CPU every layer and reads and writes several times slower than the CPU alone (1.0.17 on a
 * Galaxy S26 Ultra: 20 tok/s reading, 4–5 writing), and its weights sit in GPU memory the system
 * cannot reclaim. So the first load of a model on a GPU build times a short probe both ways and keeps
 * the faster; the GPU must win clearly ([GPU_MARGIN]), since the CPU's memory-mapped weights are the
 * ones the system can page out instead of killing the app. The choice is kept beside the model and
 * measured again when the engine build changes.
 */
object Placement {
    /** One probe: [gpuLayers] asked (-1 as many as fit, 0 the CPU), [onGpu] whether any landed there, and the speeds. */
    data class Probe(
        val gpuLayers: Int,
        val onGpu: Boolean,
        val readPerSecond: Double,
        val writePerSecond: Double,
        val draft: Int = 0,
        /** The layers that landed on the GPU and the model's layer count, as the engine reported them. */
        val offloaded: Int = 0,
        val totalLayers: Int = 0,
    ) {
        /** Seconds a typical director call (about [CALL_READ] tokens read, [CALL_WRITE] written) takes this way. */
        val callSeconds: Double get() = CALL_READ / readPerSecond.coerceAtLeast(MIN_RATE) + CALL_WRITE / writePerSecond.coerceAtLeast(MIN_RATE)

        fun line(): String = "${if (onGpu) "gpu" else "cpu"} read ${rate(readPerSecond)} write ${rate(writePerSecond)} tok/s"
    }

    /** The decision and how it was reached, as the report shows it; [threads] when the speed test measured the best count. */
    data class Choice(val gpuLayers: Int, val line: String, val threads: Int? = null, val draft: Int? = null, val totalLayers: Int? = null) {
        /** The share of [modelBytes] this choice pins on the GPU: all of it for -1, its layers' share for a part. */
        fun gpuBytes(modelBytes: Long): Long = when {
            gpuLayers == 0 -> 0L
            gpuLayers < 0 || totalLayers == null || totalLayers <= 0 -> modelBytes
            else -> modelBytes * gpuLayers.coerceAtMost(totalLayers) / totalLayers
        }
    }

    /**
     * Whether the GPU may even be tried: its copy of the weights is pinned (the system cannot page it out the way it
     * pages the CPU's memory-mapped file), so it needs the model's size plus [GPU_HEADROOM] free. On a phone a GPU
     * load without that room is how 1.0.17 and 1.0.18 were killed for memory (RSS 2.9 GB plus the GPU's share).
     * [freeBytes] null (unreadable) leaves the probe to decide.
     */
    fun gpuFits(modelBytes: Long, freeBytes: Long?): Boolean = freeBytes == null || freeBytes >= modelBytes + GPU_HEADROOM

    /** Memory the system can still give without killing anything (`MemAvailable`), on Android and Linux; null elsewhere. */
    fun freeBytes(): Long? = runCatching {
        File("/proc/meminfo").takeIf(File::canRead)?.useLines { lines ->
            lines.firstOrNull { it.startsWith("MemAvailable:") }?.let { DIGITS.find(it)?.value?.toLong()?.times(KIB) }
        }
    }.getOrNull()

    /** The layers to ask for: the GPU's only when it ran there and beat the CPU by [GPU_MARGIN] on a typical call. */
    fun choose(gpu: Probe?, cpu: Probe?): Choice {
        val gpuWins = gpu != null && gpu.onGpu && (cpu == null || gpu.callSeconds * GPU_MARGIN < cpu.callSeconds)
        val chosen = if (gpuWins) gpu!!.gpuLayers else 0
        val measured = listOfNotNull(gpu?.line(), cpu?.line()).joinToString(" · ")
        return Choice(chosen, "measured: $measured → ${if (gpuWins) "GPU" else "CPU"}")
    }

    /** The choice kept beside [model] for this engine [build]; null when there is none or the build changed. */
    fun cached(model: String, build: String): Choice? = runCatching {
        val lines = file(model).takeIf(File::isFile)?.readLines() ?: return@runCatching null
        if (lines.size < 3 || lines[0] != build.hashCode().toString()) {
            null
        } else {
            Choice(lines[1].toInt(), lines[2], lines.getOrNull(3)?.toIntOrNull(), lines.getOrNull(4)?.toIntOrNull(), lines.getOrNull(5)?.toIntOrNull())
        }
    }.getOrNull()

    fun keep(model: String, build: String, choice: Choice) {
        runCatching {
            file(model).writeText("${build.hashCode()}\n${choice.gpuLayers}\n${choice.line}\n${choice.threads ?: ""}\n${choice.draft ?: ""}\n${choice.totalLayers ?: ""}\n")
        }
    }

    /** The engine build a choice belongs to: a new app version (a new native build) measures again. */
    fun build(): String = LlamaNative.variant + "|" + LlamaNative.systemInfo()

    private fun file(model: String) = File("$model.placement")

    private fun rate(v: Double): String = ((v * TENTHS).toLong() / TENTHS).toString()

    private val DIGITS = Regex("\\d+")
    private const val KIB = 1024L

    /** Free memory beyond the model's own size for a GPU load: the context, compute buffers, the app and the system. */
    /** Kept free beside the GPU's pinned weights, for the app and the system (the speed test sizes GPU layers with it too). */
    const val GPU_HEADROOM = 2L * 1024 * 1024 * 1024

    /** The GPU must be this much faster (its time × 1.15 still below the CPU's) to be worth pinning the model in GPU memory. */
    private const val GPU_MARGIN = 1.15
    private const val CALL_READ = 800.0
    private const val CALL_WRITE = 250.0
    private const val MIN_RATE = 0.1
    private const val TENTHS = 10.0
}
