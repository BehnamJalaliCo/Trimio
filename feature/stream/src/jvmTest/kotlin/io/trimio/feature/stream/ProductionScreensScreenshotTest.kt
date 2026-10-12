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

    /** A run's technical report as the app builds it (a 12 GB phone, the 9B director on the Adreno GPU, one death). */
    private val technical = TechnicalDetails(
        runId = "p3f9a21c-7",
        status = "در حال ساخت · تلاش ۲",
        running = true,
        summary = listOf(
            TechFact("دستگاه", "samsung SM-S948B · Qualcomm SM8850"),
            TechFact("سیستم", "Android 16 (API 36) · 11 433 MB total"),
            TechFact("نسخه", "Trimio 1.0.14 (114) · Android 16"),
            TechFact("کارگردان", "qwen3.5-9b-ud-q3kxl (on device)"),
            TechFact("گفتار", "whisper-large-v3-turbo-q5"),
            TechFact("موتور مدل", "opencl · QUALCOMM Adreno(TM) 830 · 33/33 layers on the GPU · 6 threads · KV q8_0"),
        ),
        stages = listOf(
            TechStage("آماده‌سازی", 1.2f), TechStage("شنیدن (whisper)", 96.4f), TechStage("بارگذاری کارگردان", 14.8f),
            TechStage("تحلیل گفتار", 0.6f), TechStage("فهم", null, "18/48"),
        ),
        metrics = listOf(
            TechFact("مدل زبانی", "7.9 tok/s · read 61.2 tok/s · 6 calls · 1 840 tokens out · 233.0 s"),
            TechFact("شنیدن", "RTF 0.81 · 118.0 s sound in 96.4 s · 6 threads"),
            TechFact("حافظه", "peak 7 912 MB · now 7 204 MB"),
        ),
        jury = listOf(TechSitting(1, 7f, 9.1f, listOf("spelling major at 3.2s: «بیتکوین» → «بیت‌کوین»", "layout minor at 8.0s: headline over the face → move up"))),
        problems = listOf("برنامه در «llm understand 12/48» بسته شد (تلاش ۱)", "۶ دقیقه بدون پیشرفت در «understand 12/48» → صبر کرد"),
        text = (1..60).joinToString("\n") { if (it == 1) "Trimio technical report" else "  line $it · understand 12/48 qwen3.5-9b · prompt 1820 tok · out 412 tok · 52.1 s · 7.9 tok/s · ok" },
        fileName = "trimio-report-p3f9a21c-7.txt",
    )

    @Test
    fun technicalDetails() {
        shoot("technical-fa-noir", paper = false, height = 1500) { TechnicalDetailsScreen(technical, {}, {}) }
        shoot("technical-fa-paper", paper = true) { TechnicalDetailsScreen(technical.copy(running = false, status = "ساخته شد · تلاش ۲", problems = emptyList()), {}, {}) }
        shoot("technical-fa-desktop", paper = false, width = 1280, height = 800, density = 1.5f) { TechnicalDetailsScreen(technical, {}, {}) }
        // The watchdog's card on the live build, and the details one tap away.
        shoot("live-stalled-fa-noir", paper = false, height = 1100) {
            LiveBuildScreen(live.copy(stage = BuildStage.Understand, framesTotal = 0, linesDone = 18, linesTotal = 48, stall = BuildStall(6, "understand 18/48", BuildStage.Understand)), onDetails = {}) {}
        }
        // The app ended twice in the same step: an honest failure with lighter ways on.
        shoot("failure-died-fa-noir", paper = false) { BuildFailureScreen(BuildFailures.of("died-in:llm understand 12/48", BuildStage.Plan, true), {}, {}, {}, onDetails = {}) }
        shoot("result-details-fa-noir", paper = false, height = 1400) { ResultScreen(result, ResultActions(onExport = {}, onEdit = {}, onDetails = {})) }
        assertEquals(BuildStage.Understand, BuildFailures.stageOf("llm understand 12/48"))
        assertEquals(BuildStage.Listen, BuildFailures.stageOf("transcribe"))
        assertEquals(BuildStage.Render, BuildFailures.stageOf("after render"))
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
