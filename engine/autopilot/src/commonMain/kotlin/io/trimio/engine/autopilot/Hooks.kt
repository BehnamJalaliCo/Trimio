package io.trimio.engine.autopilot

import io.trimio.engine.motion.score.BeatScore
import io.trimio.engine.motion.score.NumberWords
import io.trimio.engine.motion.score.SceneScore

/**
 * The planner's first seconds: the payoff before anything else — money given opens on its own
 * voucher card most of the time (a counter or a slam are the seeded alternatives), a number on a
 * counter, else a slammed title — or, with a cold open, the payoff line's figure over the words
 * that name it. The random streams are the planner's own ([hookRng], [recipeRng]), shared, so a
 * seed's edit is the same whoever builds it.
 */
internal class Hooks(
    private val lines: List<Lines.Line>,
    private val texts: List<String>,
    private val u: Understanding,
    private val senses: List<Quantities.Sense>,
    private val figures: Figures,
    private val rtl: Boolean,
    private val hookRng: Rng,
    private val recipeRng: Rng,
    private val taste: Taste,
    /** The edit's highlight mark. */
    private val mark: String,
    private val usedRecipes: MutableList<String>,
    private val notes: MutableList<String>,
) {

    /** The payoff of line [k], shown first. */
    fun hook(k: Int, reads: List<LineRead>): BeatScore? {
        val line = lines[k]
        val number = NumberWords.findAll(texts.subList(line.first, line.last + 1)).firstOrNull()
        val title = (u.hook?.title?.takeIf { u.hook.line == k } ?: reads[k].title).ifBlank { u.hook?.title.orEmpty() }.trim()
        notes += "hook: payoff of line $k first (${number?.value ?: title})"
        // Money given opens on its own card most of the time; a counter or a slam are the seeded alternatives.
        senses[k].voucher?.let { money -> return moneyHook(k, money, title, reads) }
        val counter = number != null && (number.percent || number.value >= 2) && hookRng.chance(0.8f)
        // The number that pays off: the time left, the views; else the first said.
        val sense = senses[k]
        val q = sense.deadline ?: sense.social.firstOrNull()
        return when {
            counter && q != null -> counterHook(q.value, q.decimals, false, figures.unitLabel(q) ?: title)
            counter -> counterHook(number!!.value, number.decimals, number.percent, title)
            title.isNotBlank() -> textHook(title)
            else -> null
        }
    }

    /** The figure the cold open shows: the money given, else the line's richest number. */
    fun openingFigure(o: Planner.Opening): Quantities.Quantity? = senses[o.line].let { s ->
        s.voucher ?: s.money ?: s.deadline ?: s.social.firstOrNull() ?: s.quantities.maxByOrNull { it.value }
    }

    /** The rich figure the cold open has shown (the body does not show it again). */
    fun openingKey(o: Planner.Opening): String? = senses[o.line].let { s -> s.richShow()?.let { figures.keyOf(it, s) } }

    /**
     * The cold open's scene: the speaker saying the payoff, under its figure — the voucher for
     * money, else a counter, else the line's title slammed — from 0 s to just past the restart.
     */
    fun openingScene(o: Planner.Opening, reads: List<LineRead>): SceneScore {
        val k = o.line
        val q = openingFigure(o)
        val hold = o.length + OPENING_TAIL
        val title = (u.hook?.title?.takeIf { u.hook.line == k } ?: reads[k].title).trim()
        notes += "cold open: line $k (${q?.value ?: title}), ${o.length}s"
        val beat = when {
            q != null && q.kind == Quantities.Kind.Money -> figures.voucherBeat(q, reads[k], figures.voucherLabel(lines[k], q, reads[k]), place = "top")
                .copy(at = null, until = null, time = 0f, hold = hold, energy = 0.95f).also { usedRecipes += it.recipe }
            q != null -> counterHook(q.value, q.decimals, q.kind == Quantities.Kind.Percent, figures.unitLabel(q) ?: title).copy(time = 0f, hold = hold)
            title.isNotBlank() -> textHook(title).copy(time = 0f, hold = hold)
            else -> null
        }
        return SceneScore(time = 0f, beats = listOfNotNull(beat))
    }

    private fun moneyHook(k: Int, q: Quantities.Quantity, title: String, reads: List<LineRead>): BeatScore {
        val what = figures.voucherLabel(lines[k], q, reads[k])
        val r = hookRng.next()
        return when {
            r < VOUCHER_HOOK -> figures.voucherBeat(q, reads[k], what, place = "top")
                .copy(at = null, until = null, time = 0.05f, hold = PAYOFF_HOLD, energy = 0.95f).also { usedRecipes += it.recipe }
            r < VOUCHER_HOOK + COUNTER_HOOK -> counterHook(q.value, q.decimals, false, listOfNotNull(q.unit.ifBlank { null }, what).joinToString(" "))
            else -> textHook(title.ifBlank { "${Quantities.digits(q.value, rtl)} ${q.unit}".trim() })
        }
    }

    private fun counterHook(value: Double, decimals: Int, percent: Boolean, title: String): BeatScore {
        usedRecipes += "counter"
        // The counter shows the number: its label keeps only the words.
        val label = title.split(' ').filter { w -> w.none { it.isDigit() } && w !in setOf("٪", "%", "درصد") }.joinToString(" ")
        return BeatScore(
            recipe = "counter", time = 0.05f, hold = DATA_HOLD, value = value.toFloat(), decimals = decimals,
            suffix = if (percent) "٪" else "", label = label.ifBlank { null }, energy = 0.95f, place = "top",
        )
    }

    private fun textHook(title: String): BeatScore {
        val recipe = recipeRng.weighted(listOf("slam" to 1f, "stack" to 0.6f, "spread" to 0.4f).map { (r, w) -> r to w * taste.recipe(r) })
        usedRecipes += recipe
        return BeatScore(recipe = recipe, time = 0.05f, hold = 2.2f, text = title, energy = 0.95f, place = "top", mark = mark)
    }

    private companion object {
        const val VOUCHER_HOOK = 0.7f
        const val COUNTER_HOOK = 0.2f
        const val DATA_HOLD = 3.2f
        const val PAYOFF_HOLD = 4f

        /** The cold open's figure stays a little past the restart (the flash). */
        const val OPENING_TAIL = 0.6f
    }
}
