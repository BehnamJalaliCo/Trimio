package io.trimio.core.model.style

import io.trimio.core.model.timeline.Anchor
import kotlin.math.pow
import kotlinx.serialization.Serializable

/**
 * Everything the renderer needs to draw one design style. Style packs (phase 4) are JSON files
 * decoded into this; the Director reads it too (e.g. which preset to use for emphasised words).
 *
 * Colours are `#RRGGBB` or `#RRGGBBAA`. Sizes are fractions of the canvas short edge so a style
 * looks identical at 720p and 4K, in portrait and landscape.
 *
 * Schema v2 adds [film] (film-texture and LUT packs) and [tokens] (the design-token sheet)
 * additively: a v1 pack decodes with both in their defaults.
 */
@Serializable
data class StyleSpec(
    val id: String,
    val family: StyleFamily,
    val palette: Palette,
    val captions: CaptionSpec = CaptionSpec(),
    val background: BackgroundSpec = BackgroundSpec(),
    val overlay: OverlaySpec = OverlaySpec(),
    val elements: ElementSpec = ElementSpec(),
    val motion: MotionSpec = MotionSpec(),
    /** Sound design per event, e.g. "caption.emphasis" → "sfx/pop". */
    val sfx: Map<String, String> = emptyMap(),
    /** Layout changes when there is no footage and the style paints the whole picture. */
    val audioOnly: AudioOnlySpec = AudioOnlySpec(),
    /**
     * Extra SkSL/AGSL runtime shaders shipped with the pack, by name. A background preset
     * `shader:<name>` uses one with the standard uniforms (iResolution, iTime, iEnergy, cBase, cA, cB, cC).
     */
    val shaders: Map<String, String> = emptyMap(),
    /** Which film texture overlay and colour LUT the style wears, and its finishing film preset. */
    val film: FilmSpec = FilmSpec(),
    /** The design-token sheet (schema v2): type scale, spacing, radius, semantic colour roles, accent variants. */
    val tokens: DesignTokens = DesignTokens(),
)

@Serializable
data class AudioOnlySpec(
    val captionAnchor: Anchor = Anchor.Center,
    /** Multiplies caption size: with no footage, type is the picture. */
    val captionScale: Float = 1.25f,
    /** ring, bars or none. */
    val visualizer: String = "ring",
)

@Serializable
data class Palette(
    val background: List<String>,
    val text: String,
    val accent: String,
    val accent2: String,
    val emphasisText: String = text,
    val shadow: String = "#00000099",
)

@Serializable
enum class CaptionMode {
    /** Words appear one by one as spoken and stay until the line ends. */
    BuildUp,

    /** The whole phrase appears; the spoken word is highlighted. */
    Phrase,

    /** The whole phrase appears; colour fills each word in reading direction as it is spoken. */
    Karaoke,

    /** One big word at a time, centre screen (kinetic typography). */
    SingleWord,
}

@Serializable
enum class BoxStyle {
    None,

    /** Rounded solid pill behind the line. */
    Pill,

    /** Hard offset shadow, thick border, flat fill (neobrutalism). */
    Brutal,

    /** Frosted translucent card with light rim. */
    Glass,

    /** Highlighter stroke behind emphasised words only. */
    Highlight,
}

@Serializable
data class CaptionSpec(
    val mode: CaptionMode = CaptionMode.BuildUp,
    /** Text size as a fraction of the canvas short edge. */
    val size: Float = 0.10f,
    val weight: Int = 800,
    val uppercase: Boolean = false,
    val letterSpacingEm: Float = -0.01f,
    val maxWordsPerLine: Int = 4,
    val anchor: Anchor = Anchor.BottomCenter,
    /** Entry preset: pop, rise, slam, wipe, flip, fade. */
    val entry: String = "pop",
    val exit: String = "fade",
    val box: BoxStyle = BoxStyle.None,
    val boxColor: String? = null,
    val strokeWidth: Float = 0f,
    val strokeColor: String = "#000000",
    val shadowBlur: Float = 0.012f,
    val emphasis: EmphasisSpec = EmphasisSpec(),
)

@Serializable
data class EmphasisSpec(
    val scale: Float = 1.18f,
    /** "accent", "accent2" or "text". */
    val colorRole: String = "accent",
    val box: BoxStyle = BoxStyle.None,
    /** Emphasis threshold on [io.trimio.core.model.transcript.Word.emphasis]. */
    val threshold: Float = 0.6f,
)

@Serializable
data class BackgroundSpec(
    /** aurora, gradient, mesh, grid, solid, waves. */
    val preset: String = "aurora",
    val audioReactive: Boolean = true,
    val params: Map<String, Float> = emptyMap(),
)

