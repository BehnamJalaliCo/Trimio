package io.trimio.shared

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.trimio.core.data.FileProjectStore
import io.trimio.core.data.Project
import io.trimio.core.data.ProjectRepository
import io.trimio.core.data.ProjectStatus
import io.trimio.core.designsystem.image.decodeImage
import io.trimio.core.designsystem.signature.SignatureTheme
import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.asr.RecognitionOptions
import io.trimio.engine.asr.SpeechRecognizer
import io.trimio.engine.autopilot.Jury
import io.trimio.engine.production.AppProductionRunner
import io.trimio.engine.production.Checkpoints
import io.trimio.engine.production.Director
import io.trimio.engine.production.EditChange
import io.trimio.engine.production.EditOverrides
import io.trimio.engine.production.JvmProductionPlatform
import io.trimio.engine.production.Opened
import io.trimio.engine.production.ProductionRequest
import io.trimio.engine.production.Productions
import io.trimio.engine.production.RecentEdits
import io.trimio.engine.production.Retouch
import io.trimio.feature.editor.EditorViewModel
import io.trimio.feature.stream.BuildResult
import io.trimio.feature.stream.ResultActions
import io.trimio.feature.stream.ResultScreen
import io.trimio.shared.navigation.ProductionProjects
import io.trimio.shared.navigation.ProductionUi
import io.trimio.shared.navigation.ProjectEdits
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.trimio.engine.motion.export.Delivery as Quality

/**
 * The editor's changes as a project's version, end to end on the real engine: rendered as the
 * next version (its file, poster and numbers on the result screen), kept with the project, and
 * after the app restarts opened again with them (no recognition, no director), the undo baseline
 * at the version and the unsaved draft one undo away.
 */
class ProjectVersionsTest {
    private val dir = Files.createTempDirectory("trimio-versions").toFile()
    private val scopes = mutableListOf<CoroutineScope>()

    @AfterTest
    fun stop() {
        scopes.forEach { it.cancel() }
        dir.deleteRecursively()
    }

