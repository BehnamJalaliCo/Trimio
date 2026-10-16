package io.trimio.engine.motion.art

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance

/**
 * An animated picture — a Lottie animation or an animated SVG icon — drawn as a pure function of
 * its own clock into a [width] × [height] box of a DrawScope. The same drawing code runs on
 * Android and on the desktop (Compose graphics), so previews and exports on both look the same.
 *
 * It travels inside a [io.trimio.engine.motion.visual.VectorIcon] ([io.trimio.engine.motion.visual.VectorIcon.motion]):
 * every recipe that shows a picture plays it, and the director's picture search finds it beside
 * the still icons.
 */
interface MotionArt {
    val width: Float
    val height: Float

    /** Seconds of one play (the build of a draw-on icon; one cycle of a looping emoji). */
    val duration: Float

    /** Keeps moving after [duration] (a looping emoji, a spinner); otherwise it rests on its last frame. */
    val loops: Boolean

    /** Carries its own colours (an emoji); otherwise it is drawn in the node's tint (a line icon). */
    val colourful: Boolean

    /** What it was read from: [FORMAT_LOTTIE] or [FORMAT_SVG]. */
    val format: String

    /** The document itself, so a delivered edit can draw it again with no network ([MotionArts.read]). */
    val document: String

    /** Draws the frame [time] seconds after the art started to play (≥ 0) into its own box. */
    fun draw(scope: DrawScope, time: Float, paint: ArtPaint)

    /** A template's text slots ([MotionArts.template]), in reading order; empty for a picture. */
    val slots: List<TemplateSlot> get() = emptyList()

    /** A template's rest, seconds: built in before it, leaving after it; null when it only builds in (it then holds its last frame). */
    val hold: ClosedFloatingPointRange<Float>? get() = null

    companion object {
        const val FORMAT_LOTTIE = "lottie"
        const val FORMAT_SVG = "svg"
    }
}

/**
 * How a [MotionArt] is painted: the node's [tint], an overall [alpha], how its colours follow the
 * look, mirrored for right-to-left reading, and a [halo] tone drawn soft and wide under a line
 * drawing so it reads over any footage (null: none).
 */
data class ArtPaint(
    val tint: Color,
    val alpha: Float = 1f,
    val colouring: Colouring = Colouring.Auto,
    val mirror: Boolean = false,
    val halo: Color? = null,
    /** Draws a template's text slots with the edit's words; null leaves them empty. */
    val text: SlotText? = null,
)

/**
 * A text slot of a template as its designer set it: the placeholder [text] (never shown), its
 * [size] and [color], the justification (0 left, 1 right, 2 centre), tracking in thousandths of an
 * em, leading in px, and the box it wraps in ([box]: left, top, width, height) or null for point text
 * whose origin is the first line's baseline.
 */
data class TemplateSlot(
    val index: Int,
    val text: String,
    val size: Float,
    val bold: Boolean,
    val color: Color,
    val stroke: Color?,
    val strokeWidth: Float,
    val justify: Int,
    val tracking: Float,
    val leading: Float,
    val box: androidx.compose.ui.geometry.Rect?,
)

/** Draws [slot] at the slot's own origin (the template's transforms already applied), its words moved by [words] (index, count). */
fun interface SlotText {
    fun draw(scope: DrawScope, slot: TemplateSlot, words: (Int, Int) -> io.trimio.engine.motion.UnitState, alpha: Float)
}

/** How an animation's colours meet the look's palette. */
sealed interface Colouring {
    /** Its own colours when it is colourful, the tint when it is a single-colour drawing. */
    data object Auto : Colouring

    /** Always its own colours. */
    data object Own : Colouring

    /** Every colour becomes the tint (alpha kept): a silhouette in the look's ink. */
    data object Tint : Colouring

    /** Shades mapped from [dark] to [light] by their luminance: an emoji redrawn in the look's two colours. */
    data class Duotone(val dark: Color, val light: Color) : Colouring

    companion object {
        /** The colour [c] becomes under [colouring] for an art that is [colourful], drawn with [tint]. */
        fun map(c: Color, colouring: Colouring, colourful: Boolean, tint: Color): Color = when (colouring) {
            Own -> c
            Auto -> if (colourful) c else tint.copy(alpha = tint.alpha * c.alpha)
            Tint -> tint.copy(alpha = tint.alpha * c.alpha)
            is Duotone -> lerp(colouring.dark, colouring.light, c.luminance().coerceIn(0f, 1f)).copy(alpha = c.alpha)
        }
    }
}

