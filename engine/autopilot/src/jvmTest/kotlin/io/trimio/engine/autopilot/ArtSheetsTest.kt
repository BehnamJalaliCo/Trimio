package io.trimio.engine.autopilot

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.motion.MediaSource
import io.trimio.engine.motion.MotionFonts
import io.trimio.engine.motion.TextLayoutEngine
import io.trimio.engine.motion.export.FfmpegFootage
import io.trimio.engine.motion.export.MotionExporter
import io.trimio.engine.motion.recipe.BrandLibrary
import io.trimio.engine.motion.score.BeatScore
import io.trimio.engine.motion.score.CaptionScore
import io.trimio.engine.motion.score.Compiler
import io.trimio.engine.motion.score.SceneScore
import io.trimio.engine.motion.score.Score
import io.trimio.engine.motion.visual.VisualLibrary
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File
import kotlin.test.Test

/**
 * Contact sheets of real beats with animated pictures fetched from the web, for review:
 * Persian lines about trading, the director's English picture queries, picked by the
 * [VisualPicker] through [WebKnowledge] (catalogue search, starter pack, then the internet,
 * cached on disk), compiled by the real compiler in three styles and drawn over the creator's
 * footage. Each beat is shown at its entrance, landed and held.
 *
 * ./gradlew :engine:autopilot:jvmTest --tests '*ArtSheetsTest*' -Part.sheets=<dir> [-Part.video=<mp4>] [-Part.styles=a,b,c]
 */
class ArtSheetsTest {
    private data class Moment(val line: String, val query: String, val recipe: String, val label: String? = null, val variant: Int? = null)

    private val moments = listOf(
        Moment("بیت‌کوین مثل موشک رفت بالا", "rocket", "object", "بیت‌کوین"),
        Moment("همه‌چی سوخت و ضرر کردم", "fire", "live-reaction", variant = 1),
        Moment("با این روش سود گرفتم", "money with wings", "animated", "سود", variant = 1),
        Moment("نمودار داره میاد پایین", "chart decreasing", "animated", "ریزش"),
        Moment("حواستون باشه خطرناکه", "warning", "object", "هشدار"),
        Moment("واقعا خنده‌دار بود", "laughing", "live-reaction"),
        Moment("ایده اصلی همینه", "light bulb", "animated", "ایده", variant = 0),
        Moment("قیمت طلا هم بالا رفت", "trophy", "object", "برنده"),
    )

    @Test
    fun sheets() {
        val out = System.getProperty("art.sheets")?.let(::File) ?: return
        out.mkdirs()
        val video = System.getProperty("art.video")?.let(::File)?.takeIf { it.isFile }
        val styles = System.getProperty("art.styles")?.split(',')?.map { it.trim() } ?: listOf("street-hype", "finance-terminal", "luxury-gold")
        val knowledge = WebKnowledge.cached(HttpClient(OkHttp), File(out, "cache").path)
        val text = TextLayoutEngine(TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr), runBlocking { MotionFonts.load() })
        val brands = runBlocking { BrandLibrary.load() }
        val transcript = transcript()
        val report = StringBuilder()
        for (style in styles) {
            val t0 = System.nanoTime()
            val picked = runBlocking { VisualPicker(VisualLibrary.Empty, knowledge, look = style).pick(moments.associate { it.query to it.line }) }
            val pickMs = (System.nanoTime() - t0) / 1_000_000
            report.append("== $style (pictures in $pickMs ms)\n")
            moments.forEach { m -> report.append("  ${m.recipe.padEnd(9)} \"${m.query}\" → ${picked.picks[m.query] ?: "none"} (relevance ${picked.relevance[m.query]})\n") }
            picked.credits.forEach { report.append("  credit: $it\n") }
            val beats = moments.mapIndexedNotNull { i, m ->
                val id = picked.picks[m.query] ?: return@mapIndexedNotNull null
                BeatScore(recipe = m.recipe, time = at(i), hold = HOLD, visual = id, label = m.label, place = "top", variant = m.variant)
            }
            val score = Score(look = style, auto = false, captions = CaptionScore(show = true), scenes = listOf(SceneScore(time = 0f, beats = beats)))
            val compiled = Compiler(text, brands, picked.library).compile(Compiler.Input(score, transcript, footage = video?.let { "main" }, fps = FPS))
            compiled.beats.filter { it.recipe != "pop-captions" }.forEach { b -> report.append("  placed ${b.recipe} ${b.zone} ${b.at}–${b.out}\n") }
            sheet(compiled, video, File(out, "art-$style.png"))
        }
        File(out, "art-report.txt").writeText(report.toString())
        println(report)
    }

    private fun at(i: Int) = START + i * STEP

    /** The lines spoken one after another, each word on its own beat of time. */
    private fun transcript(): Transcript {
        val words = moments.flatMapIndexed { i, m ->
            val ws = m.line.split(' ')
            val from = at(i) - 0.2f
            ws.mapIndexed { k, w ->
                val s = from + k * WORD
                Word(w, TimeRange((s * MS).toLong(), ((s + WORD * 0.9f) * MS).toLong()), language = Language.Persian)
            }
        }
        return Transcript(Language.Persian, words)
    }

    /** Each beat at its entrance, landed and held: three frames a beat, two beats a row. */
    private fun sheet(compiled: Compiler.Output, video: File?, file: File) {
        val c = compiled.composition
        val media: MediaSource = video?.let { FfmpegFootage(it, c.fps, c.width, c.height) } ?: MediaSource.None
        val beats = compiled.beats.filter { it.recipe != "pop-captions" }
        val times = beats.flatMap { b -> listOf(b.at + 0.12f, b.at + 0.7f, (b.at + 1.5f).coerceAtMost(b.out - 0.1f)) }
        val w = (c.width * CELL).toInt()
        val h = (c.height * CELL).toInt()
        val columns = 6
        val rows = (times.size + columns - 1) / columns
        val sheet = Surface.makeRasterN32Premul(w * columns, h * rows)
        MotionExporter.Frames(c, media).use { frames ->
            for ((i, t) in times.withIndex()) {
                val img = frames.draw(t)
                sheet.canvas.drawImageRect(img, Rect.makeXYWH(((i % columns) * w).toFloat(), ((i / columns) * h).toFloat(), w.toFloat(), h.toFloat()))
            }
        }
        (media as? AutoCloseable)?.close()
        file.writeBytes(sheet.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }

    private companion object {
        const val FPS = 30
        const val START = 0.8f
        const val STEP = 2.4f
        const val HOLD = 2f
        const val WORD = 0.32f
        const val MS = 1000f
        const val CELL = 0.2f
    }
}
