package io.trimio.engine.motion

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The motion grammar: velocity-continuous keys, the Follow/Anticipate secondary DSL, organic
 * seeded stagger, and per-property overshoot bands.
 */
class MotionGrammarTest {
    private fun near(a: Float, b: Float, eps: Float = 1e-3f) = assertTrue(abs(a - b) < eps, "$a != $b (eps=$eps)")

    private fun velocity(a: Anim, t: Float, fromLeft: Boolean, h: Float = 1e-3f): Float =
        if (fromLeft) (a.at(t) - a.at(t - h)) / h else (a.at(t + h) - a.at(t)) / h

    // ------------------------------------------------------------------ velocity continuity

    @Test
    fun smoothKeysJoinWithoutAVelocityPop() {
        val smooth = Anim.keysSmooth(
            Anim.Key(0f, 0f),
            Anim.Key(1f, 8f, Easing.Linear),
            Anim.Key(2f, 8f, Easing.Linear),
            Anim.Key(3f, 0f, Easing.Linear),
        )
        for (joint in listOf(1f, 2f)) {
            val left = velocity(smooth, joint, fromLeft = true)
            val right = velocity(smooth, joint, fromLeft = false)
            near(left, right, JOINT_EPS)
        }
    }

    @Test
    fun plainKeysPopAtAJointButSmoothKeysDoNot() {
        val keys = listOf(
            Anim.Key(0f, 0f),
            Anim.Key(1f, 10f, Easing.ExpoOut),
            Anim.Key(2f, 10f, Easing.ExpoIn),
            Anim.Key(3f, 0f, Easing.Linear),
        )
        val plain = Anim.keys(keys)
        val smooth = Anim.keysSmooth(keys)
        val plainJump = abs(velocity(plain, 1f, fromLeft = true) - velocity(plain, 1f, fromLeft = false))
        val smoothJump = abs(velocity(smooth, 1f, fromLeft = true) - velocity(smooth, 1f, fromLeft = false))
        assertTrue(smoothJump < plainJump, "smooth keys must close the velocity gap: $smoothJump vs $plainJump")
        assertTrue(smoothJump < JOINT_EPS, "smooth joint still pops by $smoothJump")
    }

    // ------------------------------------------------------------------ Follow

    @Test
    fun followTrailsThePrimaryWithMeasurableLag() {
        val primary = Anim.tween(0f, 1f, 0f, 1f, Easing.Linear)
        val tight = Anim.Follow(primary, delaySec = 0.2f, damping = 1f)
        val soft = Anim.Follow(primary, delaySec = 0.2f, damping = 0.3f)
        near(tight.at(0.5f), 0.3f, 1e-2f)
        assertTrue(tight.at(0.5f) < primary.at(0.5f) - 0.1f, "follow must lag the primary: ${tight.at(0.5f)}")
        assertTrue(soft.at(0.5f) < tight.at(0.5f), "softer damping must trail further: ${soft.at(0.5f)} < ${tight.at(0.5f)}")
    }

    @Test
    fun followWithNoLagIsThePrimary() {
        val primary = Anim.tween(2f, 5f, 0f, 1f, Easing.Linear)
        near(Anim.Follow(primary, delaySec = 0f, damping = 1f).at(0.4f), primary.at(0.4f))
    }

    // ------------------------------------------------------------------ Anticipate

    @Test
    fun anticipateWindsUpAgainstTheTravelFirst() {
        val move = Anim.tween(0f, 100f, 0f, 1f, Easing.Linear)
        val a = Anim.Anticipate(move, amount = 0.1f)
        near(a.at(0f), 0f)
        near(a.at(1f), 100f)
        val windUp = a.at(0.05f)
        assertTrue(windUp < 0f, "wind-up must move against the travel: $windUp")
        assertTrue(windUp >= -0.12f * 100f, "wind-up stays in the 8–12% band: $windUp")
        assertTrue(windUp <= -0.08f * 100f * 0.5f, "wind-up is a real pre-move: $windUp")
    }

