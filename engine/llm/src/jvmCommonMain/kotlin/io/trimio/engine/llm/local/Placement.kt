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
internal object Placement {
    /** One probe: [gpuLayers] asked (-1 as many as fit, 0 the CPU), [onGpu] whether any landed there, and the speeds. */
    data class Probe(val gpuLayers: Int, val onGpu: Boolean, val readPerSecond: Double, val writePerSecond: Double) {
        /** Seconds a typical director call (about [CALL_READ] tokens read, [CALL_WRITE] written) takes this way. */
        val callSeconds: Double get() = CALL_READ / readPerSecond.coerceAtLeast(MIN_RATE) + CALL_WRITE / writePerSecond.coerceAtLeast(MIN_RATE)

        fun line(): String = "${if (onGpu) "gpu" else "cpu"} read ${rate(readPerSecond)} write ${rate(writePerSecond)} tok/s"
    }

    /** The decision and how it was reached, as the report shows it. */
    data class Choice(val gpuLayers: Int, val line: String)

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
        if (lines.size < 3 || lines[0] != build.hashCode().toString()) null else Choice(lines[1].toInt(), lines[2])
    }.getOrNull()

    fun keep(model: String, build: String, choice: Choice) {
        runCatching { file(model).writeText("${build.hashCode()}\n${choice.gpuLayers}\n${choice.line}\n") }
    }

    private fun file(model: String) = File("$model.placement")

    private fun rate(v: Double): String = ((v * TENTHS).toLong() / TENTHS).toString()

    /** The GPU must be this much faster (its time × 1.15 still below the CPU's) to be worth pinning the model in GPU memory. */
    private const val GPU_MARGIN = 1.15
    private const val CALL_READ = 800.0
    private const val CALL_WRITE = 250.0
    private const val MIN_RATE = 0.1
    private const val TENTHS = 10.0
}
