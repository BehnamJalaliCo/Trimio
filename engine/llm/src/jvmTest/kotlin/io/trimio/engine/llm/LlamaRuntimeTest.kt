package io.trimio.engine.llm

import io.trimio.engine.llm.local.LlamaRuntime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The engine's runtime report as the technical report reads it: the GPU, its layers, why the CPU. */
class LlamaRuntimeTest {
    private val laptop = """
        device=cpu|CPU|11th Gen Intel(R) Core(TM) i5-11400H|15000|16000
        device=igpu|Vulkan0|Intel(R) UHD Graphics|7000|8000
        device=gpu|Vulkan1|NVIDIA GeForce RTX 3050 Ti Laptop GPU|3600|4096
        model=qwen35 9B Q3_K - Medium
        layers=33
        gpu_layers=17
        gpu_coverage=1.000
        gated=0
        fell_back=0
        threads=4
        kv=q8_0
        gpu=NVIDIA GeForce RTX 3050 Ti Laptop GPU
        log=load_tensors: offloaded 16/33 layers to GPU
    """.trimIndent()

    @Test
    fun aLaptopGpuWithPartOfTheModel() {
        val r = LlamaRuntime.parse("vulkan", LlamaRuntime.lines(laptop), Usage(2, 2000, 900, 300, 10_000, 30_000))
        assertEquals("NVIDIA GeForce RTX 3050 Ti Laptop GPU", r.gpu)
        assertEquals(16, r.gpuLayers, "what llama.cpp reports placing wins over the request")
        assertEquals(33, r.totalLayers)
        assertNull(r.cpuReason)
        assertEquals(110f, r.usage.promptTokensPerSecond)
        assertEquals(10f, r.usage.generatedTokensPerSecond)
        assertTrue(r.summary().startsWith("llm vulkan NVIDIA GeForce RTX 3050 Ti Laptop GPU 16/33 layers, 4 threads, kv q8_0; 2 calls"), r.summary())
        assertEquals(3, r.facts.count { it.first == "device" })
    }

    @Test
    fun aPhoneGpuThatCannotRunTheQuantsStaysOnTheCpu() {
        val phone = """
            device=cpu|CPU|CPU|0|0
            device=gpu|GPUOpenCL|QUALCOMM Adreno(TM) 830|0|0
            layers=33
            gpu_layers=0
            gpu_coverage=0.650
            gated=1
            threads=7
            kv=q8_0
        """.trimIndent()
        val r = LlamaRuntime.parse("opencl", LlamaRuntime.lines(phone), Usage(calls = 0))
        assertNull(r.gpu)
        assertEquals("the GPU runs only 65% of this model's weights", r.cpuReason)
        assertEquals("llm opencl cpu only (the GPU runs only 65% of this model's weights), 7 threads, kv q8_0", r.summary())
    }

    @Test
    fun usageAddsUp() {
        val u = Usage(1, 1000, 800, 100, 2_000, 10_000) + Usage(1, 1000, 0, 200, 10_000, 20_000)
        assertEquals(2, u.calls)
        assertEquals(800, u.reusedTokens)
        assertEquals(100f, u.promptTokensPerSecond)
        assertEquals(10f, u.generatedTokensPerSecond)
    }
}
