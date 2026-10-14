package io.trimio.engine.llm.local

import io.trimio.engine.llm.Usage

/**
 * One timing of a model on this device: loaded with [run]'s threads and GPU layers, it reads a fixed text (about
 * 450 tokens, English and Persian) and writes 32 tokens. Used to place a model ([Placement]) and by the speed test.
 */
object LlamaProbe {
    /**
     * The probe at [threads] threads with [layers] on the GPU (-1 as many as fit, 0 the CPU), the model's own MTP head
     * guessing [draft] tokens a step when it has one; null when the model does not load.
     */
    fun run(modelPath: String, threads: Int, layers: Int, draft: Int = 0): Placement.Probe? {
        val h = LlamaNative.init(modelPath, CONTEXT, threads, layers, LlamaNative.options(draft))
        if (h == 0L) return null
        return try {
            val info = LlamaRuntime.lines(LlamaNative.runtimeInfo(h))
            val onGpu = LlamaRuntime.parse(LlamaNative.variant, info, Usage(calls = 0)).gpuLayers > 0
            val drafting = info["mtp_draft"]?.firstOrNull()?.toIntOrNull() ?: 0
            LlamaNative.generate(h, TEXT.repeat(REPEAT).encodeToByteArray(), null, WRITE, 0f, 0.95f, 0.05f, 1, LlamaCallback { true })
            val c = LlamaNative.lastCall(h)
            val read = (c[0] - c[1]).coerceAtLeast(1) / (c[3] / MICROS).coerceAtLeast(MIN_SECONDS)
            val write = if (c[2] > 0) c[2] / (c[4] / MICROS).coerceAtLeast(MIN_SECONDS) else read
            Placement.Probe(layers, onGpu, read, write, drafting)
        } finally {
            LlamaNative.free(h)
        }
    }

    /** Whether this build can put a model on a GPU at all (a GPU build: OpenCL, Vulkan, CUDA, Metal). */
    val gpuBuild: Boolean get() = LlamaNative.measuresPlacement

    private const val TEXT = "The director reads the transcript, finds the hook, the key numbers and the turns, and plans the edit. " +
        "کارگردان متن را می‌خواند، قلاب و عددهای کلیدی و نقطه‌های عطف را پیدا می‌کند و ادیت را برنامه‌ریزی می‌کند. "
    private const val REPEAT = 8
    private const val WRITE = 32
    private const val CONTEXT = 2048
    private const val MICROS = 1_000_000.0
    private const val MIN_SECONDS = 0.001
}
