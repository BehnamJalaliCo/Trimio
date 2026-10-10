package io.trimio.engine.motion.recipe

import io.trimio.engine.motion.Anim
import io.trimio.engine.motion.Node
import io.trimio.engine.motion.TypeSpec

/**
 * Level 2 of the motion language: recipes carry the craft. A recipe turns a small request ("slam
 * these words when they are spoken, high energy") into finished layers with timing, overshoot,
 * stagger, emphasis, exits, camera reactions and sound cues decided the way a senior motion
 * designer would. The director only names a recipe; the quality lives here.
 */
abstract class Recipe(val name: String, val kind: Kind) {
    enum class Kind { Text, Element }

    /** Slot height the recipe would like as a fraction of the frame height. */
    open val preferredHeight: Float = 0.22f

    /** Seconds the choreography needs to play out and be read. */
    open val minHold: Float = 0f

    abstract fun build(cue: Cue, look: Look, fit: Fitter): Built
}

/** Everything a recipe is told. Times are seconds on the composition clock; the slot is in px. */
data class Cue(
    val text: String = "",
    /** When the beat lands (the spoken word, the hit). */
    val at: Float,
    /** When it must be gone. */
    val out: Float,
    /** Centre and size of the slot the layout solver gave this beat. */
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float,
    /** 0 calm … 1 explosive: scales speed, overshoot, camera and sound. */
    val energy: Float = 0.6f,
    /** Word indices (within [text]) that matter most. */
    val emphasis: Set<Int> = emptySet(),
    /** Spoken start time of each word of [text], when the text is said aloud. */
    val wordTimes: List<Float>? = null,
    val value: Float? = null,
    val from: Float? = null,
    val prefix: String = "",
    val suffix: String = "",
    val decimals: Int = 0,
    val points: List<Float>? = null,
    val icon: String? = null,
    val label: String? = null,
    /** A list the recipe shows one by one (tool chips, checklist rows, bar labels). */
    val items: List<String> = emptyList(),
    /** When each item is spoken, if it is. */
    val itemTimes: List<Float?> = emptyList(),
    /** How to mark emphasis ("block", "ink", "underline", "circle"); the look decides when null. */
    val mark: String? = null,
    /** True when footage is behind: text gets a shadow, cards get more body. */
    val overMedia: Boolean = false,
    val rtl: Boolean = true,
    val seed: Int = 1,
) {
    val duration: Float get() = out - at
    val words: List<String> get() = text.split(' ').filter { it.isNotEmpty() }
}

/** Measures text so recipes can fit it into their slot. */
fun interface Fitter {
    /** The largest size ≤ [preferred] at which [text] fits [maxWidth]×[maxHeight] in ≤ [maxLines] lines. */
    fun fit(text: String, voice: Look.Voice, preferred: Float, maxWidth: Float, maxHeight: Float, maxLines: Int): Fitted
}

data class Fitted(val type: TypeSpec, val width: Float, val height: Float, val lines: Int)

/** A camera reaction, added on top of the scene's own camera (zoom is an offset from 1). */
data class CameraMove(val zoom: Anim = Anim.Zero, val x: Anim = Anim.Zero, val y: Anim = Anim.Zero, val rotation: Anim = Anim.Zero)

enum class SfxKind { Whoosh, Hit, Pop, Click, Riser, Swish, Tick, Shimmer, Boom }

data class Sfx(val at: Float, val kind: SfxKind, val gain: Float = 1f)

/** A recipe's output: layers, plus how the camera and the soundtrack react. */
data class Built(
    val nodes: List<Node>,
    val camera: List<CameraMove> = emptyList(),
    val sfx: List<Sfx> = emptyList(),
    /** Overlays drawn above everything (flashes), in frame space. */
    val overlays: List<Node> = emptyList(),
)

/** All recipes by name, with aliases so loose names from small models still resolve. */
object Recipes {
    val all: List<Recipe> = TextRecipes.all + ElementRecipes.all + ExplainerRecipes.all

    private val aliases = mapOf(
        "rise" to "mask-rise", "reveal" to "mask-rise", "headline" to "mask-rise", "title" to "mask-rise",
        "impact" to "slam", "punch" to "slam", "hit" to "slam", "boom" to "slam",
        "caption" to "pop-captions", "captions" to "pop-captions", "karaoke" to "pop-captions", "subtitle" to "pop-captions",
        "typewriter" to "type-on", "type" to "type-on", "typing" to "type-on",
        "blur" to "blur-in", "soft" to "blur-in", "elegant" to "blur-in", "luxury" to "blur-in",
        "flip3d" to "flip", "3d" to "flip",
        "scatter" to "spread", "letters" to "spread",
        "poster" to "stack", "kinetic" to "stack",
        "rgb" to "glitch", "tech" to "glitch",
        "highlight" to "mask-rise", "marker" to "mask-rise",
        "number" to "counter", "count" to "counter", "stat" to "counter", "percent" to "counter", "price" to "ticker",
        "graph" to "chart", "line-chart" to "chart", "trend" to "chart",
        "name" to "lower-third", "lowerthird" to "lower-third", "badge" to "stamp", "seal" to "stamp",
        "tools" to "chips", "tags" to "chips", "pills" to "chips", "logos" to "chips",
        "code" to "terminal", "install" to "terminal", "cli" to "terminal", "command" to "terminal", "shell" to "terminal",
        "map" to "network", "nodes" to "network", "graph-map" to "network", "mindmap" to "network", "tree" to "network",
        "usage" to "meter", "compare" to "meter", "saving" to "meter", "savings" to "meter", "before-after" to "meter",
        "cta" to "comment", "comment-cta" to "comment", "keyword" to "comment", "dm" to "comment",
        "emoji" to "icon", "sticker" to "icon", "bar" to "bars", "bar-chart" to "bars", "checklist" to "list", "steps" to "list",
    )

    fun named(name: String?): Recipe? {
        val key = name?.trim()?.lowercase()?.replace('_', '-')?.replace(' ', '-') ?: return null
        val canonical = aliases[key] ?: key
        return all.firstOrNull { it.name == canonical }
    }
}
