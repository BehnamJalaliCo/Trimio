package io.trimio.engine.motion

import kotlin.math.PI
import kotlin.math.ln
import kotlin.math.round
import kotlin.math.sqrt

/**
 * The craft rulebook as code: the measured numbers behind every motion decision — which curve a
 * role moves with, how long things hold, when the next thing may start, how small text may get,
 * and which outputs read as "generated". Nothing here animates; the planner, compiler and jury
 * check against these floors so a piece cannot quietly drift out of craft.
 */
object MotionSpec {

    /** Which curve each motion role settles with. */
    object Ease {
        /** Long-tailed ease-out: things arrive fast and settle softly. */
        val Arrival: Easing = Easing.ExpoOut

        /** power2.inOut approximation — camera moves are eased both ways, never linear. */
        val Camera: Easing = Easing.Bezier(0.45f, 0f, 0.55f, 1f)

        /** Accelerating out: exits leave quickly and never linger. */
        val Exit: Easing = Easing.ExpoIn

        /**
         * The entrance curve on purpose: emphasis must land confidently, not wobble. Overshoot
         * stays reserved for genuine hits (Easing.Hit), because a bouncing emphasis is itself a
         * generated tell (Generated.UiBounce).
         */
        val Emphasis: Easing = Easing.Enter

        /** Gentle both-ways drift for ambient moves (camera float, slow pans). */
        val Drift: Easing = Easing.SineInOut
    }

    /**
     * Spring characters. Only position and size may bounce — opacity and colour never, so they
     * always ride a monotone curve. [Overshoot] holds the per-property bands.
     */
    object Springs {
        /** Critical damping: lands with weight and never rings. */
        val NoBounce: Easing.Spring = Easing.Spring(damping = 1f)

        /** 0.7–0.85 damping reads premium: a whisper of life, no visible ring. */
        val Premium: Easing.Spring = Easing.Spring(damping = 0.8f, frequency = 2.0f)

        /** House spring: lively overshoot for things that land. */
        val Playful: Easing.Spring = Easing.Spring(damping = 0.55f)

        /** Exaggerated ring — cartoon moments only. */
        val Cartoon: Easing.Spring = Easing.Spring(damping = 0.25f)

        /**
         * Per-property overshoot bands. Opacity is pinned to zero (Generated.OpacityBounce);
         * scale and position take a band — premium a whisper (0–3%), playful a real ring (5–15%).
         */
        object Overshoot {
            enum class Prop { Position, Scale, Opacity }

            const val PremiumMin = 0f
            const val PremiumMax = 0.03f
            const val PlayfulMin = 0.05f
            const val PlayfulMax = 0.15f
            const val OpacityMax = 0f

            /** The overshoot band [prop] may ring in: playful rings more; opacity is pinned. */
            fun band(prop: Prop, playful: Boolean): ClosedFloatingPointRange<Float> = when (prop) {
                Prop.Opacity -> 0f..OpacityMax
                else -> if (playful) PlayfulMin..PlayfulMax else PremiumMin..PremiumMax
            }

            /** A spring that peaks about [fraction] past 1 (0 for a clean land). */
            fun spring(fraction: Float, frequency: Float = 2f): Easing {
                if (fraction <= 0f) return NoBounce
                // Invert the underdamped overshoot os = exp(-πζ / sqrt(1-ζ²)) for the damping.
                val os = fraction.coerceIn(MIN_OVERSHOOT, MAX_OVERSHOOT)
                val ell = ln(os)
                val z = (-ell) / sqrt((PI * PI) + ell * ell)
                return Easing.Spring(damping = z.toFloat(), frequency = frequency)
            }

            /** [spring] clamped to [prop]'s band — never past what the craft allows. */
            fun springFor(prop: Prop, fraction: Float, playful: Boolean = false, frequency: Float = 2f): Easing {
                val allowed = band(prop, playful)
                return spring(fraction.coerceIn(allowed.start, allowed.endInclusive), frequency)
            }

            private const val MIN_OVERSHOOT = 0.001f
            private const val MAX_OVERSHOOT = 0.5f
        }
    }

    /** Timing bands, in seconds unless the name says otherwise. */
    object Time {
        /** Fast move band: shorter stutters, longer drags. */
        const val FastMoveMin = 0.3f
        const val FastMoveMax = 0.6f

