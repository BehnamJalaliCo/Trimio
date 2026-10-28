package io.trimio.engine.motion

import androidx.compose.ui.geometry.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FinishingPassTest {

    @Test
    fun halationGateOpensOnlyOnHighlights() {
        assertBand(FinishingPass.halationGate(0.2f), 0f, 0f, "darks stay clean")
        assertBand(FinishingPass.halationGate(0.55f), 0f, 0f, "floor is the closed edge")
        assertBand(FinishingPass.halationGate(0.75f), 0.2f, 0.8f, "mid-high is partially open")
        assertBand(FinishingPass.halationGate(0.95f), 1f, 1f, "ceiling is fully open")
        assertBand(FinishingPass.halationGate(1.2f), 1f, 1f, "above ceiling stays open")
    }

    @Test
    fun halationGateIsMonotoneAndSoft() {
        val xs = listOf(0.5f, 0.6f, 0.65f, 0.7f, 0.75f, 0.85f, 0.95f)
        val ys = xs.map { FinishingPass.halationGate(it) }
        for (i in 1 until ys.size) {
            assertTrue(ys[i] >= ys[i - 1], "gate must not fall at ${xs[i]}")
            assertTrue(ys[i] - ys[i - 1] < 0.4f, "gate steps too hard at ${xs[i]}")
        }
    }

    @Test
    fun diffusionMixScalesWithFilterThickness() {
        val none = FinishingPass.diffusionOf(Finishing.FilmLook.Diffusion.None)
        val pro = FinishingPass.diffusionOf(Finishing.FilmLook.Diffusion.ProMist)
        val glim = FinishingPass.diffusionOf(Finishing.FilmLook.Diffusion.Glimmerglass)
        val cine = FinishingPass.diffusionOf(Finishing.FilmLook.Diffusion.CineBloom)
        assertBand(none.mix, 0f, 0f, "no diffusion mixes nothing")
        assertBand(none.radiusPx, 0f, 0f, "no diffusion has no radius")
        assertTrue(pro.mix < glim.mix, "ProMist is lighter than Glimmerglass")
        assertTrue(glim.mix < cine.mix, "Glimmerglass is lighter than CineBloom")
        assertTrue(pro.radiusPx < glim.radiusPx, "ProMist is tighter than Glimmerglass")
        assertTrue(glim.radiusPx < cine.radiusPx, "Glimmerglass is tighter than CineBloom")
        assertBand(pro.mix, 0.1f, 0.3f, "ProMist stays a whisper")
        assertBand(cine.mix, 0.3f, 0.6f, "CineBloom is a real bloom")
    }

    @Test
    fun gradeCurveLiftsLightsAndHoldsDarks() {
        val grade = Finishing.Grade(exposure = 0f, contrast = 1.06f, saturation = 1f, temperature = 0f)
        val dark = FinishingPass.gradeChannel(0.1f, FinishingPass.Channel.G, grade)
        val mid = FinishingPass.gradeChannel(0.5f, FinishingPass.Channel.G, grade)
        val light = FinishingPass.gradeChannel(0.9f, FinishingPass.Channel.G, grade)
        assertBand(dark, 0f, 0.15f, "darks stay down")
        assertBand(mid, 0.4f, 0.6f, "mid-grey holds")
        assertTrue(light > 0.88f, "lights lift on the S")
        val flat = Finishing.Grade(exposure = 0f, contrast = 1f, saturation = 1f, temperature = 0f)
        assertBand(FinishingPass.gradeChannel(0.3f, FinishingPass.Channel.G, flat), 0.29f, 0.31f, "contrast 1 is neutral")
    }

    @Test
    fun gradeTemperatureWarmsAndCools() {
        val warm = Finishing.Grade(exposure = 0f, contrast = 1f, saturation = 1f, temperature = 0.5f)
        val cool = Finishing.Grade(exposure = 0f, contrast = 1f, saturation = 1f, temperature = -0.5f)
        val rW = FinishingPass.gradeChannel(0.5f, FinishingPass.Channel.R, warm)
        val bW = FinishingPass.gradeChannel(0.5f, FinishingPass.Channel.B, warm)
        assertTrue(rW > bW, "warm temperature lifts red over blue")
        val rC = FinishingPass.gradeChannel(0.5f, FinishingPass.Channel.R, cool)
        val bC = FinishingPass.gradeChannel(0.5f, FinishingPass.Channel.B, cool)
        assertTrue(bC > rC, "cool temperature lifts blue over red")
    }

    @Test
    fun grainStaysAWhisperAndTicksAtTwelveHertz() {
        assertEquals(1.0, (FinishingPass.grainCodeValues(0f) * 255f).toDouble(), absoluteTolerance = 0.05)
        assertEquals(2.0, (FinishingPass.grainCodeValues(1f) * 255f).toDouble(), absoluteTolerance = 0.05)
        assertTrue(FinishingPass.grainCodeValues(0.05f) * 255f <= 2.05f, "default film grain stays mild")
        assertEquals(FinishingPass.grainFrame(0f), FinishingPass.grainFrame(1f / 120f), "grain holds inside a 12 Hz step")
        assertTrue(FinishingPass.grainFrame(1f) != FinishingPass.grainFrame(1.2f), "grain advances across 12 Hz steps")
        assertEquals(FinishingPass.grainFrame(0f), FinishingPass.grainFrame(1f / 24f), "30 fps frames often share one grain aim")
    }

    @Test
    fun shutterSamplesScaleWithTravel() {
        assertEquals(2, FinishingPass.shutterSamples(1f), "a nudge takes two taps")
        assertEquals(6, FinishingPass.shutterSamples(200f), "a smear takes six taps")
        val mid = FinishingPass.shutterSamples(12f)
        assertTrue(mid in 2..FinishingPass.MAX_SHUTTER_SAMPLES, "samples stay inside the adaptive band")
    }

    @Test
    fun zoomingFootageCountsAsMoving() {
        val still = MotionBlurDecide.travel(zoomed(1f), zoomed(1f))
        val punch = MotionBlurDecide.travel(zoomed(1f), zoomed(1.12f))
        assertTrue(MotionBlurDecide.blurs(MotionBlurDecide.Kind.Media), "footage may take motion blur")
        assertFalse(MotionBlurDecide.isMoving(still, unitsMove = false, thresholdPx = 1.5f), "still footage is not moving")
        assertTrue(MotionBlurDecide.isMoving(punch, unitsMove = false, thresholdPx = 1.5f), "punch-in footage is moving")
        assertFalse(MotionBlurDecide.blurs(MotionBlurDecide.Kind.Counter), "counters never blur")
        assertFalse(MotionBlurDecide.blurs(MotionBlurDecide.Kind.Effect), "full-frame effects never blur")
        assertTrue(MotionBlurDecide.isMoving(0f, unitsMove = true, thresholdPx = 1.5f), "sliding text units still blur")
    }

    // ---- private fixtures

    private fun assertBand(v: Float, lo: Float, hi: Float, message: String) {
        assertTrue(v in lo..hi, "$message: $v !in $lo..$hi")
    }

    /** Bounds of a 1080×1920 frame scaled about its centre — a punch-in's two extremes. */
    private fun zoomed(scale: Float): Rect {
        val w = 1080f * scale
        val h = 1920f * scale
        val cx = 540f
        val cy = 960f
        return Rect(cx - w / 2f, cy - h / 2f, cx + w / 2f, cy + h / 2f)
    }
}
