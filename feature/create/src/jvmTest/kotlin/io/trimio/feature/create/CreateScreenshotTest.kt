package io.trimio.feature.create

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.trimio.core.data.MediaKind
import io.trimio.core.designsystem.theme.TrimioTheme
import io.trimio.core.model.input.AudioTrackInfo
import io.trimio.core.model.input.MediaInfo
import io.trimio.core.model.input.VideoFormat
import io.trimio.core.model.text.Language
import io.trimio.engine.director.BriefParser
import io.trimio.engine.styles.StylePackRepository
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class CreateScreenshotTest {
    private val packs = runBlocking { StylePackRepository().all() }
    private val prompt = "یه ویدیوی پرانرژی برای ریلز بساز، سبک نئوبروتال، روی «سود» تأکید کن، بدون موزیک"
    private val state = CreateState(
        kind = MediaKind.Video,
        probing = false,
        info = MediaInfo(42_000, VideoFormat(1080, 1920, 30f), AudioTrackInfo(48_000, 2)),
        prompt = prompt,
        brief = BriefParser.parse(prompt),
        packs = packs,
    )

    @Test
    fun createPersian() = shoot("create-fa", Language.Persian) { CreateScreen(state, {}, {}, {}, {}, {}, {}, {}, {}) }

    @Test
    fun createAudioEnglish() = shoot("create-audio-en", Language.English) {
        CreateScreen(state.copy(kind = MediaKind.Audio, prompt = "", brief = BriefParser.parse(""), info = MediaInfo(60_000, null, AudioTrackInfo(44_100, 1))), {}, {}, {}, {}, {}, {}, {}, {})
    }

    private fun shoot(name: String, language: Language, content: @Composable () -> Unit) {
        val density = 2.625f
        ImageComposeScene((412 * density).toInt(), (915 * density).toInt(), Density(density)) {
            TrimioTheme(language = language) { content() }
        }.use { scene ->
            for (t in 0L..2_000L step 250L) scene.render(t * 1_000_000)
            val png = scene.render(3_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("build/screenshots").mkdirs()
            File("build/screenshots/$name.png").writeBytes(png)
            assertTrue(png.size > 30_000)
        }
    }
}