    @Test
    fun anticipateNeverFlipsThroughZeroOnAScaleTravel() {
        val unfold = Anim.tween(0.04f, 1f, 0f, 1f, Easing.ExpoOut)
        val a = Anim.Anticipate(unfold, amount = 0.1f)
        assertTrue(a.at(0.05f) >= 0f, "scale wind-up must not go negative: ${a.at(0.05f)}")
        near(a.at(1f), 1f)
    }

    // ------------------------------------------------------------------ organic stagger

    @Test
    fun organicStaggerIsSeededAndSpreadsAroundTheBase() {
        fun animator(seed: Int) = TextAnimator(
            TextUnit.Word, UnitState(dy = 1f), at = 0f, duration = 0.4f, stagger = 0.1f,
            jitter = 0.3f, easeVary = 0.2f, seed = seed,
        )
        val a = animator(seed = 7)
        val same = animator(seed = 7)
        val other = animator(seed = 8)
        val count = 6
        for (i in 0 until count) {
            assertEquals(a.startOf(i, count), same.startOf(i, count), "same seed must give the same start of unit $i")
            val base = i * 0.1f
            if (i > 0) {
                val spread = abs(a.startOf(i, count) - base)
                assertTrue(spread <= 0.3f * base + 1e-4f, "unit $i must sit within ±30% of $base: ${a.startOf(i, count)}")
            }
        }
        assertTrue(a.startOf(3, count) != other.startOf(3, count), "a new seed must re-shake the cascade")
    }

    @Test
    fun easeVaryChangesUnitTimingButStaysSeeded() {
        fun animator(seed: Int) = TextAnimator(
            TextUnit.Word, UnitState(dy = 1f), at = 0f, duration = 0.5f, stagger = 0f,
            easeVary = 0.25f, seed = seed,
        )
        val a = animator(seed = 11)
        val same = animator(seed = 11)
        val t = 0.2f
        for (i in 0 until 5) {
            assertEquals(a.stateAt(t, i, 5).dy, same.stateAt(t, i, 5).dy, "same seed must land unit $i the same")
        }
        // With stagger 0 every unit starts together; easeVary alone must split their mid-flight states.
        val mids = (0 until 5).map { a.stateAt(t, it, 5).dy }
        assertTrue(mids.distinct().size > 1, "easeVary must give units different speeds: $mids")
        val settled = (0 until 5).map { a.stateAt(0.8f, it, 5).dy }
        assertTrue(settled.all { abs(it) < 1e-3f }, "every unit is home once its (varied) span is over")
    }

    // ------------------------------------------------------------------ overshoot bands

    @Test
    fun overshootStaysInsideItsPerPropertyBand() {
        val overshoot = MotionSpec.Springs.Overshoot
        val playfulScale = overshoot.springFor(MotionSpec.Springs.Overshoot.Prop.Scale, fraction = 0.10f, playful = true)
        val playfulPeak = peakOf(playfulScale) - 1f
        assertTrue(playfulPeak in overshoot.PlayfulMin..overshoot.PlayfulMax, "playful scale in 5–15%: $playfulPeak")

        val premiumScale = overshoot.springFor(MotionSpec.Springs.Overshoot.Prop.Scale, fraction = 0.15f, playful = false)
        val premiumPeak = peakOf(premiumScale) - 1f
        assertTrue(premiumPeak <= overshoot.PremiumMax + BAND_EPS, "premium scale clamped to 3%: $premiumPeak")

        val opacity = overshoot.springFor(MotionSpec.Springs.Overshoot.Prop.Opacity, fraction = 0.15f, playful = true)
        assertTrue(peakOf(opacity) <= 1f + BAND_EPS, "opacity never rings: ${peakOf(opacity)}")

        val position = overshoot.springFor(MotionSpec.Springs.Overshoot.Prop.Position, fraction = 0.02f, playful = false)
        assertTrue(peakOf(position) - 1f in 0f..overshoot.PremiumMax + BAND_EPS, "premium position in 0–3%")
    }

    private fun peakOf(e: Easing): Float {
        var peak = 0f
        for (i in 0..2000) {
            val v = e.at(i / 2000f)
            if (v > peak) peak = v
        }
        return peak
    }

    private companion object {
        const val JOINT_EPS = 0.05f
        const val BAND_EPS = 0.005f
    }
}
