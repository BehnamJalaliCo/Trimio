package io.trimio.feature.stream

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.trimio.core.designsystem.theme.TrimioTheme
import io.trimio.core.model.input.CanvasSpec
import io.trimio.core.model.input.InputSource
import io.trimio.core.model.input.MediaUri
import io.trimio.core.model.text.Language
import io.trimio.core.pipeline.JobSpec
import io.trimio.core.pipeline.JobStatus
import io.trimio.core.pipeline.PipelineEvent
import io.trimio.core.pipeline.PipelineOrchestrator
import io.trimio.core.pipeline.StageId
import io.trimio.core.pipeline.demo.DemoPipeline
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Renders the build screen off-screen with real Skia at phone size, mid-render, in Persian and English.
 * Output goes to build/screenshots for design review (and later, golden comparison).
 */
class BuildStreamScreenshotTest {

    private val out = File("build/screenshots").apply { mkdirs() }

    private suspend fun midRenderState(): BuildStreamState {
        val job = JobSpec("shot", InputSource.AudioOnly(MediaUri("demo"), 42_000, CanvasSpec()), "")
        val events = PipelineOrchestrator(DemoPipeline.stages(speed = 50f)).run(job)
            .takeWhile { event ->
                val state = (event as? PipelineEvent.StateChanged)?.state
                state == null || state.current != StageId.Render || state.stages.first { it.id == StageId.Render }.fraction < 0.45f
            }
            .toList()
        return events.fold(BuildStreamState(), BuildStreamReducer::reduce)
    }

    private fun shoot(name: String, state: BuildStreamState, language: Language) {
        // 412x915 dp is a typical modern Android phone; density 2.625 matches many FHD+ devices.
        val density = 2.625f
        ImageComposeScene(width = (412 * density).toInt(), height = (915 * density).toInt(), density = Density(density)) {
            TrimioTheme(language = language) {
                BuildStreamScreen(state, styleName = if (language == Language.Persian) "لیکوئید گلس" else "Liquid Glass", onDevice = true, onCancel = {}, onRestart = {})
            }
        }.use { scene ->
            scene.render(0)
            val image = scene.render(2_000_000_000L) // let entry animations settle
            val png = image.encodeToData(EncodedImageFormat.PNG)!!.bytes
            File(out, "$name.png").writeBytes(png)
            assertTrue(png.size > 50_000, "screenshot looks empty")
        }
    }

    @Test
    fun midRenderPersian() = runTest { shoot("build-stream-fa", midRenderState(), Language.Persian) }

    @Test
    fun midRenderEnglish() = runTest { shoot("build-stream-en", midRenderState(), Language.English) }

    @Test
    fun completedPersian() = runTest {
        val done = midRenderState().copy(status = JobStatus.Completed, overall = 1f, current = null, etaMs = 0)
        shoot("build-stream-done-fa", done, Language.Persian)
    }
}
