package io.trimio.engine.motion.art

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.lerp
import io.trimio.engine.motion.Anim
import io.trimio.engine.motion.Easing
import io.trimio.engine.motion.Fill
import io.trimio.engine.motion.Node
import io.trimio.engine.motion.Shadow
import io.trimio.engine.motion.ShapeNode
import io.trimio.engine.motion.ShapeSpec
import io.trimio.engine.motion.Transform
import io.trimio.engine.motion.VectorNode
import io.trimio.engine.motion.anim
import io.trimio.engine.motion.recipe.Look
import io.trimio.engine.motion.style.StyleBook
import io.trimio.engine.motion.visual.VectorIcon

/**
 * Which kind of animated picture a style speaks: friendly full-colour animated emoji for loud,
 * playful and warm styles; single-colour line icons that draw themselves on, in the look's ink,
 * for calm, Swiss, technical and luxurious ones.
 */
enum class ArtFlavour {
    Emoji,
    Line,
    ;

    companion object {
        private val EMOJI_SOUNDS = setOf("punchy", "hype", "playful", "bright", "warm", "lofi", "organic", "retro")

        /** The flavour of the style the score's `look` names (emoji when the look is unknown). */
        fun forLook(look: String?): ArtFlavour {
            val style = StyleBook.of(look) ?: return Emoji
            return if (style.sound in EMOJI_SOUNDS) Emoji else Line
        }
    }
}

/** [this] drawn with [colouring] (and mirrored when [mirror]) whatever paint the node asks for. */
fun MotionArt.styled(colouring: Colouring = Colouring.Auto, mirror: Boolean = false, halo: Color? = null): MotionArt =
    if (colouring == Colouring.Auto && !mirror && halo == null) this else StyledArt(if (this is StyledArt) base else this, colouring, mirror, halo)

private class StyledArt(val base: MotionArt, private val colouring: Colouring, private val mirror: Boolean, private val halo: Color?) : MotionArt by base {
    override fun draw(scope: DrawScope, time: Float, paint: ArtPaint) = base.draw(scope, time, paint.copy(colouring = colouring, mirror = mirror, halo = halo))
}

/**
 * Building blocks for recipes that show an animated picture ([VectorIcon.motion]): the layers, the
 * clock, the colours the look allows, and the contrast backing it needs over footage. Static
 * icons go through the same calls and simply do not move by themselves.
 */
object AnimatedLayers {

    /**
     * How [icon] meets [look]: an emoji keeps its colours in emoji styles and becomes a duotone
     * in the look's ink and accent in line styles; a line drawing takes the ink (or the accent
     * when [accent]).
     */
    fun paint(icon: VectorIcon, look: Look, accent: Boolean = false): Colouring = when {
        !icon.colourful -> Colouring.Tint
        ArtFlavour.forLook(look.name) == ArtFlavour.Emoji -> Colouring.Own
        // The shadows deep in the accent, the lights in the ink: the emoji's modelling survives, in the look's two colours.
        else -> Colouring.Duotone(lerp(look.accent, Color.Black, DUO_DARK), if (accent) look.accent else look.ink)
    }

    /** Whether [icon] points somewhere (an arrow, "next") and should face the reading direction in right-to-left text. */
    fun directional(icon: VectorIcon): Boolean =
        (listOf(icon.name) + icon.keywords).any { k -> DIRECTIONAL.any { it in k.lowercase() } }

    /**
     * [icon] styled for [look] and the reading direction; a line drawing [overMedia] gets a halo
     * in the look's guard tone (the tone opposite its ink) so it reads over any picture.
     */
    fun styled(icon: VectorIcon, look: Look, rtl: Boolean, overMedia: Boolean = false, accent: Boolean = false): VectorIcon {
        val art = icon.motion ?: return icon
        val mirror = rtl && directional(icon) && icon.name.contains("right")
        val halo = if (overMedia && !icon.colourful) guardTone(look) else null
        return icon.copy(motion = art.styled(paint(icon, look, accent), mirror, halo))
    }

    /** The tone opposite the look's ink (its guard): dark under light ink, light under dark ink. */
    private fun guardTone(look: Look): Color = if (look.ink.luminance() > HALF) look.guard.dark else look.guard.light

    /**
     * An animated sticker: it springs in at [t0] while its animation starts, floats while held
     * (still drawings only: an animation already moves), and is backed by a soft disc in the
     * look's guard tone when a single-colour drawing sits over footage (its ink alone could
     * vanish into the picture). Sized to [size] px, uniform scale (never stretched).
     */
    fun sticker(icon: VectorIcon, x: Float, y: Float, size: Float, t0: Float, out: Float, look: Look, seed: Int, overMedia: Boolean, rtl: Boolean): List<Node> {
        val styled = styled(icon, look, rtl, overMedia)
        val landed = t0 + LAND
        val bob = if (icon.animated) Anim.Zero else Anim.Noise(seed, BOB_RATE, anim(0f, landed) { by(size * BOB, 0.6f); hold(out) })
        val nodes = mutableListOf<Node>()
        if (overMedia && !icon.colourful) nodes += backing(x, y, size, t0, look)
        nodes += VectorNode(
            styled, size, tint = look.ink.let { io.trimio.engine.motion.ColorAnim.of(it) },
            assemble = Anim.tween(0f, 1f, t0, t0 + LAND, Easing.Linear),
            shadow = Shadow(Color.Black.copy(alpha = SHADOW), 0f, size * 0.04f, size * 0.08f),
            transform = Transform(
                x = x.anim, y = y.anim + bob,
                scale = anim(SPRING_FROM, t0) { by(1f, 0.6f, Easing.Spring(0.5f, 2.2f)) },
                opacity = Anim.tween(0f, 1f, t0, t0 + 0.08f),
            ),
            name = "anim-${icon.name}",
        ).also { it.play = clock(t0) }
        return nodes
    }

    /** The animation's own clock: 0 at [t0], one second a second. */
    fun clock(t0: Float): Anim = Anim.keys(Anim.Key(t0, 0f, Easing.Hold), Anim.Key(t0 + CLOCK_SPAN, CLOCK_SPAN, Easing.Linear))

    /** A soft disc in the look's guard tone behind a line drawing over footage. */
    fun backing(x: Float, y: Float, size: Float, t0: Float, look: Look): Node {
        val tone = guardTone(look)
        return ShapeNode(
            ShapeSpec.Ellipse((size * DISC).anim, (size * DISC).anim),
            fill = Fill.Radial(listOf(tone.copy(alpha = 0.72f), tone.copy(alpha = 0.5f), tone.copy(alpha = 0f)), radius = 0.5f.anim),
            transform = Transform(x = x.anim, y = y.anim, scale = Anim.tween(0.4f, 1f, t0, t0 + 0.35f, Easing.ExpoOut), opacity = Anim.tween(0f, 1f, t0, t0 + 0.2f)),
            name = "anim-backing",
        )
    }

    private fun Color.luminance(): Float = 0.2126f * red + 0.7152f * green + 0.0722f * blue

    private val DIRECTIONAL = listOf("arrow", "chevron", "next", "forward", "right")
    private const val DUO_DARK = 0.55f
    private const val HALF = 0.5f
    private const val LAND = 0.7f
    private const val BOB = 0.03f
    private const val BOB_RATE = 0.45f
    private const val SPRING_FROM = 0.3f
    private const val SHADOW = 0.3f
    private const val DISC = 1.45f
    private const val CLOCK_SPAN = 3600f
}
