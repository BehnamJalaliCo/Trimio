package io.trimio.engine.production

import androidx.compose.ui.graphics.ImageBitmap
import io.trimio.core.model.transcript.Transcript
import io.trimio.engine.autopilot.Autopilot
import io.trimio.engine.autopilot.Jury
import io.trimio.engine.autopilot.Taste
import io.trimio.engine.autopilot.UserAsset
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.Serializable

/*
 * The production API the app's screens are built against (phase 7.1). Stable surface:
 *
 *  - [ProductionRequest]  what the creator gives: media, prompt, glossary, attachments, style, seed, director;
 *  - [ProductionState]    what a run reports: stage, 0–1 progress, preview frames, the jury's panel, the file;
 *  - [AutoEditor]         cold flows: produce, vary (a new seed), revise (the creator's feedback);
 *  - [ProductionRunner]   the app-wide single active run, observed by screens and the Android foreground service.
 *
 * Cancelling: cancel the collector of an [AutoEditor] flow, or call [ProductionRunner.cancel].
 */

/** What the creator's media is: a talking video, or a voice recording the edit is drawn over. */
@Serializable
enum class SourceKind { Video, Audio }

/**
 * Who directs. [Auto] takes the best installed on-device model that fits the device (the user's
 * pick in settings wins), [Local] one installed model by catalogue id, [Cloud] the connected cloud
 * provider, and [Rules] no model at all (fastest; the rules engine reads the brief).
 */
@Serializable
sealed interface DirectorChoice {
    @Serializable
    data object Auto : DirectorChoice

    @Serializable
    data class Local(val modelId: String) : DirectorChoice

    @Serializable
    data object Cloud : DirectorChoice

    @Serializable
    data object Rules : DirectorChoice
}

/** A picture or clip attached beside the prompt (a logo, a product shot, a screen recording). */
@Serializable
data class AttachedMedia(
    /** File path or platform URI (Android: content://…). */
    val source: String,
    /** The creator's own words about it ("our logo", "show this when I say price"), when given. */
    val caption: String? = null,
)

/**
 * One production order.
 *
 * The [glossary] is part of the brief: names, sites and terms the speaker uses («RSI یعنی …»,
 * nobitex.ir); it is appended to the [prompt] so recognition, spelling and the screen all use it.
 * [style] is one of [ProductionStyles.ids] or null for the director's own choice.
 * Serializable: a run interrupted by the system resumes from its saved request ([Checkpoints]).
 */
@Serializable
data class ProductionRequest(
    /** File path or platform URI of the video or the voice recording. */
    val media: String,
    val kind: SourceKind = SourceKind.Video,
    val prompt: String,
    val glossary: String = "",
    val assets: List<AttachedMedia> = emptyList(),
    /** A style id from [ProductionStyles.ids]; null = auto. */
    val style: String? = null,
    /** The variation: every seed gives a different, always well-made edit. */
    val seed: Long = 1L,
    val director: DirectorChoice = DirectorChoice.Auto,
    /** Where the MP4 goes; null lets the platform pick its renders folder. */
    val output: String? = null,
    /** A stable id for checkpoints and notifications; null derives one from the media and seed. */
    val id: String? = null,
) {
    /** The brief the engine reads: the prompt, the glossary, and the asked style by name. */
    val brief: String
        get() = buildString {
            append(prompt.trim())
            if (glossary.isNotBlank()) append("\n\n").append(glossary.trim())
            ProductionStyles.of(style)?.let { append("\n\nStyle: ").append(it.nameEn).append(" (").append(it.id).append(")") }
        }

    /** The checkpoint and notification key. */
    val key: String get() = id ?: "p" + (media.hashCode().toLong() and 0xFFFFFFFFL).toString(16) + "-" + seed
}

/** Where a run is. [Stage.weight] is its share of the bar. */
enum class Stage(val weight: Float) {
    /** Decoding the voice, sampling frames, finding the speaker's head. */
    Prepare(0.05f),
    /** Speech to words (whisper.cpp). */
    Transcribe(0.2f),
    /** The autopilot: listen, understand, research, plan, pictures, compile, look, jury, sound. */
    Direct(0.45f),
    /** Frames rendered, encoded and muxed with the mastered sound. */
    Render(0.3f),
    Done(0f),
}

enum class ProductionStatus { Running, Done, Failed, Cancelled }

/** The jury's verdict, flattened for screens: one score per judge, 0–10. */
data class PanelSummary(
    val min: Float,
    val mean: Float,
    /** Every judge gave 10. */
    val unanimous: Boolean,
    /** Judge id → score, in panel order. */
    val scores: List<Pair<String, Float>>,
    /** One line per finding ("typography minor at 3.2s: … → …"). */
    val findings: List<String>,
) {
    companion object {
        fun of(p: Jury.Panel) = PanelSummary(p.min, p.mean, p.unanimous, p.verdicts.map { it.judge to it.score }, p.findings())
    }
}

