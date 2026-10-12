package io.trimio.engine.autopilot.judges

import io.trimio.core.model.transcript.Word
import io.trimio.engine.autopilot.Jury
import io.trimio.engine.autopilot.Phrases
import io.trimio.engine.motion.score.BeatScore
import io.trimio.engine.motion.score.Compiler
import io.trimio.engine.motion.score.NumberWords
import io.trimio.engine.motion.score.Words

/**
 * The right element for the moment: what a graphic shows is what is being said while it is up.
 * Its words (title, label, items) are said within [BEFORE] s before it lands to [AFTER] s after it
 * leaves, its figure is a number said there, and a picture fits its line (the picker's
 * relevance). The hook may show the payoff said later, the call to action its keyword; the
 * brief's own words (a site, a term) are always welcome.
 */
internal class Relevance(private val c: Jury.Context) {

    /** A graphic out of its moment: what it shows, and the word index where that is said, if anywhere. */
    data class Miss(val placed: Compiler.Placed, val shown: String, val saidAt: Int?)

    private val words: List<Word> = c.words
    private val keys = words.map { Phrases.key(it.text) }
    private val brief = c.prompt.split(Regex("[\\s,،.!?؟:;()«»\"]+")).map(Phrases::key).filter { it.isNotEmpty() }.toSet()
    private val prelude = c.edit?.preludeLength ?: 0f
    private val rtl = words.count { Bench.persian(it.text) } * 2 > words.size

    fun misses(): List<Miss> = Bench.graphics(c).filter { it.recipe !in EXEMPT && it.at >= prelude }.mapNotNull { p ->
        val at = Bench.locate(c.score, words, p) ?: return@mapNotNull null
        val b = c.score.scenes[at.first].beats[at.second]
        miss(p, b)
    }

    private fun miss(p: Compiler.Placed, b: BeatScore): Miss? {
        val window = words.indices.filter { i -> start(i) >= p.at - BEFORE && start(i) <= p.out + AFTER }
        b.value?.takeIf { b.recipe in FIGURES }?.let { v ->
            val said = NumberWords.findAll(window.map { words[it].text }).any { kotlin.math.abs(it.value - v) <= kotlin.math.abs(v) * TOLERANCE + 0.01 }
            if (!said && !hook(p)) return Miss(p, NumberWords.normalize(v.toString()), saidWhere(v))
        }
        // A name in Latin letters on a Persian piece («Tether» for «تتر») cannot be matched by spelling: the script judge reads those.
        for (text in Bench.shownTexts(b).filter { it.isNotBlank() && (!rtl || Bench.persian(it)) }) {
            val content = text.replace(" | ", " ").split(' ').map(Phrases::key).filter { k -> k.length >= 2 && Words.isContent(k) && k !in brief }
            if (content.isEmpty()) continue
            val hits = content.count { k -> window.any { same(keys[it], k) } }
            if (hits * 2 >= content.size || hook(p)) continue
            return Miss(p, text, words.indices.firstOrNull { i -> content.firstOrNull()?.let { same(keys[i], it) } == true })
        }
        return picture(p, b)
    }

    /** A picture the picker itself rated a poor fit for its line. */
    private fun picture(p: Compiler.Placed, b: BeatScore): Miss? {
        val id = b.visual ?: return null
        val fit = c.pictures[id] ?: return null
        return if (fit < PICTURE_FIT) Miss(p, id, null) else null
    }

    /** The opening seconds: the payoff shown first is said later on purpose. */
    private fun hook(p: Compiler.Placed) = p.at <= HOOK_S

    private fun saidWhere(v: Float): Int? {
        val texts = words.map { it.text }
        return NumberWords.findAll(texts).firstOrNull { kotlin.math.abs(it.value - v) <= kotlin.math.abs(v) * TOLERANCE + 0.01 }?.start
    }

    private fun start(i: Int) = words[i].range.startMs / 1000f

    private fun same(a: String, b: String) = a == b || (a.length > 2 && b.startsWith(a)) || (b.length > 2 && a.startsWith(b))

    companion object {
        const val BEFORE = 1.5f
        const val AFTER = 0.6f
        const val HOOK_S = 1.6f
        const val TOLERANCE = 0.01
        const val PICTURE_FIT = 0.4f

        /** Graphics that are about the piece, not a line: the ask, its end card, a thread told over lines, the speaker's name. */
        val EXEMPT = setOf("comment", "endcard", "message", "lower-third", "pop-captions", "url", "pip", "photo", "b-roll")
        val FIGURES = setOf("counter", "meter", "voucher", "stats", "countdown", "progress")
    }
}
