package io.trimio.engine.autopilot

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.text.Language
import io.trimio.core.model.tools.MediaTools
import io.trimio.core.model.transcript.Transcript
import io.trimio.engine.asr.RecognitionOptions
import io.trimio.engine.asr.whisper.WhisperCppRecognizer
import io.trimio.engine.llm.LanguageModel
import io.trimio.engine.llm.RgbImage
import io.trimio.engine.motion.MotionFonts
import io.trimio.engine.motion.TextLayoutEngine
import io.trimio.engine.motion.export.Delivery
import io.trimio.engine.motion.export.FfmpegFootage
import io.trimio.engine.motion.export.MotionExporter
import io.trimio.engine.motion.export.UserMediaFiles
import io.trimio.engine.motion.MediaSources
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
    /** The eyes: the director when it can see, or a separate light vision model. */
    private val eyes: LanguageModel? = model?.takeIf { it.canSee },
    /** The installed face add-on's head over the pixel finder's (the app's AddOnPass); the default keeps the finder's. */
    private val head: suspend (List<RgbImage>, SubjectFinder.Found?) -> SubjectFinder.Found? = { _, found -> found },
) {
    private val text = TextLayoutEngine(TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr), kotlinx.coroutines.runBlocking { MotionFonts.load() })

    /** Plans one edit of [video]. [transcript] skips recognition when given. */
    suspend fun produce(
        video: File, prompt: String, seed: Long, transcript: Transcript, understanding: Understanding? = null,
        /** Pictures and clips the creator attached ([UserAsset.source] is the file); render with the same list. */
        assets: List<UserAsset> = emptyList(),
        onStage: (String, Float) -> Unit = { _, _ -> },
    ): Autopilot.Production {
        val voice = Media.audio(video, RATE)
        // The head from the pixels; when the director can see, its own eyes check the box on a few frames.
        val frames = Media.frames(video)
        val found = head(frames, SubjectFinder.locate(frames))
        val subject = eyes?.let { SubjectGrounding(it).ground(frames, found) } ?: found?.subject
        return Autopilot(text, brands, visuals, model, knowledge, taste, eyes, eyes?.let { grabber(video, assets) }).produce(
            Autopilot.Request(transcript, voice, prompt, seed, footage = "main", subject = subject, understanding = understanding, assets = assets),
            onStage,
        )
    }

    /** Small frames of a compiled edit (288×512 RGB) for the eyes. */
    private fun grabber(video: File, assets: List<UserAsset>) = FrameGrabber { output, times ->
        val c = output.composition
        MediaSources(assets.map { UserMediaFiles.source(it.id, File(it.source), c.fps, c.width, c.height) } + FfmpegFootage(video, c.fps, c.width, c.height)).use { footage ->
            val frames = MotionExporter.Frames(c, footage)
            times.map { t -> Media.small(frames.draw(t), LOOK_W, LOOK_H) }
        }
    }

    /** Renders [production] of [video] to [out] at delivery quality. */
    fun render(
        production: Autopilot.Production, video: File, out: File, delivery: Delivery = Delivery(),
        assets: List<UserAsset> = emptyList(), onFrame: (Int, Int) -> Unit = { _, _ -> },
    ) {
        val c = production.compiled.composition
        val wav = File(out.absoluteFile.parentFile, out.nameWithoutExtension + ".wav")
        Media.writeWav(production.soundtrack.audio, wav)
        // The creator's media answer to their own ids before the footage.
        fun media() = MediaSources(assets.map { UserMediaFiles.source(it.id, File(it.source), c.fps, c.width, c.height) } + FfmpegFootage(video, c.fps, c.width, c.height))
        MotionExporter(c, { if (assets.isEmpty()) FfmpegFootage(video, c.fps, c.width, c.height) else media() }, delivery).export(out, wav, onProgress = onFrame)
        wav.delete()
    }

    companion object {
        const val RATE = 48_000
        private const val LOOK_W = 288
        private const val LOOK_H = 512

        /** Speech to words with whisper.cpp; the prompt's names and jargon condition the decoder. */
        suspend fun transcribe(video: File, whisperModel: String, prompt: String, language: Language? = Language.Persian, vad: String? = null): Transcript {
            val audio = Media.audio(video, 16_000)
            // A short, well-punctuated sentence in the spoken language naming the brief's names and the
            // field's terms: better punctuation, and the terms spelled as they are written ([Loanwords.prompt]).
            val hint = System.getProperty("auto.hint")?.takeIf { it == "plain" }?.let { "سلام دوستان، امروز می‌خواهم یک نکته‌ی مهم را بگویم." } ?: Loanwords.prompt(prompt)
            return WhisperCppRecognizer(whisperModel, vadModelPath = vad).use { r ->
                // -Dtrimio.asr.threads: fewer threads than cores on a shared, busy machine (ggml's workers spin while they wait for each other).
                val threads = System.getProperty("trimio.asr.threads")?.toIntOrNull() ?: Runtime.getRuntime().availableProcessors()
                r.transcribe(audio, RecognitionOptions(language = language, initialPrompt = hint, threads = threads))
            }
        }
    }
}

/** ffmpeg-backed media helpers. */
object Media {
    fun audio(file: File, rate: Int): PcmAudio {
        val p = ProcessBuilder(MediaTools.ffmpeg, "-loglevel", "error", "-i", file.absolutePath, "-vn", "-ac", "1", "-ar", "$rate", "-f", "f32le", "-")
            .redirectError(ProcessBuilder.Redirect.DISCARD).start()
        val bytes = p.inputStream.readBytes()
        p.waitFor()
        val fb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        return PcmAudio(FloatArray(fb.remaining()).also { fb.get(it) }, rate)
    }

