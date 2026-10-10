package io.trimio.engine.autopilot

import io.trimio.engine.motion.score.NumberWords
import io.trimio.engine.motion.score.Words

/**
 * Reported speech, read the way an editor hears a story told to camera: «یکی اومده تو دایرکت
 * می‌گه …», "someone DM'd me and asked …", then the speaker's own answer («نه عزیزم …», "I told
 * him …"). Such a stretch is shown as one chat thread (the `message` recipe): the reported words
 * as incoming bubbles, the answer as an outgoing one. Bubbles quote the transcript's own words,
 * in order, so each lands as it is said; fillers are left out and long speech is split at its
 * clauses, so a bubble reads at a glance.
 */
internal class Dialogue(private val texts: List<String>, private val lines: List<Lines.Line>) {

    /** One bubble: transcript words in order; [outgoing] is the speaker's own reply. */
    data class Bubble(val words: List<Int>, val outgoing: Boolean) {
        val first: Int get() = words.first()
        val last: Int get() = words.last()
    }

    /** A thread starting on line [line] and running to line [lastLine]. */
    data class Thread(val line: Int, val lastLine: Int, val incoming: List<Bubble>, val reply: Bubble?) {
        val bubbles: List<Bubble> get() = incoming + listOfNotNull(reply)
    }

    private val norm = texts.map { NumberWords.normalize(it) }

    /**
     * Every thread in the piece. [allowed] says whether a line may be part of one (never the call
     * to action, never a line with a figure of its own); [hinted] marks lines the model read as
     * dialogue, which need no reporting phrase beyond the verb.
     */
    fun find(allowed: (Int) -> Boolean, hinted: (Int) -> Boolean): List<Thread> {
        val out = mutableListOf<Thread>()
        var k = 0
        while (k < lines.size) {
            val t = if (allowed(k)) threadAt(k, allowed, hinted) else null
            if (t != null) out += t
            k = (t?.lastLine ?: k) + 1
        }
        return out
    }

    private fun threadAt(k: Int, allowed: (Int) -> Boolean, hinted: (Int) -> Boolean): Thread? {
        val line = lines[k]
        // The last reporting verb of the line opens the quote ("someone DM'd me and asked, …").
        var say = line.range.lastOrNull { i -> said(i) } ?: return null
        // …even when a pause put it on the next line ("someone DM'd me | yesterday and asked, …").
        while (true) say = (say + 1..minOf(texts.lastIndex, say + NEXT_VERB)).takeWhile { !ends(texts[it - 1]) }.firstOrNull { said(it) } ?: break
        // Who said it may come just before, on the previous line («یکی تو کامنت‌ها | نوشته که …»).
        val from = lines.getOrNull(k - 1)?.first ?: line.first
        val reported = (from..minOf(line.last, say + 2)).any { i -> REPORTERS.any { r -> norm[i] == r || (r.length >= 3 && norm[i].startsWith(r)) } }
        if (!reported && !hinted(k)) return null
        // The reported words: from after the verb to the end of the sentence, within three more lines.
        val start = (say + 1..minOf(texts.lastIndex, say + 2)).firstOrNull { norm[it] !in OPENERS } ?: return null
        var lastLine = k
        while (lastLine + 1 < lines.size && lastLine - k < MAX_LINES && !ends(texts[maxOf(start, lines[lastLine].last)]) && allowed(lastLine + 1) &&
            !replyStarts(lines[lastLine + 1].first)
        ) {
            lastLine++
        }
        val end = (start..lines[lastLine].last).firstOrNull { ends(texts[it]) } ?: lines[lastLine].last
        lastLine = lines.indexOfFirst { end in it.range }
        val reply = reply(end + 1, lastLine, allowed)
        val incoming = bubbles(start, end, if (reply != null) MAX_INCOMING_WITH_REPLY else MAX_INCOMING)
        if (incoming.none { it.words.size >= MIN_WORDS }) return null
        val replyLine = reply?.let { r -> lines.indexOfFirst { r.last in it.range } } ?: lastLine
        return Thread(k, maxOf(lastLine, replyLine), incoming, reply)
    }

    /** The speaker's answer, when the next sentence starts as one («نه عزیزم …», «گفتم …», "I told him, …") within two lines. */
    private fun reply(from: Int, lastLine: Int, allowed: (Int) -> Boolean): Bubble? {
        if (from > texts.lastIndex || !replyStarts(from)) return null
        val k = lines.indexOfFirst { from in it.range }
        if (k < 0 || k - lastLine > 2 || !allowed(k)) return null
        // «گفتم …», "I told him, …": the answer is what follows the telling.
        var a = from
        if (norm[a] == "i" && norm.getOrNull(a + 1) in TOLD) a += 2 else if (norm[a] in TOLD) a++
        if (a <= texts.lastIndex && norm[a] in OBJECTS) a++
        if (a <= texts.lastIndex && norm[a] in OPENERS) a++
        val b = (a..lines[k].last).firstOrNull { clauseEnd(texts[it]) } ?: lines[k].last
        return bubble(a, b, outgoing = true)?.takeIf { it.words.size >= 2 }
    }

