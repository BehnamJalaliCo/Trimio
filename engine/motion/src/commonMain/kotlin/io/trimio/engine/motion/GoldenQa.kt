package io.trimio.engine.motion

/**
 * QA policy as code: the golden-frame gates and the craft audit answer as one [Verdict], so a
 * render cannot pass on flattering numbers while breaking craft, or the reverse.
 */
object GoldenQa {

    /**
     * LPIPS (deep-feature distance, lower = more similar) pairs with SSIM (structure, higher =
     * similar) and a raw pixel-drift ratio, because each catches drift the others wave through;
     * a frame passes when LPIPS ≤ [lpips] AND SSIM ≥ [ssim].
     */
    data class Thresholds(
        val lpips: Float = 0.12f,
        val ssim: Float = 0.93f,
        val pixelDrift: Float = 0.02f,
    )

    /**
     * Both gates must hold — LPIPS is lower-is-better, SSIM higher-is-better — so a flattering
     * score on one cannot buy out a miss on the other.
     */
    fun passes(lpips: Float, ssim: Float): Boolean = goldenMisses(lpips, ssim).isEmpty()

    /**
     * Fraction of pixels that differ; a pair with no pixels has nothing to drift, so the ratio
     * reads zero rather than an undefined division.
     */
    fun pixelDriftRatio(differing: Int, total: Int): Float =
        if (total <= 0) 0f else differing.toFloat() / total

    /** The ship/no-ship answer: [ok] only when craft and the golden gates are both clean. */
    data class Verdict(val ok: Boolean, val findings: List<CraftAudit.Finding>, val note: String)

    /**
     * Craft rules and golden gates in one pass — [ok] is their conjunction, never a trade-off —
     * with [note] counting what each side found.
     */
    fun review(timeline: CraftAudit.Timeline, lpips: Float = 0f, ssim: Float = 1f): Verdict {
        val craft = CraftAudit.audit(timeline)
        val golden = goldenMisses(lpips, ssim)
        val findings = craft + golden
        return Verdict(
            ok = craft.isEmpty() && golden.isEmpty(),
            findings = findings,
            note = "${findings.size} findings: ${craft.size} craft, ${golden.size} golden",
        )
    }

    /**
     * The human watch-pass checklist as stable ids, straight from [MotionSpec.Generated.all] so
     * a human pass and the machine audit share one vocabulary.
     */
    val watchPassRules: List<String> = MotionSpec.Generated.all

    /** Each missed gate becomes a finding, so every refusal in [Verdict] explains itself. */
    private fun goldenMisses(lpips: Float, ssim: Float): List<CraftAudit.Finding> {
        val gate = Thresholds()
        return listOfNotNull(
            if (lpips > gate.lpips) {
                CraftAudit.Finding("golden_lpips", "LPIPS $lpips is past the golden gate of ${gate.lpips}")
            } else {
                null
            },
            if (ssim < gate.ssim) {
                CraftAudit.Finding("golden_ssim", "SSIM $ssim falls under the golden gate of ${gate.ssim}")
            } else {
                null
            },
        )
    }
}
