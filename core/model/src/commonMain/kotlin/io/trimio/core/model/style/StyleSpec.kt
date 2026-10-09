package io.trimio.core.model.style

import io.trimio.core.model.timeline.Anchor
import kotlinx.serialization.Serializable

/**
 * Everything the renderer needs to draw one design style. Style packs (phase 4) are JSON files
 * decoded into this; the Director reads it too (e.g. which preset to use for emphasised words).
 *
 * Colours are `#RRGGBB` or `#RRGGBBAA`. Sizes are fractions of the canvas short edge so a style
 * looks identical at 720p and 4K, in portrait and landscape.
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
