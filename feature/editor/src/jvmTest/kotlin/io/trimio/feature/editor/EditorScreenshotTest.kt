package io.trimio.feature.editor

import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.trimio.core.data.Project
import io.trimio.core.data.ProjectStatus
import io.trimio.core.designsystem.theme.TrimioTheme
import io.trimio.core.model.input.InputSource
import io.trimio.core.model.input.MediaUri
import io.trimio.core.model.input.VideoFormat
import io.trimio.core.model.text.Language
import io.trimio.core.model.timeline.CaptionClip
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.render.PreviewClock
import io.trimio.engine.render.SampleTimelines
import io.trimio.engine.styles.StylePackRepository
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertTrue

class EditorScreenshotTest {
    private val packs = runBlocking { StylePackRepository().all() }
    private val timeline = SampleTimelines.cryptoSignal()
    private val transcript = Transcript(Language.Persian, timeline.clipsOf<CaptionClip>().map { Word(it.text, it.range, language = it.language, emphasis = it.emphasis) })
    private val project = Project(
        "p", "سیگنال امروز بیت\u200Cکوین", 0, 0, InputSource.Video(MediaUri("v"), 9_000, VideoFormat(1080, 1920, 30f)), "پرانرژی", "liquid-glass",
        status = ProjectStatus.Ready, transcript = transcript, timeline = timeline, style = packs.first { it.id == "liquid-glass" }.spec,
        waveform = List(180) { i -> (0.35f + 0.35f * sin(i * 0.37f) * sin(i * 0.11f)).coerceIn(0f, 1f) },
    )
    private val state = EditorState(project, timeline, project.style, packs, selectedWord = 3)

    @Test
    fun wordsTabPersian() = shoot("editor-words-fa", Language.Persian) { screen(state) }

    @Test
    fun styleTabEnglish() = shoot("editor-style-en", Language.English) { screen(state.copy(tab = EditorTab.Style, selectedWord = null)) }

    @Test
    fun soundTabTablet() = shoot("editor-sound-tablet-fa", Language.Persian, widthDp = 1000, heightDp = 700) { screen(state.copy(tab = EditorTab.Sound, selectedWord = null)) }

    @Composable
    private fun screen(s: EditorState) = EditorScreen(s, PreviewClock(2_900).also { it.playing = false }, {}, {}, {}, {}, {}, {}, { _, _ -> }, {}, {}, {}, {}, {})

    private fun shoot(name: String, language: Language, widthDp: Int = 412, heightDp: Int = 915, content: @Composable () -> Unit) {
        val density = 2.625f
        ImageComposeScene((widthDp * density).toInt(), (heightDp * density).toInt(), Density(density)) {
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
