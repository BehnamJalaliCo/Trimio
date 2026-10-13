package io.trimio.engine.production

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.BatteryManager
import android.os.PowerManager
import android.provider.OpenableColumns
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.input.MediaUri
import io.trimio.engine.asr.SpeechRecognizer
import io.trimio.engine.audio.Resampler
import io.trimio.engine.autopilot.AndroidSubject
import io.trimio.engine.autopilot.UserAsset
import io.trimio.engine.llm.RgbImage
import io.trimio.engine.media.AndroidAudioDecoder
import io.trimio.engine.models.DeviceTier
import io.trimio.engine.production.diagnostics.Fact
import io.trimio.engine.production.diagnostics.MemoryUse
import io.trimio.engine.motion.Composition
import io.trimio.engine.motion.MediaSource
import io.trimio.engine.motion.MediaSources
import io.trimio.engine.motion.StillMedia
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/**
 * Android: the voice through MediaExtractor/MediaCodec ([AndroidAudioDecoder]), small frames from
 * [MediaMetadataRetriever] ([AndroidSubject]), footage decoded in order by MediaCodec
 * ([AndroidFootage]), the export drawn onto the encoder's surface and muxed with the AAC mix
 * ([AndroidMotionExporter]), whisper.cpp and llama.cpp through the NDK builds ([LocalDirectors]).
 * Thermal status and battery set the jury's budget ([device]).
 */
