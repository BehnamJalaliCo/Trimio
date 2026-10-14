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
import io.trimio.engine.motion.recipe.SubtitleStyle
import io.trimio.engine.motion.visual.VisualLibrary
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test

/**
 * Every subtitle style on the owner's real 155 s talk as a plain reel (silences cut, 9:16): frames where words are
 * being said, for review. ./gradlew :engine:autopilot:jvmTest --tests '*SubtitleProofTest*' -Plong.video=… [-Plong.out=…]
 * [-Plong.format=16:9 for the YouTube kind's subtitles on a wide frame]
 */
class SubtitleProofTest {
    @Serializable
    data class W(val w: String, val s: Float, val e: Float)

    @Test
    fun everyStyleOnTheOwnersTalk() {
        val video = System.getProperty("long.video")?.let(::File)?.takeIf { it.isFile } ?: return
        val out = File(System.getProperty("long.out") ?: "build/subtitle-proof").apply { mkdirs() }
        val wide = System.getProperty("long.format") == "16:9"
        val saved = Json.decodeFromString(ListSerializer(W.serializer()), javaClass.getResource("/real/owner-155s.json")!!.readText())
        val words = saved.map { Word(it.w, TimeRange((it.s * MS).toLong(), (it.e * MS).toLong()), language = Language.Persian) }
        val voice = Media.audio(video, RATE)
        val brands = runBlocking { BrandLibrary.load() }
        for (style in SubtitleStyle.entries) {
            val production = runBlocking {
                if (wide) {
                    LongFormPilot(text, brands, VisualLibrary.Empty).produce(
                        LongFormPilot.Request(Transcript(Language.Persian, words), voice, "", 1L, LongFormKind.Subtitles, format = "16:9", style = style),
                    )
                } else {
                    SubtitleReel(text, brands, VisualLibrary.Empty).produce(SubtitleReel.Request(Transcript(Language.Persian, words), voice, "", 1L, style))
                }
            }
            val c = production.compiled.composition
            val lines = production.compiled.beats.filter { it.recipe == style.recipe }
            // Inside the 2nd, 10th and 20th line: a moment while its words are being said.
            val times = listOf(1, 9, 19).mapNotNull { lines.getOrNull(it) }.map { it.at + (it.out - it.at) * 0.55f } +
                // A word joined by a ZWNJ («می‌خوره»), through its line: the word being said is marked whole.
                lines.firstOrNull { "خوره" in it.text }?.let { l -> List(5) { k -> l.at + (l.out - l.at) * (0.1f + 0.2f * k) } }.orEmpty()
            FfmpegFootage(video, c.fps, c.width, c.height).use { footage ->
                val frames = MotionExporter.Frames(c, footage)
                times.forEachIndexed { k, t ->
                    File(out, "${style.id}-$k.png").writeBytes(frames.draw(t).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
                }
            }
            println("subtitles ${style.id}: ${c.width}x${c.height} ${c.duration}s, ${lines.size} lines, ${production.edit.cutCount} cuts")
        }
    }

    private companion object {
        const val MS = 1000
        const val RATE = 48_000
        val text by lazy { TextLayoutEngine(TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr), runBlocking { MotionFonts.load() }) }
    }
}
