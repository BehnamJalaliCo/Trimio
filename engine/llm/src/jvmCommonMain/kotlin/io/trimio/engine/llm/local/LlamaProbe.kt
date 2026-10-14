package io.trimio.engine.llm.local

import io.trimio.engine.llm.Usage

/**
 * One timing of a model on this device: loaded with [run]'s threads and GPU layers, it reads a fixed text (about
 * 450 tokens, English and Persian) and writes 32 tokens. Used to place a model ([Placement]) and by the speed test.
 */
object LlamaProbe {
    /**
     * The probe at [threads] threads with [layers] on the GPU (-1 as many as fit, 0 the CPU), the model's own MTP head
     * guessing [draft] tokens a step when it has one; null when the model does not load. With [repeats] above one the
     * first pass only warms the caches and the clocks, and each speed is the median of the passes after it.
     */
    fun run(modelPath: String, threads: Int, layers: Int, draft: Int = 0, repeats: Int = 1): Placement.Probe? {
        val h = LlamaNative.init(modelPath, CONTEXT, threads, layers, LlamaNative.options(draft))
        if (h == 0L) return null
        return try {
            val info = LlamaRuntime.lines(LlamaNative.runtimeInfo(h))
            val runtime = LlamaRuntime.parse(LlamaNative.variant, info, Usage(calls = 0))
            val drafting = info["mtp_draft"]?.firstOrNull()?.toIntOrNull() ?: 0
            val passes = (if (repeats > 1) repeats + 1 else 1)
            val rates = (1..passes).map { pass(h, it) }.drop(if (repeats > 1) 1 else 0)
            Placement.Probe(
                layers, runtime.gpuLayers > 0, median(rates.map { it.first }), median(rates.map { it.second }), drafting,
                offloaded = runtime.gpuLayers, totalLayers = runtime.totalLayers,
            )
        } finally {
            LlamaNative.free(h)
        }
    }

    /** What [sweep] measured on one load: each thread count with its speeds, and whether the model has an MTP head. */
    class Sweep(val rates: List<Pair<Int, Placement.Probe>>, val mtpHead: Boolean)

    /**
     * The speed test's probe: the model loaded once with [layers] on the GPU, one short warm-up pass, then each of
     * [threads] timed [passes] times on a shorter text (about 400 tokens read, 32 written), the better pass kept (a slow
     * pass is the phone doing something else, never the way being faster). Null when the model does not load.
     */
    fun sweep(modelPath: String, threads: List<Int>, layers: Int, draft: Int = 0, passes: Int = 2): Sweep? {
        val h = LlamaNative.init(modelPath, CONTEXT, threads.first(), layers, LlamaNative.options(draft))
        if (h == 0L) return null
        return try {
            val info = LlamaRuntime.lines(LlamaNative.runtimeInfo(h))
            val runtime = LlamaRuntime.parse(LlamaNative.variant, info, Usage(calls = 0))
            val drafting = info["mtp_draft"]?.firstOrNull()?.toIntOrNull() ?: 0
            pass(h, 0, 1)
            var n = 1
            val rates = threads.map { t ->
                LlamaNative.setThreads(h, t)
                val timed = (1..passes).map { pass(h, n++, QUICK_REPEAT) }
                t to Placement.Probe(
                    layers, runtime.gpuLayers > 0, timed.maxOf { it.first }, timed.maxOf { it.second }, drafting,
                    offloaded = runtime.gpuLayers, totalLayers = runtime.totalLayers,
                )
            }
            Sweep(rates, info["mtp_head"]?.firstOrNull() == "1")
        } finally {
            LlamaNative.free(h)
        }
    }

    /**
     * One read of the fixed text ([repeat] times over) and 32 tokens written: (read, write) tokens a second. Each pass
     * opens with its own number, so the engine's prompt-head reuse finds nothing to keep and every pass reads it all again.
     */
    private fun pass(h: Long, n: Int, repeat: Int = REPEAT): Pair<Double, Double> {
        LlamaNative.generate(h, ("pass $n. " + TEXT.repeat(repeat)).encodeToByteArray(), null, WRITE, 0f, 0.95f, 0.05f, 1, LlamaCallback { true })
        val c = LlamaNative.lastCall(h)
        val read = (c[0] - c[1]).coerceAtLeast(1) / (c[3] / MICROS).coerceAtLeast(MIN_SECONDS)
        val write = if (c[2] > 0) c[2] / (c[4] / MICROS).coerceAtLeast(MIN_SECONDS) else read
        return read to write
    }

    private fun median(values: List<Double>): Double = values.sorted().let { v -> if (v.size % 2 == 1) v[v.size / 2] else (v[v.size / 2 - 1] + v[v.size / 2]) / 2 }

    /** Whether this build can put a model on a GPU at all (a GPU build: OpenCL, Vulkan, CUDA, Metal). */
    val gpuBuild: Boolean get() = LlamaNative.measuresPlacement

    private const val TEXT = "The director reads the transcript, finds the hook, the key numbers and the turns, and plans the edit. " +
        "کارگردان متن را می‌خواند، قلاب و عددهای کلیدی و نقطه‌های عطف را پیدا می‌کند و ادیت را برنامه‌ریزی می‌کند. "
    private const val REPEAT = 8
    private const val QUICK_REPEAT = 3
    private const val WRITE = 32
    private const val CONTEXT = 2048
    private const val MICROS = 1_000_000.0
    private const val MIN_SECONDS = 0.001
}
