package io.trimio.engine.motion

import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import io.trimio.core.brand.BrandFonts

/**
 * Level 0 of the motion language: a retained scene graph in the spirit of After Effects layers.
 * Every node has a transform (2D + 3D rotation with perspective), opacity, blur, an optional mask,
 * a blend mode and a visibility window. Nothing here knows about captions or styles — recipes
 * (level 2) build these nodes, the compiler (level 3) places them in time and space.
 */
class Composition(
    val width: Int,
    val height: Int,
    val fps: Int,
    /** Seconds. */
    val duration: Float,
    val background: Color,
    val root: Group,
    val camera: Camera = Camera(),
    /** Shutter angle in degrees (180 = film look). 0 disables motion blur. */
    val shutterAngle: Float = 180f,
    /** Temporal samples per frame when motion blur is on. */
    val shutterSamples: Int = 6,
) {
    val frameCount: Int get() = (duration * fps).toInt()

    /** The same piece sampled at [rate] frames a second: every animation runs on seconds, so only the frames change. */
    fun withFps(rate: Int) = Composition(width, height, rate, duration, background, root, camera, shutterAngle, shutterSamples)

    /**
     * The same piece over a transparent canvas: the graphics layer for another editor, where the
     * creator's footage is cut underneath (see the alpha export).
     */
    fun transparent() = Composition(width, height, fps, duration, Color.Transparent, root, camera, shutterAngle, shutterSamples)
}

/** A camera over the whole frame: push-ins, punches, whips and handheld shake. */
class Camera(
    val zoom: Anim = Anim.One,
    val x: Anim = Anim.Zero,
    val y: Anim = Anim.Zero,
    /** Degrees. */
    val rotation: Anim = Anim.Zero,
)

/**
 * Where a node sits: its [anchor] (fraction of its own bounds, 0.5/0.5 = centre) is placed at
 * [x], [y] in the parent. Rotations are degrees; [rotationX]/[rotationY] tilt in 3D, seen through
 * a camera [perspective] pixels away.
 */
class Transform(
    val x: Anim = Anim.Zero,
    val y: Anim = Anim.Zero,
    val anchorX: Float = 0.5f,
    val anchorY: Float = 0.5f,
    val scale: Anim = Anim.One,
    val scaleX: Anim = Anim.One,
    val scaleY: Anim = Anim.One,
    val rotation: Anim = Anim.Zero,
    val rotationX: Anim = Anim.Zero,
    val rotationY: Anim = Anim.Zero,
    val skewX: Anim = Anim.Zero,
    val opacity: Anim = Anim.One,
    /** Gaussian-like blur radius in px. */
    val blur: Anim = Anim.Zero,
    val perspective: Float = 1400f,
) {
    fun copy(
        x: Anim = this.x, y: Anim = this.y, anchorX: Float = this.anchorX, anchorY: Float = this.anchorY,
        scale: Anim = this.scale, scaleX: Anim = this.scaleX, scaleY: Anim = this.scaleY, rotation: Anim = this.rotation,
        rotationX: Anim = this.rotationX, rotationY: Anim = this.rotationY, skewX: Anim = this.skewX,
        opacity: Anim = this.opacity, blur: Anim = this.blur, perspective: Float = this.perspective,
    ) = Transform(x, y, anchorX, anchorY, scale, scaleX, scaleY, rotation, rotationX, rotationY, skewX, opacity, blur, perspective)
}

/** A colour that can change over time (interpolated perceptually, in Oklab). */
class ColorAnim private constructor(private val keys: List<Pair<Anim.Key, Color>>) {
    fun at(t: Float): Color {
        if (keys.size == 1 || t <= keys.first().first.t) return keys.first().second
        if (t >= keys.last().first.t) return keys.last().second
        var i = 1
        while (keys[i].first.t < t) i++
        val (ka, a) = keys[i - 1]
        val (kb, b) = keys[i]
        val span = kb.t - ka.t
        return if (span <= 0f) b else lerp(a, b, kb.ease.at((t - ka.t) / span).coerceIn(0f, 1f))
    }

    companion object {
        fun of(color: Color) = ColorAnim(listOf(Anim.Key(0f, 0f, Easing.Hold) to color))
        fun keys(vararg keys: Triple<Float, Color, Easing>) =
            ColorAnim(keys.sortedBy { it.first }.map { (t, c, e) -> Anim.Key(t, 0f, e) to c })
        fun tween(from: Color, to: Color, t0: Float, t1: Float, ease: Easing = Easing.Standard) =
            keys(Triple(t0, from, Easing.Hold), Triple(t1, to, ease))
    }
}

val Color.anim: ColorAnim get() = ColorAnim.of(this)

