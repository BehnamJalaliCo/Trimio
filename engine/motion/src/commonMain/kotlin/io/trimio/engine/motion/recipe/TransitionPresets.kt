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
 * Designed transition presets for the dual-buffer engine's whip, zoom and glitch-lite cuts:
 * every number hand-chosen (never random), so the same cut tears the same way on every render.
 * Each preset is a named bag of motion parameters plus a method that draws its picture over the
 * frame for a few frames around the cut — the overlay half of the two-buffer pair (the shader
 * half lives in [io.trimio.engine.motion.AgslTransitions]).
 *
 * Craft: the burst stays inside 80–120 ms for glitch-lite, the whip and zoom run 280–360 ms,
 * blur rides the move and settles before the next shot is readable, and no preset bends what is
 * under it (Persian dots stay sharp).
 */
object TransitionPresets {

    /**
     * A directional whip pan: the frame slides hard in [direction] with a motion-blur smear that
     * trails the move and snaps clean on the landing. Designed numbers, not noise.
     */
    data class Whip(
        /** +1 left-to-right, −1 right-to-left (RTL pieces whip from the right). */
        val direction: Float,
        /** Travel as a share of the frame width. */
        val travel: Float,
        /** Peak blur in px at the fastest frame. */
        val blurPeak: Float,
        /** Total span (s), in the 280–360 ms band. */
        val span: Float,
    )

    /**
     * A punch zoom with a radial-feeling blur: the frame scales past its mark and settles back,
     * blur swelling on the push and clearing as it lands.
     */
    data class Zoom(
        /** Peak scale at the cut (1.12–1.22 reads premium, never cartoon). */
        val peakScale: Float,
        /** Blur at the peak (px). */
        val blurPeak: Float,
        /** Total span (s). */
        val span: Float,
    )

    /**
     * Glitch-lite: RGB-split bands and slice offsets over 80–120 ms. Seeded by the cut time so
     * the same cut tears identically on every render. Band count, offsets and life are fixed.
     */
    data class GlitchLite(
        /** Number of horizontal tear bands. */
        val bands: Int,
        /** Slice offset as a share of frame width. */
        val offset: Float,
        /** Per-band life (s) — two frames at 30 fps keeps the burst inside 80–120 ms. */
        val bandLife: Float,
        /** Soft blur added under the tears so the cut reads as motion, not a still. */
        val blur: Float,
    )

    // ------------------------------------------------------------------ designed values

    /** RTL-friendly whip: from the right, a medium drag, soft smear. */
    val WhipRtl = Whip(direction = -1f, travel = 0.22f, blurPeak = 14f, span = 0.32f)

    /** LTR whip: from the left, same hand. */
    val WhipLtr = Whip(direction = 1f, travel = 0.22f, blurPeak = 14f, span = 0.32f)

    /** A tighter whip for fast cuts: less travel, shorter smear. */
    val WhipTight = Whip(direction = -1f, travel = 0.14f, blurPeak = 9f, span = 0.28f)

    /** The house zoom punch: 18% over, soft blur, lands in a third of a second. */
    val ZoomPunch = Zoom(peakScale = 1.18f, blurPeak = 10f, span = 0.34f)

    /** A heavier zoom for impact moments. */
    val ZoomImpact = Zoom(peakScale = 1.22f, blurPeak = 14f, span = 0.36f)

    /** The house glitch-lite: five bands, 7% offset, two-frame life, light blur. */
    val GlitchLiteSoft = GlitchLite(bands = 5, offset = 0.07f, bandLife = 2f / 30f, blur = 3f)

    /** A harder glitch-lite for tech cuts. */
    val GlitchLiteHard = GlitchLite(bands = 7, offset = 0.1f, bandLife = 2f / 30f, blur = 4.5f)

    /** Every designed preset, named. */
    val all: Map<String, Any> = linkedMapOf(
        "whip-rtl" to WhipRtl,
        "whip-ltr" to WhipLtr,
        "whip-tight" to WhipTight,
        "zoom-punch" to ZoomPunch,
        "zoom-impact" to ZoomImpact,
        "glitchlite-soft" to GlitchLiteSoft,
        "glitchlite-hard" to GlitchLiteHard,
    )

    // ------------------------------------------------------------------ drawing

    /**
     * Draws the picture of [whip] at cut time [s] over a [width]×[height] frame: two soft bands
     * sweep with the move and a smear follows. Returns the overlay nodes and sound.
     */
    fun drawWhip(whip: Whip, s: Float, look: Look, width: Float, height: Float): Pair<List<Node>, List<Sfx>> {
        val overlays = mutableListOf<Node>()
        val t0 = s - whip.span * 0.55f
        val t1 = s + whip.span * 0.45f
        val dx = whip.direction * width * whip.travel
        // Two soft bands (the picture's smear) and one full-frame fade for the cut itself.
        for (i in 0 until 2) {
            val bandH = height * (0.18f + 0.14f * i)
            val slide = Anim.tween(-dx * (1f - 0.35f * i), dx * 0.25f * (i + 1), t0, t1, Easing.ExpoInOut)
            overlays += ShapeNode(
                ShapeSpec.Rect(width.anim, bandH.anim),
                fill = look.accent.copy(alpha = 0.18f - 0.06f * i).fill(),
                transform = Transform(
                    x = (width / 2f).anim + slide,
                    y = (height * (0.28f + 0.38f * i)).anim,
                    opacity = anim(0f, t0) { by(1f, whip.span * 0.25f, Easing.ExpoOut); by(0f, whip.span * 0.6f, Easing.ExpoOut) },
                    blur = Anim.tween(whip.blurPeak, 0f, s, t1, Easing.ExpoOut),
                ),
                start = t0, end = t1, name = "whip-band",
            )
        }
        overlays += EffectNode(
            Effect.Flash(look.canvas, anim(0f, t0) { by(0.55f, whip.span * 0.35f, Easing.ExpoIn); by(0f, whip.span * 0.55f, Easing.ExpoOut) }),
            start = t0, end = t1, name = "whip-dip",
        )
        val sfx = listOf(
            Sfx(t0 + whip.span * 0.15f, SfxKind.Swish, 0.7f),
            Sfx(s, SfxKind.Whoosh, 0.55f),
        )
        return overlays to sfx
    }

