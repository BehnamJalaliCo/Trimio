package io.trimio.engine.motion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The golden gate is inclusive at its own numbers and never trades off against craft: a craft
 * violation or a metric miss alone must refuse the piece, and the checklist stays the ban list.
 */
class GoldenQaTest {

    @Test
    fun metricsExactlyAtTheThresholdsPass() {
        assertTrue(GoldenQa.passes(lpips = 0.12f, ssim = 0.93f), "exactly at the thresholds must pass")
        assertTrue(GoldenQa.passes(lpips = 0f, ssim = 1f), "perfect metrics must pass")
        assertFalse(GoldenQa.passes(lpips = 0.13f, ssim = 0.93f), "lpips past the gate must fail")
        assertFalse(GoldenQa.passes(lpips = 0.12f, ssim = 0.92f), "ssim under the gate must fail")
    }

    @Test
    fun craftFindingVetoesAPerfectGoldenScore() {
        val blank = timeline(shot(el(appearsAt = 0.5f), firstFrameHasContent = false))
        val verdict = GoldenQa.review(blank, lpips = 0f, ssim = 1f)
        assertFalse(verdict.ok, "a craft finding must veto the piece, got ${verdict.note}")
        assertEquals(listOf("first_frame_empty"), verdict.findings.map { it.rule })
    }

    @Test
    fun badLpipsVetoesACleanTimeline() {
        val clean = timeline(shot(el()))
        val verdict = GoldenQa.review(clean, lpips = 0.5f, ssim = 1f)
        assertFalse(verdict.ok, "lpips past the gate must veto a clean piece, got ${verdict.note}")
        assertEquals(listOf("golden_lpips"), verdict.findings.map { it.rule })
    }

    @Test
    fun emptyComparisonHasNoPixelDrift() {
        assertEquals(0f, GoldenQa.pixelDriftRatio(differing = 4, total = 0))
        assertEquals(0.25f, GoldenQa.pixelDriftRatio(differing = 1, total = 4))
    }

    @Test
    fun watchPassRulesMirrorTheGeneratedBanList() {
        assertEquals(MotionSpec.Generated.all, GoldenQa.watchPassRules)
    }

    private fun el(name: String = "el", appearsAt: Float = 0f) =
        CraftAudit.Element(name = name, appearsAtSec = appearsAt)

    private fun shot(
        vararg elements: CraftAudit.Element,
        firstFrameHasContent: Boolean = true,
    ) = CraftAudit.Shot("shot", 0f, 2f, elements.toList(), firstFrameHasContent)

    private fun timeline(vararg shots: CraftAudit.Shot) =
        CraftAudit.Timeline(shots.toList(), hasAudio = true, durationSec = 2f)
}
