package io.trimio.engine.onnx

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class NeuralEngineTest {
    @Test
    fun androidPrefersXnnpackAndUsesNnapiOnlyForStaticShapes() {
        assertEquals(listOf(Accelerator.Xnnpack, Accelerator.Cpu), AcceleratorPlan.forPlatform(Platform.Android))
        assertEquals(
            listOf(Accelerator.Nnapi, Accelerator.Xnnpack, Accelerator.Cpu),
            AcceleratorPlan.forPlatform(Platform.Android, staticShapes = true),
        )
        assertEquals(Accelerator.Qnn, AcceleratorPlan.forPlatform(Platform.Android, staticShapes = true, qnnBundled = true).first())
    }

    @Test
    fun windowsTriesGpusThenTheCpu() {
        assertEquals(listOf(Accelerator.Cuda, Accelerator.DirectMl, Accelerator.Cpu), AcceleratorPlan.forPlatform(Platform.Windows))
    }

    @Test
    fun resolveDropsWhatTheBuildLacksAndAlwaysEndsOnTheCpu() {
        val plan = AcceleratorPlan.resolve(listOf(Accelerator.Cuda, Accelerator.DirectMl), setOf(Accelerator.Cpu))
        assertEquals(listOf(Accelerator.Cpu), plan)
        val android = AcceleratorPlan.resolve(AcceleratorPlan.forPlatform(Platform.Android), setOf(Accelerator.Xnnpack, Accelerator.Nnapi))
        assertEquals(listOf(Accelerator.Xnnpack, Accelerator.Cpu), android)
    }

    @Test
    fun tensorChecksItsShape() {
        assertEquals(6, Tensor.of(longArrayOf(2, 3), FloatArray(6)).count)
        assertFailsWith<IllegalArgumentException> { Tensor.of(longArrayOf(2, 3), FloatArray(5)) }
    }

    @Test
    fun slotKeepsOneNetworkOpenAndClosesItForTheNext() = runTest {
        val engine = FakeEngine()
        val slot = NetworkSlot(engine)
        slot.use("a.onnx") { }
        slot.use("a.onnx") { }
        assertEquals(1, slot.loads)
        slot.use("b.onnx") { }
        assertEquals(2, slot.loads)
        assertEquals(listOf("a.onnx"), engine.closed)
        assertEquals("b.onnx", slot.resident)
        slot.release()
        assertNull(slot.resident)
        assertEquals(listOf("a.onnx", "b.onnx"), engine.closed)
    }

    @Test
    fun noEngineFailsLoudlySoCallersFallBack() {
        assertTrue(!NoEngine.available())
        assertFailsWith<NeuralEngineException> { NoEngine.load("x.onnx") }
    }

    private class FakeEngine : NeuralEngine {
        val closed = mutableListOf<String>()
        override val name = "fake"

        override fun available() = true

        override fun accelerators() = setOf(Accelerator.Cpu)

        override fun load(path: String, config: SessionConfig): Network = object : Network {
            override val accelerator = Accelerator.Cpu
            override val inputNames = listOf("x")
            override val outputNames = listOf("y")

            override fun run(inputs: Map<String, Tensor>, outputs: List<String>?) = mapOf("y" to inputs.getValue("x"))

            override fun close() {
                closed += path
            }
        }
    }
}
