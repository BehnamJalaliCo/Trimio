package io.trimio.engine.autopilot.judges

import io.trimio.engine.autopilot.Finding
import io.trimio.engine.autopilot.Glossary
import io.trimio.engine.autopilot.Jury
import io.trimio.engine.autopilot.Phrases
import io.trimio.engine.autopilot.Quantities
import io.trimio.engine.autopilot.Severity
import io.trimio.engine.motion.score.BeatScore
import io.trimio.engine.motion.score.NumberWords
import io.trimio.engine.motion.score.Score

/**
 * The brief read as a checklist, each item asked for in the creator's own words and checked on
 * the edit itself, with the fix an editor would make:
 *
 * - «همهٔ سکوت‌ها و مکث‌ها حذف»: no pause longer than [PAUSE] s is left between two words;
 * - «اعداد … با المان»: every figure said (a percent, a sum, a count of ten or more) is on screen,
 *   as a graphic, while it is said — a missing one gets a counter where it is said;
 * - «کلمه‌های کلیدی با المان»: most of the lines carry a graphic — a bare line gets its key phrase;
 * - «کارت آدرس سایت»: the brief's site is on screen as an address card — where it is said, else
 *   on the last line before the ask;
 * - a handle the brief writes («ربات تلگرام @…_bot») is on the last screen.
 */
internal class BriefChecklist(private val c: Jury.Context) {
    private val p = c.prompt.lowercase().replace("ٔ", "").replace("‌", " ")
    private val glossary = Glossary.read(c.prompt)
    private val texts = c.words.map { it.text }
    private val graphics = Bench.graphics(c)
    private val prelude = c.edit?.preludeLength ?: 0f

    fun findings(): List<Finding> = pauses() + figures().map { (t, q) ->
        Bench.finding(t, Severity.Major, "the brief asks for numbers as graphics; «${texts.slice(q.at until q.at + q.count).joinToString(" ")}» is said with none", "a counter as it is said")
    }.capped(MAX_EACH) + keywords() + site() + handle()

    // ------------------------------------------------------------ checks

    private fun asked(vararg words: String) = words.any { it in p }

    /** Pauses the cut left, when the brief asks for every silence to go. */
    private fun pauses(): List<Finding> {
        if (!asked("سکوت", "مکث", "silence", "pause", "dead air") || c.edit == null) return emptyList()
        val gaps = c.words.zipWithNext().map { (a, b) -> a.range.endMs / 1000f to (b.range.startMs - a.range.endMs) / 1000f }.filter { it.second > PAUSE && it.first > prelude }
        val worst = gaps.maxByOrNull { it.second } ?: return emptyList()
        return listOf(Bench.finding(worst.first, Severity.Major, "${gaps.size} pauses over ${PAUSE}s are left (the longest ${Bench.r1(worst.second)}s)", "cut every pause to a breath"))
    }

    /** Figures said with no graphic up while they are said, when the brief asks for numbers on screen (time, figure). */
    fun figures(): List<Pair<Float, Quantities.Quantity>> {
        if (!asked("اعداد", "عددها", "عدد ها", "ارقام", "رقم", "numbers", "figures")) return emptyList()
        val senses = Quantities.senses(texts, c.lines)
        return senses.flatMap { it.quantities }.filter(::significant).distinctBy { it.at }.mapNotNull { q ->
            val t = start(q.at)
            val digits = io.trimio.core.model.text.Numerals.toLatin(NumberWords.normalize(q.value.toLong().toString()))
            val shown = graphics.any { g ->
                g.at <= t + AFTER && g.out >= t - BEFORE && (g.recipe in FIGURES || digits in io.trimio.core.model.text.Numerals.toLatin(g.text).replace("٬", "").replace(",", ""))
            }
            (t to q).takeIf { !shown && t >= prelude }
        }
    }

    /** A figure worth a graphic: a percent or a sum, a count of ten or more (not a year, not «یه»). */
    private fun significant(q: Quantities.Quantity) =
        q.kind == Quantities.Kind.Percent || q.kind == Quantities.Kind.Money || (q.value >= MIN_FIGURE && q.value !in YEARS)

    /** Lines of the body with no graphic at all, when the brief asks for its key words as elements. */
    private fun bare(): List<Int> {
        if (!asked("کلمه های کلیدی", "کلمات کلیدی", "کلیدواژه", "کلید واژه", "keyword", "key word")) return emptyList()
        return c.lines.indices.filter { k ->
            val l = c.lines[k]
            val a = start(l.first)
            val b = c.words[l.last].range.endMs / 1000f
            b - a >= MIN_LINE && a >= prelude && graphics.none { g -> Bench.overlap(g.at, g.out, a, b) > (b - a) * COVER }
        }
    }

