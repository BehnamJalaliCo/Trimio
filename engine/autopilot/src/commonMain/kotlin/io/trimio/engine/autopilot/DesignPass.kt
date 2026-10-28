package io.trimio.engine.autopilot

import io.trimio.core.model.transcript.Word
import io.trimio.engine.motion.score.BeatScore
import io.trimio.engine.motion.score.NumberWords
import io.trimio.engine.motion.score.StoryBeat
import io.trimio.engine.motion.score.StoryMap

/**
 * The design pass, between the story map and the planner: every beat earns exactly one treatment
 * and one reason for it. The map says what the story does — setup, turn, payoff, callback — and
 * this pass decides how each doing is shown, in words an editor could argue with. The planner then
 * only lays those treatments out in time ([StoryPlanner]); it invents nothing here.
 */
internal object DesignPass {

    /** One beat, one treatment, one reason. */
    data class Treatment(val beat: StoryBeat, val beatScore: BeatScore, val reason: String)

    data class Design(val treatments: List<Treatment>) {
        val notes: List<String>
            get() = treatments.map { "design: ${it.beat.kind} «${it.beat.claim}» → ${it.beatScore.recipe} — ${it.reason}" }
    }

    /** The treatment for every beat of [map], read against what [words] actually say. */
    fun design(map: StoryMap, words: List<Word>): Design = Design(map.beats.map { treat(it, words) })

    private fun treat(beat: StoryBeat, words: List<Word>): Treatment {
        val said = (beat.from..maxOf(beat.from, beat.until)).mapNotNull { words.getOrNull(it)?.text }
        val figure = NumberWords.findAll(said).firstOrNull()
        val money = said.any { it in MONEY }
        val recipe = recipeOf(beat.kind, figure != null, money)
        val reason = reasonOf(beat.kind, figure != null, money)
        val beatScore = BeatScore(
            recipe = recipe,
            at = beat.from,
            until = beat.until.takeIf { it > beat.from },
            text = beat.claim.takeIf { recipe in TEXT_TREATMENTS },
            label = beat.claim.takeIf { recipe !in TEXT_TREATMENTS },
            value = figure?.value?.toFloat(),
            decimals = figure?.decimals,
            suffix = if (figure?.percent == true) "%" else null,
            energy = beat.energy,
            place = if (beat.kind == "payoff") "top" else null,
        )
        return Treatment(beat, beatScore, reason)
    }

    /** One treatment per beat kind and content: the money card, the figure, the verdict, the echo. */
    private fun recipeOf(kind: String, figure: Boolean, money: Boolean): String = when {
        kind == "payoff" && money -> "voucher"
        kind == "payoff" && figure -> "counter"
        kind == "payoff" -> "slam"
        kind == "turn" && figure -> "meter"
        kind == "turn" -> "stamp"
        kind == "callback" -> "flip"
        figure -> "counter"
        else -> "mask-rise"
    }

    private fun reasonOf(kind: String, figure: Boolean, money: Boolean): String = when {
        kind == "payoff" && money -> "money given opens on its own card, the way the promise pays off"
        kind == "payoff" && figure -> "the payoff is a number, and a number lands as a counter"
        kind == "payoff" -> "the payoff is a claim, and a claim lands hard"
        kind == "turn" && figure -> "the turn changes a figure, and a meter says how much"
        kind == "turn" -> "the turn is the verdict of the piece, stamped"
        kind == "callback" -> "the callback echoes the setup, and a flip shows the echo"
        kind == "setup" && figure -> "the setup's figure earns a counter of its own"
        else -> "the setup says what is at stake, in its own words"
    }

    private val TEXT_TREATMENTS = setOf("slam", "mask-rise", "flip", "stack", "spread", "glitch", "type-on", "blur-in")
    private val MONEY = setOf("تتر", "تومن", "تومان", "دلار", "ریال", "$", "usdt", "dollar")
}
