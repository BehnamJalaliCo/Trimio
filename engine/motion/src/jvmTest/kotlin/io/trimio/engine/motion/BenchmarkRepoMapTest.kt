package io.trimio.engine.motion

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.assets.MusicMood
import io.trimio.engine.assets.ProceduralMusic
import io.trimio.engine.assets.ProceduralSfx
import io.trimio.engine.audio.LoudnessMeter
import io.trimio.engine.audio.ProsodyAnalyzer
import io.trimio.engine.audio.VoiceActivityDetector
import io.trimio.engine.motion.recipe.Sfx
import io.trimio.engine.motion.recipe.SfxKind
import io.trimio.engine.motion.score.Compiler
import io.trimio.engine.motion.score.EditPlan
import io.trimio.engine.motion.score.Score
import io.trimio.engine.motion.score.Subject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import java.io.DataInputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.test.Test

/**
 * Phase-4 benchmark: the owner's real talking-head video, edited end to end by the engine — once
 * from the director's score in docs/benchmark/01-repo-map, once fully automatic. The video stays
 * on the machine (pass it with -Pbench.video=/path/to.mp4); only the edit decisions are in git.
 */
class BenchmarkRepoMapTest {
    private val dir = File("../../docs/benchmark/01-repo-map")
    private val out = File("build/benchmark")

    /** Streams frames of a video through ffmpeg; seeks only when the edit jumps. */
    private class Footage(private val file: File, private val fps: Int, private val w: Int, private val h: Int) : MediaSource, AutoCloseable {
        private var process: Process? = null
        private var input: DataInputStream? = null
        private var index = -1
        private var last: ImageBitmap? = null
        private val buffer = ByteArray(w * h * 4)

        override fun frame(source: String, time: Float): ImageBitmap? {
            val target = (time * fps).roundToInt().coerceAtLeast(0)
            if (target == index && last != null) return last
            if (process == null || target < index || target > index + MAX_SKIP) restart(target)
            val stream = input ?: return last
            while (index < target) {
                try { stream.readFully(buffer) } catch (_: java.io.EOFException) { return last }
                index++
            }
            last = Image.makeRaster(ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.UNPREMUL), buffer, w * 4).toComposeImageBitmap()
            return last
        }

        private fun restart(target: Int) {
            close()
            val p = ProcessBuilder(
                "ffmpeg", "-loglevel", "error", "-ss", "%.3f".format(target / fps.toFloat()), "-i", file.absolutePath,
                "-f", "rawvideo", "-pix_fmt", "rgba", "-r", "$fps", "-",
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            process = p
            input = DataInputStream(p.inputStream.buffered(1 shl 22))
            index = target - 1
        }

        override fun close() {
            input?.close()
            process?.destroy()
            process = null
            input = null
        }

        companion object { const val MAX_SKIP = 45 }
    }

