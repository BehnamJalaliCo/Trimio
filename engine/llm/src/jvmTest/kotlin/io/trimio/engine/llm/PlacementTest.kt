package io.trimio.engine.llm

import io.trimio.engine.llm.local.Placement
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlacementTest {
    @Test
    fun theGpuMustClearlyBeatTheCpu() {
        // 1.0.17 on the S26 Ultra: the Adreno build read at 20 tok/s and wrote at 5.
        val adreno = Placement.Probe(-1, onGpu = true, readPerSecond = 20.0, writePerSecond = 5.0)
        val cpu = Placement.Probe(0, onGpu = false, readPerSecond = 150.0, writePerSecond = 14.0)
        assertEquals(0, Placement.choose(adreno, cpu).gpuLayers)
        assertTrue("→ CPU" in Placement.choose(adreno, cpu).line)

        // A laptop's discrete GPU wins by far.
        val rtx = Placement.Probe(-1, onGpu = true, readPerSecond = 900.0, writePerSecond = 40.0)
        assertEquals(-1, Placement.choose(rtx, cpu).gpuLayers)

        // Barely faster is not worth pinning the model in GPU memory.
        val close = Placement.Probe(-1, onGpu = true, readPerSecond = 160.0, writePerSecond = 15.0)
        assertEquals(0, Placement.choose(close, cpu).gpuLayers)

        // A GPU probe that landed nothing on the GPU, or none at all, keeps the CPU.
        assertEquals(0, Placement.choose(rtx.copy(onGpu = false), cpu).gpuLayers)
        assertEquals(0, Placement.choose(null, cpu).gpuLayers)
        assertEquals(-1, Placement.choose(rtx, null).gpuLayers)
    }

    @Test
    fun theChoiceIsKeptUntilTheEngineChanges() {
        val model = Files.createTempDirectory("placement").resolve("m.gguf").toString()
        assertNull(Placement.cached(model, "opencl|a"))
        val choice = Placement.Choice(0, "measured: gpu read 20 write 5 tok/s · cpu read 150 write 14 tok/s → CPU")
        Placement.keep(model, "opencl|a", choice)
        assertEquals(choice, Placement.cached(model, "opencl|a"))
        assertNull(Placement.cached(model, "opencl|b"), "a new engine build measures again")
    }
}
