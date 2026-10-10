package io.trimio.engine.autopilot

import io.trimio.core.model.audio.PcmAudio
import io.trimio.engine.assets.MusicMood
import io.trimio.engine.motion.score.EditPlan
import kotlin.math.abs
import kotlin.math.log10
import kotlin.test.Test
import kotlin.test.assertTrue

/** The voice splice: no clicks and no level dips at joins, silence where the restart breathes, music on the restart. */
class SoundtrackTest {
    private val rate = 48_000
    private val soundtrack = Soundtrack(rate)

    /** Cuts mid-tone at arbitrary phases: the worst case for a click. */
    private val joins = EditPlan(
        listOf(EditPlan.Segment(0f, 0.5f, 0f), EditPlan.Segment(0.737f, 1.2f, 0.5f), EditPlan.Segment(1.611f, 2.3f, 0.963f)),
    )

    @Test
    fun crossfadesLeaveNoClick() {
        val tone = SyntheticVoice(3f).word(0f, 3f).pcm()
        val n = (joins.duration * rate).toInt()
        val at = listOf(0.5f, 0.963f)
        val spliced = soundtrack.splice(tone, joins, n)
        val worst = SyntheticVoice.worstJump(spliced, rate, at)
        assertTrue(worst < 1.6f, "a click at a join: ${worst}× the material's own largest step")
        // The detector itself: the same cuts butted together without fades do click.
        assertTrue(SyntheticVoice.worstJump(butted(tone, joins, n), rate, at) > 3f, "hard cuts must register as clicks")
    }

    @Test
    fun crossfadesKeepTheLevelThroughTheJoin() {
        // Noise: the two sides are uncorrelated, so equal-power gains hold the level exactly.
        var seed = 3
        val noise = PcmAudio(FloatArray(3 * rate) { seed = seed * 1_103_515_245 + 12_345; (((seed ushr 16) and 0x7FFF) / 32_768f - 0.5f) * 0.4f }, rate)
        val spliced = soundtrack.splice(noise, joins, (joins.duration * rate).toInt())
        val level = db(spliced, 0.1f, 0.4f)
        for (t in listOf(0.5f, 0.963f)) {
            val around = db(spliced, t - 0.004f, t + 0.004f)
            assertTrue(abs(around - level) < 1.5f, "level at the join $t: $around dB vs $level dB")
        }
    }

    @Test
    fun theColdOpenFadesWithinItselfAndTheRestartIsClean() {
        val tone = SyntheticVoice(3f).word(0f, 3f).pcm()
        val edit = EditPlan.identity(3f).withColdOpen(listOf(1.0f..1.5f, 1.8f..2.1f), gap = 0.25f)
        val spliced = soundtrack.splice(tone, edit, (edit.duration * rate).toInt())
        val restart = edit.preludeLength
        val worst = SyntheticVoice.worstJump(spliced, rate, listOf(0f + 0.03f, 0.5f, restart, edit.bodyStart))
        assertTrue(worst < 1.6f, "a click at the cold open's edges: $worst×")
        // The restart's transition plays over silence: the cold open has faded out by it, the body not begun.
        val gap = ((restart + 0.001f) * rate).toInt() until (edit.bodyStart * rate).toInt()
        assertTrue(gap.all { abs(spliced[it]) < 1e-6f }, "voice in the restart's gap")
    }

    @Test
    fun musicEntersOnTheRestart() {
        val silent = PcmAudio(FloatArray(4 * rate), rate)
        val edit = EditPlan.identity(4f).withColdOpen(listOf(2f..3f), gap = 0.25f)
        val out = soundtrack.master(silent, edit, emptyList(), edit.duration, MusicMood.entries.first(), musicSeed = 1, cues = emptyList()).audio.samples
        assertTrue((0 until (0.99f * rate).toInt()).all { abs(out[it]) < 1e-5f }, "the flash-forward plays dry")
        assertTrue(db(out, 1.2f, 2.2f) > -40f, "the bed is in after the restart")
    }

    private fun db(x: FloatArray, a: Float, b: Float): Float {
        val r = (a * rate).toInt() until (b * rate).toInt()
        return 10f * log10(r.sumOf { (x[it] * x[it]).toDouble() }.toFloat() / r.count() + 1e-12f)
    }

    /** The cut list butted together sample-exact, no fades. */
    private fun butted(voice: PcmAudio, edit: EditPlan, n: Int) = FloatArray(n).also { out ->
        for (s in edit.played) {
            val a = (s.sourceStart * rate).toInt()
            val o = (s.outStart * rate).toInt()
            for (i in 0 until (s.length * rate).toInt()) if (o + i < n) out[o + i] = voice.samples[a + i]
        }
    }
}
