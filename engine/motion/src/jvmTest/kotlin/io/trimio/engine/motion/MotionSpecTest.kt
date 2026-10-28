package io.trimio.engine.motion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The rulebook's measured numbers hold their bands: a spring damping silently drifting above 0.85
 * or a last-line hold falling under two seconds reads wrong on screen and no code review sees it.
 */
class MotionSpecTest {

    @Test
    fun rolesBindToTheirMeasuredCurves() {
        assertEquals(Easing.ExpoOut, MotionSpec.Ease.Arrival)
        assertEquals(Easing.Bezier(0.45f, 0f, 0.55f, 1f), MotionSpec.Ease.Camera)
        assertEquals(Easing.ExpoIn, MotionSpec.Ease.Exit)
        assertEquals(Easing.SineInOut, MotionSpec.Ease.Drift)
        // Emphasis aliases the entrance curve: overshoot on text reads as a UI bounce.
        assertEquals(Easing.Enter, MotionSpec.Ease.Emphasis)
    }

    @Test
    fun springsStayInTheMeasuredDampingBands() {
        val springs = listOf(
            MotionSpec.Springs.NoBounce, MotionSpec.Springs.Premium,
            MotionSpec.Springs.Playful, MotionSpec.Springs.Cartoon,
        )
        for (spring in springs) {
            assertTrue(spring.damping > 0f && spring.damping <= 1f, "damping ${spring.damping} out of (0,1]")
            assertTrue(spring.frequency > 0f, "frequency ${spring.frequency}")
        }
        assertEquals(1f, MotionSpec.Springs.NoBounce.damping)
        assertTrue(MotionSpec.Springs.Premium.damping in 0.7f..0.85f)
    }

    @Test
    fun moveTimingsSitInTheMeasuredBands() {
        val t = MotionSpec.Time
        assertTrue(t.FastMoveMax >= t.FastMoveMin)
        assertTrue(t.FastMoveMin in 0.2f..1.0f && t.FastMoveMax in 0.2f..1.0f)
        assertTrue(t.inFastMoveRange(0.45f))
        assertTrue(!t.inFastMoveRange(0.2f) && !t.inFastMoveRange(0.9f))
        assertTrue(t.ExitDurationScale in 0.4f..0.5f)
        assertTrue(t.OverlapStart in 0.2f..0.6f && t.OverlapEnd >= t.OverlapStart && t.OverlapEnd <= 0.6f)
        assertEquals(1, t.StaggerLetterFrames)
        assertTrue(t.StaggerWord in 0.06f..0.12f)
        assertTrue(t.StaggerItem in 0.04f..0.08f)
        assertTrue(t.PrePayoffHoldMin in 0.2f..1.0f && t.PrePayoffHoldMax >= t.PrePayoffHoldMin)
        assertTrue(t.MontageCutMin >= 0.1f && t.MontageCutMax > t.MontageCutMin && t.MontageCutMax <= 1f)
        assertTrue(t.ShotAvgMin in 1.5f..3.5f && t.ShotAvgMax >= t.ShotAvgMin)
        assertEquals(3f, t.DeadStretchMax)
    }

    @Test
    fun holdsScaleWithWordsAndProtectTheLastLine() {
        val t = MotionSpec.Time
        assertEquals(0.5, t.HoldBase.toDouble(), absoluteTolerance = 1e-6)
        assertEquals(1.7, t.holdFor(4).toDouble(), absoluteTolerance = 1e-5)
        assertEquals(2.0, t.holdForLine(2, isLast = true).toDouble(), absoluteTolerance = 1e-5)
        assertEquals(3.5, t.holdForLine(10, isLast = true).toDouble(), absoluteTolerance = 1e-4)
        assertTrue(t.holdForLine(10, isLast = true) >= t.LastLineMinHold)
        assertEquals(t.holdFor(4).toDouble(), t.holdForLine(4, isLast = false).toDouble(), absoluteTolerance = 1e-5)
    }

    @Test
    fun beatsDeriveFromIndexWithoutDrift() {
        assertEquals(14, MotionSpec.Beat.framesPerBeat(30, 128f))
        assertEquals(450, MotionSpec.Beat.beatFrame(32, 30, 128f))
        assertEquals(225, MotionSpec.Beat.beatFrame(16, 30, 128f))
        assertEquals(0, MotionSpec.Beat.beatFrame(0, 30, 128f))
        // 128 BPM at 30 fps is 14.0625 frames per beat; accumulating the rounded length drifts
        // two frames early by beat 32 — documented here so the index formula is never "optimised"
        // into a running sum.
        var naive = 0
        repeat(32) { naive += MotionSpec.Beat.framesPerBeat(30, 128f) }
        assertEquals(448, naive)
        assertTrue(naive < MotionSpec.Beat.beatFrame(32, 30, 128f), "accumulating rounded beats lands early")
    }

    @Test
    fun captionsAreSizedByEnergy() {
        val c = MotionSpec.Captions
        assertEquals(2..3, c.WordsHighEnergy)
        assertEquals(3..5, c.WordsConversational)
        assertEquals(4..6, c.WordsCalm)
        assertTrue(c.WordsHighEnergy.last <= c.WordsCalm.last)
        assertEquals(150f, c.BreakPauseMs)
    }

    @Test
    fun generatedTellsAreNamedAndUnique() {
        val all = MotionSpec.Generated.all
        assertTrue(all.isNotEmpty())
        assertEquals(all.size, all.toSet().size, "ban-list ids must be unique")
        for (id in listOf(
            MotionSpec.Generated.LinearMotion,
            MotionSpec.Generated.SlowCreepScale,
            MotionSpec.Generated.PostLandSlide,
            MotionSpec.Generated.UiBounce,
            MotionSpec.Generated.OpacityBounce,
            MotionSpec.Generated.UniformEntry,
            MotionSpec.Generated.SameSceneEntry,
            MotionSpec.Generated.BreathingLoop,
            MotionSpec.Generated.ClickRings,
        )) assertTrue(id in all, id)
    }

    @Test
    fun textStaysAboveTheReadabilityFloor() {
        val l = MotionSpec.Legibility
        assertEquals(28f, l.MinTextPxAt1080)
        assertEquals(28.08, l.minTextPx(1080).toDouble(), absoluteTolerance = 1e-3)
        assertTrue(l.minTextPx(1080) >= l.MinTextPxAt1080)
        assertTrue(l.TitleHeightFractionMin in 0.03f..0.1f && l.TitleHeightFractionMax > l.TitleHeightFractionMin)
    }
}
