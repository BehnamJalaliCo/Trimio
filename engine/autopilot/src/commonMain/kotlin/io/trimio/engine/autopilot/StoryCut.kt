package io.trimio.engine.autopilot

import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.motion.score.EditPlan

/**
 * The editor's first pass, before any picture: what to say at all. A talking head off the cuff says
 * things twice, asks for the comment before the point is made and again at the end, and starts
 * with a breath of «I don't even know what to say». An editor keeps every idea once, in its first
 * full telling, keeps the ask once, at the end, and opens on the first line that says something.
 * Measured against the owner's reference edit (docs/research/REFERENCE_EDL.md), which cut a
 * 155 s reel to 97 s this way.
 *
 * Whole clauses go ([clauses]), never parts of one, so every word left is said as it was said:
 *
 * - **the opening** — the clauses before the first one that says something (a hesitation, no content);
 * - **early asks** — every clause asking for the comment, the follow or the DM before the last run of asks;
 * - **repeats** — a clause whose content words ([content]) were [COVERED] said already, at least [GAP]
 *   seconds earlier (a point developed over a few clauses is no repeat: its words run on);
 * - **islands** — under [ISLAND] seconds left standing between two cuts (the rest of a passage that went).
 *
 * Never more than [MAX_SHARE] of what is said, and nothing in a short piece ([MIN_PIECE]).
 */
internal object StoryCut {

    /** Words (transcript indices) the cut leaves out, whole clauses at a time, with why. */
    class Cut(val dropped: Set<Int>, val notes: List<String>) {
        val isEmpty: Boolean get() = dropped.isEmpty()
    }

    /** Whether the brief keeps every word («چیزی حذف نکن», "keep everything", subtitles only). */
    fun keepsAll(prompt: String): Boolean {
        val p = prompt.lowercase().replace("\u200C", " ")
        return KEEP_ALL.any { it in p }
    }

    /**
     * The clauses of [lines]: a line broken after a comma or a sentence end, or at a pause of [CLAUSE_PAUSE] — the
     * unit an editor cuts (a line often ends one thought and starts the next: «the site again, entry is 50 USDT»).
     */
    fun clauses(words: List<Word>, lines: List<Lines.Line>): List<IntRange> = lines.flatMap { l ->
        val out = mutableListOf<IntRange>()
        var from = l.first
        for (i in l.first..l.last) {
            val next = words.getOrNull(i + 1)
            val paused = next != null && (next.range.startMs - words[i].range.endMs) / MS >= CLAUSE_PAUSE
            if (i == l.last || words[i].text.lastOrNull()?.let { it in BREAKS } == true || paused) {
                out += from..i
                from = i + 1
            }
        }
        out
    }

    /**
     * [gists]: what each of [lines] says in a few English words (the reading's [LineRead.gist]), when the director wrote them:
     * a line whose gist an earlier line already said, and whose own words partly repeat it, is the same point made again.
     */
    fun choose(words: List<Word>, lines: List<Lines.Line>, hookLine: Int? = null, keyword: String = "", gists: List<String> = emptyList()): Cut {
        if (lines.size < MIN_LINES || words.isEmpty()) return Cut(emptySet(), emptyList())
        if (end(words.last()) - seconds(words.first()) < MIN_PIECE) return Cut(emptySet(), emptyList())
        val units = clauses(words, lines)
        val hook = hookLine?.let { lines.getOrNull(it) }?.range
        val pass = Pass(units.map { words.subList(it.first, it.last + 1) }, units.indices.filter { u -> hook != null && units[u].first in hook }.toSet())
        pass.opening()
        pass.asks(keyword.let(::normal).takeIf { it.length >= 2 })
        pass.repeats()
        if (gists.isNotEmpty()) pass.restated(units.map { u -> lines.indexOfFirst { u.first in it.range } }, gists)
        pass.islands()
        pass.capped()
        val notes = pass.dropped.map { (q, why) -> "story cut: ${seconds(pass.text[q].first()).one()}s «${pass.text[q].joinToString(" ") { it.text }}» — $why" }
        return Cut(pass.dropped.keys.flatMap { units[it] }.toSet(), notes)
    }

