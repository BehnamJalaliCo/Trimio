package io.trimio.shared

import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.trimio.core.data.JobRunner
import io.trimio.core.data.Project
import io.trimio.core.data.ProjectRepository
import io.trimio.core.data.ProjectStatus
import io.trimio.core.data.SettingsRepository
import io.trimio.core.model.input.CanvasSpec
import io.trimio.core.model.input.InputSource
import io.trimio.core.model.input.MediaUri
import io.trimio.core.model.text.Language
import io.trimio.core.pipeline.JobStatus
import io.trimio.shared.di.previewPlatformModule
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.jetbrains.skia.EncodedImageFormat
import org.koin.core.context.GlobalContext
import org.koin.core.context.stopKoin
import java.io.File
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The whole app wired through DI: first run renders, a build fills the project, an export follows. */
class AppFlowTest {

    @BeforeTest
    fun start() = initTrimio(previewPlatformModule("test"))

    @AfterTest
    fun stop() = stopKoin()

    @Test
    fun firstRunShowsOnboarding() = javax.swing.SwingUtilities.invokeAndWait {
        // Navigation entries own lifecycles, which must be driven from the main (UI) thread.
        val density = 2.625f
        ImageComposeScene((412 * density).toInt(), (915 * density).toInt(), Density(density)) {
            TrimioApp(Language.Persian)
        }.use { scene ->
            // Settings load off the main thread: give real time between frames.
            for (t in 0L..3_000L step 100L) { scene.render(t * 1_000_000); Thread.sleep(15) }
            val png = scene.render(4_000_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("build/screenshots").mkdirs()
            File("build/screenshots/app-onboarding-fa.png").writeBytes(png)
            assertTrue(png.size > 50_000)
        }
    }

    @Test
    fun buildThenExportUpdatesTheProject(): Unit = runBlocking {
        val koin = GlobalContext.get()
        val projects = koin.get<ProjectRepository>()
        val runner = koin.get<JobRunner>()
        koin.get<SettingsRepository>().load()
        val project = Project("p1", "test", 1, 1, InputSource.AudioOnly(MediaUri("a"), 9_000, CanvasSpec()), "پرانرژی")
        projects.save(project)

        val build = runner.build(project)
        withTimeout(60_000) { build.state.filterNotNull().first { it.status != JobStatus.Running } }
        // The repository is updated right after the terminal state is published.
        withTimeout(5_000) { projects.projects.first { list -> list.any { it.id == "p1" && it.status == ProjectStatus.Ready } } }
        val ready = projects.get("p1")!!
        assertNotNull(ready.timeline)
        assertNotNull(ready.style)
        assertTrue(ready.transcript!!.words.isNotEmpty())

        val export = assertNotNull(runner.export(ready))
        val final = withTimeout(60_000) { export.state.filterNotNull().first { it.status != JobStatus.Running } }
        assertEquals(JobStatus.Completed, final.status)
    }
}