/** How a surface is painted. */
sealed interface Fill {
    data class Solid(val color: ColorAnim) : Fill
    /** Linear gradient across the node's bounds at [angle] degrees (0 = left→right). */
    data class Linear(val colors: List<Color>, val angle: Anim = Anim.Zero, val stops: List<Float>? = null) : Fill
    /** Radial gradient from the bounds' centre (offset by [cx], [cy] fractions). */
    data class Radial(val colors: List<Color>, val cx: Anim = 0.5f.anim, val cy: Anim = 0.5f.anim, val radius: Anim = 0.7f.anim) : Fill
}

fun Color.fill(): Fill = Fill.Solid(anim)

data class Stroke(val fill: Fill, val width: Float, val round: Boolean = true)

data class Shadow(val color: Color, val dx: Float = 0f, val dy: Float = 8f, val blur: Float = 24f)

/**
 * How text over footage keeps a contrast floor whatever is behind it. The renderer samples the
 * footage under the text every frame and, only as much as needed to reach [floor] (WCAG-style
 * ratio) against the brightest (for light ink) or darkest (for dark ink) part of it, adds the
 * look's guard in the tone opposite the ink: a soft glyph [Style.Halo] or a stepped [Style.Plate].
 */
data class Guard(
    val style: Style, val light: Color, val dark: Color, val floor: Float = 4.5f,
    /** Plate corner radius as a fraction of the font size (0 for Swiss-sharp looks). */
    val radius: Float = 0.2f,
) {
    enum class Style { Halo, Plate }
}

/** Alpha mask drawn in the masked node's local space. */
data class Mask(val node: Node, val invert: Boolean = false)

/** The base of every layer. [start]/[end] bound when it exists (seconds, composition time). */
sealed class Node(
    val transform: Transform,
    val start: Float,
    val end: Float,
    val mask: Mask?,
    val blend: BlendMode,
    val name: String,
) {
    fun isAlive(t: Float) = t >= start && t < end
}

class Group(
    val children: List<Node>,
    transform: Transform = Transform(anchorX = 0f, anchorY = 0f),
    /** Explicit bounds (for anchoring); defaults to the composition frame when null. */
    val width: Float? = null,
    val height: Float? = null,
    start: Float = 0f,
    end: Float = Float.POSITIVE_INFINITY,
    mask: Mask? = null,
    blend: BlendMode = BlendMode.SrcOver,
    name: String = "group",
    /** A distortion over everything the group draws (glitch, ripple, lens, film halation…), see [Filter]. */
    val filter: Filter? = null,
) : Node(transform, start, end, mask, blend, name)

/**
 * A runtime-shader distortion of a group's pixels: the group renders off-screen and is drawn
 * through [family] (a filter family). [amount] 0 leaves it untouched (and costs nothing);
 * [progress] drives travelling distortions such as a ripple's ring.
 */
data class Filter(val family: ShaderFamily, val amount: Anim, val progress: Anim = Anim.Zero, val seed: Int = 1)

/**
 * The shader families: stage backgrounds (light, particles, waves) and distortions of what is
 * already drawn. Written once in SkSL ([FxSources]); each has a CPU fallback for devices whose
 * compiler rejects it. [id] is the name styles use (`StyleLanguage.background`, [named]).
 */
enum class ShaderFamily(val id: String, val filter: Boolean) {
    /** Volumetric glow: a light above the frame with rays through haze. */
    Glow("glow", false),
    /** Warm anamorphic light leaks drifting in from the edges. */
    Leaks("leaks", false),
    /** Out-of-focus discs drifting up. */
    Bokeh("bokeh", false),
    /** Motes in a volumetric beam. */
    Dust("dust", false),
    /** A liquid, domain-warped colour gradient. */
    Liquid("liquid", false),
    Chromatic("chromatic", true),
    Lens("lens", true),
    Glitch("glitch", true),
    Ripple("ripple", true),
    /** Film halation: highlights bleed red. */
    Halation("halation", true),
    ;

    companion object {
        private val aliases = mapOf(
            "light" to Glow, "volumetric" to Glow, "rays" to Glow, "god-rays" to Glow, "spotlight" to Glow,
            "leak" to Leaks, "light-leak" to Leaks, "light-leaks" to Leaks, "film-leak" to Leaks,
            "particles" to Dust, "motes" to Dust, "bokeh-lights" to Bokeh,
            "waves" to Liquid, "wave" to Liquid, "gradient" to Liquid, "mesh" to Liquid, "fluid" to Liquid, "glass" to Liquid,
            "rgb" to Chromatic, "aberration" to Chromatic, "chromatic-aberration" to Chromatic,
            "barrel" to Lens, "distortion" to Lens, "film" to Halation, "halo" to Halation, "displace" to Ripple,
        )

        /** The family a style or a small model names (loose: "light-leak", "particles", "film"), or null. */
        fun named(name: String?): ShaderFamily? {
            val key = name?.trim()?.lowercase()?.replace(' ', '-')?.replace('_', '-') ?: return null
            return entries.firstOrNull { it.id == key } ?: aliases[key]
        }
    }
}

