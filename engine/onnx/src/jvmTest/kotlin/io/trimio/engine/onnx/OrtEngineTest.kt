package io.trimio.engine.onnx

import java.io.File
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** The real runtime on the host: a 247-byte graph written for this test (y = 2x + 1, uint8 → float, float → int64). */
class OrtEngineTest {
    private val engine = OrtEngine()

    private fun tinyModel(): File = File.createTempFile("tiny", ".onnx").apply {
        deleteOnExit()
        writeBytes(Base64.getDecoder().decode(TINY))
    }

    @Test
    fun runsOnTheCpuAndConvertsEveryTensorType() {
        assertTrue(engine.available(), "ONNX Runtime must load on the host")
        assertTrue(Accelerator.Cpu in engine.accelerators())
        engine.load(tinyModel().path).use { net ->
            assertEquals(Accelerator.Cpu, net.accelerator)
            assertEquals(setOf("x", "pix"), net.inputNames.toSet())
            val out = net.run(
                mapOf(
                    "x" to Tensor.of(longArrayOf(1, 3), floatArrayOf(0f, 1f, 2.5f)),
                    "pix" to Tensor.ofBytes(longArrayOf(1, 2), byteArrayOf(7, 250.toByte())),
                ),
            )
            assertContentEquals(floatArrayOf(1f, 3f, 6f), out.getValue("y").floats)
            assertContentEquals(floatArrayOf(7f, 250f), out.getValue("z").floats)
            assertContentEquals(longArrayOf(1, 3, 6), out.getValue("k").longs)
            assertContentEquals(longArrayOf(1, 3), out.getValue("y").shape)
        }
    }

    @Test
    fun gpuPreferencesFallBackToTheCpu() {
        val config = SessionConfig(accelerators = listOf(Accelerator.Cuda, Accelerator.DirectMl, Accelerator.Nnapi))
        engine.load(tinyModel().path, config).use { assertEquals(Accelerator.Cpu, it.accelerator) }
    }

    @Test
    fun aMissingFileIsAnEngineError() {
        assertFailsWith<NeuralEngineException> { engine.load("/nonexistent/model.onnx") }
    }

    private companion object {
        const val TINY =
            "CAg67AEKEAoBeAoDdHdvEgF0IgNNdWwKEAoBdAoDb25lEgF5IgNBZGQKGQoDcGl4EgF6IgRDYXN0KgkKAnRvGAGgAQIKFwoBeRIBayIEQ2FzdCoJCgJ0bxgHoAEC" +
                "EgR0aW55Kg0QASIEAAAAQEIDdHdvKg0QASIEAACAP0IDb25lWhQKAXgSDwoNCAESCQoCCAEKAxIBbloWCgNwaXgSDwoNCAISCQoCCAEKAxIBbWIUCgF5Eg8K" +
                "DQgBEgkKAggBCgMSAW5iFAoBehIPCg0IARIJCgIIAQoDEgFtYhQKAWsSDwoNCAcSCQoCCAEKAxIBbkIECgAQDQ=="
    }
}
