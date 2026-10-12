package io.trimio.engine.llm.local

import io.trimio.engine.llm.ModelRuntime
import io.trimio.engine.llm.Usage

/** Reads the engine's `key=value` runtime report (native `runtimeInfo`) into a [ModelRuntime]. */
internal object LlamaRuntime {
    /** Lines grouped by key, in order (`device` and `log` repeat). */
    fun lines(text: String): Map<String, List<String>> =
        text.lineSequence().filter { '=' in it }.groupBy({ it.substringBefore('=') }, { it.substringAfter('=').trim() })

    fun parse(variant: String, info: Map<String, List<String>>, usage: Usage): ModelRuntime {
        fun int(key: String) = info[key]?.firstOrNull()?.toIntOrNull() ?: 0
        // What llama.cpp itself reports placing ("offloaded 20/33 layers to GPU") wins over what was asked.
        val offloaded = info["log"].orEmpty().firstNotNullOfOrNull { OFFLOADED.find(it) }
        val gpuLayers = offloaded?.groupValues?.get(1)?.toIntOrNull() ?: int("gpu_layers")
        val total = offloaded?.groupValues?.get(2)?.toIntOrNull() ?: int("layers")
        val gpus = info["gpu"].orEmpty()
        val hasGpu = info["device"].orEmpty().any { it.startsWith("gpu|") || it.startsWith("igpu|") }
        val reason = when {
            int("gated") == 1 -> "the GPU runs only ${(info["gpu_coverage"]?.firstOrNull()?.toFloatOrNull() ?: 0f).times(PERCENT).toInt()}% of this model's weights"
            int("fell_back") == 1 -> "loading on the GPU failed"
            hasGpu && gpuLayers == 0 -> "no free GPU memory"
            else -> null
        }
        return ModelRuntime(
            backend = variant.ifEmpty { "cpu" },
            gpu = gpus.joinToString(" + ").takeIf { gpuLayers > 0 && it.isNotEmpty() },
            gpuLayers = gpuLayers,
            totalLayers = total,
            threads = int("threads"),
            kvCache = info["kv"]?.firstOrNull() ?: "f16",
            cpuReason = reason,
            usage = usage,
            facts = info.flatMap { (k, values) -> values.map { k to it } },
        )
    }

    private val OFFLOADED = Regex("offloaded (\\d+)/(\\d+) layers")
    private const val PERCENT = 100
}
