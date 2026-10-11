package io.trimio.engine.autopilot.judges

import io.trimio.engine.autopilot.Finding
import io.trimio.engine.autopilot.Judge
import io.trimio.engine.autopilot.Jury
import io.trimio.engine.autopilot.Quantities
import io.trimio.engine.autopilot.RulesUnderstander
import io.trimio.engine.autopilot.Severity
import io.trimio.engine.autopilot.Verdict
import io.trimio.engine.motion.score.NumberWords
import io.trimio.engine.motion.score.Score
import io.trimio.engine.motion.score.Words
import io.trimio.engine.motion.style.StyleBook

/**
 * The client's representative: the edit does what the brief asked. The brief is read by keywords
 * ([RulesUnderstander.brief]): the style it names (by name or style word, or a plain look word any fitting style answers), its caption mode (word by word: at most three
 * words a caption; none: no captions), the call-to-action keyword it quotes, and the elements it
 * asks for — a voucher card, a countdown, logos, a progress bar, stats, punch-ins, tightened
 * pauses — each required only when the speech holds what it needs (money given, time left, a
 * named brand, a quota, two social numbers).
 */
class BriefJudge : Judge {
    override val name = "brief"
    override val dimension = "brief fidelity"

    /** An element a brief can ask for: its words, whether the speech supports it, whether the edit has it. */
    private class Ask(val what: String, val words: List<String>, val supported: (Jury.Context) -> Boolean, val shown: (Jury.Context) -> Boolean)

    override fun score(c: Jury.Context): Verdict {
        val brief = RulesUnderstander.brief(c.prompt)
        val f = mutableListOf<Finding>()
        wantedStyle(c.prompt, brief.look, c.score.look)?.let { want ->
            f += Bench.finding(0f, Severity.Major, "the brief asks for the $want style, the edit is ${c.score.look}", "use $want")
        }
        val captions = c.score.captions
        when {
            brief.captions == "word" && (!captions.show || captions.maxWords > WORD_CAPTIONS) ->
                f += Bench.finding(
                    0f, Severity.Major, "word-by-word captions asked; captions show ${if (captions.show) "${captions.maxWords} words" else "nothing"}",
                    "captions of at most $WORD_CAPTIONS words",
                )
            brief.captions == "none" && captions.show -> f += Bench.finding(0f, Severity.Major, "the brief asks for no captions", "hide the captions")
        }
        keyword(c)?.let { k ->
            val shown = Bench.graphics(c).any { it.recipe == Bench.COMMENT && norm(it.text) == norm(k) }
            if (!shown) f += Bench.finding(c.duration, Severity.Major, "the brief's keyword «$k» is not the call to action on screen", "the comment field types «$k»")
        }
        // The creator's own pictures and clips are part of the brief: every one is shown.
        val used = c.score.scenes.flatMap { sc -> sc.beats.mapNotNull { it.media } }.toSet()
        c.assets.filter { it !in used }.forEach { id -> f += Bench.finding(0f, Severity.Major, "the creator's own $id is not in the edit", "place it where it is talked about") }
        val p = prompt(c.prompt)
        for (ask in ASKS) {
            if (ask.words.none { it in p } || !ask.supported(c) || ask.shown(c)) continue
            f += Bench.finding(0f, Severity.Major, "the brief asks for ${ask.what}; the edit has none", "show ${ask.what} where it is said")
        }
        return Verdict.of(this, f)
    }

    override fun revise(c: Jury.Context, verdict: Verdict): Score? {
        val brief = RulesUnderstander.brief(c.prompt)
        var score = c.score
        wantedStyle(c.prompt, brief.look, score.look)?.let { score = score.copy(look = it) }
        score = when (brief.captions) {
            "word" -> score.copy(captions = score.captions.copy(show = true, maxWords = minOf(score.captions.maxWords, WORD_CAPTIONS)))
            "none" -> score.copy(captions = score.captions.copy(show = false))
            else -> score
        }
        return score.takeIf { it != c.score }
    }

