package io.trimio.feature.create

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.trimio.core.data.MediaKind
import io.trimio.core.designsystem.signature.SignatureTheme
import io.trimio.core.model.input.AudioTrackInfo
import io.trimio.core.model.input.MediaInfo
import io.trimio.core.model.input.VideoFormat
import io.trimio.core.model.text.Language
import io.trimio.engine.director.BriefParser
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Renders the create screen and the style picker off-screen (Persian RTL, noir and paper, phone
 * and desktop) into build/screenshots for design review against board 2.
 */
class CreateScreenshotTest {
    private val prompt = "یک ریلز پرانرژی برای سیگنال بیت‌کوین بساز؛ هوک در ۳ ثانیهٔ اول، روی «سود» و اعداد تأکید کن، موسیقی آرام"
    private val glossary = listOf("tetherland.com", "ووچر = کد هدیه", "USDT")
    private val state = CreateState(
        kind = MediaKind.Video,
        probing = false,
        info = MediaInfo(42_000, VideoFormat(1080, 1920, 30f), AudioTrackInfo(48_000, 2)),
        prompt = prompt,
        brief = BriefParser.parse(prompt),
        glossary = glossary,
        attachments = listOf(
            Attachment("a", AttachmentKind.Logo, "logo.png", "Tetherland", caption = "لوگوی صرافی"),
            Attachment("b", AttachmentKind.Clip, "app.mp4", "app.mp4", caption = "صفحهٔ ثبت‌نام"),
        ),
        styleId = "noir",
    )

    @Test
    fun createNoir() = shoot("create-fa-noir", paper = false) { CreateScreen(state, CreateActions()) }

    @Test
    fun createPaper() = shoot("create-fa-paper", paper = true) { CreateScreen(state, CreateActions()) }

    @Test
    fun createExpertAudio() = shoot("create-fa-expert-audio", paper = false) {
        val brief = "هوک: سؤال تند در ثانیهٔ اول\nمقصد: یوتیوب شورتس\nتأکید: «ووچر» و ۳۵۰ تتر"
        CreateScreen(state.copy(kind = MediaKind.Audio, expert = true, prompt = brief, brief = BriefParser.parse(brief), attachments = emptyList(), styleId = null), CreateActions())
    }

    @Test
    fun createEmptyAndError() {
        shoot("create-fa-empty", paper = true) { CreateScreen(CreateState(MediaKind.Video, probing = false, hasMedia = false), CreateActions()) }
        shoot("create-fa-unreadable", paper = false) { CreateScreen(CreateState(MediaKind.Video, probing = false, info = null), CreateActions()) }
    }

    @Test
    fun stylePicker() {
        shoot("styles-fa-noir", paper = false) { CreateScreen(state, CreateActions(), pickerOpen = true) }
        shoot("styles-fa-paper", paper = true) { CreateScreen(state, CreateActions(), pickerOpen = true) }
    }

    @Test
    fun desktop() {
        shoot("create-fa-desktop", paper = false, width = 1280, height = 800, density = 1.5f) { CreateScreen(state, CreateActions()) }
        shoot("styles-fa-desktop", paper = true, width = 1280, height = 800, density = 1.5f) { CreateScreen(state, CreateActions(), pickerOpen = true) }
    }

    @Test
    fun enginePromptCarriesGlossaryAndCaptions() {
        val text = EnginePrompt.compose("ریلز بساز", glossary, state.attachments)
        assertEquals(
            "ریلز بساز\nواژه‌نامه (املای دقیق): tetherland.com؛ «ووچر» یعنی کد هدیه؛ «USDT»\nپیوست‌ها: لوگو Tetherland (لوگوی صرافی)؛ کلیپ app.mp4 (صفحهٔ ثبت‌نام)",
            text,
        )
        assertEquals(28, StyleCards.all.size)
    }

    private fun shoot(name: String, paper: Boolean, width: Int = 390, height: Int = 844, density: Float = 2f, content: @Composable () -> Unit) {
        ImageComposeScene((width * density).toInt(), (height * density).toInt(), Density(density)) {
            SignatureTheme(language = Language.Persian, paper = paper) { Box(Modifier.fillMaxSize()) { content() } }
        }.use { scene ->
            for (t in 0L..1_500L step 250L) scene.render(t * 1_000_000)
            val png = scene.render(2_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("build/screenshots").mkdirs()
            File("build/screenshots/$name.png").writeBytes(png)
            assertTrue(png.size > 30_000)
        }
    }
}