    /** One run of the cut over the clauses' [text], what it drops so far and why. */
    private class Pass(val text: List<List<Word>>, val inHook: Set<Int>) {
        val dropped = sortedMapOf<Int, String>()
        private var ending: Float? = null

        /** Where the piece's own ending starts: its last [ENDING_SHARE], where a story pays off and callbacks are meant. */
        private val close = text.firstOrNull()?.let { a -> text.last().let { z -> seconds(a.first()) + (end(z.last()) - seconds(a.first())) * (1f - ENDING_SHARE) } }

        /** The ask and what leads into it («if you want to trade without worry, …») are the ending: never a repeat; nor is the ending. */
        fun closing(k: Int) = k !in dropped && (ending?.let { seconds(text[k].first()) >= it } == true || close?.let { seconds(text[k].first()) >= it } == true)

        private fun kept(k: Int) = k !in dropped && !closing(k) && k !in inHook

        /** The opening: what comes before the first clause that says something. */
        fun opening() {
            for (k in text.indices.take(MAX_OPENING)) {
                // «اصلا نمی‌دونم چی بگم»: a hesitation that says next to nothing; «اِم الان ساعت شش صبحه» says something.
                val said = content(text[k]).size
                val hesitant = text[k].any { normal(it.text) in HESITATIONS } && said <= HESITANT_CONTENT
                if (!hesitant && said > 0) break
                dropped[k] = "a hesitant opening"
            }
            if (dropped.size == text.size) dropped.clear()
        }

        /** Early asks: only the last run of asking clauses stays (with what joins them: «توی این بازار، بیا»). */
        fun asks(keyword: String?) {
            val asks = text.indices.filter { k -> asking(text[k], keyword) }
            val last = asks.lastOrNull() ?: return
            var from = last
            while (asks.any { it in from - ASK_REACH until from }) from = asks.last { it in from - ASK_REACH until from }
            asks.filter { it < from }.forEach { dropped.putIfAbsent(it, "an early ask (the end asks again)") }
            ending = seconds(text[from].first()) - ASK_LEAD
        }

        /**
         * Repeats: first telling wins. A clause repeats when one earlier passage ([PASSAGE] seconds of kept clauses,
         * ending [GAP] seconds or more before it) already said [COVERED] of its content words — scattered words said
         * here and there are no repeat. An instruction («بریزید», «برش بزنید») is a step, never a repeat.
         */
        fun repeats() {
            val said = mutableListOf<Pair<Float, Set<String>>>()
            val last = mutableMapOf<String, Float>()
            for (k in text.indices.filter { it !in dropped }) {
                val c = content(text[k])
                val start = seconds(text[k].first())
                // A word said in the last few seconds is a point being developed («280 times. 280 times.»), not repeated.
                val fresh = c.filter { w -> last[w]?.let { start - it < GAP } == true }.toSet()
                val earlier = said.filter { (t, _) -> start - t >= GAP }
                val covered = earlier.maxOfOrNull { (from, _) ->
                    val passage = earlier.filter { (t, _) -> t >= from && t - from <= PASSAGE }.flatMap { it.second }.toSet()
                    c.count { it in passage && it !in fresh }
                } ?: 0
                val cuttable = kept(k) && text[k].none { instruction(it.text) }
                if (cuttable && c.size >= MIN_CONTENT && covered >= c.size * COVERED) {
                    dropped[k] = "said before"
                } else {
                    said += start to c.toSet()
                    c.forEach { last[it] = end(text[k].last()) }
                }
            }
        }

        /**
         * The same point made again in other words: a clause whose line's gist ([lineOf], [gists]) shares [GIST_SHARE] of its
         * words with an earlier kept line's (at least [GAP] seconds before), and whose own content words already were said
         * in part ([HALF_COVERED]) — the director's reading and the words agreeing, so a vague gist alone never cuts.
         */
        fun restated(lineOf: List<Int>, gists: List<String>) {
            val said = mutableListOf<String>()
            val heard = mutableSetOf<String>()
            val keptGists = mutableListOf<Pair<Float, Set<String>>>()
            for (k in text.indices.filter { it !in dropped }) {
                val c = content(text[k])
                val start = seconds(text[k].first())
                val g = gistWords(gists.getOrNull(lineOf[k]).orEmpty())
                val match = g.size >= MIN_GIST && keptGists.any { (t, e) -> start - t >= GAP && (g intersect e).size >= maxOf(MIN_GIST, (g.size * GIST_SHARE).toInt()) }
                val partly = c.isNotEmpty() && c.count { it in heard } >= c.size * HALF_COVERED
                val cuttable = kept(k) && text[k].none { instruction(it.text) }
                if (match && partly && cuttable) {
                    dropped[k] = "said before"
                } else {
                    c.forEach { heard += it; said += it }
                    if (g.isNotEmpty()) keptGists += start to g
                }
            }
        }

