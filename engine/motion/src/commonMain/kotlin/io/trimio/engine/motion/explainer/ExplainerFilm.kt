package io.trimio.engine.motion.explainer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * An audio-only explainer film (phase 8c): the screenwriter turns the narration into chapters, each chapter
 * keeps one stage that builds up beat by beat, and every beat is a visual metaphor for one sentence.
 *
 * This is the contract between the screenwriter/director (engine/autopilot `explainer`) and the stage
 * renderer (engine/motion `explainer`). Times are seconds on the narration's clock.
 */
@Serializable
data class ExplainerFilm(
    val art: ArtDirection,
    val chapters: List<Chapter>,
    /** Caption chunks shown on the band, in order; empty = no captions. */
    val captions: List<CaptionChunk> = emptyList(),
    val language: String = "fa",
)

/** A few words of narration on the caption band, timed to the speech. */
@Serializable
data class CaptionChunk(val text: String, val start: Double, val end: Double)

/** Per-film look, chosen from the topic and the seed so no two films look alike. */
@Serializable
data class ArtDirection(
    /** Palette id from the renderer's palette book (e.g. "paper-sand", "ink-night"). */
    val palette: String,
    /** Layout grammar id: where the chapter header, the stage and the caption band sit. */
    val layout: String,
    /** How chapters are marked (e.g. "dashes", "numerals", "rule"). */
    val chapterMark: String,
    /** Caption treatment id (e.g. "band-pill", "plain-lower", "none"). */
    val captionStyle: String,
    val seed: Long,
)

@Serializable
data class Chapter(
    /** Short heading shown on the stage for the whole chapter (often the subject's name). */
    val title: String,
    val start: Double,
    val end: Double,
    /** Index into the palette's accents; neighbouring chapters differ. */
    val accent: Int,
    val beats: List<Beat>,
)

/**
 * One step of the stage. [element] names a stage element that later beats can refer to again
 * (fill it, check its items, morph it, dim it), which keeps a chapter continuous instead of cutting.
 */
@Serializable
data class Beat(
    val at: Double,
    val until: Double,
    /** The narration sentence (or clause) this beat illustrates. */
    val line: String,
    val op: StageOp,
    val element: String,
    /** Set when [op] creates or replaces an element. */
    val primitive: Primitive? = null,
    val content: BeatContent = BeatContent(),
    /** Why this picture fits this sentence, for the jury (not shown). */
    val metaphor: String = "",
)

@Serializable
enum class StageOp {
    /** Bring a new element onto the stage. */
    @SerialName("add") Add,
    /** Reveal the element's items one by one (chips, tiles, steps, bars). */
    @SerialName("build") Build,
    /** Mark items as done or chosen (tick, highlight a path). */
    @SerialName("check") Check,
    /** Move a meter, bar or counter to [BeatContent.value]. */
    @SerialName("fill") Fill,
    /** Draw attention: accent, pulse or underline. */
    @SerialName("highlight") Highlight,
    /** Push into the background without removing. */
    @SerialName("dim") Dim,
    /** Turn the element into another primitive with a shape morph. */
    @SerialName("morph") Morph,
    /** Swap the element for a new one in the same place. */
    @SerialName("replace") Replace,
    /** Remove the element. */
    @SerialName("clear") Clear,
}

/** What a beat puts on screen. Unused fields stay empty. */
@Serializable
data class BeatContent(
    val text: String = "",
    val caption: String = "",
    val items: List<Item> = emptyList(),
    /** Target value for meters, counters and bars (0..1 for meters, any number for counters). */
    val value: Double? = null,
    val unit: String = "",
    /** Item indices that [StageOp.Check] or [StageOp.Highlight] act on. */
    val picks: List<Int> = emptyList(),
    /** Optional icon or animation query for the art library (English keywords). */
    val visual: String = "",
)

@Serializable
data class Item(
    val label: String,
    val value: Double? = null,
    val visual: String = "",
)

/** The compositional diagram language: small pieces that combine and build up progressively. */
@Serializable
enum class Primitive {
    @SerialName("heading") Heading,
    @SerialName("kicker") Kicker,
    @SerialName("big-word") BigWord,
    @SerialName("stat") Stat,
    @SerialName("quote") Quote,
    @SerialName("note") Note,
    @SerialName("chips") Chips,
    @SerialName("icon-tiles") IconTiles,
    @SerialName("task-tiles") TaskTiles,
    @SerialName("checklist") Checklist,
    @SerialName("steps") Steps,
    @SerialName("timeline") Timeline,
    @SerialName("meter") Meter,
    @SerialName("bars") Bars,
    @SerialName("blocks") Blocks,
    @SerialName("ring") Ring,
    @SerialName("dots") Dots,
    @SerialName("versus") Versus,
    @SerialName("before-after") BeforeAfter,
    @SerialName("scale") Scale,
    @SerialName("ladder") Ladder,
    @SerialName("funnel") Funnel,
    @SerialName("cycle") Cycle,
    @SerialName("flow") Flow,
    @SerialName("branches") Branches,
    @SerialName("tangle") Tangle,
    @SerialName("profile") Profile,
    @SerialName("badge") Badge,
    @SerialName("clock") Clock,
    @SerialName("thinking") Thinking,
    @SerialName("chat") Chat,
    @SerialName("stack") Stack,
    @SerialName("grid") Grid,
    @SerialName("map") Map,
    @SerialName("icon") Icon,
    @SerialName("mark") Mark,
}
