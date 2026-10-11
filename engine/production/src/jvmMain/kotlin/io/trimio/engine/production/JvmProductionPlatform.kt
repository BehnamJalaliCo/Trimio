package io.trimio.engine.production

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.trimio.core.model.audio.PcmAudio
import io.trimio.engine.asr.SpeechRecognizer
import io.trimio.engine.autopilot.Autopilot
import io.trimio.engine.autopilot.Media
import io.trimio.engine.autopilot.UserAsset
import io.trimio.engine.llm.RgbImage
import io.trimio.engine.motion.MediaSource
import io.trimio.engine.motion.MediaSources
import io.trimio.engine.motion.export.Delivery
import io.trimio.engine.motion.export.FfmpegFootage
import io.trimio.engine.motion.export.MotionExporter
import io.trimio.engine.motion.export.UserMediaFiles
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File

/**
 * Desktop and server: media through ffmpeg ([Media], [FfmpegFootage]), frames drawn by Skia and
 * encoded by [MotionExporter], speech by the host whisper.cpp build, the director by llama.cpp
 * ([LocalDirectors]) or the cloud. Needs `ffmpeg` and `ffprobe` on the PATH.
 */
class JvmProductionPlatform(
    /** Where renders go when the request names no output. */
    private val renders: File,
    private val recognizer: () -> SpeechRecognizer? = { null },
    private val directors: suspend (DirectorChoice) -> Director = { Director.None },
    private val delivery: Delivery = Delivery(),
    private val device: () -> DeviceState = { DeviceState() },
) : ProductionPlatform {

    override suspend fun audio(media: String, rate: Int): PcmAudio = io { Media.audio(File(media), rate) }

    override suspend fun frames(media: String, count: Int, width: Int): List<RgbImage> = io { Media.frames(File(media), count, width) }

    override suspend fun asset(source: String, id: String, caption: String?): UserAsset = io { Media.asset(File(source), id, caption = caption) }

    override fun media(request: ProductionRequest, assets: List<UserAsset>, fps: Int, width: Int, height: Int): MediaSource {
        // The creator's media answer to their own ids before the footage, which answers every id.
        val own = assets.map { UserMediaFiles.source(it.id, File(it.source), fps, width, height) }
        val footage = if (request.kind == SourceKind.Video) listOf(FfmpegFootage(File(request.media), fps, width, height)) else emptyList()
        return MediaSources(own + footage)
    }

    override fun measurer() = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)

    override fun recognizer(): SpeechRecognizer? = recognizer.invoke()

    override suspend fun director(choice: DirectorChoice): Director = directors(choice)

    override fun device(): DeviceState = device.invoke()

    override suspend fun export(
        production: Autopilot.Production,
        request: ProductionRequest,
        assets: List<UserAsset>,
        out: String,
        onFrame: (Int, Int) -> Unit,
    ): String = io {
        val file = File(out).absoluteFile
        file.parentFile.mkdirs()
        val c = production.compiled.composition
        val wav = File(file.parentFile, file.nameWithoutExtension + ".mix.wav")
        Media.writeWav(production.soundtrack.audio, wav)
        try {
            MotionExporter(c, { media(request, assets, c.fps, c.width, c.height) }, delivery).export(file, wav, onProgress = onFrame)
        } finally {
            wav.delete()
        }
        file.path
    }

    override fun outputFor(request: ProductionRequest): String = File(renders, request.key + ".mp4").path

    /** Blocking ffmpeg and Skia work, interruptible so a cancelled run stops waiting on it. */
    private suspend fun <T> io(block: () -> T): T = runInterruptible(Dispatchers.IO, block)
}
