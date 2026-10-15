package io.trimio.engine.autopilot

import io.trimio.core.model.transcript.Word
import io.trimio.engine.motion.recipe.Meaning
import io.trimio.engine.motion.score.BeatScore

/**
 * The storyboard's richer cards, found in what is said ([Storyboard]): each one a pattern an editor
 * hears and draws the same way whatever the piece is about.
 *
 * - **versus** — two prices, one theirs («ورودیشم دو میلیون…») and one ours («پنجاه تتر ورودی‌مونه»): one card,
 *   us against them, growing a line for each gain said after it («به ما پولی نمی‌دی», «رایگانه»);
 * - **assistant** — an AI assistant the viewer is sent to ask («برو تو چت‌جی‌پی‌تی بزن ربات تریدیار»): its window,
 *   the question typed in it, and the follow-up asked as a second bubble;
 * - **steps** — the ask as steps: comment the word, check the DM, what is waiting there (site, channel, bot).
 */
internal object BoardDevices {

    /** A device's beat and the words it stands for (the beats the planner put on them go). */
    class Found(val beat: BeatScore, val words: IntRange)

    fun all(words: List<Word>, lines: List<Lines.Line>, keyword: String?): List<Found> {
        val texts = words.map { it.text }
        val clauses = if (lines.isEmpty()) emptyList() else StoryCut.clauses(words, lines)
        return listOfNotNull(versus(texts, clauses), assistant(texts, clauses), steps(texts, keyword))
    }

    // ------------------------------------------------------------------ versus

    private class Price(val q: Quantities.Quantity, val ours: Boolean, val what: String?)

    fun versus(texts: List<String>, clauses: List<IntRange>): Found? {
        val persian = texts.any(::persian)
        val prices = Quantities.read(texts).filter { it.kind == Quantities.Kind.Money && it.value > 0 }.mapNotNull { q -> owner(texts, q) }
        val theirs = prices.firstOrNull { !it.ours } ?: return null
        val ours = prices.firstOrNull { it.ours } ?: return null
        val (first, second) = if (theirs.q.at < ours.q.at) theirs to ours else ours to theirs
        if (second.q.at - first.q.last > MAX_APART) return null
        fun side(p: Price) = listOf(
            if (p.ours) say(persian, "ما", "Us") else say(persian, "اونا", "Them"),
            "${Storyboard.money(p.q.value.toDouble(), persian)} ${p.q.unit}".trim(),
            p.what.orEmpty(),
        ).joinToString("|")
        val land = second.q.at
        // What it costs us is said after the comparison: each gain in the clauses that follow is a line the card grows.
        val gains = clauses.filter { c -> c.first > second.q.last && c.first - second.q.last <= GAIN_REACH }
            .filter { c -> Meaning.tone(texts.slice(c)) == Meaning.Tone.Gain || texts.slice(c).any { StoryCut.normal(it) in FREE } }
            .take(MAX_LINES)
        val items = listOf("gain:${side(ours)}", "loss:${side(theirs)}") + gains.map { c -> "gain:${short(texts, c)}" }
        val until = (gains.lastOrNull()?.last ?: second.q.last).coerceAtMost(texts.lastIndex)
        val beat = BeatScore(
            recipe = "board-versus", at = land, until = until, items = items, itemAt = listOf(-1, -1) + gains.map { it.first }, place = "top",
        )
        return Found(beat, minOf(first.q.at, land)..until)
    }