        /** Islands: a short stretch left between two cuts goes with them. */
        fun islands() {
            var k = 1
            while (k < text.size) {
                if (k in dropped || k - 1 !in dropped) {
                    k++
                } else {
                    var j = k
                    while (j < text.size && kept(j)) j++
                    val between = j < text.size && j in dropped
                    if (between && end(text[j - 1].last()) - seconds(text[k].first()) < ISLAND) for (q in k until j) dropped[q] = "left between two cuts"
                    k = j + 1
                }
            }
        }

        /** Never most of the piece: past [MAX_SHARE], the repeats come back from the last one. */
        fun capped() {
            val total = text.sumOf { length(it).toDouble() }.toFloat()
            fun share() = dropped.keys.sumOf { length(text[it]).toDouble() }.toFloat() / total
            while (share() > MAX_SHARE) {
                val back = dropped.entries.lastOrNull { it.value == "said before" || it.value == "left between two cuts" } ?: break
                dropped.remove(back.key)
            }
        }
    }

    /**
     * [cut] applied: the body of [edit] without the dropped words' stretches (each from just after the last word kept
     * before it to just before the next word kept), and the words, lines and reading re-indexed to what is left. A
     * line that lost a clause in its middle becomes two, each with the line's reading.
     */
    class Applied(
        val edit: EditPlan,
        val transcript: Transcript,
        val shown: Transcript,
        val lines: List<Lines.Line>,
        val understanding: Understanding,
        /** Old word index → new one, for the words kept. */
        val index: Map<Int, Int>,
    )

    fun apply(cut: Cut, edit: EditPlan, transcript: Transcript, shown: Transcript, lines: List<Lines.Line>, understanding: Understanding): Applied {
        val words = transcript.words
        val gone = cut.dropped
        val spans = spans(words, gone)
        val index = words.indices.filter { it !in gone }.withIndex().associate { (n, old) -> old to n }
        // Each line's kept words, in runs: one line per run, each with the line's reading.
        val pieces = lines.mapIndexed { k, l -> k to runs(l, gone) }
        val newLines = pieces.flatMap { (_, runs) -> runs.map { Lines.Line(index.getValue(it.first), index.getValue(it.last)) } }
        val firstPiece = mutableMapOf<Int, Int>()
        val reads = mutableListOf<LineRead>()
        for ((k, runs) in pieces) {
            if (runs.isNotEmpty()) firstPiece[k] = reads.size
            runs.forEachIndexed { n, _ -> reads += understanding.lines.getOrElse(k) { LineRead() }.let { if (n == 0) it else it.copy(fixed = "") } }
        }
        fun kept(t: Transcript) = Transcript(t.language, t.words.filterIndexed { n, _ -> n !in gone })
        val u = understanding
        val reading = u.copy(
            lines = if (u.lines.isEmpty()) u.lines else reads,
            hook = u.hook?.let { h -> firstPiece[h.line]?.let { h.copy(line = it) } },
            fixes = u.fixes.mapNotNull { f -> index[f.at]?.let { f.copy(at = it) } },
            entities = u.entities.mapNotNull { e ->
                val at = index[e.at] ?: return@mapNotNull null
                val until = if (e.until < 0) -1 else (e.until downTo e.at).firstNotNullOfOrNull { index[it] } ?: -1
                e.copy(at = at, until = until)
            },
        )
        return Applied(edit.without(spans), kept(transcript), kept(shown), newLines, reading, index)
    }

