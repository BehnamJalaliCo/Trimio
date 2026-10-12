package io.trimio.feature.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.trimio.core.designsystem.signature.SignatureTheme
import io.trimio.core.model.text.Language
import io.trimio.engine.models.DeviceMemory
import io.trimio.engine.models.ModelCatalog
import io.trimio.engine.models.ModelFit
import io.trimio.engine.models.ModelState
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The model manager (7.6) in Persian RTL: a 12 GB phone (noir, paper), a fresh 8 GB phone, and a desktop. */
class ModelManagerScreenshotTest {
    private val states = mapOf(
        "qwen3.5-4b-q4km" to ModelState.Installed,
        "whisper-large-v3-turbo-q5" to ModelState.Installed,
        "qwen3.5-9b-ud-q3kxl" to ModelState.Downloading(0.42f, 2_100_000_000),
        "whisper-small-q8" to ModelState.Failed("network"),
    )

    private fun ui(ram: Int, desktop: Boolean = false, s: Map<String, ModelState> = states) =
        ModelShelf.build(ModelCatalog.all, s, DeviceMemory(ram, desktop), activeDirector = null, activeSpeech = "whisper-large-v3-turbo-q5")

    @Test
    fun phone() {
        shoot("models-fa-noir", paper = false) { ModelManagerScreen(ui(12), ModelActions()) }
        shoot("models-fa-paper", paper = true) { ModelManagerScreen(ui(12), ModelActions()) }
        shoot("models-fa-empty", paper = false) { ModelManagerScreen(ui(8, s = emptyMap()), ModelActions()) }
    }

    /** The «موتور: …» line: measured by a build (the GPU and its layers), or not yet (only the threads). */
    @Test
    fun hardwareLine() {
        val measured = HardwareLine("QUALCOMM Adreno(TM) 830", "opencl", 33, 33, 6, measured = true)
        val speeds = mapOf("qwen3.5-4b-q4km" to 14.2f)
        shoot("models-hardware-fa-noir", paper = false) {
            ModelManagerScreen(ModelShelf.build(ModelCatalog.all, states, DeviceMemory(12), null, "whisper-large-v3-turbo-q5", hardware = measured, speeds = speeds), ModelActions())
        }
        shoot("models-hardware-fa-paper", paper = true) {
            ModelManagerScreen(ModelShelf.build(ModelCatalog.all, states, DeviceMemory(12), null, null, hardware = HardwareLine(threads = 6)), ModelActions())
        }
        assertEquals(14.2f, ModelShelf.build(ModelCatalog.all, states, DeviceMemory(12), null, null, speeds = speeds).directors.first { it.id == "qwen3.5-4b-q4km" }.measuredTokensPerSecond)
    }

    @Test
    fun desktop() = shoot("models-fa-desktop", paper = false, width = 1280, height = 800, density = 1.5f) { ModelManagerScreen(ui(32, desktop = true), ModelActions()) }

    @Test
    fun tiersFollowTheDevice() {
        assertEquals("qwen3.5-4b-q4km", ui(8, s = emptyMap()).recommended?.id)
        assertEquals(ModelFit.TooBig, ui(8).directors.first { it.id.startsWith("qwen3.6") }.fit)
        assertEquals("4B", ui(8).directors.first { it.id == "qwen3.5-4b-q4km" }.tier)
        assertTrue(ui(12).directors.first { it.id == "qwen3.5-4b-q4km" }.active)
    }

    private fun shoot(name: String, paper: Boolean, width: Int = 390, height: Int = 844, density: Float = 2f, content: @Composable () -> Unit) {
        ImageComposeScene((width * density).toInt(), (height * density).toInt(), Density(density)) {
            SignatureTheme(language = Language.Persian, paper = paper) { Box(Modifier.fillMaxSize()) { content() } }
        }.use { scene ->
            for (t in 0L..1_000L step 250L) scene.render(t * 1_000_000)
            val png = scene.render(1_500_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("build/screenshots").mkdirs()
            File("build/screenshots/$name.png").writeBytes(png)
            assertTrue(png.size > 30_000)
        }
    }
}