    /**
     * Whose price [q] is: the word that names it says so first («ورودیشم دو میلیون» theirs, «ورودی‌مونه» ours), then the
     * ending heard as a word after the figure («پنجاه تتر ورودی مونه»), then «ما» / «اونا» just before it; null when
     * nothing says.
     */
    private fun owner(texts: List<String>, q: Quantities.Quantity): Price? {
        fun n(i: Int) = texts.getOrNull(i)?.let(StoryCut::normal).orEmpty()
        fun mine(w: String) = w in OURS || OURS.any { s -> w.endsWith(s) && w.length > s.length + 1 }
        fun theirs(w: String) = w in THEM || (w.length > THEIRS_MIN && THEIRS.any { s -> w.endsWith(s) })
        val before = n(q.at - 1)
        val after = (q.last + 1..q.last + OWNER_AFTER).map(::n)
        val ours = when {
            mine(before) -> true
            theirs(before) -> false
            after.any(::mine) -> true
            after.any { it in THEM } -> false
            n(q.at - OWNER_BEFORE) == "ما" || before == "ما" -> true
            else -> return null
        }
        // What the price is: the content word naming it, its possessive ending taken off («ورودیشم» → «ورودی»).
        val around = listOf(q.at - 1) + (q.last + 1..q.last + OWNER_AFTER)
        val what = around.mapNotNull { texts.getOrNull(it) }
            .firstOrNull { w -> StoryCut.content(listOf(word(w))).isNotEmpty() && Quantities.unitOf(StoryCut.normal(w)) == null && !mine(StoryCut.normal(w)) }
            ?.let(::bare)
        return Price(q, ours, what)
    }

    private fun bare(word: String): String {
        val w = word.trim('،', ',', '.').replace("‌", "")
        val ending = (OURS + THEIRS + listOf("م")).sortedByDescending { it.length }.firstOrNull { w.endsWith(it) && w.length - it.length >= MIN_STEM }
        return ending?.let { w.dropLast(it.length) } ?: w
    }

    // ------------------------------------------------------------------ assistant

    fun assistant(texts: List<String>, clauses: List<IntRange>): Found? {
        val said = texts.map(StoryCut::normal)
        val at = said.indices.firstOrNull { i -> name(said, i) != null } ?: return null
        val (name, length) = name(said, at)!!
        val clause = clauses.firstOrNull { at in it } ?: (at..minOf(texts.lastIndex, at + CLAUSE_REACH))
        // The question: what is said after the name in its clause, without the verbs that send the viewer there.
        val query = (at + length..clause.last).map { texts[it].trim('،', ',', '.') }.filter { StoryCut.normal(it) !in SENDS && StoryCut.normal(it).isNotEmpty() }
        if (query.isEmpty()) return null
        val asks = listOf(query.take(QUERY_WORDS).joinToString(" ") to (at + length))
        val follow = clauses.firstOrNull { c -> c.first > clause.last && c.first - clause.last <= FOLLOW_REACH && c.any { said[it] in QUESTIONS || texts[it].endsWith('؟') } }
            ?.let { c -> short(texts, c) to c.first }
        val bubbles = asks + listOfNotNull(follow)
        // The window stays while the assistant is talked about: to the follow-up, or the last time it is named nearby.
        val named = (at + length until minOf(said.size, at + FOLLOW_REACH)).lastOrNull { name(said, it) != null }
        val until = listOfNotNull(follow?.second?.let { f -> clauses.firstOrNull { f in it }?.last }, named?.let { n -> clauses.firstOrNull { n in it }?.last }, clause.last).max()
        val beat = BeatScore(
            recipe = "board-chat", at = at, until = until, label = name, text = bubbles.first().first, items = bubbles.map { it.first },
            itemAt = bubbles.map { it.second }, place = "top",
        )
        return Found(beat, at..until)
    }

    /** The assistant named at [i] (its display name and how many words its name took), or null. */
    private fun name(said: List<String>, i: Int): Pair<String, Int>? {
        for ((spoken, shown) in ASSISTANTS) {
            val one = said[i]
            val two = one + said.getOrNull(i + 1).orEmpty()
            if (one == spoken) return shown to 1
            if (two == spoken) return shown to 2
        }
        return null
    }

    // ------------------------------------------------------------------ steps