@Serializable
data class OverlaySpec(
    val grain: Float = 0.025f,
    val vignette: Float = 0.35f,
    val letterbox: Float = 0f,
)

@Serializable
data class ElementSpec(
    /** glass, solid, brutal, outline. */
    val card: String = "glass",
    val cornerRadius: Float = 0.04f,
)

@Serializable
data class MotionSpec(
    /** 0 calm … 1 hyper. Scales overshoot and speed of every preset. */
    val energy: Float = 0.6f,
    /** Punch-in zoom on footage at emphasised words. */
    val cameraPunch: Boolean = true,
)

/**
 * How the style wears the content packs' film material: which overlay of `overlay.film-textures`
 * (grain, dust, paper, halftone, light leak, bokeh) lies over the frame, which LUT of
 * `look.cinematic-luts` grades the footage, and which finishing film preset (the keys of
 * `Finishing.filmPresets`: clean, film, dream) the pass runs. Null texture or LUT: procedural
 * grain and the raw grade only.
 */
@Serializable
data class FilmSpec(
    /** Overlay id of `overlay.film-textures` ("grain-35mm", "paper-kraft", "leak-warm-1", …). */
    val texture: String? = null,
    /** How strongly the texture lies over the frame, 0..1. */
    val textureOpacity: Float = 0.3f,
    /** LUT id of `look.cinematic-luts` ("teal-orange", "warm-film", …); null grades nothing. */
    val lut: String? = null,
    /** Finishing film preset key: "clean", "film" or "dream". */
    val preset: String = "film",
    /** Grain the finishing pass adds on top of the preset (null: the preset's own, plus any grain texture). */
    val grain: Float? = null,
    /** Halation the finishing pass adds on top of the preset (null: the preset's own). */
    val halation: Float? = null,
)

/**
 * The style's token sheet (schema v2): the type scale, the spacing and radius steps, the semantic
 * colour roles and the accent variants — the numbers layouts and micro-variation read, so a pack
 * states its design system as data instead of burying it in presets. Additive: a pack without a
 * sheet gets these defaults, which match the house numbers.
 */
@Serializable
data class DesignTokens(
    val type: TypeScale = TypeScale(),
    val spacing: Spacing = Spacing(),
    val radius: Radius = Radius(),
    val colors: ColorRoles = ColorRoles(),
    /** Accent variants beyond [Palette.accent] (multi-accent); the palette's own accent leads. */
    val accents: List<String> = emptyList(),
)

/**
 * The type scale as a modular scale: display, title, caption and label sit at whole steps of [ratio]
 * around the caption line (step 0), so the roles never drift apart — a title is always one
 * `ratio` above its captions whatever the canvas.
 */
@Serializable
data class TypeScale(
    /** Step-to-step ratio: 1.2 the minor third, 1.25 the major third, 1.333 the perfect fourth. */
    val ratio: Float = DEFAULT_RATIO,
    /** Whole steps from the caption line; they must descend display > title > caption > label. */
    val display: Int = 3,
    val title: Int = 1,
    val caption: Int = 0,
    val label: Int = -1,
) {
    /** Size of [step] relative to the caption line: `ratio.pow(step)`. */
    fun sizeOf(step: Int): Float = ratio.pow(step)

    val displaySize: Float get() = sizeOf(display)
    val titleSize: Float get() = sizeOf(title)
    val captionSize: Float get() = sizeOf(caption)
    val labelSize: Float get() = sizeOf(label)

    companion object {
        const val DEFAULT_RATIO = 1.25f
    }
}

/** The spacing scale, in fractions of the canvas short edge (the same unit as every size). */
@Serializable
data class Spacing(val unit: Float = 0.02f) {
    val xs: Float get() = unit * 0.5f
    val sm: Float get() = unit
    val md: Float get() = unit * 2f
    val lg: Float get() = unit * 3f
    val xl: Float get() = unit * 4f
}

/** The radius scale for cards and blocks, in fractions of the canvas short edge (0 = Swiss sharp). */
@Serializable
data class Radius(val unit: Float = 0.04f) {
    val sm: Float get() = unit * 0.5f
    val md: Float get() = unit
    val lg: Float get() = unit * 2f
}

/**
 * Semantic colour roles over the palette: what a surface is called when a layout asks for one.
 * Null falls back to the palette ([Palette.background], [Palette.text], …) so a v1 pack keeps
 * answering; a set value wins. `primary`/`onPrimary` are the accent and its text.
 */
@Serializable
data class ColorRoles(
    val surface: String? = null,
    val onSurface: String? = null,
    val muted: String? = null,
    val primary: String? = null,
    val onPrimary: String? = null,
    val secondary: String? = null,
    val warning: String? = null,
    val danger: String? = null,
)
