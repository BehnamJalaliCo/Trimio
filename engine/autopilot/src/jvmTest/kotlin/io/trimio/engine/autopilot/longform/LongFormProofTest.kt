package io.trimio.engine.autopilot.longform

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.autopilot.Media
import io.trimio.engine.motion.MotionFonts
import io.trimio.engine.motion.TextLayoutEngine
import io.trimio.engine.motion.export.FfmpegFootage
import io.trimio.engine.motion.export.MotionExporter
import io.trimio.engine.motion.recipe.BrandLibrary
import io.trimio.engine.motion.visual.VisualLibrary
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test

/**
 * The long-form proof on a real recording: the owner's 155 s talk, cut as YouTube's pro kind, its frames drawn where
 * each device shows (subtitles, chapter cards, the subscribe ask, a push-in) and the YouTube text written beside them.
 *
 * ./gradlew :engine:autopilot:jvmTest --tests '*LongFormProofTest*' -Plong.video=… [-Plong.format=16:9] [-Plong.out=…]
 */
class LongFormProofTest {
    @Serializable
    data class W(val w: String, val s: Float, val e: Float)

    @Test
    fun proof() {
        val video = System.getProperty("long.video")?.let(::File)?.takeIf { it.isFile } ?: return
        val out = File(System.getProperty("long.out") ?: "build/long-proof").apply { mkdirs() }
        val format = System.getProperty("long.format") ?: "16:9"
        val saved = Json.decodeFromString(ListSerializer(W.serializer()), javaClass.getResource("/real/owner-155s.json")!!.readText())
        val words = saved.map { Word(it.w, TimeRange((it.s * MS).toLong(), (it.e * MS).toLong()), language = Language.Persian) }
        val voice = Media.audio(video, RATE)
        val pilot = LongFormPilot(text, runBlocking { BrandLibrary.load() }, VisualLibrary.Empty)
        val production = runBlocking {
            pilot.produce(LongFormPilot.Request(Transcript(Language.Persian, words), voice, "", 1L, LongFormKind.Pro, format = format))
        }
        val c = production.compiled.composition
        val beats = production.compiled.beats
        // One frame inside each kind of device, plus plain subtitles early, mid and late.
        val times = beats.filter { it.recipe != "subtitles" }.groupBy { it.recipe }.flatMap { (_, b) -> b.take(2).map { (it.at + it.out) / 2f } } +
            listOf(5f, c.duration / 2f, c.duration - 10f)
        FfmpegFootage(video, c.fps, c.width, c.height).use { footage ->
            val frames = MotionExporter.Frames(c, footage)
            times.sorted().forEach { t ->
                File(out, "at-${"%.1f".format(t)}.png").writeBytes(frames.draw(t).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
            }
        }
        val pack = production.youtube!!
        File(out, "description.txt").writeText(pack.description)
        File(out, "titles.txt").writeText(pack.titles.joinToString("\n"))
        File(out, "subtitles.srt").writeText(pack.srt)
        File(out, "beats.txt").writeText(beats.joinToString("\n") { "${it.recipe} ${it.at}–${it.out}" })
        println("long-form ${c.width}x${c.height} ${c.duration}s: ${beats.groupingBy { it.recipe }.eachCount()}")
    }

    private companion object {
        const val MS = 1000
        const val RATE = 48_000
        val text by lazy { TextLayoutEngine(TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr), runBlocking { MotionFonts.load() }) }
    }
}
