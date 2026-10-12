package io.trimio.engine.motion.recipe

import androidx.compose.ui.graphics.Color
import io.trimio.engine.motion.Anim
import io.trimio.engine.motion.Easing
import io.trimio.engine.motion.Effect
import io.trimio.engine.motion.EffectNode
import io.trimio.engine.motion.Group
import io.trimio.engine.motion.Node
import io.trimio.engine.motion.ShapeNode
import io.trimio.engine.motion.ShapeSpec
import io.trimio.engine.motion.Transform
import io.trimio.engine.motion.anim
import io.trimio.engine.motion.fill

/**
 * Cuts drawn over the frame (the compiler keeps its own whip, zoom, flash, leak, glitch and
 * ripple): a film burn, glitch slices, a dip to the canvas and a cool light leak. They sit over
 * the picture for a few frames around the cut and never bend what is under them, so the words of
 * either scene stay sharp (in Persian a softened dot is another letter).
 */
object Transitions {
    /** The cuts drawn here. */
    val KINDS = setOf("burn", "slice", "dip", "leak-cool")

    /**
     * Adds the overlay and sound of a [kind] cut at [s] (a frame of [width] × [height]) and
     * returns true, or false for a kind this does not draw.
     */
    fun overlay(kind: String?, s: Float, look: Look, width: Float, height: Float, overlays: MutableList<Node>, sfx: MutableList<Sfx>): Boolean {
        when (kind) {
            "burn" -> burn(s, look, overlays, sfx)
            "slice" -> slice(s, look, width, height, overlays, sfx)
            "dip" -> {
                val dip = anim(0f, s - DIP) { by(1f, DIP, Easing.ExpoIn); by(0f, DIP * 1.4f, Easing.ExpoOut) }
                overlays += EffectNode(Effect.Flash(look.canvas, dip), start = s - DIP - 0.01f, end = s + DIP * 1.5f, name = "dip")
                sfx += Sfx(s - 0.1f, SfxKind.Whoosh, 0.5f)
            }
            "leak-cool" -> {
                val strength = anim(0f, s - 0.3f) { by(0.8f, 0.3f, Easing.SineInOut); by(0f, 0.7f, Easing.SineInOut) }
                overlays += EffectNode(Effect.LightLeak(look.accent, Anim.tween(1f, 0f, s - 0.3f, s + 0.7f, Easing.SineInOut), strength), start = s - 0.35f, end = s + 0.8f, name = "leak-cool")
                sfx += Sfx(s - 0.25f, SfxKind.Shimmer, 0.6f)
            }
            else -> return false
        }
        return true
    }

    /** Film burn: a warm flare floods in from the edge, two quick flickers, and clears. */
    private fun burn(s: Float, look: Look, overlays: MutableList<Node>, sfx: MutableList<Sfx>) {
        val warm = Color(0xFFFF8A3D)
        val strength = anim(0f, s - 0.28f) { by(1f, 0.28f, Easing.ExpoIn); by(0f, 0.6f, Easing.ExpoOut) }
        overlays += EffectNode(Effect.LightLeak(warm, Anim.tween(0f, 1f, s - 0.3f, s + 0.6f, Easing.SineInOut), strength), start = s - 0.32f, end = s + 0.65f, name = "burn")
        val flicker = Anim.keys(
            listOf(
                Anim.Key(s - 0.1f, 0f, Easing.Hold), Anim.Key(s - 0.066f, 0.55f, Easing.Hold), Anim.Key(s - 0.033f, 0.1f, Easing.Hold),
                Anim.Key(s, 0.8f, Easing.Hold), Anim.Key(s + 0.033f, 0.3f, Easing.Hold), Anim.Key(s + 0.2f, 0f, Easing.ExpoOut),
            ),
        )
        overlays += EffectNode(Effect.Flash(androidx.compose.ui.graphics.lerp(warm, look.hot, 0.3f), flicker), start = s - 0.11f, end = s + 0.22f, name = "burn-flicker")
        sfx += Sfx(s - 0.2f, SfxKind.Riser, 0.4f)
        sfx += Sfx(s, SfxKind.Hit, 0.6f)
    }

    /**
     * Glitch slices: bands of the accent, the ink and the canvas tear across the frame for a few
     * frames, offset sideways, jumping each frame.
     */
    private fun slice(s: Float, look: Look, width: Float, height: Float, overlays: MutableList<Node>, sfx: MutableList<Sfx>) {
        val colors = listOf(look.accent, look.canvas, look.hot, look.ink)
        val bars = mutableListOf<Node>()
        var k = (s * 1000f).toInt() * 7 + 3
        for (i in 0 until SLICES) {
            k = k * 1103515245 + 12345
            val y = height * (((k ushr 8) and 0xFFFF) / 65535f)
            k = k * 1103515245 + 12345
            val h = height * (0.01f + 0.05f * (((k ushr 8) and 0xFF) / 255f))
            val t0 = s - 0.1f + (i % 4) * FRAME
            val dx = (if (i % 2 == 0) 1f else -1f) * width * 0.08f
            val jump = Anim.keys(listOf(Anim.Key(t0, dx, Easing.Hold), Anim.Key(t0 + FRAME, -dx * 0.6f, Easing.Hold), Anim.Key(t0 + FRAME * 2, dx * 0.3f, Easing.Hold)))
            bars += ShapeNode(
                ShapeSpec.Rect((width * (0.5f + 0.6f * (i % 3) / 2f)).anim, h.anim), fill = colors[i % colors.size].copy(alpha = 0.92f).fill(),
                transform = Transform(x = (width / 2f).anim + jump, y = y.anim, opacity = Anim.keys(listOf(Anim.Key(t0, 1f, Easing.Hold), Anim.Key(t0 + FRAME * 3, 0f, Easing.Hold)))),
                start = t0, end = t0 + FRAME * 3, name = "slice",
            )
        }
        overlays += Group(bars, name = "slices", start = s - 0.12f, end = s + 0.2f)
        sfx += Sfx(s - 0.05f, SfxKind.Click, 0.7f)
        sfx += Sfx(s, SfxKind.Swish, 0.6f)
    }

    private const val DIP = 0.16f
    private const val FRAME = 1f / 30f
    private const val SLICES = 12
}