    private fun decodeAudio(file: File, rate: Int): PcmAudio {
        val p = ProcessBuilder("ffmpeg", "-loglevel", "error", "-i", file.absolutePath, "-ac", "1", "-ar", "$rate", "-f", "f32le", "-")
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val bytes = p.inputStream.readBytes()
        p.waitFor()
        val bb = java.nio.ByteBuffer.wrap(bytes).order(java.nio.ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return PcmAudio(FloatArray(bb.remaining()).also { bb.get(it) }, rate)
    }

    private fun words(): List<Word> = Json.parseToJsonElement(dir.resolve("words.json").readText()).jsonArray.map { e ->
        val o = e.jsonObject
        val s = o.getValue("s").jsonPrimitive.float
        val t = o.getValue("e").jsonPrimitive.float
        Word(o.getValue("w").jsonPrimitive.content, TimeRange((s * 1000).toLong(), (t * 1000).toLong()), language = Language.Persian)
    }

    private fun silences(speech: List<TimeRange>, duration: Float): List<ClosedFloatingPointRange<Float>> {
        val gaps = mutableListOf<ClosedFloatingPointRange<Float>>()
        var cursor = 0f
        for (r in speech) {
            val a = r.startMs / 1000f
            if (a - cursor > 0.2f) gaps += cursor..a
            cursor = maxOf(cursor, r.endMs / 1000f)
        }
        if (duration - cursor > 0.2f) gaps += cursor..duration
        return gaps
    }

    @Test
    fun benchmark() {
        val video = System.getProperty("bench.video")?.let(::File)?.takeIf { it.exists() } ?: return
        val rate = 48_000
        val voice = decodeAudio(video, rate)
        val duration = voice.samples.size / rate.toFloat()
        val speech = VoiceActivityDetector().detect(voice)
        // For cutting, pauses are judged against the speaker's level, with short hangover.
        val pauses = VoiceActivityDetector(hangoverMs = 70, mergeGapMs = 120, belowSpeechDb = 24f).detect(voice)
        val gaps = silences(pauses, duration)
        val aligned = EditPlan.alignToSpeech(words(), gaps)
        val features = ProsodyAnalyzer().features(voice, speech, LoudnessMeter.measure(voice).integratedLufs.toFloat(), 0f)
        val stress = ProsodyAnalyzer().emphasis(features, aligned)
        val transcript = Transcript(Language.Persian, aligned.mapIndexed { i, w -> w.copy(emphasis = stress[i].coerceIn(0f, 1f)) })
        val edit = EditPlan.tighten(transcript.words, gaps, duration)
        val report = StringBuilder()
        report.appendLine("pauses: ${gaps.joinToString { "%.2f–%.2f".format(it.start, it.endInclusive) }}")
        report.appendLine("speech: ${speech.joinToString { "%.2f–%.2f".format(it.startMs / 1000f, it.endMs / 1000f) }}")
        report.appendLine("cuts: ${edit.cutCount}, %.2fs → %.2fs".format(duration, edit.duration))
        report.appendLine("stress: " + transcript.words.joinToString(" ") { "${it.text}(${(it.emphasis * 100).toInt()})" })

        val variants = mapOf(
            "directed" to Score.parse(dir.resolve("score.json").readText()),
            "auto" to Score(look = "noir", bpm = 118f, captions = io.trimio.engine.motion.score.CaptionScore(maxWords = 3)),
        )
        val only = System.getProperty("bench.only")
        for ((name, score) in variants) {
            if (only != null && only != name) continue
            val probe = MotionTestKit(Composition(1080, 1920, 30, 1f, androidx.compose.ui.graphics.Color.Black, Group(emptyList())))
            val compiled = Compiler(probe.renderer.text).compile(
                Compiler.Input(score, transcript, footage = "main", edit = edit, subject = Subject(0.32f, 0.39f, 0.7f, 0.7f)),
            )
            report.appendLine("\n== $name")
            compiled.beats.forEach { report.appendLine("  %-12s %-6s %5.2f–%5.2f  %s".format(it.recipe, it.zone, it.at, it.out, it.text)) }
            compiled.notes.forEach { report.appendLine("  note: $it") }
            Footage(video, 30, 720, 1280).use { footage ->
                val kit = MotionTestKit(compiled.composition, footage)
                val d = compiled.composition.duration
                kit.contactSheet((0 until 24).map { 0.3f + it * (d - 0.5f) / 23f }, out.resolve("$name-sheet.png"), columns = 8, scale = 0.2f)
                System.getProperty("bench.frames")?.split(',')?.forEach { kit.png(it.toFloat(), out.resolve("$name-at-$it.png")) }
                if (System.getProperty("bench.video.out") != "false") {
                    val wav = out.resolve("$name.wav")
                    mix(voice, edit, compiled.composition.duration, compiled.sfx, transcript, wav)
                    kit.mp4(out.resolve("$name.mp4"), audio = wav)
                }
            }
        }
        out.resolve("report.txt").apply { parentFile.mkdirs() }.writeText(report.toString())
    }

    /**
     * The soundtrack: the voice cut to the edit (10 ms crossfades at cuts), a music bed that ducks
     * under speech and breathes up in the gaps, and the sound cues. Peak-normalised to -1 dBFS.
     */
    private fun mix(voice: PcmAudio, edit: EditPlan, duration: Float, cues: List<Sfx>, transcript: Transcript, file: File) {
        val rate = voice.sampleRate
        val n = (duration * rate).toInt()
        val mix = FloatArray(n)
        val fade = rate / 100
        for (seg in edit.segments) {
            val a = (seg.sourceStart * rate).toInt()
            val len = (seg.length * rate).toInt()
            val o = (seg.outStart * rate).toInt()
            for (i in 0 until len) {
                val k = o + i
                if (k !in mix.indices || a + i !in voice.samples.indices) continue
                val g = minOf(1f, i / fade.toFloat(), (len - i) / fade.toFloat())
                mix[k] += voice.samples[a + i] * g
            }
        }
        // Music: ducked to -21 dB under words, -13 dB between them; out on the last half second.
        val bed = ProceduralMusic(rate).render(MusicMood.Uplifting).audio.samples
        val spoken = BooleanArray(n).also { s ->
            for (w in edit.remap(transcript).words) for (i in (w.range.startMs * rate / 1000).toInt() until (w.range.endMs * rate / 1000).toInt()) if (i in s.indices) s[i] = true
        }
        val attack = exp(-1.0 / (rate * 0.06)).toFloat()
        val release = exp(-1.0 / (rate * 0.35)).toFloat()
        var gain = db(-13f)
        for (i in 0 until n) {
            val target = if (spoken[i]) db(-21f) else db(-13f)
            val c = if (target < gain) attack else release
            gain = target + (gain - target) * c
            val tail = ((n - i) / (rate * 0.6f)).coerceIn(0f, 1f)
            mix[i] += bed[i % bed.size] * gain * tail
        }
        addCues(mix, cues, rate)
        val peak = mix.maxOf { abs(it) }.coerceAtLeast(1e-6f)
        val norm = db(-1f) / peak
        val bytes = java.io.ByteArrayOutputStream()
        fun le(v: Int, k: Int) = repeat(k) { bytes.write((v shr (8 * it)) and 0xFF) }
        bytes.write("RIFF".toByteArray()); le(36 + n * 2, 4); bytes.write("WAVEfmt ".toByteArray())
        le(16, 4); le(1, 2); le(1, 2); le(rate, 4); le(rate * 2, 4); le(2, 2); le(16, 2)
        bytes.write("data".toByteArray()); le(n * 2, 4)
        for (v in mix) le((v * norm * 32_767f).roundToInt().coerceIn(-32_768, 32_767), 2)
        file.parentFile.mkdirs()
        file.writeBytes(bytes.toByteArray())
    }

    private fun addCues(mix: FloatArray, cues: List<Sfx>, rate: Int) {
        val sfx = ProceduralSfx(rate)
        val voices = mapOf(
            SfxKind.Whoosh to ("whoosh" to 0.32f), SfxKind.Swish to ("swoosh" to 0.22f), SfxKind.Hit to ("impact" to 0.3f),
            SfxKind.Boom to ("impact" to 0.5f), SfxKind.Pop to ("pop" to 0.25f), SfxKind.Click to ("glitch" to 0.2f),
            SfxKind.Riser to ("riser" to 0.2f), SfxKind.Tick to ("tick" to 0.18f), SfxKind.Shimmer to ("shimmer" to 0.22f),
        )
        for ((i, cue) in cues.withIndex()) {
            val (id, g) = voices.getValue(cue.kind)
            val pcm = sfx.generate(id, seed = i + 1) ?: continue
            val start = (cue.at * rate).toInt()
            for (j in pcm.samples.indices) if (start + j in mix.indices) mix[start + j] += pcm.samples[j] * g * cue.gain
        }
    }

    private fun db(v: Float) = 10f.pow(v / 20f)
}