    /** The source stretches [gone] words take: from just after the word kept before to just before the word kept after. */
    private fun spans(words: List<Word>, gone: Set<Int>): List<ClosedFloatingPointRange<Float>> {
        val spans = mutableListOf<ClosedFloatingPointRange<Float>>()
        var i = 0
        while (i < words.size) {
            if (i !in gone) {
                i++
                continue
            }
            var j = i
            while (j + 1 < words.size && j + 1 in gone) j++
            val before = words.getOrNull(i - 1)
            val after = words.getOrNull(j + 1)
            // A cut at the very start or end takes the edit's head or tail with it: no sliver of room tone is left.
            // Words the recogniser timed overlapping leave no gap: the cut then sits on the kept word's own edge, never inside it.
            val from = before?.let { b -> end(b) + ((seconds(words[i]) - end(b)) / 2f).coerceIn(0f, EDGE) } ?: 0f
            val to = after?.let { a -> seconds(a) - ((seconds(a) - end(words[j])) / 2f).coerceIn(0f, EDGE) } ?: Float.MAX_VALUE
            if (to > from) spans += from..to
            i = j + 1
        }
        return spans
    }

    /** The runs of [line]'s words that stay. */
    private fun runs(line: Lines.Line, gone: Set<Int>): List<IntRange> {
        val runs = mutableListOf<IntRange>()
        var from = -1
        for (w in line.first..line.last + 1) {
            val kept = w <= line.last && w !in gone
            if (kept && from < 0) from = w
            if (!kept && from >= 0) {
                runs += from until w
                from = -1
            }
        }
        return runs
    }

    /** An instruction to the viewer: a Persian plural imperative («بریزید», «بزنین»), not «باید». */
    private fun instruction(word: String): Boolean {
        val w = normal(word)
        return w.length >= MIN_WORD + 1 && w.startsWith("ب") && (w.endsWith("ید") || w.endsWith("ین")) && w != "باید" && w !in STOP
    }

    /** Whether [line] asks the viewer for something: two different asking words, or the piece's keyword with one. */
    private fun asking(line: List<Word>, keyword: String?): Boolean {
        val said = line.map { normal(it.text) }
        val hits = ASKS.filter { a -> said.any { it.startsWith(a) } }.toSet()
        val withKeyword = keyword != null && said.any { it.startsWith(keyword) } && hits.any { it !in TOPIC_ASKS }
        return hits.size >= 2 || withKeyword
    }

    /** The words that carry a line's meaning, stemmed: no function words, no fillers, nothing under three letters. */
    fun content(line: List<Word>): List<String> = line.mapNotNull { w ->
        val n = normal(w.text)
        if (n.length < MIN_WORD || n in STOP) null else stem(n)
    }

    /** A word as compared: no joiner, Arabic letters as Persian, no punctuation, lower case. */
    fun normal(text: String): String = buildString {
        for (c in text.lowercase()) {
            when (c) {
                'ي', 'ى' -> append('ی')
                'ك' -> append('ک')
                'ة' -> append('ه')
                '‌' -> {}
                else -> if (c.isLetterOrDigit()) append(c)
            }
        }
    }

    /** The light stem two tellings of one idea share: «می‌دیم»/«بدیم» → «دی», «سایتمون» → «سایت». */
    private fun stem(word: String): String {
        var w = word
        for (p in PREFIXES) if (w.startsWith(p) && w.length - p.length >= MIN_WORD) { w = w.substring(p.length); break }
        for (s in SUFFIXES) if (w.endsWith(s) && w.length - s.length >= MIN_WORD) { w = w.dropLast(s.length); break }
        return w
    }

    private fun seconds(w: Word) = w.range.startMs / MS
    private fun end(w: Word) = w.range.endMs / MS
    private fun length(line: List<Word>) = end(line.last()) - seconds(line.first())
    private fun Float.one() = (kotlin.math.round(this * 10f) / 10f).toString()

    private const val MS = 1000f
    private const val MIN_LINES = 6
    private const val MIN_PIECE = 40f
    private const val MAX_OPENING = 3
    /** A hesitation with this many content words or fewer says nothing yet. */
    private const val HESITANT_CONTENT = 1
    /** Asking clauses this close (in clauses) are one ask. */
    private const val ASK_REACH = 3
    /** Seconds before the last ask that lead into it. */
    private const val ASK_LEAD = 8f
    /** A pause this long inside a line ends a clause. */
    private const val CLAUSE_PAUSE = 0.3f
    private const val BREAKS = "،,.!?؟…;؛"
    private const val MIN_CONTENT = 2
    private const val MIN_WORD = 3
    private const val COVERED = 0.7f
    private const val HALF_COVERED = 0.4f
    private const val GIST_SHARE = 0.6f
    private const val MIN_GIST = 2

