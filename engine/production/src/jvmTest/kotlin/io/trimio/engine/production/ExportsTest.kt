package io.trimio.engine.production

import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.asr.RecognitionOptions
import io.trimio.engine.asr.SpeechRecognizer
import io.trimio.engine.autopilot.Jury
import io.trimio.engine.motion.export.Delivery
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.w3c.dom.Element
import java.io.File
import java.nio.file.Files
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Exports of a finished edit on the desktop platform (ffmpeg + Skia), on a synthetic 3-second
 * clip (a test pattern and a tone; no one's footage): every destination frame is laid out again
 * by the compiler and the file has the asked size and rate; the graphics layer has alpha; the
 * package for other editors parses and lines up with the cut.
 */
class ExportsTest {
    private val dir = Files.createTempDirectory("exports-test").toFile()
    private val words = listOf("سلام", "دوستان،", "امروز", "یک", "نکته‌ی", "مهم", "می‌گم.")
    private val recognizer = object : SpeechRecognizer {
        override suspend fun transcribe(audio: PcmAudio, options: RecognitionOptions, onProgress: (Float) -> Unit, onWord: suspend (Int, Word) -> Unit): Transcript {
            onProgress(1f)
            return Transcript(Language.Persian, words.mapIndexed { i, w -> Word(w, TimeRange(200L + i * 380L, 200L + i * 380L + 320L), language = Language.Persian) })
        }
    }
    private val platform = JvmProductionPlatform(File(dir, "renders"), recognizer = { recognizer }, delivery = Delivery.Draft.copy(workers = 1))
    private val exports = EditExports(platform)

    private lateinit var edit: Edit

    @BeforeTest
    fun produce() = runBlocking {
        made?.let { edit = it; return@runBlocking }
        val clip = File(dir, "clip.mp4")
        ffmpeg(
            "-f", "lavfi", "-i", "testsrc2=size=360x640:rate=30:duration=3", "-f", "lavfi",
            // A voice-like tone: bursts where the stand-in recogniser hears words, silence between them.
            "-i", "aevalsrc=0.5*sin(2*PI*180*t)*gte(t\\,0.2)*lt(t\\,2.86)*lt(mod(t-0.2\\,0.38)\\,0.32):s=48000:d=3",
            "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", clip.path,
        )
        val editor = Productions(platform, jury = Jury(budget = Jury.Budget(rounds = 0, variations = 0)))
        val last = editor.produce(ProductionRequest(clip.path, prompt = "یک ویدیوی کوتاه و پرانرژی", seed = 5)).toList().last()
        edit = assertNotNull(last.edit, "failed: ${last.error}")
        assertTrue(edit.production.compiled.composition.duration > 2f, "a real edit of the words: ${edit.production.compiled.composition.duration}s")
        made = edit
    }

    private companion object {
        /** One production for every test: the exports are what is under test. */
        var made: Edit? = null
    }

    @Test
    fun everyDestinationIsLaidOutAgainAtItsSizeAndRate() = runBlocking {
        val asked = listOf(ExportSpec("9:16", 720, 24), ExportSpec("16:9", 720, 30), ExportSpec("1:1", 720, 60), ExportSpec("9:16", 1440, 24))
        for (spec in asked) {
            val out = File(dir, "out-${spec.format.replace(':', 'x')}-${spec.shortEdge}-${spec.fps}.mp4")
            var last = 0f
            val file = File(exports.video(edit, spec, out.path) { last = it })
            assertEquals(1f, last, "progress reaches the end for $spec")
            val probe = probe(file, "v:0", "width,height,r_frame_rate,codec_name")
            assertEquals(spec.size.first.toString(), probe["width"], "width of $spec")
            assertEquals(spec.size.second.toString(), probe["height"], "height of $spec")
            assertEquals("${spec.fps}/1", probe["r_frame_rate"], "rate of $spec")
            assertEquals("h264", probe["codec_name"])
            assertEquals("aac", probe(file, "a:0", "codec_name")["codec_name"], "the mastered sound rides along")
            // Same length whatever the frame: the cut and the sound are the edit's own.
            val seconds = probeDuration(file)
            assertTrue(kotlin.math.abs(seconds - edit.production.compiled.composition.duration) < 0.2f, "duration $seconds for $spec")
        }
        // The frame changes the layout, not just the canvas: a landscape edit places its graphics anew.
        val wide = assertNotNull(edit.production.relaid("16:9", 30))
        assertEquals(1920 to 1080, wide.composition.width to wide.composition.height)
        assertEquals(edit.production.compiled.beats.size, wide.beats.size)
    }

    @Test
    fun theSpecTheEditWasMadeAtReusesItsFile() = runBlocking {
        val c = edit.production.compiled.composition
        assertEquals(edit.output, exports.video(edit, ExportSpec("9:16", 1080, c.fps), File(dir, "same.mp4").path))
    }

    @Test
    fun projectPackageForOtherEditors() = runBlocking {
        val spec = ExportSpec("9:16", 720, 30)
        val pkg = exports.project(edit, spec, File(dir, "project").path, "test-project")
        val names = pkg.files.map { File(it).name }
        assertEquals(listOf("test-project.fcpxml", "test-project.edl", "test-project.srt", "test-project-graphics.mov", "test-project-mix.wav"), names)
        pkg.files.forEach { assertTrue(File(it).length() > 0, "$it written") }

        // The graphics layer: ProRes 4444 with alpha, at the asked size and rate, transparent where the footage goes.
        val mov = File(pkg.files[3])
        val v = probe(mov, "v:0", "codec_name,pix_fmt,width,height,r_frame_rate")
        assertEquals("prores", v["codec_name"])
        assertTrue(v["pix_fmt"].orEmpty().startsWith("yuva"), "alpha channel: ${v["pix_fmt"]}")
        assertEquals("720", v["width"])
        assertEquals("30/1", v["r_frame_rate"])
        val png = File(dir, "layer.png")
        ffmpeg("-ss", "1.0", "-i", mov.path, "-frames:v", "1", "-pix_fmt", "rgba", png.path)
        val image = javax.imageio.ImageIO.read(png)
        val alphas = (0 until image.width step 8).flatMap { x -> (0 until image.height step 8).map { y -> image.getRGB(x, y) ushr 24 } }
        assertTrue(alphas.any { it < 16 }, "the footage area is transparent")

        // FCPXML: one source clip per event on the spine, the layer on lane 1, frame-exact times that add up.
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(pkg.files[0]))
        assertEquals("1.10", doc.documentElement.getAttribute("version"))
        val events = ProjectFiles.events(edit.production.edit, spec.fps)
        val spine = doc.getElementsByTagName("spine").item(0) as Element
        val clips = children(spine).filter { it.tagName == "asset-clip" }
        assertEquals(events.size, clips.size)
        var cursor = 0L
        for (el in children(spine)) {
            assertEquals(cursor, frames(el.getAttribute("offset"), spec.fps), "spine is contiguous at ${el.tagName}")
            cursor += frames(el.getAttribute("duration"), spec.fps)
        }
        val total = frames((doc.getElementsByTagName("sequence").item(0) as Element).getAttribute("duration"), spec.fps)
        assertEquals(total, cursor, "the spine fills the sequence")
        val lane = (0 until doc.getElementsByTagName("asset-clip").length).map { doc.getElementsByTagName("asset-clip").item(it) as Element }.filter { it.getAttribute("lane") == "1" }
        assertEquals(1, lane.size, "the graphics layer above the cut")
        assertTrue(doc.getElementsByTagName("media-rep").let { reps -> (0 until reps.length).all { (reps.item(it) as Element).getAttribute("src").startsWith("file:") } })

        // EDL: CMX 3600 events whose record times follow the cut.
        val edl = File(pkg.files[1]).readLines()
        assertEquals("FCM: NON-DROP FRAME", edl[1])
        val lines = edl.filter { it.matches(Regex("\\d{3} {2}AX {7}AA/V {2}C {8}(\\d\\d:\\d\\d:\\d\\d:\\d\\d ?){4}")) }
        assertEquals(events.size, lines.size)
        assertEquals(ProjectFiles.tc(events.first().record, spec.fps), lines.first().split(Regex(" +"))[6])

        // SRT: numbered cues in order, every word heard.
        val srt = File(pkg.files[2]).readText()
        val cues = srt.trim().split("\n\n")
        cues.forEachIndexed { i, cue ->
            val parts = cue.lines()
            assertEquals("${i + 1}", parts[0])
            assertTrue(parts[1].matches(Regex("\\d\\d:\\d\\d:\\d\\d,\\d{3} --> \\d\\d:\\d\\d:\\d\\d,\\d{3}")), parts[1])
        }
        assertTrue(words.all { w -> srt.contains(w.trimEnd('،', '.')) }, srt)
    }

    private fun children(e: Element): List<Element> = (0 until e.childNodes.length).mapNotNull { e.childNodes.item(it) as? Element }

    private fun frames(rational: String, fps: Int): Long {
        if (rational == "0s") return 0
        val (n, d) = rational.removeSuffix("s").split('/')
        assertEquals(fps.toLong(), d.toLong(), "times on the $fps fps grid: $rational")
        return n.toLong()
    }

    private fun probe(file: File, stream: String, entries: String): Map<String, String> {
        val p = ProcessBuilder("ffprobe", "-v", "error", "-select_streams", stream, "-show_entries", "stream=$entries", "-of", "default=nw=1", file.path).start()
        return p.inputStream.bufferedReader().readLines().mapNotNull { l -> l.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
    }

    private fun probeDuration(file: File): Float {
        val p = ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", file.path).start()
        return p.inputStream.bufferedReader().readText().trim().toFloat()
    }

    private fun ffmpeg(vararg args: String) {
        val p = ProcessBuilder(listOf("ffmpeg", "-y", "-loglevel", "error") + args).redirectErrorStream(true).start()
        val log = p.inputStream.bufferedReader().readText()
        check(p.waitFor() == 0) { "ffmpeg: $log" }
    }
}