    fun steps(texts: List<String>, keyword: String?): Found? {
        val said = texts.map(StoryCut::normal)
        val from = (texts.size * (1f - ASK_SHARE)).toInt()
        val comment = (from until said.size).firstOrNull { said[it].startsWith("کامنت") || said[it] == "comment" } ?: return null
        val dm = (comment until said.size).firstOrNull { i -> DM.any { said[i].startsWith(it) } }
        val word = keyword?.takeIf { it.isNotBlank() }
        val steps = mutableListOf(
            (if (persian(texts[comment])) "کامنت ${word?.let { "«$it»" }.orEmpty()}" else "Comment ${word?.let { "\"$it\"" }.orEmpty()}").trim() to comment,
        )
        if (dm != null) {
            steps += (if (persian(texts[dm])) "چک دایرکت" else "Check your DM") to dm
            val waiting = (dm until minOf(said.size, dm + WAITING_REACH)).mapNotNull { i -> WAITING.entries.firstOrNull { said[i].startsWith(it.key) }?.let { it.value to i } }
                .distinctBy { it.first }
            if (waiting.size >= 2) steps += waiting.joinToString("، ") { it.first } to waiting.first().second
        }
        if (steps.size < 2) return null
        val until = steps.last().second
        val beat = BeatScore(recipe = "board-steps", at = comment, until = until, items = steps.map { it.first }, itemAt = steps.map { it.second }, place = "top")
        return Found(beat, comment..until)
    }

    // ------------------------------------------------------------------ helpers

    /** A clause in a few words, from its first word that says something. */
    fun short(texts: List<String>, c: IntRange): String {
        val start = c.firstOrNull { i -> StoryCut.content(listOf(word(texts[i]))).isNotEmpty() } ?: c.first
        return (start..c.last).take(SHORT_WORDS).joinToString(" ") { texts[it].trim('،', ',', '.') }
    }

    private fun word(w: String) = Word(w, io.trimio.core.model.time.TimeRange(0, 1), language = io.trimio.core.model.text.Language.Persian)

    private fun persian(w: String) = w.any { it in '؀'..'ۿ' }

    private fun say(persian: Boolean, fa: String, en: String) = if (persian) fa else en

    private const val MAX_APART = 80
    private const val GAIN_REACH = 30
    private const val MAX_LINES = 2
    private const val OWNER_BEFORE = 2
    private const val OWNER_AFTER = 3
    private const val THEIRS_MIN = 3
    private const val MIN_STEM = 3
    private const val CLAUSE_REACH = 8
    private const val QUERY_WORDS = 3
    private const val FOLLOW_REACH = 45
    private const val ASK_SHARE = 0.3f
    private const val WAITING_REACH = 14
    private const val SHORT_WORDS = 4

    private val OURS = listOf("مونه", "مون", "مان")
    private val THEIRS = listOf("شونه", "شون", "شم", "ش")
    private val THEM = setOf("اونا", "اونها", "بقیه", "انها")
    private val FREE = setOf("رایگانه", "رایگان", "مجانی", "free")
    private val SENDS = setOf("بزن", "بپرس", "بگو", "سرچ", "بکن", "کن", "برو", "ببین", "توی", "تو", "رو", "به", "از", "ask", "search", "type")
    private val QUESTIONS = setOf("چجوریه", "چطوره", "چیه", "چقدره", "کدومه", "how", "what")
    private val DM = listOf("دایرکت", "دایریکت", "direct", "dm")
    private val WAITING = linkedMapOf(
        "سایت" to "سایت", "کانال" to "کانال", "ربات" to "ربات", "روبات" to "ربات", "لینک" to "لینک", "پیج" to "پیج", "اپ" to "اپ",
        "site" to "site", "channel" to "channel", "bot" to "bot", "link" to "link",
    )
    /** Assistants as said (normalized, a two-word name joined) and as written. */
    private val ASSISTANTS = listOf(
        "چتجیپیتی" to "ChatGPT", "چتچیپیتی" to "ChatGPT", "چتجیپیتی" to "ChatGPT", "chatgpt" to "ChatGPT", "جمینای" to "Gemini", "gemini" to "Gemini",
        "گراک" to "Grok", "grok" to "Grok", "پرپلکسیتی" to "Perplexity", "perplexity" to "Perplexity",
    )
}
