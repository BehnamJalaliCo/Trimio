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
    val sfx: Map<String, String> = emptyMap(),
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
    val captionScale: Float = 1.25f,
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
    BuildUp,
    Phrase,
    Karaoke,
    SingleWord,
}

@Serializable
enum class BoxStyle {
    None,
    Pill,
    Brutal,
    Glass,
    Highlight,
}

@Serializable
data class CaptionSpec(
    val mode: CaptionMode = CaptionMode.BuildUp,
    val size: Float = 0.10f,
    val weight: Int = 800,
    val uppercase: Boolean = false,
    val letterSpacingEm: Float = -0.01f,
    val maxWordsPerLine: Int = 4,
    val anchor: Anchor = Anchor.BottomCenter,
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
    val colorRole: String = "accent",
    val box: BoxStyle = BoxStyle.None,
    val threshold: Float = 0.6f,
)

@Serializable
data class BackgroundSpec(
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
    val card: String = "glass",
    val cornerRadius: Float = 0.04f,
)

@Serializable
data class MotionSpec(
    val energy: Float = 0.6f,
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
    val texture: String? = null,
    val textureOpacity: Float = 0.3f,
    val lut: String? = null,
    val preset: String = "film",
    val grain: Float? = null,
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
    val accents: List<String> = emptyList(),
)

/**
 * The type scale as a modular scale: display, title, caption and label sit at whole steps of [ratio]
 * around the caption line (step 0), so the roles never drift apart — a title is always one
 * `ratio` above its captions whatever the canvas.
 */
@Serializable
data class TypeScale(
    val ratio: Float = DEFAULT_RATIO,
    val display: Int = 3,
    val title: Int = 1,
    val caption: Int = 0,
    val label: Int = -1,
) {
    fun sizeOf(step: Int): Float = ratio.pow(step)

    val displaySize: Float get() = sizeOf(display)
    val titleSize: Float get() = sizeOf(title)
    val captionSize: Float get() = sizeOf(caption)
    val labelSize: Float get() = sizeOf(label)

    companion object {
        const val DEFAULT_RATIO = 1.25f
    }
}

@Serializable
data class Spacing(val unit: Float = 0.02f) {
    val xs: Float get() = unit * 0.5f
    val sm: Float get() = unit
    val md: Float get() = unit * 2f
    val lg: Float get() = unit * 3f
    val xl: Float get() = unit * 4f
}

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