    /** Draws the picture of [zoom] at cut time [s]: a scale punch with a blur swell. */
    fun drawZoom(zoom: Zoom, s: Float, look: Look, width: Float, height: Float): Pair<List<Node>, List<Sfx>> {
        val t0 = s - zoom.span * 0.5f
        val t1 = s + zoom.span * 0.5f
        val overlays = mutableListOf<Node>()
        // A soft vignette ring that swells with the punch — the picture half of the zoom.
        overlays += ShapeNode(
            ShapeSpec.Ellipse((width * 0.9f).anim, (width * 0.9f).anim),
            fill = Color.Transparent.fill(),
            stroke = io.trimio.engine.motion.Stroke(look.accent.copy(alpha = 0.22f).fill(), width * 0.012f, round = true),
            transform = Transform(
                x = (width / 2f).anim, y = (height / 2f).anim,
                scale = anim(1f, t0) { by(zoom.peakScale, zoom.span * 0.45f, Easing.ExpoOut); by(1f, zoom.span * 0.55f, Easing.ExpoOut) },
                opacity = anim(0f, t0) { by(1f, zoom.span * 0.2f); by(0f, zoom.span * 0.55f, Easing.ExpoOut) },
            ),
            start = t0, end = t1, name = "zoom-ring",
        )
        overlays += EffectNode(
            Effect.Flash(look.canvas, anim(0f, t0) { by(0.35f, zoom.span * 0.3f, Easing.ExpoIn); by(0f, zoom.span * 0.5f, Easing.ExpoOut) }),
            start = t0, end = t1, name = "zoom-dip",
        )
        val sfx = listOf(
            Sfx(t0, SfxKind.Riser, 0.4f),
            Sfx(s, SfxKind.Hit, 0.65f),
        )
        return overlays to sfx
    }

    /**
     * Draws the picture of [glitch] at cut time [s]: RGB-split tear bands and slice offsets,
     * seeded by the cut so every render matches. Stays inside the 80–120 ms band.
     */
    fun drawGlitchLite(glitch: GlitchLite, s: Float, width: Float, height: Float): Pair<List<Node>, List<Sfx>> {
        val overlays = mutableListOf<Node>()
        val channels = listOf(CH_RED, CH_GREEN, CH_BLUE)
        var k = (s * 1000f).toInt() * 13 + 7
        val lead = 0.035f
        val stagger = 0.012f
        for (i in 0 until glitch.bands) {
            k = k * 1103515245 + 12345
            val y = height * (((k ushr 8) and 0xFFFF) / 65535f)
            k = k * 1103515245 + 12345
            val band = height * (0.014f + 0.05f * (((k ushr 8) and 0xFF) / 255f))
            val t0 = s - lead + (i % 3) * stagger
            val dx = (if (i % 2 == 0) 1f else -1f) * width * glitch.offset
            for (c in channels.indices) {
                val jump = Anim.keys(
                    listOf(
                        Anim.Key(t0, dx * (1f + 0.22f * c), Easing.Hold),
                        Anim.Key(t0 + 1f / 30f, -dx * (0.45f + 0.12f * c), Easing.Hold),
                    ),
                )
                overlays += ShapeNode(
                    ShapeSpec.Rect((width * (0.34f + 0.08f * c)).anim, band.anim),
                    fill = channels[c].copy(alpha = 0.5f).fill(),
                    transform = Transform(
                        x = (width / 2f).anim + jump, y = y.anim,
                        opacity = Anim.keys(listOf(Anim.Key(t0, 1f, Easing.Hold), Anim.Key(t0 + glitch.bandLife, 0f, Easing.Hold))),
                        blur = glitch.blur.anim,
                    ),
                    start = t0, end = t0 + glitch.bandLife, name = "glitchlite-band",
                )
            }
        }
        val sfx = listOf(
            Sfx(s - 0.05f, SfxKind.Swish, 0.75f),
            Sfx(s, SfxKind.Click, 0.5f),
        )
        return overlays to sfx
    }

    /** Draws any named preset at [s]; returns false for a name this does not know. */
    fun draw(name: String?, s: Float, look: Look, width: Float, height: Float, overlays: MutableList<Node>, sfx: MutableList<Sfx>): Boolean {
        val (nodes, sounds) = when (name) {
            "whip", "whip-rtl" -> drawWhip(WhipRtl, s, look, width, height)
            "whip-ltr" -> drawWhip(WhipLtr, s, look, width, height)
            "whip-tight" -> drawWhip(WhipTight, s, look, width, height)
            "zoom", "zoom-punch" -> drawZoom(ZoomPunch, s, look, width, height)
            "zoom-impact" -> drawZoom(ZoomImpact, s, look, width, height)
            "glitchlite", "glitchlite-soft" -> drawGlitchLite(GlitchLiteSoft, s, width, height)
            "glitchlite-hard" -> drawGlitchLite(GlitchLiteHard, s, width, height)
            else -> return false
        }
        overlays += nodes
        sfx += sounds
        return true
    }

    private val CH_RED = Color(0xFFFF2E4C)
    private val CH_GREEN = Color(0xFF2EFFB0)
    private val CH_BLUE = Color(0xFF2E6BFF)
}