    private fun keywords(): List<Finding> {
        val bare = bare()
        val lines = c.lines.count { l -> c.words[l.last].range.endMs - c.words[l.first].range.startMs >= MIN_LINE * MS }
        if (bare.isEmpty() || bare.size <= lines * BARE_SHARE) return emptyList()
        val what = "${bare.size} of $lines lines show no element; the brief asks for the key words as graphics"
        return listOf(Bench.finding(start(c.lines[bare.first()].first), Severity.Major, what, "the line's key phrase on screen"))
    }

    private fun site(): List<Finding> {
        val domain = glossary.sites.firstOrNull() ?: return emptyList()
        if (!asked("سایت", "وبسایت", "وب سایت", "website", "site", "url", "آدرس")) return emptyList()
        val shown = graphics.any { it.text.contains(domain, ignoreCase = true) } || c.score.scenes.any { s -> s.beats.any { it.recipe == "url" } }
        return if (shown) emptyList() else listOf(Bench.finding(c.duration, Severity.Major, "the brief asks for the site card; $domain is never on screen", "an address card for $domain"))
    }

    private fun handle(): List<Finding> {
        val handle = glossary.handles.firstOrNull() ?: return emptyList()
        val end = c.duration * LAST_SHARE
        val shown = c.score.scenes.any { s -> s.beats.any { b -> Bench.shownTexts(b).any { it.contains(handle.removePrefix("@"), ignoreCase = true) } } } &&
            graphics.any { it.out >= end }
        return if (shown) emptyList() else listOf(Bench.finding(c.duration, Severity.Major, "the brief's $handle is not on the last screen", "the ask names $handle"))
    }

    // ------------------------------------------------------------ fixes

    fun revise(): Score {
        var score = c.score
        for ((_, q) in figures().take(MAX_EACH)) score = add(score, q.at, counter(q))
        for (k in bare().take(MAX_EACH)) {
            val title = Phrases.title(c.words, c.lines[k]).ifBlank { continue }
            score = add(score, c.lines[k].first, BeatScore(recipe = "mask-rise", text = title, place = "top", energy = KEY_ENERGY))
        }
        if (site().isNotEmpty()) score = siteCard(score)
        if (handle().isNotEmpty()) score = named(score)
        return score
    }

    private fun counter(q: Quantities.Quantity) = BeatScore(
        recipe = "counter", at = q.at, until = q.at + q.count - 1, value = q.value.toFloat(), decimals = q.decimals,
        suffix = if (q.kind == Quantities.Kind.Percent) "٪" else "", label = q.unit.ifBlank { null }, place = "top",
    )

    /** [beat] landing on word [i], in the scene that holds it. */
    private fun add(score: Score, i: Int, beat: BeatScore): Score {
        val k = Bench.sceneAt(score, c.words, start(i))
        return Bench.addBeat(score, k, beat.copy(at = i))
    }

    /** The site's address card: where its name is said, else on the line before the ask. */
    private fun siteCard(score: Score): Score {
        val domain = glossary.sites.first()
        val stem = domain.substringBefore('.').lowercase()
        val said = texts.indices.firstOrNull { i -> Phrases.key(texts[i]).let { it == stem || (it.length > 2 && stem.startsWith(it)) } }
        val ask = Bench.askedBy(score, c.words)
        val before = c.lines.lastOrNull { l -> ask == null || start(l.first) < ask - CARD_S }?.first
        val i = said ?: before ?: return score
        return add(score, i, BeatScore(recipe = "url", text = domain, brand = stem, place = "top", hold = CARD_S))
    }

    /** The brief's handle on the ask: the comment field's or end card's label names it. */
    private fun named(score: Score): Score {
        val handle = glossary.handles.first()
        var done = false
        val scenes = score.scenes.map { s ->
            s.copy(beats = s.beats.map { b -> if (!done && (b.recipe == Bench.COMMENT || b.recipe == "endcard")) b.copy(label = handle).also { done = true } else b })
        }
        if (done) return score.copy(scenes = scenes)
        val last = c.lines.lastOrNull() ?: return score
        return add(score, last.first, BeatScore(recipe = "endcard", text = if (rtl()) "پیام بده" else "Message", label = handle, icon = "dm", place = "top", hold = CARD_S))
    }

    private fun rtl() = c.words.count { Bench.persian(it.text) } * 2 > c.words.size

    private fun start(i: Int) = Bench.start(c.words, i)

    private companion object {
        const val PAUSE = 0.45f
        const val MAX_EACH = 3
        const val BEFORE = 1.0f
        const val AFTER = 0.8f
        const val MIN_FIGURE = 10.0
        val YEARS = 1300.0..2100.0
        const val MIN_LINE = 1.2f
        const val MS = 1000f
        const val COVER = 0.3f
        const val BARE_SHARE = 0.3f
        const val CARD_S = 3f
        const val LAST_SHARE = 0.8f
        const val KEY_ENERGY = 0.7f
        val FIGURES = setOf("counter", "voucher", "stats", "countdown", "progress", "meter", "chart", "bars")
    }
}
