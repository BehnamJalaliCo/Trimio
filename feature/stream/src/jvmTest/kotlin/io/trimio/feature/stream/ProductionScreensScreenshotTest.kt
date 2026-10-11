package io.trimio.feature.stream

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.LinearGradientShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.trimio.core.designsystem.signature.SignatureTheme
import io.trimio.core.model.text.Language
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The production screens (live build, result, failure) off-screen in Persian RTL, noir and paper,
 * phone and desktop, into build/screenshots for review against board 2.
 */
class ProductionScreensScreenshotTest {

    /** Synthetic preview frames: a warm graded plate with a headline bar and a lime block. */
    private fun frame(k: Int): ImageBitmap {
        val img = ImageBitmap(270, 480)
        val canvas = Canvas(img)
        val paint = Paint().apply { shader = LinearGradientShader(Offset(0f, 0f), Offset(270f, 480f), listOf(Color(0xFF2A160C), Color(0xFF0C0B0A), Color(0xFF16210B))) }
        canvas.drawRect(Rect(0f, 0f, 270f, 480f), paint)
        canvas.drawCircle(Offset(190f, 140f), 120f, Paint().apply { color = Color(0x55FF965A) })
        canvas.drawRect(Rect(40f, 260f - k * 6f, 230f, 300f - k * 6f), Paint().apply { color = Color(0xFFF2EDE4) })
        canvas.drawRect(Rect(90f, 320f, 230f, 360f), Paint().apply { color = Color(0xFFD7FF3A) })
        return img
    }

    private val judges = JudgeScore.Panel.mapIndexed { i, j -> j.copy(score = if (i < 7) 10f else null) }

    private val live = LiveBuild(
        styleName = "نوآر لوکس",
        progress = 0.68f,
        stage = BuildStage.Jury,
        stageSeconds = mapOf(BuildStage.Listen to 2.1f, BuildStage.Understand to 9.4f, BuildStage.Plan to 6.2f),
        previews = (0 until 4).map(::frame),
        framesDone = 214, framesTotal = 315, etaSeconds = 40,
        directorNote = "«پنج درصد» را با شمارندهٔ عدد و ضربهٔ بیس می‌سازم؛ دوربین آرام زوم می‌کند و کلمه با هایلایت لیمویی می‌نشیند.",
        noteEmphasis = listOf("هایلایت لیمویی"),
        judges = judges,
        transcript = "امروز بیت‌کوین پنج درصد رشد کرد و سیگنال خرید ما به اولین هدف رسید…",
        transcriptEmphasis = listOf("بیت‌کوین", "پنج درصد", "سیگنال خرید"),
    )

    private val result = BuildResult(
        title = "سیگنال امروز بیت‌کوین", styleName = "نوآر لوکس", durationMs = 42_000, buildSeconds = 72, onDevice = true,
        scenes = 7, words = 97, effects = 12, lufs = -14f,
        judges = JudgeScore.Panel.map { it.copy(score = 10f) },
        findings = listOf("تایپوگرافی: تیتر هوک در ثانیهٔ ۰٫۸ درشت‌تر شد", "صدا: ضربهٔ شمارنده روی ضرب موسیقی نشست"),
        poster = frame(2),
    )

    @Test
    fun liveBuild() {
        shoot("live-build-fa-noir", paper = false) { LiveBuildScreen(live) {} }
        shoot("live-build-fa-paper", paper = true) { LiveBuildScreen(live.copy(stage = BuildStage.Plan, judges = JudgeScore.Panel, previews = emptyList(), framesTotal = 0)) {} }
        shoot("live-build-fa-desktop", paper = false, width = 1280, height = 800, density = 1.5f) { LiveBuildScreen(live) {} }
        // The editor's changes rendered as the next version: only the render stage runs.
        shoot("live-render-edit-fa-noir", paper = false) {
            LiveBuildScreen(LiveBuild(styleName = "پاپ", progress = 0.42f, stage = BuildStage.Render, previews = listOf(frame(1)), framesDone = 132, framesTotal = 315, etaSeconds = 9)) {}
        }
    }

    @Test
    fun result() {
        shoot("result-fa-noir", paper = false) { ResultScreen(result, ResultActions()) }
        shoot("result-fa-paper", paper = true) { ResultScreen(result.copy(version = 2, saved = true), ResultActions()) }
        shoot("result-fa-desktop", paper = true, width = 1280, height = 800, density = 1.5f) { ResultScreen(result, ResultActions()) }
        // With the edit held: the editor and the export screen are offered too.
        shoot("result-editor-fa-noir", paper = false, height = 1320) { ResultScreen(result, ResultActions(onExport = {}, onEdit = {})) }
        // A version the creator changed in the editor and rendered: named as theirs, editing continues from it.
        shoot("result-edited-fa-noir", paper = false, height = 1320) {
            ResultScreen(result.copy(version = 3, edited = true, styleName = "پاپ"), ResultActions(onExport = {}, onEdit = {}))
        }
    }

    @Test
    fun failures() {
        shoot("failure-voice-fa-noir", paper = false) { BuildFailureScreen(BuildFailures.of("silent", BuildStage.Listen, true), {}, {}, {}) }
        shoot("failure-cancelled-fa-paper", paper = true) { BuildFailureScreen(BuildFailures.cancelled(BuildStage.Plan, true), {}, {}, {}) }
    }

    @Test
    fun stagesFromTheEngine() {
        assertEquals(BuildStage.Listen, BuildStage.of("Transcribe", "transcribe"))
        assertEquals(BuildStage.Understand, BuildStage.of("Direct", "understand"))
        assertEquals(BuildStage.Jury, BuildStage.of("Direct", "jury"))
        assertEquals(BuildStage.Sound, BuildStage.of("Direct", "sound"))
        assertEquals(BuildStage.Render, BuildStage.of("Render", "render 120/450"))
    }

    private fun shoot(name: String, paper: Boolean, width: Int = 390, height: Int = 844, density: Float = 2f, content: @Composable () -> Unit) {
        ImageComposeScene((width * density).toInt(), (height * density).toInt(), Density(density)) {
            SignatureTheme(language = Language.Persian, paper = paper) { Box(Modifier.fillMaxSize()) { content() } }
        }.use { scene ->
            for (t in 0L..2_000L step 100L) scene.render(t * 1_000_000)
            val png = scene.render(2_500_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("build/screenshots").mkdirs()
            File("build/screenshots/$name.png").writeBytes(png)
            assertTrue(png.size > 30_000)
        }
    }
}
