package io.trimio.shared

import androidx.compose.ui.graphics.ImageBitmap
import io.trimio.core.data.FileProjectStore
import io.trimio.core.data.ProjectRepository
import io.trimio.core.data.ProjectStatus
import io.trimio.engine.production.Checkpoints
import io.trimio.engine.production.Edit
import io.trimio.engine.production.ProductionRequest
import io.trimio.engine.production.ProductionRunner
import io.trimio.engine.production.ProductionState
import io.trimio.engine.production.ProductionStatus
import io.trimio.engine.production.SourceKind
import io.trimio.shared.navigation.ProductionProjects
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Every production becomes a project: saved as it starts, followed to its file, kept across restarts, deleted with its files. */
class ProductionProjectsTest {
    private val dir = Files.createTempDirectory("trimio-productions").toFile()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** A runner the test drives by hand. */
    private class FakeRunner : ProductionRunner {
        val state = MutableStateFlow<ProductionState?>(null)
        val started = mutableListOf<ProductionRequest>()
        override val active: StateFlow<ProductionState?> = state
        override fun start(request: ProductionRequest) {
            started += request
            state.value = ProductionState(request.key)
        }
        override fun vary(edit: Edit, seed: Long) = Unit
        override fun revise(edit: Edit, feedback: String) = Unit
        override fun cancel() {
            state.value = state.value?.copy(status = ProductionStatus.Cancelled)
        }
    }

    @AfterTest
    fun stop() {
        scope.cancel()
        dir.deleteRecursively()
    }

    private fun repository() = ProjectRepository(FileProjectStore(Path(dir.path, "projects")))

    @Test
    fun aRunBecomesAProjectWithItsFileAndPoster(): Unit = runBlocking {
        val repo = repository()
        val runner = FakeRunner()
        val checkpoints = Checkpoints(Path(dir.path, "productions"))
        val productions = ProductionProjects(repo, runner, checkpoints, scope, Path(dir.path, "projects"))
        productions.start()
        val request = ProductionRequest(media = "/clip.mp4", kind = SourceKind.Audio, prompt = "یک پادکست با زیرنویس کینتیک بساز؛ روی اعداد تأکید کن", seed = 7)
        val id = productions.create(request, ProductionProjects.titleOf(request.prompt, audioOnly = true, persian = true), null, "kinetic-poster")

        val building = withTimeout(5_000) { repo.projects.first { list -> list.any { it.id == id } } }.first { it.id == id }
        assertEquals(ProjectStatus.Building, building.status)
        assertEquals("یک پادکست با زیرنویس کینتیک…", building.title)
        assertEquals(request.key, building.production?.key)

        runner.state.value = ProductionState(request.key, progress = 0.5f)
        withTimeout(5_000) { repo.projects.first { list -> list.any { it.id == id && (it.production?.progress ?: 0f) >= 0.5f } } }

        val video = File(dir, "renders/${request.key}.mp4").apply { parentFile.mkdirs(); writeText("mp4") }
        runner.state.value = ProductionState(request.key, ProductionStatus.Done, progress = 1f, output = video.path, previews = listOf(ImageBitmap(27, 48)))
        val done = withTimeout(5_000) { repo.projects.first { list -> list.any { it.id == id && it.status == ProjectStatus.Ready } } }.first { it.id == id }
        val record = assertNotNull(done.production)
        assertEquals(video.path, done.outputUri)
        assertEquals(1, record.version)
        assertFalse(record.seen)
        val poster = assertNotNull(record.poster)
        assertTrue(File(poster).length() > 0)

        // After a restart the project is still there, with everything the result screen needs.
        val reopened = repository().apply { load() }.get(id)
        assertEquals(done, reopened)
        assertEquals(request, ProductionProjects.decode(assertNotNull(reopened?.production).request))

        productions.delete(done)
        withTimeout(5_000) { repo.projects.first { list -> list.none { it.id == id } } }
        assertFalse(video.exists())
        assertFalse(File(poster).exists())
    }

    @Test
    fun aRunReplacedOrLostIsKeptAsInterruptedAndRetries(): Unit = runBlocking {
        val repo = repository()
        val runner = FakeRunner()
        val productions = ProductionProjects(repo, runner, Checkpoints.None, scope, null)
        productions.start()
        val first = ProductionRequest(media = "/a.mp4", prompt = "اول", seed = 1)
        val second = ProductionRequest(media = "/b.mp4", prompt = "دوم", seed = 2)
        val a = productions.create(first, "اول", null, null)
        withTimeout(5_000) { repo.projects.first { list -> list.any { it.id == a } } }
        val b = productions.create(second, "دوم", null, null)
        // Starting the second run stops the first: it is kept, marked as interrupted.
        val stopped = withTimeout(5_000) { repo.projects.first { list -> list.any { it.id == a && it.status == ProjectStatus.Failed } } }.first { it.id == a }
        assertEquals(ProductionProjects.INTERRUPTED, stopped.error)
        withTimeout(5_000) { repo.projects.first { list -> list.any { it.id == b && it.status == ProjectStatus.Building } } }

        runner.state.value = ProductionState(second.key, ProductionStatus.Failed, error = "no speech model")
        val failed = withTimeout(5_000) { repo.projects.first { list -> list.any { it.id == b && it.status == ProjectStatus.Failed } } }.first { it.id == b }
        assertEquals("no speech model", failed.error)
        assertNull(failed.outputUri)

        assertEquals(second, productions.retry(failed))
        assertEquals(second, runner.started.last())
        withTimeout(5_000) { repo.projects.first { list -> list.any { it.id == b && it.status == ProjectStatus.Building && it.error == null } } }
    }
}
