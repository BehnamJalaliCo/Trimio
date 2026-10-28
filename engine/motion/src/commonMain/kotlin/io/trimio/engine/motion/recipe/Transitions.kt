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
import io.trimio.engine.motion.Stroke
import io.trimio.engine.motion.Transform
import io.trimio.engine.motion.anim
import io.trimio.engine.motion.fill

/**
 * Cuts drawn over the frame (the compiler keeps its own whip, zoom, flash and leak; glitch and
 * ripple arrive here too, so a cut always has a picture and never only a sound): a film burn,
 * glitch slices, a dip to the canvas, a cool light leak, a glitch-lite burst and a ripple ring.
 * They sit over the picture for a few frames around the cut and never bend what is under them, so
 * the words of either scene stay sharp (in Persian a softened dot is another letter).
 */
object Transitions {
    /** The cuts drawn here. */
    val KINDS = setOf("burn", "slice", "dip", "leak-cool", "glitch", "ripple")

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
            "glitch" -> glitchLite(s, width, height, overlays, sfx)
            "ripple" -> rippleCut(s, width, height, look, overlays, sfx)
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
                Anim.Key(s - 0.1f, 0f, Easing.Hold), Anim.Key(s - 0.066f, 0.4f, Easing.Hold), Anim.Key(s - 0.033f, 0.1f, Easing.Hold),
                Anim.Key(s, 0.55f, Easing.Hold), Anim.Key(s + 0.033f, 0.25f, Easing.Hold), Anim.Key(s + 0.2f, 0f, Easing.ExpoOut),
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

    /**
     * Glitch-lite, the glitch cut's picture: RGB-split bands and sideways slice offsets over
     * 80–120 ms around the cut — a glitch that only made a sound left the frame dead. Seeded by
     * the cut time, so the same cut tears the same way on every render.
     */
    fun glitchLite(s: Float, width: Float, height: Float, overlays: MutableList<Node>, sfx: MutableList<Sfx>) {
        val channels = listOf(CH_RED, CH_GREEN, CH_BLUE)
        var k = (s * 1000f).toInt() * 13 + 7
        for (i in 0 until GLITCH_BANDS) {
            k = k * 1103515245 + 12345
            val y = height * (((k ushr 8) and 0xFFFF) / 65535f)
            k = k * 1103515245 + 12345
            val band = height * (0.014f + 0.05f * (((k ushr 8) and 0xFF) / 255f))
            val t0 = s - GLITCH_LEAD + (i % 3) * GLITCH_STAGGER
            val dx = (if (i % 2 == 0) 1f else -1f) * width * 0.07f
            for (c in channels.indices) {
                val jump = Anim.keys(
                    listOf(
                        Anim.Key(t0, dx * (1f + 0.22f * c), Easing.Hold),
                        Anim.Key(t0 + FRAME, -dx * (0.45f + 0.12f * c), Easing.Hold),
                    ),
                )
                overlays += ShapeNode(
                    ShapeSpec.Rect((width * (0.34f + 0.08f * c)).anim, band.anim),
                    fill = channels[c].copy(alpha = 0.5f).fill(),
                    transform = Transform(x = (width / 2f).anim + jump, y = y.anim, opacity = Anim.keys(listOf(Anim.Key(t0, 1f, Easing.Hold), Anim.Key(t0 + GLITCH_LIFE, 0f, Easing.Hold)))),
                    start = t0, end = t0 + GLITCH_LIFE, name = "glitch-lite",
                )
            }
        }
        sfx += Sfx(s - 0.05f, SfxKind.Swish, 0.8f)
    }

    /**
     * The ripple cut's picture: two rings expanding from the centre and two soft bands sweeping
     * the frame — on a flat stage the picture filter has nothing to bend, so the cut draws its
     * own image over whatever is there.
     */
    fun rippleCut(s: Float, width: Float, height: Float, look: Look, overlays: MutableList<Node>, sfx: MutableList<Sfx>) {
        val span = maxOf(width, height) * 1.2f
        for (i in 0 until RIPPLE_RINGS) {
            val t0 = s - RIPPLE_LEAD + i * RIPPLE_LAG
            val grow = Anim.tween(0f, span * (1f - 0.25f * i), t0, t0 + RIPPLE_SPAN, Easing.QuintOut)
            overlays += ShapeNode(
                ShapeSpec.Ellipse(grow, grow),
                stroke = Stroke(look.accent.copy(alpha = 0.5f - 0.18f * i).fill(), width * RIPPLE_RIM, round = true),
                transform = Transform(
                    x = (width / 2f).anim, y = (height / 2f).anim,
                    opacity = Anim.tween(1f, 0f, t0 + RIPPLE_SPAN * 0.35f, t0 + RIPPLE_SPAN, Easing.SineInOut),
                ),
                start = t0, end = t0 + RIPPLE_SPAN, name = "ripple-ring",
            )
        }
        val bands = listOf(look.accent, look.hot)
        for (i in bands.indices) {
            val slide = Anim.tween(-width * 0.06f, width * 0.06f, s - RIPPLE_LEAD, s + RIPPLE_SPAN * 0.6f, Easing.SineInOut)
            overlays += ShapeNode(
                ShapeSpec.Rect(width.anim, (height * RIPPLE_BAND).anim),
                fill = bands[i].copy(alpha = 0.22f).fill(),
                transform = Transform(
                    x = (width / 2f).anim + slide, y = (height * (0.3f + 0.4f * i)).anim,
                    opacity = Anim.keys(
                        listOf(
                            Anim.Key(s - RIPPLE_LEAD, 0f, Easing.Linear), Anim.Key(s, 1f, Easing.Linear),
                            Anim.Key(s + RIPPLE_SPAN * 0.6f, 0f, Easing.SineInOut),
                        ),
                    ),
                ),
                start = s - RIPPLE_LEAD, end = s + RIPPLE_SPAN * 0.6f, name = "ripple-band",
            )
        }
        sfx += Sfx(s - 0.2f, SfxKind.Shimmer, 0.7f)
    }

    private const val DIP = 0.16f
    private const val FRAME = 1f / 30f
    private const val SLICES = 12
    private const val GLITCH_BANDS = 5
    private const val GLITCH_LEAD = 0.035f
    private const val GLITCH_STAGGER = 0.012f
    /** One band lives two frames: the whole burst stays inside the 80–120 ms a glitch cut is allowed. */
    private const val GLITCH_LIFE = FRAME * 2
    private const val RIPPLE_RINGS = 2
    private const val RIPPLE_LEAD = 0.12f
    private const val RIPPLE_LAG = 0.14f
    private const val RIPPLE_SPAN = 0.55f
    private const val RIPPLE_RIM = 0.012f
    private const val RIPPLE_BAND = 0.035f
    private val CH_RED = Color(0xFFFF2E4C)
    private val CH_GREEN = Color(0xFF2EFFB0)
    private val CH_BLUE = Color(0xFF2E6BFF)
}
