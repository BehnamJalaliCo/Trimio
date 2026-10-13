package io.trimio.engine.autopilot.explainer

import io.trimio.engine.autopilot.Enumerations
import io.trimio.engine.autopilot.Lines
import io.trimio.engine.autopilot.Names
import io.trimio.engine.autopilot.Phrases

/**
 * Where the narration changes subject, and what each part is about. A chapter starts where a new
 * name becomes the topic («مدل اول Haiku», "Then there's Opus", «اسمش سانت هست»), where the speaker
 * counts off («اول»، «دوم»، «در نهایت», "first", "next", "finally"), where the words change (few
 * content words shared across the gap) and where the voice rests. Chapters are long enough to build
 * a stage (a few sentences) and short enough to stay one subject.
 */
object Chapters {

    /** One chapter: its clauses, its heading, and the name it is about when it is one. */
    data class Plan(val clauses: IntRange, val title: String, val subject: String? = null)

    fun find(n: Narration): List<Plan> {
        val clauses = n.clauses
        if (clauses.isEmpty()) return emptyList()
        val subjects = clauses.map { subjectsOf(n, it) }
        val gaps = (1 until clauses.size).associateWith { k -> gapScore(n, k, subjects) }
        val cuts = choose(n, gaps)
        val ranges = (listOf(0) + cuts).zip(cuts + clauses.size).map { (a, b) -> a until b }
        val titles = mutableSetOf<String>()
        return ranges.mapIndexed { i, r ->
            val subject = topicOf(r, subjects, i == 0)
            val title = title(n, r, subject, i, titles)
            titles += Phrases.key(title)
            Plan(r, title, subject)
        }
    }

    /** Names said in [c] (Latin names, the understanding's names, «اسمش X»): its possible subjects, in order. */
    fun subjectsOf(n: Narration, c: Clause): List<String> {
        val out = mutableListOf<String>()
        for (i in c.words) {
            val w = Names.bare(n.texts[i]).trim('«', '»', '"', '“', '”')
            val latin = w.any { it in 'A'..'Z' || it in 'a'..'z' } && w.length >= 2
            val named = latin && Names.isName(n.texts, i, n.prompt)
            val entity = n.understanding.entities.firstOrNull { it.at == i && it.kind in NAMED }?.name
            // «اسمش سانت هست», "it's called Opus": the word after the naming cue is a name, in any script.
            val prev = if (i > c.first) Metaphors.bare(n.texts[i - 1]) else ""
            val called = prev in CALLED && Phrases.nounish(n.texts[i])
            // «مدل فیبل»: a word nobody writes after the narration's category noun, or said twice, is a name in Persian letters.
            val persianName = n.unknown(i) && Phrases.nounish(n.texts[i]) && (prev == n.category || n.keys.count { it == n.keys[i] } >= 2)
            if (entity != null) out += entity else if (named || called || persianName) out += w
        }
        return out.distinct()
    }

    /** How strongly the narration turns to a new subject just before clause [k]. */
    private fun gapScore(n: Narration, k: Int, subjects: List<List<String>>): Float {
        val c = n.clauses[k]
        var s = 0f
        val recent = (maxOf(0, k - RECENT) until k).flatMap { subjects[it] }.toSet()
        // A new name taking the floor (not a list of names: that is an agenda, not a turn).
        val fresh = subjects[k].filter { it !in recent }
        val agenda = subjects[k].size > 2
        val ahead = ((k + 1)..minOf(n.clauses.lastIndex, k + AHEAD)).flatMap { subjects[it] }
        if (fresh.isNotEmpty() && !agenda) s += if (fresh.any { it in ahead } || leads(n, c, fresh.first())) NAME_TURN else NAME_TURN * HALF
        // Counting off at the clause's head.
        val head = c.words.take(HEAD).map { Metaphors.bare(n.texts[it]) }
        if (head.any { it in STRONG_MARKS } || Enumerations.step(n.texts, Lines.Line(c.first, c.last)) != null) s += MARK
        if (head.firstOrNull() in SOFT_MARKS) s += SOFT
        // «این مدل …», "the next model": the narration turns to another of the things it walks through.
        if (n.category != null && head.size >= 2 && head[0] in DEMONSTRATIVES && head[1] == n.category) s += MEMBER
        // Few words in common across the gap.
        val before = content(n, (maxOf(0, k - WINDOW) until k))
        val after = content(n, (k until minOf(n.clauses.size, k + WINDOW)))
        if (before.isNotEmpty() && after.isNotEmpty()) s += SHIFT * (1f - before.intersect(after).size.toFloat() / minOf(before.size, after.size))
        // The voice rests before a new part.
        val rest = c.start - n.clauses[k - 1].end
        s += when {
            rest >= LONG_REST -> REST
            rest >= SHORT_REST -> REST * HALF
            else -> 0f
        }
        if (c.text.trimEnd().lastOrNull()?.let { it in QUESTION } == true) s += ASKS
        return s
    }

