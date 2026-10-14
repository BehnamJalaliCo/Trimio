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

    /**
     * Treatments of the same device (layout × choreography): the compiler picks one per beat
     * ([Variety]) and hands it over as [Cue.variant]; 0 is the recipe's signature form.
     */
    open val variants: Int = 1

    /** How many of the [variants] are distinct layouts (the rest are other moves of them). */
    open val layouts: Int = 1

    /**
     * How much the look wants each treatment (a Swiss look never bounces, a calm one never slams);
     * zero rules one out. One weight per variant.
     */
    open fun variantWeights(look: Look, cue: Cue): List<Float> = List(variants) { 1f }

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
    /** Brand marks for the items, when they are brands. */
    val marks: List<BrandMark> = emptyList(),
    /** Pictures from the visual vocabulary: the beat's own, and one per item. */
    val visual: io.trimio.engine.motion.visual.VectorIcon? = null,
    val itemVisuals: List<io.trimio.engine.motion.visual.VectorIcon?> = emptyList(),
    /** How to mark emphasis ("block", "ink", "underline", "circle"); the look decides when null. */
    val mark: String? = null,
    /** True when footage is behind: text gets a shadow, cards get more body. */
    val overMedia: Boolean = false,
    val rtl: Boolean = true,
    val seed: Int = 1,
    /** The creator's own picture or clip, by media source id, and its width ÷ height. */
    val media: String? = null,
    val aspect: Float = 1f,
    /** The frame, in px: a full-frame cut-in fills it whatever slot it was given. */
    val frameWidth: Float = 0f,
    val frameHeight: Float = 0f,
    /** Which of the recipe's treatments ([Recipe.variants]) to build; 0 is its signature form. */
    val variant: Int = 0,
    /**
     * The edit's variety seed ([io.trimio.engine.motion.score.Score.variety]): 0 keeps every
     * finishing touch in its signature form, any other value lets them vary ([Variety.touch]).
     */
    val variety: Int = 0,
    /** Who says it, for spoken captions (0, 1, … when two voices or more were heard): each voice gets its colour. */
    val speaker: Int = 0,
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

/** Sound cues. [Rewind] is a reverse swell that peaks 0.36 s after its start (a cold open's restart). */
enum class SfxKind { Whoosh, Hit, Pop, Click, Riser, Swish, Tick, Shimmer, Boom, Rewind }

data class Sfx(val at: Float, val kind: SfxKind, val gain: Float = 1f)

/** A recipe's output: layers, plus how the camera and the soundtrack react. */
data class Built(
    val nodes: List<Node>,
    val camera: List<CameraMove> = emptyList(),
    val sfx: List<Sfx> = emptyList(),
    /** Overlays drawn above everything (flashes), in frame space. */
    val overlays: List<Node> = emptyList(),
    /**
     * Where the graphic sits once it has landed, in frame px, when the recipe knows it tighter than
     * its slot: captions and other graphics keep off it ([Box.overlaps]); null means the whole slot.
     */
    val box: Box? = null,
)

