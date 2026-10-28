package io.trimio.engine.motion.recipe

import androidx.compose.ui.graphics.Color
import io.trimio.engine.motion.Anim
import io.trimio.engine.motion.CounterNode
import io.trimio.engine.motion.Easing
import io.trimio.engine.motion.Fill
import io.trimio.engine.motion.Group
import io.trimio.engine.motion.Node
import io.trimio.engine.motion.Shadow
import io.trimio.engine.motion.ShapeNode
import io.trimio.engine.motion.ShapeSpec
import io.trimio.engine.motion.Stroke
import io.trimio.engine.motion.TextAnimator
import io.trimio.engine.motion.TextNode
import io.trimio.engine.motion.TextUnit
import io.trimio.engine.motion.Transform
import io.trimio.engine.motion.UnitState
import io.trimio.engine.motion.anim
import io.trimio.engine.motion.fill

/**
 * A designer's template shelf: ten hand-crafted looks (the CapCut lesson - a human designs the
 * moment, the engine fills it). Each carries its own signature, its own three-layer choreography
 * (primary travel, lagged follow-through, fade overlapping the travel) and its timing from the
 * P1 design tokens. The director names one the way a cut is chosen in After Effects: by the
 * moment it was designed for.
 *
 * Craft floors every template keeps: entry 200-350 ms, exit at 65-75% of it, stagger 30-60 ms,
 * overshoot in band (premium 0-3%, playful 5-15%), two to four words a shot, Persian set right
 * to left with the entrance from the right, and nothing cheap (no glow, no particle, no shake).
 */
object DesignTemplates {

    // ------------------------------------------------------------------ timing tokens

    /** Designed entry durations (s): the 200-350 ms band, one per hand. */
    private const val ENTRY_SNAP = 0.22f
    private const val ENTRY_LIFT = 0.28f
    private const val ENTRY_GLIDE = 0.34f

    /** Exits run 65-75% of the entry - the craft band, never the same length. */
    private const val EXIT_SHARE = 0.7f

    /** Word/row stagger in the 30-60 ms band. */
    private const val STAGGER_TIGHT = 0.035f
    private const val STAGGER_SOFT = 0.055f

    /** Overshoot springs: premium rings a whisper; playful a real land. */
    private val SpringPremium = Easing.Spring(0.8f, 2.0f)
    private val SpringPlayful = Easing.Spring(0.55f, 2.2f)

    /** The fade overlaps the travel by this much (s) so the two never share a first frame. */
    private const val FADE_LEAD = 0.05f

    /** Follow-through lag (s) and the secondary channel damping. */
    private const val FOLLOW_LAG = 0.06f
    private const val FOLLOW_DAMP = 0.65f

    private fun exitSpan(entry: Float) = entry * EXIT_SHARE

    /** The house three-layer entrance: primary + lagged trail + overlapping fade. */
    private fun layers(at: Float, entry: Float, primary: Anim, trailPeak: Float = 1.2f): Transform {
        val fade = anim(0f, at - FADE_LEAD - entry) { by(1f, FADE_LEAD + entry * 2.2f, Easing.Linear) }
        val trail = Anim.Follow(
            anim(0f, at) {
                by(trailPeak, entry * 0.4f, Easing.ExpoOut)
                by(0f, entry * 0.6f, Easing.ExpoOut)
            },
            FOLLOW_LAG,
            FOLLOW_DAMP,
        )
        return Transform(x = primary, opacity = fade, blur = trail)
    }
