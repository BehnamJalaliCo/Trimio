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

    /** «افزودن مدل از روی سیستم» (Windows): a 32B from the creator's disk in the directors, active, with its note. */
    @Test
    fun ownModelOnWindows() {
        val own = io.trimio.engine.models.OwnModels.spec(
            io.trimio.engine.models.OwnModels.Entry("own-1a2b3c4d", "D:\\AI\\Qwen3-32B-Q4_K_M.gguf", "Qwen3-32B-Q4_K_M", 19_800_000_000L),
        )
        val shelf = ModelShelf.build(ModelCatalog.all + own, states + (own.id to ModelState.Installed), DeviceMemory(64, true), own.id, "whisper-large-v3-turbo-q5")
        val row = shelf.directors.first { it.id == own.id }
        assertEquals("32B", ModelShelf.tierOf(own))
        assertEquals("", row.tier, "its name already says it")
        assertTrue(row.own && row.active)
        val actions = ModelActions(onAddOwn = {}, ownNote = OwnNote(added = "Qwen3-32B-Q4_K_M"))
        shoot("models-fa-own-desktop", paper = false, width = 1280, height = 1100, density = 1.25f) { ModelManagerScreen(shelf, actions) }
        shoot("models-fa-own-refused", paper = true) { ModelManagerScreen(shelf, ModelActions(onAddOwn = {}, ownNote = OwnNote(refusal = io.trimio.engine.models.OwnModels.Refusal.Projector))) }
    }

    /** «دانلود همه»: seventeen items in a laptop window; the confirm and cancel buttons stay on screen (the list scrolls). */
    @Test
    fun downloadAllDialog() {
        val many = ModelCatalog.all.filter { it.addOn != null }.take(17)
        shoot("models-fa-download-all-desktop", paper = false, width = 1280, height = 720, density = 1.25f) {
            Box(Modifier.fillMaxSize()) {
                ModelManagerScreen(ui(32, desktop = true), ModelActions())
                DownloadDialog(many, {}, {})
            }
        }
        shoot("models-fa-download-all-phone", paper = true) {
            Box(Modifier.fillMaxSize()) {
                ModelManagerScreen(ui(12), ModelActions())
                DownloadDialog(many, {}, {})
            }
        }
    }

    /**
     * The owner's phone as 1.0.20 shows it (12 GB, Gemma E4B and large-v3 installed): the speed test at the top of the
     * screen, and both illustrators offered among the add-ons (the fast one and the studio one with its two companions).
     */
    @Test
    fun ownersPhone() {
        val mine = mapOf("gemma-4-e4b-qat-q4" to ModelState.Installed, "whisper-large-v3-q5" to ModelState.Installed)
        val shelf = ModelShelf.build(ModelCatalog.all, mine, DeviceMemory(12), "gemma-4-e4b-qat-q4", null)
        val offered = shelf.boosts.flatMap { it.rows }.map { it.row.id }
        val a = io.trimio.engine.models.IllustrationAddOns
        assertTrue(listOf(a.sdxs.id, a.klein.id).all { it in offered }, "illustrators offered on a 12 GB phone: $offered")
        val speed = SpeedUi(
            listOf(io.trimio.engine.models.SpeedRow(io.trimio.engine.models.SpeedRow.Kind.Director, "کارگردان · ۸ هسته", "۲۸ توکن در ثانیه", best = true),
                io.trimio.engine.models.SpeedRow(io.trimio.engine.models.SpeedRow.Kind.Speech, "Whisper · ۸ هسته", "۰٫۹× زمان واقعی"),),
            done = true,
        )
        shoot("models-fa-owners-phone", paper = true, height = 3400) { ModelManagerScreen(shelf, ModelActions(onSpeedTest = {}, speed = speed)) }
        shoot("settings-fa-speed-test", paper = true, height = 1400) {
            SettingsScreen(
                SettingsUi(io.trimio.core.data.AppSettings(), emptyMap(), io.trimio.core.data.DeviceInfo(12, "Android 17", "1.0.21", 1)),
                SettingsActions(onModels = {}, onSpeedTest = {}, speed = SpeedUi(running = true, progress = 0.47f, rows = speed.rows.take(1))),
            )
        }
        // After the test, on the next visit: the way every build runs now.
        shoot("models-fa-speed-kept", paper = true, height = 1700) { ModelManagerScreen(shelf, ModelActions(onSpeedTest = {}, speed = SpeedUi(rows = speed.rows, kept = true))) }
    }

    @Test
    fun desktop() = shoot("models-fa-desktop", paper = false, width = 1280, height = 800, density = 1.5f) { ModelManagerScreen(ui(32, desktop = true), ModelActions()) }

    /** «تقویت کیفیت»: the add-on tiers and the one-tap set, on a tall page so the whole section shows. */
    @Test
    fun qualityBoost() {
        val installing = states + ("bge-m3-q8" to ModelState.Downloading(0.3f, 190_000_000))
        shoot("models-boost-fa-noir", paper = false, height = 3000) { ModelManagerScreen(ui(12, s = installing), ModelActions()) }
        shoot("models-boost-fa-desktop", paper = true, height = 3200) { ModelManagerScreen(ui(16, desktop = true), ModelActions()) }
        val phone = ui(12)
        val plan = requireNotNull(phone.boostPlan)
        assertTrue("bge-m3-q8" in plan.ids && "qwen3-reranker-0.6b-q8" in plan.ids, plan.ids.toString())
        assertEquals(listOf("Essential", "Pro", "Ultra"), phone.boosts.map { it.tier.name }.filter { it in setOf("Essential", "Pro", "Ultra") }.distinct())
        assertTrue(phone.boosts.flatMap { it.rows }.none { it.row.id == "qwen3-reranker-4b-q4km" }, "laptop-only add-ons are not offered on a phone")
        assertTrue(ui(16, desktop = true).boosts.flatMap { it.rows }.any { it.row.id == "qwen3-reranker-4b-q4km" && it.recommended })
    }

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