    /** One process of the app: its stores over [dir], the engine, the runner, the edits and the projects. */
    private inner class App(recognizer: SpeechRecognizer?) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default).also { scopes += it }
        val repository = ProjectRepository(FileProjectStore(Path(dir.path, "projects")))
        val checkpoints = Checkpoints(Path(dir.path, "productions"))
        val editor = Productions(
            JvmProductionPlatform(
                File(dir, "renders"),
                recognizer = { recognizer ?: error("recognition is not run again") },
                directors = { if (recognizer == null) error("the director is not asked again") else Director.None },
                delivery = Quality.Draft.copy(workers = 1),
            ),
            checkpoints,
            jury = Jury(budget = Jury.Budget(rounds = 0, variations = 0)),
        )
        val runner = AppProductionRunner(editor, scope, checkpoints)
        val recent = RecentEdits(runner, scope)
        val edits = ProjectEdits(recent, repository, editor)
        val projects = ProductionProjects(repository, runner, checkpoints, scope, Path(dir.path, "projects"), edits)

        suspend fun until(id: String, test: (Project) -> Boolean): Project =
            withTimeout(TIMEOUT) { repository.projects.first { list -> list.any { it.id == id && test(it) } } }.first { it.id == id }
    }

    @Test
    fun anEditorVersionIsKeptAndOpensAgainAfterARestart(): Unit = runBlocking {
        // Before: a production, then the creator's changes rendered as its next version.
        val first = App(heard)
        first.repository.load()
        first.projects.start()
        val request = ProductionRequest(clip().path, prompt = "سیگنال امروز بیت‌کوین", seed = 4)
        val id = first.projects.create(request, "سیگنال امروز", null, null)
        val made = first.until(id) { it.status == ProjectStatus.Ready }
        val director = assertIs<Opened.Ready>(first.edits.open(id)).edit
        assertTrue(director.overrides.isEmpty)

        val cut = director.base.bench!!.transcript.words.indexOfFirst { it.text.startsWith("رشد") }
        val k = director.base.score.scenes.indexOfFirst { it.beats.isNotEmpty() }
        val changes = EditOverrides().with(EditChange.Delete(cut, cut, true)).with(EditChange.Element(Retouch.key(k, 0), false)).with(EditChange.Style("pop"))
        assertNotNull(first.projects.retouch(made, director, changes))
        val edited = first.until(id) { it.status == ProjectStatus.Ready && it.production?.version == 2 }
        val record = assertNotNull(edited.production)
        assertEquals(changes, EditOverrides.decode(record.overrides), "the version keeps the creator's changes")
        assertNull(record.draft)
        assertEquals("pop", record.styleId)
        val file = File(assertNotNull(edited.outputUri))
        assertTrue(file.name.endsWith("-e${changes.fingerprint}.mp4") && file.length() > 10_000, "the version's own file: $file")
        assertTrue(File(assertNotNull(record.poster)).length() > 0)
        assertTrue(made.outputUri!! in record.files && file.path in record.files, "every version's file is kept")
        // The result screen shows this version: its file, marked as the creator's edit.
        val result = assertNotNull(ProductionUi.result(edited, null, persian = true, saved = false))
        assertTrue(result.edited)
        assertEquals(2, result.version)
        shoot("result-edited-version-fa", result.copy(poster = decodeImage(File(record.poster!!).readBytes())))
        val current = assertNotNull(first.edits.find(id), "the version is held as the project's edit")
        assertEquals(changes, current.overrides)

        // After a restart: nothing held; the edit is made again from what was kept.
        val second = App(recognizer = null)
        second.repository.load()
        second.projects.start()
        assertNull(second.edits.find(id))
        assertTrue(second.edits.openable(id))
        val again = assertIs<Opened.Ready>(second.edits.open(id)).edit
        assertEquals(changes, again.overrides)
        assertEquals(file.path, again.output)
        assertEquals(current.production.compiled.beats, again.production.compiled.beats, "the same edit as before the restart")
        assertTrue(current.production.soundtrack.audio.samples.contentEquals(again.production.soundtrack.audio.samples))

        // The editor opens at the version (nothing to undo); unsaved changes from before come back one undo away.
        val draft = changes.with(EditChange.Hit(cut - 1, true))
        second.projects.keep(id, draft)
        second.until(id) { it.production?.draft != null }
        val editor = withContextMain { EditorViewModel(id, second.edits, second.editor, second.projects) }
        val opened = withTimeout(TIMEOUT) { editor.state.first { it is EditorViewModel.State.Open && !it.ui.working } } as EditorViewModel.State.Open
        assertTrue(opened.ui.unsaved, "the draft is back")
        assertTrue(opened.ui.canUndo)
        withContextMain { editor.undo() }
        val back = withTimeout(TIMEOUT) { editor.state.first { it is EditorViewModel.State.Open && !it.ui.unsaved } } as EditorViewModel.State.Open
        assertFalse(back.ui.canUndo, "undo stops at the version as it was made")
        assertNull(withContextMain { editor.leave() }, "nothing to render")
        second.until(id) { it.production?.draft == null }
        withContextMain { editor.redo() }
        assertEquals(draft, withContextMain { editor.leave() }, "leaving with changes hands them over to be rendered")
    }

    private suspend fun <T> withContextMain(block: () -> T): T = kotlinx.coroutines.withContext(Dispatchers.Main) { block() }

    /** The result screen as the creator sees this version, into build/screenshots for review. */
    private fun shoot(name: String, result: BuildResult) {
        val density = 2f
        ImageComposeScene((390 * density).toInt(), (1320 * density).toInt(), Density(density)) {
            SignatureTheme(language = Language.Persian, paper = false) {
                Box(Modifier.fillMaxSize()) { ResultScreen(result, ResultActions(onExport = {}, onEdit = {})) }
            }
        }.use { scene ->
            for (t in 0L..2_000L step 100L) scene.render(t * 1_000_000)
            val png = scene.render(2_500_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("build/screenshots").mkdirs()
            File("build/screenshots/$name.png").writeBytes(png)
            assertTrue(png.size > 30_000)
        }
    }

    /** A test pattern with a voice-like tone where the stand-in recogniser hears its words. */
    private fun clip(): File = File(dir, "clip.mp4").also { file ->
        val p = ProcessBuilder(
            "ffmpeg", "-y", "-loglevel", "error",
            "-f", "lavfi", "-i", "testsrc2=size=360x640:rate=30:duration=4.6",
            "-f", "lavfi", "-i", "aevalsrc=0.5*sin(2*PI*190*t)*gte(t\\,0.25)*lt(t\\,4.3)*lt(mod(t-0.25\\,0.4)\\,0.34):s=48000:d=4.6",
            "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", file.path,
        ).redirectErrorStream(true).start()
        val log = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0 && file.length() > 0) { "ffmpeg: $log" }
    }

    private companion object {
        const val TIMEOUT = 600_000L
        val said = listOf("سلام", "امروز", "بیت‌کوین", "پنج", "درصد", "رشد", "کرد", "و", "سیگنال", "رسید.")

        /** The words whisper would hear in [clip]. */
        val heard = object : SpeechRecognizer {
            override suspend fun transcribe(audio: PcmAudio, options: RecognitionOptions, onProgress: (Float) -> Unit, onWord: suspend (Int, Word) -> Unit): Transcript {
                onProgress(1f)
                return Transcript(Language.Persian, said.mapIndexed { i, w -> Word(w, TimeRange(250L + i * 400L, 250L + i * 400L + 340L), language = Language.Persian) })
            }
        }
    }
}
