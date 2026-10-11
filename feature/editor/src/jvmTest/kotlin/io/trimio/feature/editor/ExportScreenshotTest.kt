package io.trimio.feature.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.trimio.core.designsystem.signature.SignatureTheme
import io.trimio.core.model.text.Language
import io.trimio.engine.production.ExportSpec
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The export screen (board 4, first phone): Persian and English, noir and paper, phone and desktop, a run and its end. */
class ExportScreenshotTest {
    private val ui = ExportUi(
        title = "سیگنال امروز بیت‌کوین", scenes = 7, styleName = "نوآر", durationMs = 36_000, verdict = ExportVerdict.Approved,
        unsupported = setOf(ExportQuality.Uhd to 60),
    )

    @Test
    fun export() {
        shoot("export-fa", paper = false) { ExportScreen(ui, ExportActions()) }
        shoot("export-fa-paper", paper = true) { ExportScreen(ui, ExportActions()) }
        shoot("export-en", paper = false, language = Language.English) { ExportScreen(ui.copy(title = "Today's Bitcoin signal", styleName = "Noir"), ExportActions()) }
        shoot("export-fa-running", paper = false) {
            ExportScreen(ui.copy(destination = ExportDestination.YouTube, job = ExportJob(ExportKind.Video, progress = 0.42f, etaSeconds = 14)), ExportActions())
        }
        shoot("export-fa-done", paper = false) {
            ExportScreen(ui.copy(job = ExportJob(ExportKind.Video, progress = 1f, files = listOf("/x/سیگنال-reels-1080x1920-30.mp4"))), ExportActions())
        }
        shoot("export-fa-desktop", paper = false, width = 1280, height = 800, density = 1.5f) { ExportScreen(ui.copy(desktop = true), ExportActions()) }
        shoot("export-fa-missing", paper = false) { ExportScreen(ExportUi(missing = NotOpen.SourceMissing), ExportActions()) }
        shoot("export-fa-opening", paper = false) { ExportScreen(ExportUi(opening = true), ExportActions()) }
    }

    @Test
    fun estimateMatchesTheBoard() {
        // A 36 s reel at 1080p30: about 48 MB and 25 s on a phone, as the board says.
        assertEquals("~48 MB · 25s", ExportEstimate.label(ExportEstimate.of(36f, ExportSpec("9:16", 1080, 30), ExportUi.DEFAULT_SECONDS_PER_FRAME)))
        val (big, slow) = ExportEstimate.of(36f, ExportSpec("9:16", 2160, 60), ExportUi.DEFAULT_SECONDS_PER_FRAME)
        assertTrue(big > 48_000_000L * 3 && slow > 25 * 6, "4K60 is far larger and slower")
        assertEquals(1920 to 1080, ExportSpec("16:9", 1080, 30).size)
        assertEquals(2160 to 2160, ExportSpec("1:1", 2160, 30).size)
        assertEquals(720 to 1280, ExportSpec("9:16", 720, 24).size)
    }

    private fun shoot(name: String, paper: Boolean, language: Language = Language.Persian, width: Int = 390, height: Int = 844, density: Float = 2f, content: @Composable () -> Unit) {
        ImageComposeScene((width * density).toInt(), (height * density).toInt(), Density(density)) {
            SignatureTheme(language = language, paper = paper) { Box(Modifier.fillMaxSize()) { content() } }
        }.use { scene ->
            for (t in 0L..1_000L step 250L) scene.render(t * 1_000_000)
            val png = scene.render(1_500_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("build/screenshots").mkdirs()
            File("build/screenshots/$name.png").writeBytes(png)
            assertTrue(png.size > 30_000)
        }
    }
}
