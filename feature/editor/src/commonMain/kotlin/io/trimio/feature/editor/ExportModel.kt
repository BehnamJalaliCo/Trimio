package io.trimio.feature.editor

import androidx.compose.ui.graphics.ImageBitmap
import io.trimio.engine.production.ExportSpec
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** Where the video goes: each destination is a frame the motion engine lays the edit out for. */
enum class ExportDestination(val format: String, val fa: String, val en: String) {
    Reels("9:16", "ریلز", "Reels"),
    Shorts("9:16", "شورتس", "Shorts"),
    YouTube("16:9", "یوتیوب", "YouTube"),
    Square("1:1", "مربع", "Square"),
    ;

    companion object {
        fun of(format: String): ExportDestination = entries.firstOrNull { it.format == format.trim() } ?: Reels
    }
}

/** The file's size, by its short side; listed in the board's reading order (720p at the start). */
enum class ExportQuality(val shortEdge: Int, val label: String) {
    Hd(720, "720p"),
    FullHd(1080, "1080p"),
    Qhd(1440, "2K"),
    Uhd(2160, "4K"),
    ;

    companion object {
        fun of(shortEdge: Int): ExportQuality = entries.minBy { kotlin.math.abs(it.shortEdge - shortEdge) }
    }
}

/** What an export run is making. */
enum class ExportKind { Video, Project, Captions }

/** A run on the export screen: progress 0..1, then done (with its files) or failed. */
data class ExportJob(
    val kind: ExportKind,
    val progress: Float = 0f,
    val files: List<String> = emptyList(),
    val error: String? = null,
    /** The video is in the gallery (or the Videos folder on desktop). */
    val saved: Boolean = false,
    /** Seconds left, when the pace is known. */
    val etaSeconds: Int? = null,
) {
    val done: Boolean get() = files.isNotEmpty() && error == null
    val running: Boolean get() = !done && error == null
}

/** What the jury said about the edit, for the approval chip. */
sealed interface ExportVerdict {
    /** Every judge gave 10. */
    data object Approved : ExportVerdict

    /** The best panel within the budget: its lowest score. */
    data class Best(val min: Float) : ExportVerdict

    data object None : ExportVerdict
}

/** The export screen's state: the edit at the top, the three choices, the estimate and the run. */
data class ExportUi(
    val title: String = "",
    val scenes: Int = 0,
    val styleName: String? = null,
    val durationMs: Long = 0,
    val verdict: ExportVerdict = ExportVerdict.None,
    val poster: ImageBitmap? = null,
    val destination: ExportDestination = ExportDestination.Reels,
    val quality: ExportQuality = ExportQuality.FullHd,
    val fps: Int = 30,
    /** Quality and rate pairs this device's encoder cannot write. */
    val unsupported: Set<Pair<ExportQuality, Int>> = emptySet(),
    val job: ExportJob? = null,
    /** Saving goes to the Videos folder rather than a gallery. */
    val desktop: Boolean = false,
    /** The edit is no longer held (the app restarted since it was made). */
    val missing: Boolean = false,
    /** The pace of this device's encoder: seconds per frame at 1080p (measured once an export ran). */
    val secondsPerFrame: Float = DEFAULT_SECONDS_PER_FRAME,
) {
    val spec: ExportSpec get() = ExportSpec(destination.format, quality.shortEdge, fps)
    val busy: Boolean get() = job?.running == true

    /** About how large the file will be and how long it takes to make (bytes, seconds). */
    val estimate: Pair<Long, Int> get() = ExportEstimate.of(durationMs / MS, spec, secondsPerFrame)

    companion object {
        val Rates = listOf(24, 30, 60)

        /** A phone-class pace at 1080p, refined by the first export this session. */
        const val DEFAULT_SECONDS_PER_FRAME = 0.023f
        private const val MS = 1000f
    }
}

/**
 * The file size and render time an export will take, before it runs: H.264 at the delivery's
 * quality averages about 0.17 bits a pixel at 30 fps on motion graphics over footage (a 36 s
 * 1080p reel is about 48 MB), and the render time scales with the pixels drawn.
 */
object ExportEstimate {
    fun of(seconds: Float, spec: ExportSpec, secondsPerFrame: Float): Pair<Long, Int> {
        val (w, h) = spec.size
        val pixels = w.toLong() * h
        // Higher rates cost fewer bits a frame (smaller differences between frames).
        val bitsPerSecond = pixels * BITS_PER_PIXEL * spec.fps * rateFactor(spec.fps) + AUDIO_BPS
        val bytes = (bitsPerSecond * seconds / BITS).roundToLong()
        val frames = seconds * spec.fps
        val time = (frames * secondsPerFrame * pixels / HD_PIXELS).roundToInt().coerceAtLeast(1)
        return bytes to time
    }

    /** "~48 MB · 25s" (Latin, as the board shows it). */
    fun label(estimate: Pair<Long, Int>): String {
        val mb = estimate.first / MB
        val size = if (mb >= GB_IN_MB) "${(mb / GB_STEP).roundToInt() / TENTHS}.${(mb / GB_STEP).roundToInt() % TENTHS} GB" else "${mb.roundToInt().coerceAtLeast(1)} MB"
        val s = estimate.second
        val time = if (s >= MINUTE) "${s / MINUTE}m ${(s % MINUTE).toString().padStart(2, '0')}s" else "${s}s"
        return "~$size · $time"
    }

    private fun rateFactor(fps: Int): Float = when {
        fps >= HIGH_RATE -> 0.62f
        fps <= FILM_RATE -> 1.08f
        else -> 1f
    }

    private const val BITS_PER_PIXEL = 0.168f
    private const val AUDIO_BPS = 320_000f
    private const val BITS = 8f
    private const val HD_PIXELS = 1080f * 1920f
    private const val MB = 1_000_000f
    private const val GB_IN_MB = 1000f
    private const val GB_STEP = 100f
    private const val TENTHS = 10
    private const val MINUTE = 60
    private const val HIGH_RATE = 50
    private const val FILM_RATE = 25
}

/** What the export screen can ask for. */
data class ExportActions(
    val onBack: () -> Unit = {},
    val onDestination: (ExportDestination) -> Unit = {},
    val onQuality: (ExportQuality) -> Unit = {},
    val onFps: (Int) -> Unit = {},
    val onExport: () -> Unit = {},
    val onProject: () -> Unit = {},
    val onCaptions: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onShare: () -> Unit = {},
)