class AndroidProductionPlatform(
    private val context: Context,
    private val recognizer: () -> SpeechRecognizer?,
    private val directors: suspend (DirectorChoice) -> Director,
    /** The phone's class by RAM ([io.trimio.engine.models.DeviceMemory.tier]). */
    private val tier: DeviceTier,
    /** The next lighter director after a model id ([LocalDirectors.lighter]). */
    private val lighter: suspend (String?) -> DirectorChoice = { DirectorChoice.Rules },
    /** The speech model recognition uses now ([LocalDirectors.speechModel]). */
    private val speech: () -> String? = { null },
    /** The installed perception add-ons ([LocalDirectors.perception]). */
    private val perception: () -> io.trimio.engine.perception.Perception? = { null },
) : ProductionPlatform {
    private val app = context.applicationContext

    override suspend fun audio(media: String, rate: Int): PcmAudio {
        val decoded = AndroidAudioDecoder(app).decodeMono(MediaUri(mediaUri(media).toString()))
        return withContext(Dispatchers.Default) { Resampler().resample(decoded, rate) }
    }

    override suspend fun frames(media: String, count: Int, width: Int): List<RgbImage> =
        withContext(Dispatchers.IO) { AndroidSubject.frames(app, mediaUri(media), count, width) }

    override suspend fun asset(source: String, id: String, caption: String?): UserAsset = withContext(Dispatchers.IO) {
        val uri = mediaUri(source)
        val name = displayName(source)
        if (isVideo(source)) {
            val frames = AndroidSubject.frames(app, uri, count = CLIP_FRAMES, width = THUMB)
            val seconds = MediaMetadataRetriever().run {
                try {
                    setDataSource(app, uri)
                    extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toFloatOrNull()?.div(MS)
                } finally {
                    release()
                }
            }
            UserAsset(id, UserAsset.Kind.Video, frames.firstOrNull() ?: RgbImage(1, 1, ByteArray(3)), frames = frames, duration = seconds, caption = caption, source = name)
        } else {
            val (rgb, alpha) = thumbnail(decode(source, THUMB))
            UserAsset(id, UserAsset.Kind.Image, rgb, alpha, caption = caption, source = name)
        }
    }

    override fun media(request: ProductionRequest, assets: List<UserAsset>, fps: Int, width: Int, height: Int): MediaSource {
        val sources = request.assets.zip(assets).map { (attached, asset) ->
            if (asset.kind == UserAsset.Kind.Video) AndroidFootage(app, attached.source, width, height, asset.id)
            else StillMedia(asset.id, decode(attached.source, maxOf(width, height)).asImageBitmap())
        }
        val footage = if (request.kind == SourceKind.Video) listOf(AndroidFootage(app, request.media, width, height)) else emptyList()
        return MediaSources(sources + footage + FilmPictures)
    }

    override fun measurer() = TextMeasurer(createFontFamilyResolver(app), Density(1f), LayoutDirection.Ltr)

    override fun speaker(): Speaker = AndroidSpeaker()

    /** Previews draw into software bitmaps: runtime shaders need the hardware canvas the export uses. */
    override val previewShaders: Boolean get() = false

    override fun recognizer(): SpeechRecognizer? = recognizer.invoke()

    override fun perception(): io.trimio.engine.perception.Perception? = runCatching { perception.invoke() }.getOrNull()

    override suspend fun director(choice: DirectorChoice): Director = directors(choice)

    override fun device(): DeviceState {
        val power = app.getSystemService(PowerManager::class.java)
        val battery = app.getSystemService(BatteryManager::class.java)
        val status = power?.currentThermalStatus ?: PowerManager.THERMAL_STATUS_NONE
        val level = battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: FULL
        return DeviceState(
            tier = tier,
            hot = status >= PowerManager.THERMAL_STATUS_SEVERE,
            critical = status >= PowerManager.THERMAL_STATUS_CRITICAL,
            lowBattery = battery?.isCharging == false && level in 0 until LOW_BATTERY,
        )
    }

    override fun about(): List<Fact> {
        val ram = app.getSystemService(android.app.ActivityManager::class.java)?.let { am ->
            android.app.ActivityManager.MemoryInfo().also(am::getMemoryInfo)
        }
        val soc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}" else Build.HARDWARE
        return listOfNotNull(
            Fact("device", "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})"),
            Fact("chip", soc),
            Fact("os", "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"),
            ram?.let { Fact("memory", "${it.totalMem / MB} MB total · ${it.availMem / MB} MB free now" + if (it.lowMemory) " · low" else "") },
            Fact("cores", "${Runtime.getRuntime().availableProcessors()} (fast: ${io.trimio.engine.llm.local.FastCores.count() ?: "?"})"),
            Fact("tier", tier.name),
            Fact("thermal", app.getSystemService(PowerManager::class.java)?.currentThermalStatus?.toString() ?: "?"),
        )
    }

    override fun memory(): MemoryUse? = ProcessMemory.read()

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
    ): String {
        val c = composition
        // Footage decoded at the output size, so a 4K file gets 4K pictures, not stretched 1080p ones.
        val media = media(request, assets, c.fps, (c.width * scale).roundToInt(), (c.height * scale).roundToInt())
        return try {
            AndroidMotionExporter(app).export(c, media, sound, File(out), onFrame, scale).path
        } finally {
            (media as? AutoCloseable)?.close()
        }
    }

    override fun canEncode(width: Int, height: Int, fps: Int): Boolean = AndroidMotionExporter.supports(width, height, fps)

    override suspend fun probe(media: String): SourceFacts = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(app, mediaUri(media))
            fun key(k: Int) = retriever.extractMetadata(k)?.toFloatOrNull() ?: 0f
            val rotated = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION).toInt() % HALF_TURN == QUARTER_TURN
            val w = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH).toInt()
            val h = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT).toInt()
            val seconds = key(MediaMetadataRetriever.METADATA_KEY_DURATION) / MS
            val frames = key(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)
            val fps = if (seconds > 0f && frames > 0f) (frames / seconds).roundToInt() else 0
            SourceFacts(displayName(media), media.takeIf { it.startsWith("/") }?.let { "file://$it" }, seconds, if (rotated) h else w, if (rotated) w else h, fps)
        } finally {
            retriever.release()
        }
    }

    override fun outputFor(request: ProductionRequest): String = File(File(app.filesDir, "renders").apply { mkdirs() }, request.key + ".mp4").path

    private fun isVideo(source: String): Boolean {
        val type = runCatching { app.contentResolver.getType(mediaUri(source)) }.getOrNull()
        return type?.startsWith("video/") ?: (source.substringAfterLast('.').lowercase() in VIDEO)
    }

    /** The picture at [source], at most [max] px on its long side, upright, in software memory. */
    private fun decode(source: String, max: Int): Bitmap {
        val decoder = ImageDecoder.createSource(app.contentResolver, mediaUri(source))
        return ImageDecoder.decodeBitmap(decoder) { d, info, _ ->
            val k = minOf(1f, max / maxOf(info.size.width, info.size.height).toFloat())
            d.setTargetSize((info.size.width * k).roundToInt().coerceAtLeast(1), (info.size.height * k).roundToInt().coerceAtLeast(1))
            d.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    }

    /** Packed RGB and its alpha (null when fully opaque), as the director's pixel cues read them. */
    private fun thumbnail(bitmap: Bitmap): Pair<RgbImage, ByteArray?> {
        val w = bitmap.width
        val h = bitmap.height
        val argb = IntArray(w * h).also { bitmap.getPixels(it, 0, w, 0, 0, w, h) }
        val rgb = ByteArray(w * h * 3)
        val alpha = ByteArray(w * h)
        for (i in argb.indices) {
            val c = argb[i]
            rgb[i * 3] = (c shr 16).toByte()
            rgb[i * 3 + 1] = (c shr 8).toByte()
            rgb[i * 3 + 2] = c.toByte()
            alpha[i] = (c ushr 24).toByte()
        }
        val clear = alpha.any { (it.toInt() and 0xFF) < OPAQUE }
        return RgbImage(w, h, rgb) to alpha.takeIf { clear }
    }

    /** The file's name ("nike-logo.png"): a clue to what it is. The renderer opens the attachment's own URI ([media]). */
    private fun displayName(source: String): String = runCatching {
        app.contentResolver.query(mediaUri(source), arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: source.substringAfterLast('/')

    private companion object {
        const val THUMB = 256
        const val CLIP_FRAMES = 3
        const val MS = 1000f
        const val MB = 1024L * 1024L
        const val OPAQUE = 250
        const val HALF_TURN = 180
        const val QUARTER_TURN = 90
        const val FULL = 100
        const val LOW_BATTERY = 20
        val VIDEO = setOf("mp4", "mov", "m4v", "webm", "3gp", "mkv")
    }
}