    /** The speaker's head from five small frames across the video ([SubjectFinder]; never the whole frame). */
    fun subject(file: File): Subject? = SubjectFinder.locate(frames(file))?.subject

    /**
     * [count] small RGB frames spread across the video, [width] px wide: the face finder reads
     * them (it scales them down itself) and a seeing model can look at the same pictures.
     */
    fun frames(file: File, count: Int = 5, width: Int = 288): List<io.trimio.engine.llm.RgbImage> {
        val (sw, sh) = FfmpegFootage.probe(file)
        val h = (width * sh / sw.toFloat()).roundToInt() / 2 * 2
        val duration = duration(file)
        return (1..count).mapNotNull { k ->
            val t = duration * k / (count + 1f)
            val p = ProcessBuilder(
                MediaTools.ffmpeg, "-loglevel", "error", "-ss", "%.2f".format(java.util.Locale.ROOT, t), "-i", file.absolutePath, "-frames:v", "1",
                "-vf", "scale=$width:$h", "-f", "rawvideo", "-pix_fmt", "rgb24", "-",
            ).redirectError(ProcessBuilder.Redirect.DISCARD).start()
            val bytes = p.inputStream.readBytes()
            p.waitFor()
            bytes.takeIf { it.size == width * h * 3 }?.let { io.trimio.engine.llm.RgbImage(width, h, it) }
        }
    }

    fun duration(file: File): Float {
        val p = ProcessBuilder(MediaTools.ffprobe, "-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", file.absolutePath).start()
        val out = p.inputStream.bufferedReader().readText().trim()
        p.waitFor()
        return out.toFloatOrNull() ?: 0f
    }

    /** A rendered frame scaled down to packed RGB for a vision model. */
    fun small(image: org.jetbrains.skia.Image, w: Int, h: Int): io.trimio.engine.llm.RgbImage {
        val surface = org.jetbrains.skia.Surface.makeRasterN32Premul(w, h)
        surface.canvas.drawImageRect(
            image, org.jetbrains.skia.Rect.makeWH(image.width.toFloat(), image.height.toFloat()), org.jetbrains.skia.Rect.makeWH(w.toFloat(), h.toFloat()),
            org.jetbrains.skia.SamplingMode.LINEAR, null, true,
        )
        val bitmap = org.jetbrains.skia.Bitmap().apply { allocPixels(org.jetbrains.skia.ImageInfo.makeN32(w, h, org.jetbrains.skia.ColorAlphaType.PREMUL)) }
        surface.makeImageSnapshot().readPixels(bitmap)
        val bgra = bitmap.readPixels()!!
        val rgb = ByteArray(w * h * 3)
        for (i in 0 until w * h) {
            // N32 is BGRA on little-endian hosts.
            rgb[i * 3] = bgra[i * 4 + 2]; rgb[i * 3 + 1] = bgra[i * 4 + 1]; rgb[i * 3 + 2] = bgra[i * 4]
        }
        return io.trimio.engine.llm.RgbImage(w, h, rgb)
    }

    /**
     * A file the creator attached, read for the director: a small thumbnail (and its transparency,
     * the logo cue), and for a clip a few frames and its length. [UserAsset.source] is the file.
     */
    fun asset(file: File, id: String, kind: UserAsset.Kind? = null, caption: String? = null): UserAsset {
        if (UserMediaFiles.isVideo(file)) {
            val frames = frames(file, count = 3, width = THUMB)
            return UserAsset(id, UserAsset.Kind.Video, frames.first(), frames = frames, duration = duration(file), caption = caption, source = file.absolutePath)
        }
        val image = org.jetbrains.skia.Image.makeFromEncoded(file.readBytes())
        val (rgb, alpha) = thumbnail(image)
        return UserAsset(id, kind ?: UserAsset.Kind.Image, rgb, alpha, caption = caption, source = file.absolutePath)
    }

    /** [image] scaled to at most [max] px on its long side: packed RGB and its alpha (null when fully opaque). */
    fun thumbnail(image: org.jetbrains.skia.Image, max: Int = THUMB): Pair<io.trimio.engine.llm.RgbImage, ByteArray?> {
        val k = minOf(1f, max / maxOf(image.width, image.height).toFloat())
        val w = (image.width * k).roundToInt().coerceAtLeast(1)
        val h = (image.height * k).roundToInt().coerceAtLeast(1)
        val surface = org.jetbrains.skia.Surface.makeRaster(org.jetbrains.skia.ImageInfo.makeN32(w, h, org.jetbrains.skia.ColorAlphaType.UNPREMUL))
        surface.canvas.drawImageRect(image, org.jetbrains.skia.Rect.makeWH(image.width.toFloat(), image.height.toFloat()), org.jetbrains.skia.Rect.makeWH(w.toFloat(), h.toFloat()))
        val bitmap = org.jetbrains.skia.Bitmap().apply { allocPixels(org.jetbrains.skia.ImageInfo.makeN32(w, h, org.jetbrains.skia.ColorAlphaType.UNPREMUL)) }
        surface.makeImageSnapshot().readPixels(bitmap)
        val bgra = bitmap.readPixels()!!
        val rgb = ByteArray(w * h * 3)
        val alpha = ByteArray(w * h)
        for (i in 0 until w * h) {
            rgb[i * 3] = bgra[i * 4 + 2]; rgb[i * 3 + 1] = bgra[i * 4 + 1]; rgb[i * 3 + 2] = bgra[i * 4]
            alpha[i] = bgra[i * 4 + 3]
        }
        val clear = alpha.any { (it.toInt() and 0xFF) < OPAQUE }
        return io.trimio.engine.llm.RgbImage(w, h, rgb) to alpha.takeIf { clear }
    }

    private const val THUMB = 256
    private const val OPAQUE = 250

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