/** A rectangle in frame px: what a graphic covers at rest (see [Built.box]). */
data class Box(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f

    fun overlaps(o: Box, margin: Float = 0f): Boolean =
        left < o.right + margin && o.left < right + margin && top < o.bottom + margin && o.top < bottom + margin

    /** Whether it lies inside a [width] × [height] frame with [margin] px to spare on every side. */
    fun inside(width: Float, height: Float, margin: Float = 0f): Boolean =
        left >= margin && top >= margin && right <= width - margin && bottom <= height - margin

    companion object {
        fun around(cx: Float, cy: Float, w: Float, h: Float) = Box(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }
}

/** All recipes by name, with aliases so loose names from small models still resolve. */
object Recipes {
    val all: List<Recipe> = TextRecipes.all + ElementRecipes.all + ExplainerRecipes.all + BrandRecipes.all + ObjectRecipes.all + DataRecipes.all + MessageRecipes.all +
        UiRecipes.all + MediaRecipes.all + YouTubeRecipes.all + Families.all + io.trimio.engine.motion.art.AnimatedRecipes.all

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
        "logo" to "logos", "brands" to "logos", "brand" to "logos", "apps" to "logos", "tools" to "logos", "tags" to "chips", "pills" to "chips",
        "code" to "terminal", "install" to "terminal", "cli" to "terminal", "command" to "terminal", "shell" to "terminal",
        "map" to "network", "nodes" to "network", "graph-map" to "network", "mindmap" to "network", "tree" to "network",
        "usage" to "meter", "compare" to "meter", "saving" to "meter", "savings" to "meter",
        "cta" to "comment", "comment-cta" to "comment", "keyword" to "comment",
        "sticker" to "object", "emoji" to "object", "illustration" to "object", "prop" to "object",
        "picture" to "object", "visual" to "object",
        "stickers" to "objects", "ingredients" to "objects", "items" to "objects", "things" to "objects",
        "bar" to "bars", "bar-chart" to "bars", "checklist" to "list",
        "timer" to "countdown", "deadline" to "countdown", "time-left" to "countdown", "hours-left" to "countdown", "urgency" to "countdown",
        "clock" to "countdown", "countdown-timer" to "countdown", "expires" to "countdown", "last-chance" to "countdown",
        "kpi" to "stats", "kpis" to "stats", "metrics" to "stats", "numbers" to "stats", "figures" to "stats", "statistics" to "stats",
        "stat-row" to "stats", "proof" to "stats",
        "progress-bar" to "progress", "capacity" to "progress", "filled" to "progress", "quota" to "progress",
        "spots" to "progress", "seats" to "progress", "loading" to "progress", "sold-out" to "progress",
        "coupon" to "voucher", "ticket" to "voucher", "gift-card" to "voucher", "giftcard" to "voucher", "gift" to "voucher",
        "chat" to "message", "dm" to "message", "direct" to "message", "message-thread" to "message", "sms" to "message",
        "testimonial-chat" to "message", "messages" to "message", "conversation" to "message", "chat-thread" to "message",
        "address" to "url", "url-bar" to "url", "domain" to "url", "address-bar" to "url",
        "broll" to "b-roll", "cutaway" to "b-roll", "cut-in" to "b-roll", "picture-in-picture" to "pip", "inset" to "pip",
        "product-card" to "product", "packshot" to "product", "device" to "screenshot", "user-logo" to "logo-mark",
        "app-screen" to "ui-feed", "feed" to "ui-feed", "app-feed" to "ui-feed", "scroll" to "ui-feed", "social-feed" to "ui-feed",
        "code-editor" to "ui-code", "editor" to "ui-code", "ide" to "ui-code", "coding" to "ui-code",
        "dashboard" to "ui-dashboard", "analytics" to "ui-dashboard", "admin" to "ui-dashboard", "report" to "ui-dashboard",
        "notifications" to "ui-chat", "notification" to "ui-chat", "push" to "ui-chat", "alerts" to "ui-chat",
        "browser" to "ui-browser", "website" to "ui-browser", "web-page" to "ui-browser", "landing" to "ui-browser", "landing-page" to "ui-browser", "site" to "ui-browser",
        "icon-scene" to "ui-scene", "vector" to "ui-scene", "icons" to "ui-scene", "element" to "ui-scene",
        "reward" to "voucher", "bonus" to "voucher", "airdrop" to "voucher", "prize" to "voucher", "promo-code" to "voucher",
    )

    fun named(name: String?): Recipe? {
        val key = name?.trim()?.lowercase()?.replace('_', '-')?.replace(' ', '-') ?: return null
        val canonical = aliases[key] ?: Families.aliases[key] ?: key
        return all.firstOrNull { it.name == canonical }
    }
}