/** Typeface choice by brand role; weight picks the variable instance or nearest static cut. */
data class TypeSpec(
    val role: BrandFonts.Role,
    val weight: Int,
    /** px */
    val size: Float,
    /** Multiple of [size]. */
    val lineHeight: Float = 1.15f,
    /** em */
    val tracking: Float = 0f,
)

enum class TextAlign { Start, Center, End }

/**
 * Text, broken into balanced lines inside [maxWidth], with per-unit animators (characters, words
 * or lines) and per-word decorations (highlighter blocks, underlines, colour flips). Persian stays
 * correctly shaped while letters animate: units are drawn by clipping the shaped line.
 */
class TextNode(
    val text: String,
    val type: TypeSpec,
    val fill: Fill,
    val maxWidth: Float = Float.POSITIVE_INFINITY,
    val align: TextAlign = TextAlign.Center,
    val rtl: Boolean = true,
    val animators: List<TextAnimator> = emptyList(),
    val decorations: List<Decoration> = emptyList(),
    val stroke: Stroke? = null,
    val shadow: Shadow? = null,
    transform: Transform = Transform(),
    start: Float = 0f,
    end: Float = Float.POSITIVE_INFINITY,
    mask: Mask? = null,
    blend: BlendMode = BlendMode.SrcOver,
    name: String = "text",
) : Node(transform, start, end, mask, blend, name) {
    /** Sits on a body of its own (a card, a bubble, a pill): its contrast is designed, not guarded. */
    var onBody: Boolean = false

    /**
     * The colour of the body it was designed on (a card, a pill, a tag), when the recipe knows it:
     * tests and the jury hold its ink against it to the contrast floor.
     */
    var ground: Color? = null

    /** Set by the compiler on text over footage; see [Guard]. */
    var guard: Guard? = null
}

/** A number that rolls from one value to another (prices, percentages, counts). */
class CounterNode(
    val value: Anim,
    val decimals: Int,
    val prefix: String = "",
    val suffix: String = "",
    val persianDigits: Boolean = true,
    val grouping: Boolean = true,
    val type: TypeSpec,
    val fill: Fill,
    val shadow: Shadow? = null,
    transform: Transform = Transform(),
    start: Float = 0f,
    end: Float = Float.POSITIVE_INFINITY,
    mask: Mask? = null,
    blend: BlendMode = BlendMode.SrcOver,
    name: String = "counter",
) : Node(transform, start, end, mask, blend, name) {
    /** See [TextNode.onBody]. */
    var onBody: Boolean = false

    /** See [TextNode.ground]. */
    var ground: Color? = null

    /** See [TextNode.guard]. */
    var guard: Guard? = null
}

sealed interface ShapeSpec {
    data class Rect(val width: Anim, val height: Anim, val radius: Anim = Anim.Zero) : ShapeSpec
    data class Ellipse(val width: Anim, val height: Anim) : ShapeSpec
    /** SVG path data in a [viewport]-sized box, scaled to [size] px. */
    data class Path(val data: String, val viewport: Float, val size: Float) : ShapeSpec
    /** A polyline through points (chart lines), in px. */
    data class Polyline(val points: List<Pair<Float, Float>>, val smooth: Boolean = true) : ShapeSpec
}

class ShapeNode(
    val shape: ShapeSpec,
    val fill: Fill? = null,
    val stroke: Stroke? = null,
    /** Draw-on: the visible portion of the outline, 0..1 (strokes only). */
    val trimStart: Anim = Anim.Zero,
    val trimEnd: Anim = Anim.One,
    val shadow: Shadow? = null,
    transform: Transform = Transform(),
    start: Float = 0f,
    end: Float = Float.POSITIVE_INFINITY,
    mask: Mask? = null,
    blend: BlendMode = BlendMode.SrcOver,
    name: String = "shape",
) : Node(transform, start, end, mask, blend, name)

/**
 * An icon from the visual vocabulary. [assemble] 0→1 builds it layer by layer (each part pops
 * into place in drawing order); single-colour icons take [tint].
 */