    /** The reported words [start]..[end] as up to [max] bubbles: clauses, short ones joined, repeats left out. */
    private fun bubbles(start: Int, end: Int, max: Int): List<Bubble> {
        val clauses = mutableListOf<IntRange>()
        var a = start
        for (i in start..end) {
            if (clauseEnd(texts[i]) || i == end) {
                clauses += a..i
                a = i + 1
            }
        }
        val out = mutableListOf<Bubble>()
        var pending: List<Int> = emptyList()
        for (c in clauses) {
            val words = pending + kept(c)
            if (words.size < MIN_WORDS && c != clauses.last()) {
                pending = words
                continue
            }
            pending = emptyList()
            val b = bubble(words) ?: continue
            if (repeats(b, out)) continue
            out += b
        }
        return out.take(max)
    }

    private fun bubble(a: Int, b: Int, outgoing: Boolean): Bubble? = bubble(kept(a..b), outgoing)

    /** At most [MAX_WORDS] words; a shortened one ends on a word that means something. */
    private fun bubble(words: List<Int>, outgoing: Boolean = false): Bubble? {
        var w = words.take(MAX_WORDS)
        while (words.size > MAX_WORDS && w.size > 1 && !Words.isContent(texts[w.last()])) w = w.dropLast(1)
        return if (w.isEmpty()) null else Bubble(w, outgoing)
    }

    /** A bubble saying again what an earlier one said («ما می‌رفتیم ثبت‌نام می‌کردیم» twice). */
    private fun repeats(b: Bubble, before: List<Bubble>): Boolean {
        val said = before.flatMap { it.words }.map { norm[it] }.toSet()
        val content = b.words.filter { Words.isContent(texts[it]) }
        return content.isNotEmpty() && content.count { norm[it] in said } * 10 >= content.size * REPEAT_TENTHS
    }

    /** The clause's words without fillers («بابا», «دیگه», "just"). */
    private fun kept(c: IntRange): List<Int> = c.filter { i -> norm[i].trimEnd('.', '،', ',', '?', '؟', '!') !in FILLERS && norm[i].isNotEmpty() }

    private fun said(i: Int): Boolean {
        val n = norm[i].trimEnd('.', '،', ',', ':', '?', '؟')
        return n in SAY || (n in ENGLISH_SAY && i > 0)
    }

    private fun replyStarts(i: Int): Boolean {
        val n = norm.getOrNull(i)?.trimEnd('.', '،', ',', '!') ?: return false
        return n in REPLY || (n == "i" && norm.getOrNull(i + 1)?.trimEnd(',') in TOLD)
    }

    private fun ends(w: String) = w.last() in ".!?؟…"
    private fun clauseEnd(w: String) = w.last() in ".!?؟…،,;؛:"

    private companion object {
        /** Verbs that report someone's words. */
        val SAY = setOf(
            "میگه", "میگن", "گفته", "گفت", "گفتن", "پرسیده", "پرسید", "میپرسه", "نوشته", "نوشت", "مینویسه",
        )
        val ENGLISH_SAY = setOf("asked", "asks", "said", "says", "wrote", "writes", "commented", "messaged", "dmd", "dm'd", "dmed", "texted")

        /** Who or where the words came from: someone, a follower, a friend, the DMs, the comments. */
        val REPORTERS = listOf(
            "یکی", "یه", "کسی", "فالوور", "دوستم", "رفیقم", "بنده", "دایرکت", "دایرک", "پیام", "کامنت", "ازم", "بهم", "someone", "somebody", "follower",
            "friend", "guy", "girl", "dm", "message", "comment", "asked", "a",
        )

        /** Words between the verb and the quote («می‌گه که …», "asked me, …"). */
        val OPENERS = setOf("که", "me", "us", "and", ":", "")
        val REPLY = setOf("نه", "آره", "اره", "بله", "گفتم", "جواب", "عزیزم", "ببین", "no", "nope", "yes", "yeah", "look", "listen")
        val TOLD = setOf("گفتم", "told", "said", "replied", "answered")
        val OBJECTS = setOf("him", "her", "them", "you", "بهش", "بهشون")
        val FILLERS = setOf("بابا", "دیگه", "یعنی", "خب", "خوب", "اصلا", "مثلا", "من", "just", "um", "uh", "well", "actually", "basically")

        const val MAX_LINES = 3
        const val NEXT_VERB = 4
        const val MAX_WORDS = 9
        const val MIN_WORDS = 4
        const val MAX_INCOMING = 3
        const val MAX_INCOMING_WITH_REPLY = 2
        const val REPEAT_TENTHS = 6
    }
}
