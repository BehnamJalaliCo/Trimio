package io.trimio.feature.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.use
import io.trimio.core.designsystem.signature.FootagePlaceholder
import io.trimio.core.designsystem.signature.MicroLabel
import io.trimio.core.designsystem.signature.Sig
import io.trimio.core.designsystem.signature.SigNoir
import io.trimio.core.designsystem.signature.SignatureTheme
import io.trimio.core.model.text.Language
import io.trimio.engine.production.CaptionDensity
import io.trimio.engine.production.FactElement
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The editor's five tools off-screen against board 3 (docs/design/screens/3-editor.png): Persian
 * and English phones, and the wide desktop window, into build/screenshots for review.
 */
class EditorScreenshotTest {

    private fun wave(n: Int, hi: Float = 1f) = (0 until n).map { i -> (abs(sin(i * 0.6) * cos(i * 0.21)).toFloat() * hi).coerceIn(0.05f, 1f) }

    private val chips = listOf(
        WordChip(0, "امروز", cut = false, emphasised = false, edited = false, hit = false),
        WordChip(1, "بیت‌کوین", cut = false, emphasised = true, edited = false, hit = false),
        WordChip(2, "پنج درصد", cut = false, emphasised = true, edited = false, hit = false, last = 3),
        WordChip(4, "رشد کرد", cut = false, emphasised = false, edited = false, hit = false, last = 5),
        WordChip(6, "و خب", cut = true, emphasised = false, edited = false, hit = false, last = 7),
        WordChip(8, "سیگنال", cut = false, emphasised = false, edited = false, hit = false),
    )

    private val ui = EditorUi(
        tool = EditorTool.Text, position = 12.2f, duration = 42f, canUndo = true,
        text = TextTool(
            chips = chips, selection = 2..3, quote = "پنج درصد", spanStart = 11.8f, spanEnd = 12.6f, windowStart = 10f, windowEnd = 15.2f,
            segments = listOf(Segment(10f, 10.9f, false), Segment(10.9f, 11.8f, false), Segment(11.8f, 12.6f, true), Segment(12.6f, 13.6f, false), Segment(14.2f, 15f, false)),
            peaks = wave(66), emphasised = true, hasNumber = true,
        ),
        style = StyleTool(listOf("noir", "kinetic-poster", "lumen", "bold-brutalist", "neon-cyber", "paper"), "noir", "noir", 80, CaptionDensity.Phrase),
        sound = SoundTool(
            moods = listOf(MoodRow("energetic", "پالس شب", "Night pulse", 122), MoodRow("chill", "مه صبح", "Morning mist", 84), MoodRow("cinematic", "سینمایی", "Cinematic", 92)),
            selected = "energetic", director = "energetic", musicDb = -18f, effects = true, effectCount = 12, lufs = -14f,
            peaks = wave(66), cues = listOf(0.34f, 0.37f, 0.4f, 0.43f, 0.46f),
        ),
        elements = ElementsTool(
            rows = listOf(
                ElementRow("b0.0", FactElement.Kind.Number, "۵٪", 11f, "پنج درصد", on = true, suggested = false),
                ElementRow("b1.0", FactElement.Kind.Brand, "بیت‌کوین", 4f, "بیت‌کوین", on = true, suggested = false),
                ElementRow("b2.0", FactElement.Kind.Chart, "قیمت", 18f, "قیمت", on = true, suggested = false),
                ElementRow("d0", FactElement.Kind.Icon, "فلش صعودی", 29f, "تارگت", on = false, suggested = true),
            ),
            suggestions = 5, more = true,
        ),
        cut = CutTool(
            sourceDuration = 42f, duration = 36f, peaks = wave(66, 1f), removed = listOf(0.18f..0.27f, 0.46f..0.52f, 0.71f..0.82f),
            silences = true, pauses = 7, pauseSeconds = 4.1f, fillers = true, fillerCount = 5, fillerWords = listOf("خب", "یعنی", "اممم"), beatCuts = false,
        ),
    )