    /**
     * The style the brief asks for when the edit's is not one it would accept: one it names by
     * name or style word ([StyleBook.asked]), else one answering its plain look word ("dark" →
     * noir, which any dark style answers). Null when the edit's style is fine.
     */
    private fun wantedStyle(prompt: String, tone: String, look: String): String? {
        val asked = StyleBook.asked(prompt)
        if (asked.isNotEmpty()) return asked.first().id.takeIf { asked.none { it.id == look } }
        if (tone == "auto") return null
        return tone.takeIf { StyleBook.of(look)?.answers(tone) != true && look != tone }
    }

    /** The keyword the brief asks viewers to send, when it names one that is said: «کلمهٔ تتر رو کامنت کن» → تتر. */
    private fun keyword(c: Jury.Context): String? {
        val tokens = c.prompt.replace("‌", " ").split(Regex("[\\s«»\"“”،,.:!?؟]+")).filter { it.isNotEmpty() }
        val said = c.words.map { norm(it.text) }.toSet()
        // «کلمه به کلمه» is a caption mode, not a keyword: the word after the marker must be a said content word, never a function word.
        val candidates = tokens.indices.filter { i -> i + 1 < tokens.size && tokens[i].removeSuffix("ٔ").removeSuffix("ی") in MARKERS }
            .map { it + 1 }.filter { i -> tokens[i].lowercase() !in NOT_KEYWORDS && Words.isContent(tokens[i]) && norm(tokens[i]) in said }
        val asks = tokens.indices.filter { tokens[it].lowercase() in ASK_WORDS }
        return candidates.minByOrNull { i -> asks.minOfOrNull { kotlin.math.abs(it - i) } ?: Int.MAX_VALUE }?.let { tokens[it] }
    }

    private companion object {
        const val WORD_CAPTIONS = 3
        const val LONG_PAUSE_MS = 400L
        val MARKERS = setOf("کلمه", "word", "keyword")
        val NOT_KEYWORDS = setOf("به", "by", "for", "را", "رو")
        val ASK_WORDS = setOf("کامنت", "comment", "بفرست", "بنویس", "send", "type")

        fun norm(s: String) = NumberWords.normalize(s).trim { !it.isLetterOrDigit() }.replace("‌", "")
        fun prompt(s: String) = s.lowercase().replace("ٔ", "").replace("‌", " ")

        fun senses(c: Jury.Context) = Quantities.senses(c.words.map { it.text }, c.lines)
        fun has(c: Jury.Context, vararg recipes: String) = c.compiled.beats.any { it.recipe in recipes }

        val ASKS = listOf(
            Ask("a voucher card", listOf("ووچر", "voucher", "کارت هدیه", "gift card"), { c -> senses(c).any { it.voucher != null } }, { c -> has(c, "voucher") }),
            Ask("a countdown", listOf("شمارش معکوس", "countdown", "تایمر", "timer"), { c -> senses(c).any { it.deadline != null } }, { c -> has(c, "countdown") }),
            Ask("a progress bar", listOf("نوار", "progress", "ظرفیت"), { c -> senses(c).any { it.quota != null || it.filled != null } }, { c -> has(c, "progress", "meter") }),
            Ask("stats", listOf("آمار", "stats", "بازدید"), { c -> senses(c).any { it.social.size >= 2 } }, { c -> has(c, "stats", "counter", "bars") }),
            Ask("logos", listOf("لوگو", "logo"), { c -> c.understanding.entities.any { it.kind in BRANDS } }, { c ->
                has(c, "logos", "logo-mark") || c.score.scenes.any { s -> s.beats.any { b -> b.items.any { i -> i.any { it in 'A'..'Z' || it in 'a'..'z' } } } }
            }),
            Ask("punch-ins", listOf("پانچ", "punch"), { c -> c.footage }, { c ->
                c.score.scenes.count { it.camera == "push-in" || (it.beats.isEmpty() && it.bg == null) } >= 1
            }),
            Ask("tightened pauses", listOf("مکث", "pause"), { c ->
                c.edit != null && c.source.zipWithNext().any { (a, b) -> b.range.startMs - a.range.endMs > LONG_PAUSE_MS }
            }, { c -> (c.edit?.segments?.size ?: 0) > 1 }),
        )
        val BRANDS = setOf("brand", "app", "product", "organization")
    }
}
