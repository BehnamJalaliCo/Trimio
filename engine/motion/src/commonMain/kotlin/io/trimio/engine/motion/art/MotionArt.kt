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

    companion object {
        const val FORMAT_LOTTIE = "lottie"
        const val FORMAT_SVG = "svg"
    }
}

/** How a [MotionArt] is painted: the node's [tint], an overall [alpha], and how its colours follow the look. */
data class ArtPaint(val tint: Color, val alpha: Float = 1f, val colouring: Colouring = Colouring.Auto, val mirror: Boolean = false)

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

    override fun draw(scope: DrawScope, time: Float, paint: ArtPaint) {
        val local = if (loops) time.mod(duration) else time.coerceIn(0f, duration - 1f / animation.frameRate)
        LottiePainter(animation, paint, colourful).draw(scope, animation.inPoint + local.coerceAtLeast(0f) * animation.frameRate)
    }

    private companion object {
        const val CHROMA = 0.12f
    }
}