    /** Whether [name] is said in the first words of [c] (the clause is about it). */
    private fun leads(n: Narration, c: Clause, name: String) = c.words.take(HEAD + 1).any { Names.bare(n.texts[it]).contains(name.split(' ').first(), ignoreCase = true) }

    /** Content words (stems) of the clauses in [range]. */
    private fun content(n: Narration, range: IntRange): Set<String> =
        range.flatMap { n.clauses[it].words }.filter { Phrases.nounish(n.texts[it]) }.map { Metaphors.bare(n.texts[it]).take(STEM) }.toSet()

    /** The cuts: the strongest turns first, keeping every chapter between the shortest and longest a stage holds. */
    private fun choose(n: Narration, gaps: Map<Int, Float>): List<Int> {
        val clauses = n.clauses
        val most = (n.duration / MIN_SECONDS).toInt().coerceIn(1, MAX_CHAPTERS)
        val cuts = sortedSetOf<Int>()
        fun fits(k: Int): Boolean {
            val lo = cuts.lastOrNull { it < k } ?: 0
            val hi = cuts.firstOrNull { it > k } ?: clauses.size
            // The opening may be one sentence (a hook before the first subject); every other chapter holds a stage.
            val opening = lo == 0
            val left = span(clauses, lo, k) >= (if (opening) OPENING_SECONDS else MIN_SECONDS) && k - lo >= (if (opening) 1 else MIN_CLAUSES)
            return left && span(clauses, k, hi) >= MIN_SECONDS && hi - k >= MIN_CLAUSES
        }
        for ((k, score) in gaps.entries.sortedByDescending { it.value }) {
            if (cuts.size + 1 >= most || score < THRESHOLD) break
            if (fits(k)) cuts += k
        }
        // A chapter too long for one stage is split at its best turn.
        var split = true
        while (split && cuts.size + 1 < MAX_CHAPTERS) {
            val bounds = listOf(0) + cuts.toList() + clauses.size
            val long = bounds.zipWithNext().firstOrNull { (a, b) -> span(clauses, a, b) > MAX_SECONDS }
            val k = long?.let { (a, b) -> ((a + 1) until b).filter { fits(it) }.maxByOrNull { gaps[it] ?: 0f } }
            if (k == null) split = false else cuts += k
        }
        return cuts.toList()
    }

    private fun span(clauses: List<Clause>, from: Int, until: Int): Double =
        if (until <= from) 0.0 else clauses[until - 1].end - clauses[from].start

    /** The name a chapter is about: the one it opens with, else the one it says most. */
    private fun topicOf(r: IntRange, subjects: List<List<String>>, opening: Boolean): String? {
        val said = r.flatMap { subjects[it] }
        if (said.isEmpty()) return null
        val first = subjects[r.first].firstOrNull() ?: subjects.getOrNull(r.first + 1)?.firstOrNull()?.takeIf { !opening }
        return first ?: said.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
    }

