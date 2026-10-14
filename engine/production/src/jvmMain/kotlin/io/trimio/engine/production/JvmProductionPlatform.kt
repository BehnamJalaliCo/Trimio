package io.trimio.engine.production

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.trimio.core.model.audio.PcmAudio
import io.trimio.engine.asr.SpeechRecognizer
import io.trimio.engine.autopilot.Media
import io.trimio.engine.autopilot.UserAsset
import io.trimio.engine.llm.RgbImage
import io.trimio.core.model.tools.MediaTools
import io.trimio.engine.motion.ClearMedia
import io.trimio.engine.motion.Composition
import io.trimio.engine.motion.MediaSource
import io.trimio.engine.motion.MediaSources
import io.trimio.engine.motion.export.Delivery
import io.trimio.engine.motion.export.FfmpegFootage
import io.trimio.engine.motion.export.MotionExporter
import io.trimio.engine.motion.export.UserMediaFiles
import io.trimio.engine.production.diagnostics.Fact
import io.trimio.engine.production.diagnostics.MemoryUse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import java.io.File
import kotlin.math.roundToInt

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
    /** The next lighter director after a model id ([LocalDirectors.lighter]). */
    private val lighter: suspend (String?) -> DirectorChoice = { DirectorChoice.Rules },
    /** The speech model recognition uses now ([LocalDirectors.speechModel]). */
    private val speech: () -> String? = { null },
    /** The installed perception add-ons ([LocalDirectors.perception]). */
    private val perception: () -> io.trimio.engine.perception.Perception? = { null },
    /** The image generator add-on, when installed (SDXS through stable-diffusion.cpp). */
    private val diffusion: () -> io.trimio.engine.illustration.generate.DiffusionEngine? = { null },
) : ProductionPlatform {

    override suspend fun audio(media: String, rate: Int): PcmAudio = io { Media.audio(File(media), rate) }

    override suspend fun frames(media: String, count: Int, width: Int): List<RgbImage> = io { Media.frames(File(media), count, width) }

    override suspend fun asset(source: String, id: String, caption: String?): UserAsset = io { Media.asset(File(source), id, caption = caption) }

    override fun media(request: ProductionRequest, assets: List<UserAsset>, fps: Int, width: Int, height: Int): MediaSource {
        // The creator's media answer to their own ids before the footage, which answers every id.
        val own = assets.map { UserMediaFiles.source(it.id, File(it.source), fps, width, height) }
        val footage = if (request.kind == SourceKind.Video) listOf(FfmpegFootage(File(request.media), fps, width, height)) else emptyList()
        return MediaSources(own + footage + FilmPictures)
    }

    override fun measurer() = TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr)

    override fun speaker(): Speaker = JvmSpeaker()

    override fun recognizer(): SpeechRecognizer? = recognizer.invoke()

    override fun perception(): io.trimio.engine.perception.Perception? = runCatching { perception.invoke() }.getOrNull()

    override fun diffusion(): io.trimio.engine.illustration.generate.DiffusionEngine? = runCatching { diffusion.invoke() }.getOrNull()

    override suspend fun director(choice: DirectorChoice): Director = directors(choice)

    override fun device(): DeviceState = device.invoke()

    override fun about(): List<Fact> {
        val os = java.lang.management.ManagementFactory.getOperatingSystemMXBean()
        val ram = (os as? com.sun.management.OperatingSystemMXBean)?.let { "${it.totalMemorySize / MB} MB total · ${it.freeMemorySize / MB} MB free now" }
        return listOfNotNull(
            Fact("os", "${System.getProperty("os.name")} ${System.getProperty("os.version")} (${System.getProperty("os.arch")})"),
            ram?.let { Fact("memory", it) },
            Fact("cores", "${Runtime.getRuntime().availableProcessors()}"),
            Fact("java", "${System.getProperty("java.vendor")} ${System.getProperty("java.version")} · heap max ${Runtime.getRuntime().maxMemory() / MB} MB"),
            System.getProperty("trimio.native.variants")?.let { Fact("native builds offered", it) },
        )
    }

    override fun memory(): MemoryUse? = ProcessMemory.read {
        val pools = java.lang.management.ManagementFactory.getMemoryPoolMXBeans().filter { it.type == java.lang.management.MemoryType.HEAP }
        (pools.sumOf { it.peakUsage?.used ?: 0L } / MB).toInt().takeIf { it > 0 }
    }

    override fun speechModel(): String? = speech()

    override suspend fun lighter(current: String?): DirectorChoice = lighter.invoke(current)

    override suspend fun render(
        composition: Composition,
        sound: PcmAudio,
        request: ProductionRequest,
        assets: List<UserAsset>,
        out: String,
        scale: Float,
        onFrame: (Int, Int) -> Unit,
    ): String = io {
        val file = File(out).absoluteFile
        file.parentFile.mkdirs()
        val c = composition
        val wav = File(file.parentFile, file.nameWithoutExtension + ".mix.wav")
        Media.writeWav(sound, wav)
        // Footage decoded at the output size, so a 4K file gets 4K pictures, not stretched 1080p ones.
        val w = (c.width * scale).roundToInt()
        val h = (c.height * scale).roundToInt()
        try {
            MotionExporter(c, { media(request, assets, c.fps, w, h) }, delivery, scale).export(file, wav, onProgress = onFrame)
        } finally {
            wav.delete()
        }
        file.path
    }

    override suspend fun renderGraphics(
        composition: Composition,
        request: ProductionRequest,
        assets: List<UserAsset>,
        out: String,
        scale: Float,
        onFrame: (Int, Int) -> Unit,
    ): String = io {
        val file = File(out).absoluteFile
        val c = composition
        val w = (c.width * scale).roundToInt()
        val h = (c.height * scale).roundToInt()
        // The creator's pictures are graphics; the footage is what the other editor cuts underneath.
        MotionExporter(c, { MediaSources(assets.map { UserMediaFiles.source(it.id, File(it.source), c.fps, w, h) } + ClearMedia) }, delivery, scale).exportGraphics(file, onFrame)
        file.path
    }

    override suspend fun probe(media: String): SourceFacts = io {
        val file = File(media).absoluteFile
        val p = ProcessBuilder(
            MediaTools.ffprobe, "-v", "error", "-show_entries", "format=duration:stream=codec_type,width,height,avg_frame_rate:stream_side_data=rotation",
            "-of", "default=nw=1", file.path,
        ).redirectErrorStream(true).start()
        val text = p.inputStream.bufferedReader().readText()
        p.waitFor()
        fun value(key: String) = Regex("^$key=([-0-9./]+)", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)
        val picture = text.contains("codec_type=video")
        val size = if (picture) runCatching { FfmpegFootage.probe(file) }.getOrNull() ?: (0 to 0) else 0 to 0
        val rate = value("avg_frame_rate")?.split('/')?.let { (a, b) -> a.toFloat() / b.toFloat().coerceAtLeast(1f) }?.takeIf { it.isFinite() } ?: 0f
        SourceFacts(file.name, file.toURI().toString(), value("duration")?.toFloatOrNull() ?: 0f, size.first, size.second, rate.roundToInt())
    }

    override fun outputFor(request: ProductionRequest): String = File(renders, request.key + ".mp4").path

    /** Blocking ffmpeg and Skia work, interruptible so a cancelled run stops waiting on it. */
    private suspend fun <T> io(block: () -> T): T = runInterruptible(Dispatchers.IO, block)

    private companion object {
        const val MB = 1024L * 1024L
    }
}