    /** A gist's words that carry its point: lower case, no filler, plurals and -ing folded. */
    private fun gistWords(gist: String): Set<String> = gist.lowercase().split(Regex("[^a-z0-9]+"))
        .filter { it.length > 2 && it !in GIST_STOP }.map { it.removeSuffix("ing").removeSuffix("s") }.toSet()

    private val GIST_STOP = setOf("the", "and", "for", "that", "this", "with", "about", "they", "their", "them", "from", "are", "was", "his", "her", "you", "your",
        "our", "has", "have", "not", "but", "who", "what", "how", "why", "says", "said", "speaker", "explains", "talks", "mentions", "people", "one", "out")
    private const val GAP = 6f
    private const val ISLAND = 8f
    /** Seconds of kept clauses that count as one telling of an idea. */
    private const val PASSAGE = 10f
    /** The share at the end of a piece that is its own ending. */
    private const val ENDING_SHARE = 0.15f
    private const val MAX_SHARE = 0.45f
    /** The cut stops this far from a word kept on either side (half the pause when it is shorter). */
    private const val EDGE = 0.06f

    private val HESITATIONS = setOf("نمیدونم", "نمیدونستم", "وای", "اممم", "ام", "um", "uh", "erm")
    /** Asking words, as a word starts: Persian and English. */
    private val ASKS = listOf(
        "کامنت", "دایرکت", "دایریکت", "بفرست", "بفرستید", "کلمه", "عضو", "فالو", "لینک", "بیو", "سیو", "شیر", "سابسکرایب", "کانال",
        "comment", "dm", "follow", "link", "bio", "subscribe", "save", "share",
    )
    /** Asking words that are also what a piece talks about («کانال», "link"): with the keyword alone they ask nothing. */
    private val TOPIC_ASKS = setOf("کانال", "لینک", "link")
    private val PREFIXES = listOf("نمی", "می", "ن")
    private val SUFFIXES = listOf("هایمون", "هامون", "هاتون", "هاشون", "های", "ها", "مون", "تون", "شون", "یم", "ید", "ند", "رو", "ه", "ی", "م", "ت", "ش", "و")
    private val STOP = setOf(
        "من", "تو", "ما", "شما", "اون", "اونا", "این", "اینا", "که", "و", "به", "از", "در", "رو", "را", "با", "هم", "یه", "یک", "دیگه", "اصلا",
        "آقا", "اقا", "میگن", "هست", "هستیم", "هستن", "بود", "کن", "بکن", "کنی", "کنیم", "کنه", "کلن", "کلا", "تازه", "همه", "همش", "اونم",
        "اینم", "ولی", "یا", "اگر", "اگه", "چی", "چه", "چجوری", "کدوم", "واسه", "برای", "توی", "دارن", "داره", "داریم", "دارم", "داری",
        "بری", "برو", "بیا", "بیاید", "بیایید", "نه", "هر", "خیلی", "فلان", "بیسار", "مثلا", "نمیدونم", "میدونم", "وای", "خب", "حالا", "باید",
        "شما", "شه", "میشه", "نمیشه", "چون", "پس", "اینجا", "اونجا", "دیگر", "است", "هستش", "کنید", "بکنید", "رفتم", "گفتم", "میکنن", "میکنه",
        "کردم", "کردی", "کرد", "کردیم", "کردن", "کردند", "کنم", "کنن", "شد", "شدم", "شدی", "شدیم", "بودم", "بودیم", "داشتم", "داشتیم",
        "دادم", "دادیم", "گفت", "زدم", "زدیم", "میکنم", "میکنی", "میکنیم", "میشه", "میخوام", "میخوای", "نمیشه", "باشه", "باشین", "هستی",
        "the", "and", "you", "your", "that", "this", "for", "are", "with", "just", "like", "but", "what", "they", "have", "can",
    )
    private val KEEP_ALL = listOf("چیزی حذف نکن", "چیزی رو حذف نکن", "کات نزن", "همه رو نگه دار", "بدون حذف", "keep everything", "don't cut", "do not cut", "no cuts")
}