/**
 * A finished edit the creator can vary or give feedback on: the production itself plus everything
 * the next run reuses (the words, the reading, the request).
 */
class Edit(
    val request: ProductionRequest,
    val production: Autopilot.Production,
    /**
     * The MP4 (path). After the editor changed the edit it names the file this exact edit renders
     * to, which exists only once it has been rendered ([AutoEditor.retouch]).
     */
    val output: String,
    /** The recognised words on the source clock: variations and revisions reuse them. */
    val transcript: Transcript,
    /** The creator's taste so far: feedback accumulates across revisions. */
    val taste: Taste = Taste(),
    /** The director's production the editor's [overrides] apply to ([production] itself until the creator changes something). */
    val base: Autopilot.Production = production,
    /** The creator's own changes in the editor ([Retouch]). */
    val overrides: EditOverrides = EditOverrides(),
    /** The attachments as read for this edit (the renderer opens them by id). */
    val assets: List<UserAsset> = emptyList(),
    /** What the spelling gate warned about in the creator's own text (kept as written). */
    val warnings: List<String> = emptyList(),
) {
    /** Whether the editor can change this edit (it kept the director's workbench). */
    val editable: Boolean get() = Retouch.editable(base)
}

/**
 * What a run reports, as often as something changes.
 *
 * [progress] is overall 0–1 (monotonic within a run). [step] is the engine's own sub-stage name
 * ("understand", "jury", "render 120/450"…) for the detail line. [previews] are small frames
 * (about 270 px wide) of the compiled edit as it forms, newest last, at most [MAX_PREVIEWS].
 * [panel] arrives with the jury's verdict; [output] and [edit] with [ProductionStatus.Done].
 */
data class ProductionState(
    val key: String,
    val status: ProductionStatus = ProductionStatus.Running,
    val stage: Stage = Stage.Prepare,
    val step: String = "",
    val progress: Float = 0f,
    val previews: List<ImageBitmap> = emptyList(),
    val panel: PanelSummary? = null,
    /** The production report's lines (what the director decided), once directed. */
    val report: List<String> = emptyList(),
    val output: String? = null,
    val edit: Edit? = null,
    val error: String? = null,
) {
    val finished: Boolean get() = status != ProductionStatus.Running

    companion object {
        const val MAX_PREVIEWS = 6
    }
}

/**
 * The automatic editor. Every function returns a cold [Flow]: collecting starts the run,
 * cancelling the collector cancels it (whisper, the model and the encoder all stop at their next
 * checkpoint). The last state is [ProductionStatus.Done] with the file, or Failed.
 */
interface AutoEditor {
    fun produce(request: ProductionRequest): Flow<ProductionState>

    /** Another variation of [edit]: the same words and reading, the next seed (or [seed]). */
    fun vary(edit: Edit, seed: Long = edit.request.seed + 1): Flow<ProductionState>

    /**
     * The creator's reaction in their own words ("پرانرژی‌ترش کن", "less text", "no music"): read
     * by the [io.trimio.engine.autopilot.Collaborator] into the taste, and the next edit follows it.
     */
    fun revise(edit: Edit, feedback: String): Flow<ProductionState>

    /**
     * [edit]'s director's cut with the creator's [overrides] applied ([Retouch]): compiled, gated,
     * mastered and judged, not rendered — fast enough to follow every change in the editor. The
     * result's [Edit.output] names the file it renders to ([retouch]).
     */
    suspend fun touch(edit: Edit, overrides: EditOverrides): Edit = throw UnsupportedOperationException("this engine cannot change an edit")

    /** The final render of [edit] with [overrides]: Render stage progress, then Done with the file. */
    fun retouch(edit: Edit, overrides: EditOverrides): Flow<ProductionState> =
        flowOf(ProductionState(edit.request.key, status = ProductionStatus.Failed, error = "this engine cannot change an edit"))

    /** Frames and sound of [edit] for the editor's player; null where the platform cannot draw them. */
    fun playback(edit: Edit): EditPlayback? = null
}

/**
 * The app's one active production: screens and the Android foreground service observe [active];
 * starting another run cancels the current one.
 */
interface ProductionRunner {
    val active: StateFlow<ProductionState?>

    fun start(request: ProductionRequest)
    fun vary(edit: Edit, seed: Long = edit.request.seed + 1)
    fun revise(edit: Edit, feedback: String)

    /** Renders [edit] with the editor's [overrides] as the active run ([AutoEditor.retouch]). */
    fun retouch(edit: Edit, overrides: EditOverrides) = Unit
    fun cancel()
}