class VectorNode(
    val icon: io.trimio.engine.motion.visual.VectorIcon,
    val size: Float,
    val tint: ColorAnim = Color.White.anim,
    val assemble: Anim = Anim.One,
    val shadow: Shadow? = null,
    transform: Transform = Transform(),
    start: Float = 0f,
    end: Float = Float.POSITIVE_INFINITY,
    mask: Mask? = null,
    blend: BlendMode = BlendMode.SrcOver,
    name: String = "vector",
) : Node(transform, start, end, mask, blend, name) {
    /**
     * For an animated picture ([io.trimio.engine.motion.visual.VectorIcon.motion]): seconds into its
     * own animation at composition time t. Null plays it from the start of [assemble] (or [start]).
     */
    var play: Anim? = null

    val width: Float get() = if (icon.width >= icon.height) size else size * icon.width / icon.height
    val height: Float get() = if (icon.height >= icon.width) size else size * icon.height / icon.width
}

/** Colour grade for footage: exposure, contrast, saturation and a warm/cool tint. */
data class Grade(
    val exposure: Anim = Anim.Zero,
    val contrast: Anim = Anim.One,
    val saturation: Anim = Anim.One,
    /** -1 cool … +1 warm. */
    val temperature: Anim = Anim.Zero,
    /**
     * Draw the picture as ink on whatever is behind it: its darks become this colour, its paper turns
     * transparent (a pencil sketch over a coloured ground). Null draws it as it is.
     */
    val ink: androidx.compose.ui.graphics.Color? = null,
)

enum class Fit { Cover, Contain }

/** Footage or a still, fetched from the [MediaSource] at render time. */
class MediaNode(
    val source: String,
    val width: Float,
    val height: Float,
    val fit: Fit = Fit.Cover,
    /** Seconds into the source at composition time [start]. */
    val sourceOffset: Float = 0f,
    val speed: Float = 1f,
    val grade: Grade = Grade(),
    val radius: Anim = Anim.Zero,
    transform: Transform = Transform(),
    start: Float = 0f,
    end: Float = Float.POSITIVE_INFINITY,
    mask: Mask? = null,
    blend: BlendMode = BlendMode.SrcOver,
    name: String = "media",
    /** Only the person in the picture ([MediaSource.matte]); nothing at all when no matte can be had. */
    val matte: Boolean = false,
) : Node(transform, start, end, mask, blend, name)

/** Procedural full-frame effects: backgrounds, light and texture. */
sealed interface Effect {
    /** Slow drifting multi-point gradient (mesh-like), the base of audio-only scenes. */
    data class Aurora(val colors: List<Color>, val speed: Float = 0.08f, val seed: Int = 1) : Effect
    /** Film grain; [amount] 0..1. */
    data class Grain(val amount: Float = 0.04f, val seed: Int = 7) : Effect
    data class Vignette(val amount: Anim = 0.45f.anim) : Effect
    /** A warm light leak sweeping across, added on top. */
    data class LightLeak(val color: Color, val progress: Anim, val strength: Anim = 0.55f.anim) : Effect
    /** Full-frame flash (impact frames). */
    data class Flash(val color: Color, val opacity: Anim) : Effect
    /** A scrim to keep text readable over footage: dark from [edge] fading to clear. */
    data class Scrim(val fromBottom: Boolean, val coverage: Float, val opacity: Anim = 0.75f.anim) : Effect
    /** Repeating hairline grid (Swiss/data looks). */
    data class Grid(val step: Float, val color: Color, val opacity: Anim = Anim.One) : Effect

    /**
     * A stage background drawn by a runtime shader ([ShaderFamily], not a filter). [colors] are the
     * canvas, accent, hot and ink colours; [overlay] draws it as light only (screened, black base),
     * for leaks or dust over footage.
     */
    data class Shader(
        val family: ShaderFamily, val colors: List<Color>, val amount: Anim = Anim.One, val seed: Int = 1, val overlay: Boolean = false,
    ) : Effect

    /**
     * Frosted glass behind a card: the [stage] field re-drawn inside a rounded panel ([x], [y],
     * [width], [height] in px, frame space), refracted at the rim and frosted. The stage is
     * procedural, so the glass needs no read-back of the frame. Over footage there is no glass.
     */
    data class Glass(
        val stage: Shader, val x: Float, val y: Float, val width: Float, val height: Float, val radius: Float,
        val frost: Float = 1f, val opacity: Anim = Anim.One,
    ) : Effect
}

class EffectNode(
    val effect: Effect,
    transform: Transform = Transform(anchorX = 0f, anchorY = 0f),
    start: Float = 0f,
    end: Float = Float.POSITIVE_INFINITY,
    blend: BlendMode = BlendMode.SrcOver,
    name: String = "effect",
) : Node(transform, start, end, null, blend, name)
