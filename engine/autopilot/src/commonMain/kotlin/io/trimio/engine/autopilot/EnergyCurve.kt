package io.trimio.engine.autopilot

import io.trimio.core.model.transcript.Word
import io.trimio.engine.motion.score.BeatScore
import io.trimio.engine.motion.score.SceneScore
import io.trimio.engine.motion.score.Score

/**
 * The energy curve, the rhythm's new hierarchy. A piece is not a metronome: it breathes, it rises,
 * and before a big strike it goes quiet — the 3–4 seconds of silence that make the strike land.
 * [calm] marks those windows so the keep-alive pass leaves them empty and the critic never calls
 * them dead air; [gap] sizes the ordinary event gap by the local energy instead of one global beat.
 */
internal object EnergyCurve {

    /** The quiet kept before a big beat: silence is what gives the strike its force. */
    const val SILENCE = 3.5f

    /** A beat at this energy or above is a strike worth silencing for. */
    const val BIG_ENERGY = 0.8f

    /** One landing on the curve: when, and how hard. */
    data class Impulse(val at: Float, val energy: Float) {
        val big: Boolean get() = energy >= BIG_ENERGY
    }

    /** Every graphic landing, on the clock, with the energy it carries. */
    fun impulses(score: Score, words: List<Word>): List<Impulse> =
        score.scenes.flatMap { sc -> sc.beats.map { Impulse(land(it, sc, score, words), it.energy ?: 0.6f) } }.sortedBy { it.at }

    /** Windows to leave empty: [SILENCE] seconds of calm closing on each big beat. */
    fun calm(score: Score, words: List<Word>): List<ClosedFloatingPointRange<Float>> =
        impulses(score, words).filter { it.big }.map { (it.at - SILENCE).coerceAtLeast(0f)..it.at }

    /** Whether [t] sits in the calm before a strike. */
    fun inCalm(t: Float, calm: List<ClosedFloatingPointRange<Float>>) = calm.any { t in it }

    /**
     * The keep-alive gap at energy [energy]: a hot stretch wants an event sooner, a calm one can
     * hold its breath. Hierarchical — the curve sets the pace, not one constant.
     */
    fun gap(energy: Float): Float {
        val e = energy.coerceIn(0f, 1f)
        return MIN_GAP + (MAX_GAP - MIN_GAP) * (1f - e)
    }

    private fun land(b: BeatScore, sc: SceneScore, score: Score, words: List<Word>): Float {
        b.time?.let { return it }
        b.at?.let { return words.getOrNull(it)?.range?.startMs?.div(1000f) ?: 0f }
        val from = sc.from ?: score.scenes.firstOrNull()?.from ?: 0
        return words.getOrNull(from)?.range?.startMs?.div(1000f) ?: 0f
    }

    private const val MIN_GAP = 1.8f
    private const val MAX_GAP = 4.5f
}