        /** Exits run 40–50% shorter than their entrances. */
        const val ExitDurationScale = 0.45f

        /** The next element starts when the previous is 30–50% done. */
        const val OverlapStart = 0.3f
        const val OverlapEnd = 0.5f

        const val StaggerLetterFrames = 1
        const val StaggerWord = 0.09f
        const val StaggerItem = 0.06f

        const val HoldBase = 0.5f
        const val HoldPerWord = 0.3f

        /** A closing line is never on screen for less than this. */
        const val LastLineMinHold = 2.0f

        /** The pause before a payoff: short enough to stay tense, long enough to land. */
        const val PrePayoffHoldMin = 0.3f
        const val PrePayoffHoldMax = 0.75f

        const val MontageCutMin = 0.13f
        const val MontageCutMax = 0.8f

        const val ShotAvgMin = 1.8f
        const val ShotAvgMax = 3.0f

        /** Longer than this with nothing changing is a craft warning. */
        const val DeadStretchMax = 3.0f

        /** How long a line holds after it lands: base plus reading time. */
        fun holdFor(wordCount: Int): Float = HoldBase + HoldPerWord * wordCount

        /** [holdFor], floored for the last line so the payoff cannot leave while it is unread. */
        fun holdForLine(wordCount: Int, isLast: Boolean): Float =
            if (isLast) maxOf(holdFor(wordCount), LastLineMinHold) else holdFor(wordCount)

        fun inFastMoveRange(duration: Float): Boolean = duration in FastMoveMin..FastMoveMax
    }

    /** Readability floors: nothing on screen smaller than these. */
    object Legibility {
        /** The floor at a 1080 short side, where the fraction below lands on ~28 px. */
        const val MinTextPxAt1080 = 28f
        const val MinTextFractionOfShortSide = 0.026f
        const val TitleHeightFractionMin = 0.04f
        const val TitleHeightFractionMax = 0.09f

        /** Smallest readable text at [shortSidePx]: 2.6% of the short side. */
        fun minTextPx(shortSidePx: Int): Float = shortSidePx * MinTextFractionOfShortSide
    }

    /** Beats to frames. */
    object Beat {
        fun framesPerBeat(fps: Int, bpm: Float): Int = round(fps * 60f / bpm).toInt()

        /**
         * Frame of beat [index], computed from the index in one step. Accumulating
         * [framesPerBeat] drifts whenever a beat is not a whole number of frames — 128 BPM at
         * 30 fps is 14.0625 frames/beat, so 32 accumulated beats land two frames early.
         */
        fun beatFrame(index: Int, fps: Int, bpm: Float): Int = round(index * fps * 60f / bpm).toInt()
    }

    /** Spoken caption groups: one group visible at a time, sized by the energy of the line. */
    object Captions {
        val WordsHighEnergy: IntRange = 2..3
        val WordsConversational: IntRange = 3..5
        val WordsCalm: IntRange = 4..6

        /** Silence between groups, in milliseconds. */
        const val BreakPauseMs = 150f
    }

    /**
     * Ban-list of "generated" tells: patterns that read as machine output however well they are
     * executed. Each may appear once on purpose; none may become a habit.
     */
    object Generated {
        /** Straight linear movement from A to B. */
        const val LinearMotion = "linear-motion"
        /** Scale creeping slowly through the whole scene — Ken Burns by default. */
        const val SlowCreepScale = "slow-creep-scale"
        /** An element still sliding after it has landed. */
        const val PostLandSlide = "post-land-slide"
        /** A springy UI-style bounce on a title or a card. */
        const val UiBounce = "ui-bounce"
        /** Opacity ringing past its target. */
        const val OpacityBounce = "opacity-bounce"
        /** Every element entering with the same fade + slide-up. */
        const val UniformEntry = "uniform-entry"
        /** Every scene entering the same way. */
        const val SameSceneEntry = "same-scene-entry"
        /** Breathing / floating / pulsing standing in for motion. */
        const val BreathingLoop = "breathing-loop"
        /** Click ripple rings on touches and highlights. */
        const val ClickRings = "click-rings"

        val all: List<String> = listOf(
            LinearMotion, SlowCreepScale, PostLandSlide, UiBounce, OpacityBounce,
            UniformEntry, SameSceneEntry, BreathingLoop, ClickRings,
        )
    }
}
