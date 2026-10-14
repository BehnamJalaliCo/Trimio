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
import io.trimio.engine.motion.Composition
import io.trimio.engine.motion.MediaSource
import io.trimio.engine.production.diagnostics.Fact
import io.trimio.engine.production.diagnostics.MemoryUse

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

    /** Sound out for the editor's player ([EditPlayback]); null plays it silent on a wall clock. */
    fun speaker(): Speaker? = null

    /** Speech to words (whisper.cpp); null when no speech model is installed. */
    fun recognizer(): SpeechRecognizer?

    /** The director (and its eyes) for [choice]; [Director.None] means the rules direct alone. */
    suspend fun director(choice: DirectorChoice): Director

    /** Device class, heat and battery right now: they set the jury's budget ([Juries.forDevice]). */
    fun device(): DeviceState = DeviceState()

    /** The device for the technical report: maker and model, chip, OS, memory, cores. */
    fun about(): List<Fact> = emptyList()

    /** The process's memory now and at its peak, where the platform can read it. */
    fun memory(): MemoryUse? = null

    /** The speech model recognition uses now (catalogue id or file name), when known. */
    fun speechModel(): String? = null

    /** The installed perception add-ons ([LocalDirectors.perception]); null (the default) runs without them. */
    fun perception(): io.trimio.engine.perception.Perception? = null

    /** The image generator add-on (SDXS through stable-diffusion.cpp) when installed and this build has its engine; null otherwise. */
    fun diffusion(): io.trimio.engine.illustration.generate.DiffusionEngine? = null

    /** The next lighter director after the model [current] (a smaller installed one, else the rules). */
    suspend fun lighter(current: String?): DirectorChoice = DirectorChoice.Rules

    /** Renders [production] to the MP4 at [out] with its mastered soundtrack; [onFrame] gets (done, total). */
    suspend fun export(
        production: Autopilot.Production,
        request: ProductionRequest,
        assets: List<UserAsset>,
        out: String,
        onFrame: (Int, Int) -> Unit,
    ): String = render(production.compiled.composition, production.soundtrack.audio, request, assets, out, 1f, onFrame, production.sourceSound(request))

    /**
     * Renders [composition] (an edit as delivered, or laid out again for another frame or rate)
     * with [sound] to the MP4 at [out], [scale] times the composition's size (2 for 4K from the
     * 1080-wide layout); [onFrame] gets (done, total).
     */
    suspend fun render(
        composition: Composition,
        sound: PcmAudio,
        request: ProductionRequest,
        assets: List<UserAsset>,
        out: String,
        scale: Float,
        onFrame: (Int, Int) -> Unit,
        /** A source whose own sound the file carries as recorded instead of [sound] (a long-form edit, [sourceSound]). */
        sourceAudio: String? = null,
    ): String

    /**
     * The motion graphics alone over transparency (the footage left out) for another editor's
     * timeline, at [out]; null where the platform has no codec with alpha (phones).
     */
    suspend fun renderGraphics(
        composition: Composition,
        request: ProductionRequest,
        assets: List<UserAsset>,
        out: String,
        scale: Float,
        onFrame: (Int, Int) -> Unit,
    ): String? = null

    /** Whether this device's encoder takes [width]×[height] at [fps] (phones top out below some 4K rates). */
    fun canEncode(width: Int, height: Int, fps: Int): Boolean = true

    /** The facts another editor needs to relink the creator's file: its name, size and length; null when unknown. */
    suspend fun probe(media: String): SourceFacts? = null

    /** Where a request without [ProductionRequest.output] renders to. */
    fun outputFor(request: ProductionRequest): String
}

/** The creator's source file as another editor sees it. */
data class SourceFacts(
    /** The file's name ("talk.mp4"), for relinking. */
    val name: String,
    /** A file:// URL where the platform has one (desktop); null for content the editor relinks by name. */
    val url: String?,
    val durationSeconds: Float,
    /** Display size, rotation applied; 0 when the file has no picture. */
    val width: Int,
    val height: Int,
    /** The source's own frame rate, rounded (0 when unknown). */
    val fps: Int = 0,
)

/**
 * Who directs and who looks at frames (the director itself when it can see); [note] says why no model directs
 * (none installed, or too big for this memory) for the technical report.
 */
data class Director(val model: LanguageModel?, val eyes: LanguageModel? = model?.takeIf { it.canSee }, val note: String? = null) {
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

/**
 * The source whose recorded sound a long-form edit's file keeps (its [request] media), or null: a YouTube edit plays
 * as recorded, so the original track goes in untouched (a long video's sound is never held in memory whole, and is
 * analysed at the speech rate only). Any cut (a retouch) and the edit's own soundtrack is used again.
 */
fun Autopilot.Production.sourceSound(request: ProductionRequest): String? {
    val whole = edit.segments.singleOrNull()?.let { it.sourceStart == 0f && it.outStart == 0f } == true && edit.opening.isEmpty() && edit.outro == null
    return request.media.takeIf { whole && request.kind == SourceKind.Video && request.edit.longForm }
}
