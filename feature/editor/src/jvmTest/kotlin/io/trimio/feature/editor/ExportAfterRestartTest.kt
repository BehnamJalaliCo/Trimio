package io.trimio.feature.editor

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import io.trimio.core.data.SettingsRepository
import io.trimio.core.designsystem.signature.SignatureTheme
import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.asr.RecognitionOptions
import io.trimio.engine.asr.SpeechRecognizer
import io.trimio.engine.autopilot.Jury
import io.trimio.engine.production.Checkpoints
import io.trimio.engine.production.Edit
import io.trimio.engine.production.EditExports
import io.trimio.engine.production.JvmProductionPlatform
import io.trimio.engine.production.Opened
import io.trimio.engine.production.ProducedEdits
import io.trimio.engine.production.ProductionRequest
import io.trimio.engine.production.Productions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.io.files.Path
import org.jetbrains.skia.EncodedImageFormat
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import io.trimio.engine.motion.export.Delivery as Quality

/**
 * The export screen after the app restarted, on the real engine: the edit is made again from what
 * its run kept (no recognition, no director) and the screen opens on it with its poster; when the
 * creator's video is gone, the screen says exactly that. Both into build/screenshots for review.
 */
class ExportAfterRestartTest {
    private val dir = Files.createTempDirectory("export-restart").toFile()
    private val checkpoints = Checkpoints(Path(File(dir, "productions").path))
    private val said = listOf("سلام", "دوستان،", "امروز", "یک", "نکته‌ی", "مهم", "می‌گم.")

    private fun platform(recognizer: SpeechRecognizer?) = JvmProductionPlatform(
        File(dir, "renders"), recognizer = { recognizer ?: error("recognition is not run again") }, delivery = Quality.Draft.copy(workers = 1),
    )

    /** What the app holds after a restart: nothing in memory, the edit opened from what was kept. */
    private fun restarted(engine: Productions, request: ProductionRequest) = object : ProducedEdits {
        override fun find(id: String): Edit? = null
        override suspend fun open(id: String): Opened = engine.reopen(request)
    }

    @Test
    fun theExportOpensAKeptEditAfterARestartAndSaysWhenTheSourceIsGone(): Unit = runBlocking {
        val clip = clip()
        val heard = object : SpeechRecognizer {
            override suspend fun transcribe(audio: PcmAudio, options: RecognitionOptions, onProgress: (Float) -> Unit, onWord: suspend (Int, Word) -> Unit): Transcript {
                onProgress(1f)
                return Transcript(Language.Persian, said.mapIndexed { i, w -> Word(w, TimeRange(200L + i * 380L, 200L + i * 380L + 320L), language = Language.Persian) })
            }
        }
        val jury = Jury(budget = Jury.Budget(rounds = 0, variations = 0))
        val request = ProductionRequest(clip.path, prompt = "یک نکته‌ی مهم، کوتاه و پرانرژی", seed = 6)
        val made = assertNotNull(Productions(platform(heard), checkpoints, jury = jury).produce(request).toList().last().edit)

        val after = platform(null)
        val engine = Productions(after, checkpoints, jury = jury)
        val settings = SettingsRepository(null)
        val export = withContext(Dispatchers.Main) { ExportViewModel(ExportArgs("p1"), restarted(engine, request), EditExports(after), settings) }
        val ui = withTimeout(TIMEOUT) { export.state.first { !it.opening && it.poster != null } }
        assertNull(ui.missing)
        assertEquals((made.production.compiled.composition.duration * 1000).toLong(), ui.durationMs, "the same edit as before the restart")
        assertEquals(made.production.score.scenes.size.coerceAtLeast(1), ui.scenes)
        shoot("export-after-restart-fa", ui)

        // The creator deleted the video: only that is said (the run kept everything else).
        val gone = request.copy(media = File(dir, "deleted.mp4").path, id = request.key)
        val missing = withContext(Dispatchers.Main) { ExportViewModel(ExportArgs("p1"), restarted(engine, gone), EditExports(after), settings) }
        val told = withTimeout(TIMEOUT) { missing.state.first { it.missing != null } }
        assertEquals(NotOpen.SourceMissing, told.missing)
        shoot("export-source-missing-fa", told)
    }

    private fun shoot(name: String, ui: ExportUi) {
        val density = 2f
        ImageComposeScene((390 * density).toInt(), (844 * density).toInt(), Density(density)) {
            SignatureTheme(language = Language.Persian, paper = false) { Box(Modifier.fillMaxSize()) { ExportScreen(ui, ExportActions()) } }
        }.use { scene ->
            for (t in 0L..1_000L step 250L) scene.render(t * 1_000_000)
            val png = scene.render(1_500_000_000L).encodeToData(EncodedImageFormat.PNG)!!.bytes
            File("build/screenshots").mkdirs()
            File("build/screenshots/$name.png").writeBytes(png)
            assertTrue(png.size > 30_000)
        }
    }

    /** A 3-second test pattern with a voice-like tone where the stand-in recogniser hears its words. */
    private fun clip(): File = File(dir, "clip.mp4").also { file ->
        val p = ProcessBuilder(
            "ffmpeg", "-y", "-loglevel", "error",
            "-f", "lavfi", "-i", "testsrc2=size=360x640:rate=30:duration=3",
            "-f", "lavfi", "-i", "aevalsrc=0.5*sin(2*PI*180*t)*gte(t\\,0.2)*lt(t\\,2.86)*lt(mod(t-0.2\\,0.38)\\,0.32):s=48000:d=3",
            "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", file.path,
        ).redirectErrorStream(true).start()
        val log = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0 && file.length() > 0) { "ffmpeg: $log" }
    }

    private companion object {
        const val TIMEOUT = 300_000L
    }
}
