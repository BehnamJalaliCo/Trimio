package io.trimio.engine.production

import androidx.compose.ui.text.TextMeasurer
import io.trimio.core.model.audio.PcmAudio
import io.trimio.engine.asr.SpeechRecognizer
import io.trimio.engine.autopilot.Autopilot
import io.trimio.engine.autopilot.Jury
import io.trimio.engine.autopilot.UserAsset
import io.trimio.engine.llm.LanguageModel
import io.trimio.engine.llm.RgbImage
import io.trimio.engine.models.DeviceTier
import io.trimio.engine.motion.MediaSource

/**
 * What differs between platforms; everything else (the autopilot, previews, checkpoints, the
 * jury's budget) is common. Desktop: ffmpeg, Skia and the host whisper/llama builds
 * (JvmProductionPlatform). Android: MediaExtractor/MediaCodec, MediaMetadataRetriever,
 * MediaMuxer and the NDK builds (AndroidProductionPlatform).
 *
 * Blocking work must move off the caller's thread itself (Dispatchers.IO) and honour cancellation.
 */
interface ProductionPlatform {
    /** The sound of [media] as mono float PCM at [rate] Hz (channels averaged). */
    suspend fun audio(media: String, rate: Int): PcmAudio

    /** [count] small upright RGB frames spread across the video [media], [width] px wide; empty when it has no picture. */
    suspend fun frames(media: String, count: Int, width: Int): List<RgbImage>

    /** An attachment read for the director: a thumbnail (and its alpha), and a clip's frames and length. */
    suspend fun asset(source: String, id: String, caption: String?): UserAsset

    /**
     * The pictures the renderer draws from: the creator's [assets] under their ids, then the
     * footage of [request] (any other id; none for audio-only). Closed after use when AutoCloseable.
     */
    fun media(request: ProductionRequest, assets: List<UserAsset>, fps: Int, width: Int, height: Int): MediaSource

    /** A text measurer at density 1 (sp == px), as [io.trimio.engine.motion.MotionRenderer] needs. */
    fun measurer(): TextMeasurer

    /**
     * Runtime shaders in preview frames. Android draws previews into a software bitmap, where
     * runtime shaders are not available: false draws their CPU fallbacks.
     */
    val previewShaders: Boolean get() = true

    /** Speech to words (whisper.cpp); null when no speech model is installed. */
    fun recognizer(): SpeechRecognizer?

    /** The director (and its eyes) for [choice]; [Director.None] means the rules direct alone. */
    suspend fun director(choice: DirectorChoice): Director

    /** Device class, heat and battery right now: they set the jury's budget ([Juries.forDevice]). */
    fun device(): DeviceState = DeviceState()

    /** Renders [production] to the MP4 at [out] with its mastered soundtrack; [onFrame] gets (done, total). */
    suspend fun export(
        production: Autopilot.Production,
        request: ProductionRequest,
        assets: List<UserAsset>,
        out: String,
        onFrame: (Int, Int) -> Unit,
    ): String

    /** Where a request without [ProductionRequest.output] renders to. */
    fun outputFor(request: ProductionRequest): String
}

/** Who directs and who looks at frames (the director itself when it can see). */
data class Director(val model: LanguageModel?, val eyes: LanguageModel? = model?.takeIf { it.canSee }) {
    companion object {
        val None = Director(null, null)
    }
}

/** What the jury can afford right now. */
data class DeviceState(
    /** The phone's class by RAM; null on desktop and servers (the full budget). */
    val tier: DeviceTier? = null,
    /** Throttling (Android thermal status severe or worse). */
    val hot: Boolean = false,
    /** About to shut down for heat (critical, emergency, shutdown). */
    val critical: Boolean = false,
    /** Battery low and not charging. */
    val lowBattery: Boolean = false,
)

/** The jury a device can afford: [Jury.forDevice] by tier, lowered when the phone is hot or the battery low. */
object Juries {
    fun forDevice(state: DeviceState): Jury = when {
        state.critical -> Jury(budget = Jury.Budget(rounds = 1, variations = 0))
        state.hot || state.lowBattery -> Jury.forDevice(DeviceTier.Standard)
        else -> Jury.forDevice(state.tier)
    }
}