/** Reads animation documents by format. */
object MotionArts {
    /** The animation in [document] ([MotionArt.FORMAT_LOTTIE] JSON or [MotionArt.FORMAT_SVG] icon body), or null when it cannot be drawn faithfully. */
    fun read(format: String, document: String, width: Float = DEFAULT_SVG_BOX, height: Float = DEFAULT_SVG_BOX, loops: Boolean? = null): MotionArt? =
        readOrWhy(format, document, width, height, loops).first

    /** A Lottie template: its text layers kept as [MotionArt.slots]; plays once and holds its last frame unless [loops]. */
    fun template(document: String, loops: Boolean = false): Pair<MotionArt?, String?> =
        try {
            LottieArt(LottieReader.read(document, texts = true), document, loops) to null
        } catch (e: Unsupported) {
            null to e.message
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            null to "unreadable: ${e.message}"
        }

    /** As [read], with the reason when it is refused. */
    fun readOrWhy(format: String, document: String, width: Float = DEFAULT_SVG_BOX, height: Float = DEFAULT_SVG_BOX, loops: Boolean? = null): Pair<MotionArt?, String?> =
        try {
            when (format) {
                MotionArt.FORMAT_LOTTIE -> LottieArt(LottieReader.read(document), document, loops ?: true) to null
                MotionArt.FORMAT_SVG -> SvgMotionArt(SvgMotionReader.read(document), document, width, height) to null
                else -> null to "unknown format $format"
            }
        } catch (e: Unsupported) {
            null to e.message
        } catch (@Suppress("TooGenericExceptionCaught") e: RuntimeException) {
            null to "unreadable: ${e.message}"
        }

    const val DEFAULT_SVG_BOX = 24f
}

/** A [LottieAnimation] as [MotionArt]. */
internal class LottieArt(private val animation: LottieAnimation, override val document: String, override val loops: Boolean) : MotionArt {
    override val width: Float get() = animation.width
    override val height: Float get() = animation.height
    override val duration: Float get() = animation.duration
    override val format: String get() = MotionArt.FORMAT_LOTTIE

    override val colourful: Boolean by lazy {
        animation.colours.any { c -> c.size >= 3 && (maxOf(c[0], c[1], c[2]) - minOf(c[0], c[1], c[2])) > CHROMA }
    }

    override val hold: ClosedFloatingPointRange<Float>? get() = animation.hold

    override val slots: List<TemplateSlot> by lazy {
        animation.texts.map { t ->
            val d = t.doc
            val box = d.boxSize?.takeIf { it.size >= 2 && it[0] > 0f }?.let { sz ->
                val ps = d.boxPosition ?: floatArrayOf(0f, 0f)
                androidx.compose.ui.geometry.Rect(ps.getOrElse(0) { 0f }, ps.getOrElse(1) { 0f }, ps.getOrElse(0) { 0f } + sz[0], ps.getOrElse(1) { 0f } + sz[1])
            }
            TemplateSlot(
                index = t.slot, text = d.text, size = d.size, bold = BOLD.containsMatchIn(d.font), color = colour(d.fill),
                stroke = d.stroke?.takeIf { d.strokeWidth > 0f }?.let(::colour), strokeWidth = d.strokeWidth,
                justify = d.justify, tracking = d.tracking, leading = d.lineHeight, box = box,
            )
        }
    }

    override fun draw(scope: DrawScope, time: Float, paint: ArtPaint) {
        val local = if (loops) time.mod(duration) else time.coerceIn(0f, duration - 1f / animation.frameRate)
        LottiePainter(animation, paint, colourful, if (paint.text != null) slots else emptyList()).draw(scope, animation.inPoint + local.coerceAtLeast(0f) * animation.frameRate)
    }

    private fun colour(c: FloatArray): Color {
        val scale = if (c.any { it > 1f }) 255f else 1f
        fun ch(i: Int) = (c.getOrElse(i) { 0f } / scale).coerceIn(0f, 1f)
        return Color(ch(0), ch(1), ch(2), 1f)
    }

    private companion object {
        const val CHROMA = 0.12f
        val BOLD = Regex("(?i)bold|black|heavy|semibold|extra")
    }
}