    /** The board's still: warm footage, the price tag and «پنج درصد» on the lime block. */
    private val frame: ImageBitmap by lazy {
        ImageComposeScene(540, 960, Density(1.4f)) {
            SignatureTheme(language = Language.Persian) {
                Box(Modifier.fillMaxSize()) {
                    FootagePlaceholder(Modifier.fillMaxSize())
                    Column(Modifier.align(Alignment.TopStart).padding(top = 150.dp, start = 26.dp), horizontalAlignment = Alignment.Start) {
                        MicroLabel("BTC / USDT", color = SigNoir.emphasis)
                        Text(
                            buildAnnotatedString {
                                append("⁦+5.2")
                                withStyle(SpanStyle(color = SigNoir.action)) { append("%") }
                                append("⁩")
                            },
                            style = Sig.type.figure.copy(fontSize = 52.sp, lineHeight = 56.sp), color = SigNoir.ink,
                        )
                    }
                    Column(Modifier.align(Alignment.TopStart).padding(top = 268.dp, start = 26.dp), horizontalAlignment = Alignment.Start) {
                        Text("پنج", style = Sig.type.hero.copy(fontSize = 44.sp, lineHeight = 50.sp), color = SigNoir.ink)
                        Box(Modifier.clip(RoundedCornerShape(5.dp)).background(SigNoir.emphasis).padding(horizontal = 8.dp)) {
                            Text("درصد", style = Sig.type.hero.copy(fontSize = 42.sp, lineHeight = 52.sp), color = SigNoir.onEmphasis)
                        }
                    }
                }
            }
        }.use { it.render().toComposeImageBitmap() }
    }

    @Test
    fun fivePhoneTools() {
        EditorTool.entries.forEach { tool ->
            shoot("editor-${tool.name.lowercase()}-fa", Language.Persian) { EditorScreen(ui.copy(tool = tool), EditorActions(), frame) }
        }
        shoot("editor-text-en", Language.English) { EditorScreen(ui.copy(tool = EditorTool.Text), EditorActions(), frame) }
        shoot("editor-sound-en", Language.English) { EditorScreen(ui.copy(tool = EditorTool.Sound), EditorActions(), frame) }
        shoot("editor-folded-fa", Language.Persian) { EditorScreen(ui.copy(folded = true, playing = true), EditorActions(), frame) }
    }

    /** An explainer film: its palette and sound only; a YouTube video: its words (no cut or hit), look and devices. */
    @Test
    fun aFilmAndAYouTubeVideo() {
        val palettes = listOf("paper-warm", "ink-night", "chalk", "mint", "dusk", "mono", "sand", "news-gold")
        val film = ui.copy(
            tool = EditorTool.Style, tools = listOf(EditorTool.Style, EditorTool.Sound),
            style = StyleTool(palettes, "chalk", "paper-warm", palettes = true),
        )
        shoot("editor-film-palette-fa", Language.Persian) { EditorScreen(film, EditorActions(), frame) }
        shoot("editor-film-palette-wide-en", Language.English, width = 1280, height = 800, density = 1.5f) { EditorScreen(film, EditorActions(), frame) }
        shoot("editor-film-sound-fa", Language.Persian) { EditorScreen(film.copy(tool = EditorTool.Sound), EditorActions(), frame) }
        val youtube = ui.copy(tools = listOf(EditorTool.Text, EditorTool.Style, EditorTool.Elements), text = ui.text.copy(cuts = false))
        shoot("editor-youtube-text-fa", Language.Persian) { EditorScreen(youtube, EditorActions(), frame) }
    }

    @Test
    fun wideWindow() {
        EditorTool.entries.forEach { tool ->
            shoot("editor-${tool.name.lowercase()}-wide-fa", Language.Persian, width = 1280, height = 800, density = 1.5f) { EditorScreen(ui.copy(tool = tool), EditorActions(), frame) }
        }
        shoot("editor-cut-wide-en", Language.English, width = 1280, height = 800, density = 1.5f) { EditorScreen(ui.copy(tool = EditorTool.Cut), EditorActions(), frame) }
    }

    @Test
    fun chipsGroupThePickAndTheCuts() {
        val words = (0..5).map { WordChip(it, "w$it", cut = it in 4..5, emphasised = false, edited = false, hit = false) }
        val grouped = EditorUi.chips(words, 1..2)
        assertEquals(listOf("w0", "w1 w2", "w3", "w4 w5"), grouped.map { it.text })
        assertEquals(2, grouped[1].last)
    }

    @Test
    fun timecodes() {
        assertEquals("00:42", clock(42.4f))
        assertEquals("01:05", clock(65f))
        assertEquals("00:11.8", clockTenths(11.8f))
    }

    private fun shoot(name: String, language: Language, width: Int = 390, height: Int = 844, density: Float = 2f, content: @Composable () -> Unit) {
        ImageComposeScene((width * density).toInt(), (height * density).toInt(), Density(density)) {
            SignatureTheme(language = language) { Box(Modifier.fillMaxSize()) { content() } }
        }.use { scene ->
            for (t in 0L..1_500L step 100L) scene.render(t * 1_000_000)
            val png = scene.render(2_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("build/screenshots").mkdirs()
            File("build/screenshots/$name.png").writeBytes(png)
            assertTrue(png.size > 30_000)
        }
    }
}
