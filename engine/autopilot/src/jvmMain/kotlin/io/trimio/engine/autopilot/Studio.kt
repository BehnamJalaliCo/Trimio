package io.trimio.engine.autopilot

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.text.Language
import io.trimio.core.model.transcript.Transcript
import io.trimio.engine.asr.RecognitionOptions
import io.trimio.engine.asr.whisper.WhisperCppRecognizer
import io.trimio.engine.llm.LanguageModel
import io.trimio.engine.motion.MotionFonts
import io.trimio.engine.motion.TextLayoutEngine
import io.trimio.engine.motion.export.Delivery
import io.trimio.engine.motion.export.FfmpegFootage
import io.trimio.engine.motion.export.MotionExporter
import io.trimio.engine.motion.recipe.BrandLibrary
import io.trimio.engine.motion.score.Subject
import io.trimio.engine.motion.visual.VisualLibrary
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/**
 * The autopilot on desktop and server: a video file and a prompt in, a finished MP4 out. Speech
 * is recognised with the app's own whisper.cpp, the director model runs through llama.cpp (or the
 * cloud), media goes through ffmpeg.
 */
class Studio(
    private val brands: BrandLibrary,
    private val visuals: VisualLibrary,
    private val model: LanguageModel? = null,
    private val knowledge: Knowledge = Knowledge.Offline,
    private val taste: Taste = Taste(),
) {
    private val text = TextLayoutEngine(TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr), kotlinx.coroutines.runBlocking { MotionFonts.load() })

    /** Plans one edit of [video]. [transcript] skips recognition when given. */
    suspend fun produce(video: File, prompt: String, seed: Long, transcript: Transcript, understanding: Understanding? = null, onStage: (String, Float) -> Unit = { _, _ -> }): Autopilot.Production {
        val voice = Media.audio(video, RATE)
        val subject = Media.subject(video)
        return Autopilot(text, brands, visuals, model, knowledge, taste).produce(
            Autopilot.Request(transcript, voice, prompt, seed, footage = "main", subject = subject, understanding = understanding),
            onStage,
        )
    }

    /** Renders [production] of [video] to [out] at delivery quality. */
    fun render(production: Autopilot.Production, video: File, out: File, delivery: Delivery = Delivery(), onFrame: (Int, Int) -> Unit = { _, _ -> }) {
        val c = production.compiled.composition
        val wav = File(out.absoluteFile.parentFile, out.nameWithoutExtension + ".wav")
        Media.writeWav(production.soundtrack.audio, wav)
        MotionExporter(c, { FfmpegFootage(video, c.fps, c.width, c.height) }, delivery).export(out, wav, onProgress = onFrame)
        wav.delete()
    }

    companion object {
        const val RATE = 48_000

        /** Speech to words with whisper.cpp; the prompt's names and jargon condition the decoder. */
        suspend fun transcribe(video: File, whisperModel: String, prompt: String, language: Language? = Language.Persian): Transcript {
            val audio = Media.audio(video, 16_000)
            val names = Regex("[A-Za-z][A-Za-z0-9.+-]*(?: [A-Z][A-Za-z0-9.+-]*)*").findAll(prompt).map { it.value }.distinct().take(12).toList()
            // A short, well-punctuated sentence in the spoken language: better punctuation and spelling, names spelled right.
            val hint = if (names.isEmpty()) "سلام دوستان، امروز می‌خواهم یک نکته‌ی مهم را بگویم." else "در این ویدیو درباره‌ی " + names.joinToString("، ") + " صحبت می‌کنم."
            return WhisperCppRecognizer(whisperModel).use { r ->
                r.transcribe(audio, RecognitionOptions(language = language, initialPrompt = hint, threads = Runtime.getRuntime().availableProcessors()))
            }
        }
    }
}

/** ffmpeg-backed media helpers. */
object Media {
    fun audio(file: File, rate: Int): PcmAudio {
        val p = ProcessBuilder("ffmpeg", "-loglevel", "error", "-i", file.absolutePath, "-vn", "-ac", "1", "-ar", "$rate", "-f", "f32le", "-")
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val bytes = p.inputStream.readBytes()
        p.waitFor()
        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return PcmAudio(FloatArray(fb.remaining()).also { fb.get(it) }, rate)
    }

    /** The speaker's face from five small frames across the video. */
    fun subject(file: File): Subject? {
        val (sw, sh) = FfmpegFootage.probe(file)
        val w = 90
        val h = (w * sh / sw.toFloat()).roundToInt() / 2 * 2
        val duration = duration(file)
        val frames = (1..5).mapNotNull { k ->
            val t = duration * k / 6f
            val p = ProcessBuilder(
                "ffmpeg", "-loglevel", "error", "-ss", "%.2f".format(t), "-i", file.absolutePath, "-frames:v", "1",
                "-vf", "scale=$w:$h", "-f", "rawvideo", "-pix_fmt", "rgba", "-",
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val bytes = p.inputStream.readBytes()
            p.waitFor()
            bytes.takeIf { it.size == w * h * 4 }
        }
        return SubjectFinder.find(frames, w, h)
    }

    fun duration(file: File): Float {
        val p = ProcessBuilder("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", file.absolutePath).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        return out.toFloatOrNull() ?: 0f
    }

    fun writeWav(audio: PcmAudio, file: File) {
        val n = audio.samples.size
        val bytes = ByteBuffer.allocate(44 + n * 2).order(ByteOrder.LITTLE_ENDIAN)
        bytes.put("RIFF".toByteArray()).putInt(36 + n * 2).put("WAVEfmt ".toByteArray()).putInt(16).putShort(1).putShort(1)
            .putInt(audio.sampleRate).putInt(audio.sampleRate * 2).putShort(2).putShort(16).put("data".toByteArray()).putInt(n * 2)
        for (v in audio.samples) bytes.putShort((v * 32_767f).roundToInt().coerceIn(-32_768, 32_767).toShort())
        file.absoluteFile.parentFile.mkdirs()
        file.writeBytes(bytes.array())
    }
}
