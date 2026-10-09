package io.trimio.feature.studio

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.trimio.core.data.Project
import io.trimio.core.data.ProjectStatus
import io.trimio.core.designsystem.theme.TrimioTheme
import io.trimio.core.model.input.CanvasSpec
import io.trimio.core.model.input.InputSource
import io.trimio.core.model.input.MediaUri
import io.trimio.core.model.input.VideoFormat
import io.trimio.core.model.text.Language
import io.trimio.engine.render.SampleTimelines
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

class StudioScreenshotTest {
    private val now = 1_800_000_000_000L
    private val sample = SampleTimelines.cryptoSignal()
    private val projects = listOf(
        Project("a", "سیگنال امروز بیت\u200Cکوین", now - 3_600_000, now - 120_000, InputSource.Video(MediaUri("a"), 9_000, VideoFormat(1080, 1920, 30f)), "پرانرژی", "liquid-glass", status = ProjectStatus.Ready, timeline = sample, style = SampleTimelines.previewStyle),
        Project("b", "پادکست هفتگی", now - 7_200_000, now - 7_200_000, InputSource.AudioOnly(MediaUri("b"), 60_000, CanvasSpec()), "آرام", "kinetic-typography", status = ProjectStatus.Building),
        Project("c", "آموزش فارکس", now - 90_000_000, now - 90_000_000, InputSource.Video(MediaUri("c"), 60_000, VideoFormat(1920, 1080, 30f)), "", "neobrutalism", status = ProjectStatus.Ready, timeline = sample.copy(styleId = "liquid-glass"), style = SampleTimelines.previewStyle),
    )

    @Test
    fun studioPersian() = shoot("studio-fa", Language.Persian) { StudioScreen(projects, now, {}, {}, {}, {}) }

    /** Accessibility: the largest common font scale must not break the layout. */
    @Test
    fun studioLargeFontPersian() = shoot("studio-font160-fa", Language.Persian, fontScale = 1.6f) { StudioScreen(projects, now, {}, {}, {}, {}) }

    @Test
    fun studioEmptyEnglish() = shoot("studio-empty-en", Language.English) { StudioScreen(emptyList(), now, {}, {}, {}, {}) }

    private fun shoot(name: String, language: Language, fontScale: Float = 1f, content: @Composable () -> Unit) {
        val density = 2.625f
        ImageComposeScene((412 * density).toInt(), (915 * density).toInt(), Density(density, fontScale)) {
            TrimioTheme(language = language) { content() }
        }.use { scene ->
            // Several frames: effects launch on the first, entry springs start on the second.
            for (t in 0L..2_000L step 250L) scene.render(t * 1_000_000)
            val image = scene.render(3_000_000_000L)
            val png = image.encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("build/screenshots").mkdirs()
            File("build/screenshots/$name.png").writeBytes(png)
            assertTrue(png.size > 30_000)
        }
    }
}