    /**
     * A chapter's heading: its subject's name, a step as counted, the noun it keeps coming back to
     * («تورم», "savings"), or its first clause's strongest phrase — two or three words, never a
     * verb or an adverb at its edges, English in title case.
     */
    private fun title(n: Narration, r: IntRange, subject: String?, index: Int, used: Set<String>): String {
        if (subject != null && Phrases.key(subject) !in used) return subject
        val first = n.clauses[r.first]
        Enumerations.step(n.texts, Lines.Line(first.first, first.last))?.let { s ->
            val t = edges(s.map { Phrases.clean(n.texts[it]) })
            if (t.isNotBlank() && Phrases.key(t) !in used) return cased(n, t)
        }
        val words = r.flatMap { n.clauses[it].words }
        val nouns = words.filter { Phrases.nounish(n.texts[it]) && Metaphors.bare(n.texts[it]) !in EDGE_WORDS && Metaphors.bare(n.texts[it]).length >= MIN_NOUN }
        val repeated = nouns.groupBy { stem(n.texts[it]) }.filter { it.value.size >= 2 && it.key !in used }.maxByOrNull { it.value.size }
        if (repeated != null) return cased(n, repeated.value.map { Phrases.clean(n.texts[it]) }.minByOrNull { it.length }!!)
        if (index == 0) {
            val t = edges(n.understanding.title.split(' ').take(DERIVED_WORDS))
            if (t.isNotBlank() && Phrases.key(t) !in used) return cased(n, t)
        }
        val phrase = edges(Phrases.title(n.words, Lines.Line(first.first, first.last), used).split(' ').take(DERIVED_WORDS))
        return cased(n, phrase.ifBlank { nouns.firstOrNull()?.let { Phrases.clean(n.texts[it]) } ?: Phrases.clean(n.texts[words.first()]) })
    }

    /** A phrase without verbs, modals or adverbs at its edges («باید پولت» → «پولت», "Finally avoid" → ""). */
    private fun edges(words: List<String>): String {
        fun weak(w: String) = Metaphors.bare(w) in EDGE_WORDS || Phrases.verb(Metaphors.bare(w)) || !Phrases.nounish(w)
        return words.filter { it.isNotBlank() }.dropWhile(::weak).dropLastWhile(::weak).joinToString(" ")
    }

    /** A word's stem for counting repeats: «پولت», «پول» and «پولش» are one noun; "savings" and "saving" too. */
    fun stem(w: String): String {
        val b = Metaphors.bare(w)
        val cut = STEM_SUFFIXES.firstOrNull { b.endsWith(it) && b.length - it.length >= MIN_NOUN }
        return (if (cut != null) b.dropLast(cut.length) else b).take(STEM)
    }

    private fun cased(n: Narration, t: String) = if (n.persian) t else t.replaceFirstChar { it.uppercaseChar() }

    private val NAMED = setOf("brand", "product", "app", "person", "organization", "place")
    private val CALLED = setOf("اسمش", "اسم", "called", "named", "نام", "نامش")
    private val STRONG_MARKS = setOf(
        "اول", "اولین", "دوم", "دومین", "سوم", "سومین", "چهارم", "پنجم", "بعدی", "بعدیش", "آخر", "آخری", "آخرین", "نهایت", "first", "second", "third", "fourth",
        "fifth", "next", "finally", "lastly", "last",
    )
    private val SOFT_MARKS = setOf("حالا", "خب", "اما", "ولی", "now", "so", "but", "then", "بعدش")
    private val DEMONSTRATIVES = setOf("این", "اون", "یه", "یک", "the", "this", "that", "another", "next")
    private const val QUESTION = "?؟"

    private const val RECENT = 3
    private const val AHEAD = 3
    private const val HEAD = 3
    private const val WINDOW = 3
    private const val STEM = 5
    private const val NAME_TURN = 3f
    private const val HALF = 0.5f
    private const val MARK = 2f
    private const val SOFT = 0.6f
    private const val MEMBER = 1.6f
    private const val SHIFT = 1.4f
    private const val REST = 1f
    private const val ASKS = 0.5f
    private const val LONG_REST = 0.8
    private const val SHORT_REST = 0.45
    private const val THRESHOLD = 2.2f
    private const val MIN_SECONDS = 6.0
    private const val OPENING_SECONDS = 2.5
    private const val MIN_NOUN = 3
    private val STEM_SUFFIXES = listOf("هایش", "هاشون", "هاتون", "های", "ها", "تون", "شون", "مون", "ات", "ت", "ش", "م", "ی", "s")
    private val EDGE_WORDS = setOf(
        "باید", "نباید", "شاید", "اگه", "اگر", "فقط", "حتی", "finally", "first", "then", "here", "also", "just", "really", "most", "every", "step", "number",
        "one", "two", "three", "four", "suddenly", "whole", "great", "same", "دوم", "سوم", "اول",
    )
    private const val MAX_SECONDS = 24.0
    private const val MIN_CLAUSES = 2
    private const val MAX_CHAPTERS = 9
    private const val DERIVED_WORDS = 2
}
