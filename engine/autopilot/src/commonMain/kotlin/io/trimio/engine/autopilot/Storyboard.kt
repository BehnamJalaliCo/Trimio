package io.trimio.engine.autopilot

import io.trimio.core.model.transcript.Word
import io.trimio.engine.motion.recipe.Meaning
import io.trimio.engine.motion.score.BeatScore
import io.trimio.engine.motion.score.SceneScore
import io.trimio.engine.motion.score.Score

/**
 * The talking head as the owner's reference edit shows it (docs/research/REFERENCE_EDL.md): the
 * speaker is never replaced or shrunk, and every sentence gets a small card in the band above the
 * head, in one interface language ([io.trimio.engine.motion.recipe.Board]). The planner still
 * decides what each line shows and when; this pass says it in cards:
 *
 * - no full-frame stage, no corner inset: every scene stays on the footage;
 * - a count said aloud and then broken down («ten signals a day, nine hit stop-loss, one wins») is one
 *   grid of tiles that turns red and green as the parts are said ([tiles]), holding across the lines;
 * - a figure is a price card, a comparison a versus card, a list chips that tick, steps a tracker, a
 *   quoted claim a speech bubble; any other line a pill with an icon and a few words;
 * - one kinetic title in the piece — the strongest — stays a title; every other title becomes a pill;
 * - captions three or four words at a time, the stressed word on a block coloured by what it means.
 */
internal object Storyboard {

    /** Whether a piece is told this way: a talking head with footage, unless the brief asks for full-frame graphics. */
    fun wanted(prompt: String, footage: Boolean): Boolean {
        if (!footage) return false
        val p = prompt.lowercase().replace("‌", " ")
        return STAGE_WORDS.none { it in p }
    }

    fun board(
        score: Score, words: List<Word>, lines: List<Lines.Line> = emptyList(), reads: List<LineRead> = emptyList(), keyword: String? = null,
        log: (String) -> Unit = {},
    ): Score {
        val texts = words.map { it.text }
        val grids = tiles(texts)
        grids.forEach { log("storyboard: tiles «${it.beat.text}» ${it.beat.items.joinToString()}") }
        val devices = BoardDevices.all(words, lines, keyword).filter { d -> grids.none { g -> d.words.first <= g.words.last && d.words.last >= g.words.first } }
        devices.forEach { log("storyboard: ${it.beat.recipe} ${it.beat.items.joinToString(" / ")}") }
        val covered = (grids.flatMap { it.words } + devices.flatMap { it.words }).toSet()
        // The one title that stays a title: the most energetic text beat (the punchline).
        val title = score.scenes.flatMap { it.beats }.filter { it.recipe in TITLES && !it.text.isNullOrBlank() }.maxByOrNull { it.energy ?: 0f }
        val shells = score.scenes.map { s -> s.copy(bg = null, inset = null, transition = if (s.bg != null) null else s.transition, beats = emptyList()) }
        // Beats without a word to land on keep their scene; the cards are laid out on the words, one after another.
        // A beat that quotes the speech lands where it is said: it is laid out on that word like any other.
        val steps = devices.firstOrNull { it.beat.recipe == "board-steps" }
        val placed = score.scenes.map { s ->
            s.beats.map { b -> if (b.at == null && b.time == null) located(b, s.from ?: 0, texts) else b }.map { b ->
                // The ask's steps come first; the comment card (the end card) takes over when they are done.
                if (b.recipe == "comment" && steps != null && (b.at ?: 0) < steps.words.last) b.copy(at = steps.words.last, until = null, time = null) else b
            }
        }
        val loose = placed.mapIndexed { k, beats -> k to beats.filter { it.at == null }.mapNotNull { b -> if (b === title) b.copy(place = "top") else card(b, texts) } }
        val cards = placed.flatten().filter { it.at != null }.mapNotNull { b ->
            when {
                b === title -> b.copy(place = "top")
                b.at in covered && b.recipe != "pop-captions" -> null
                else -> card(b, texts)
            }
        }.toMutableList()
        cards += grids.map { it.beat } + devices.map { it.beat }
        // The steps end on the comment field (the end card): it lands as they finish and holds to the end.
        if (steps != null && cards.none { it.recipe == "comment" } && loose.none { (_, bs) -> bs.any { it.recipe == "comment" } }) {
            val word = keyword?.takeIf { it.isNotBlank() } ?: steps.beat.items.firstOrNull().orEmpty()
            cards += BeatScore(recipe = "comment", at = steps.words.last, until = texts.lastIndex, text = word, label = "کامنت کن", place = "top")
        }
        filled(cards, words, lines, reads)
        val sequenced = sequence(cards.filter { it.recipe != "pop-captions" }, words) + cards.filter { it.recipe == "pop-captions" }
        sequenced.filter { it.recipe != "pop-captions" }.forEach { b -> log("storyboard card: ${b.recipe} @${b.at}–${b.until} «${b.text.orEmpty()}» ${b.items.joinToString(" / ")}") }
        val merged = laidOut(shells, sequenced, loose)
        return score.copy(scenes = merged, captions = score.captions.copy(maxWords = CAPTION_WORDS, mark = MEANING))
    }

    /**
     * The scenes over the cards: a scene starting inside a card's span would cut it there, so it joins the one before;
     * every card goes to the scene its word is in, the beats with no word to their own scene's heir.
     */
    private fun laidOut(scenes: List<SceneScore>, sequenced: List<BeatScore>, loose: List<Pair<Int, List<BeatScore>>>): List<SceneScore> {
        val spans = sequenced.filter { it.recipe != "pop-captions" }.map { b -> b.at!!.let { a -> a to (b.until ?: a) } }
        val kept = scenes.indices.filter { k -> k == 0 || spans.none { (a, z) -> (scenes[k].from ?: 0) in (a + 1)..z } }
        val merged = kept.map { scenes[it] }.toMutableList()
        fun sceneOf(word: Int) = merged.indexOfLast { (it.from ?: 0) <= word }.coerceAtLeast(0)
        for (b in sequenced) sceneOf(b.at!!).let { k -> merged[k] = merged[k].copy(beats = merged[k].beats + b) }
        for ((k, beats) in loose) {
            val into = kept.indexOfLast { it <= k }.coerceAtLeast(0)
            merged[into] = merged[into].copy(beats = merged[into].beats + beats)
        }
        return merged
    }

    /**
     * The cards one after another, as the reference lays them: two landing within [CLASH] seconds keep the richer
     * one (a grid over a figure over a title over a pill, the longer pill of two); a pill of one plain word says
     * nothing and goes; each card stays up until the next one lands (at most [HOLD_UNTIL_NEXT] seconds more than
     * its own), always in the band above the head.
     */
    private fun sequence(cards: List<BeatScore>, words: List<Word>): List<BeatScore> {
        fun start(i: Int) = words.getOrNull(i)?.range?.startMs?.div(MS) ?: 0f
        val meaningful = cards.filter { b -> b.recipe != "board-pill" || says(b.text.orEmpty()) }
        val out = mutableListOf<BeatScore>()
        for (b in meaningful.sortedBy { it.at }) {
            val prev = out.lastOrNull()
            // The ask's field is never traded for another card: it is the ending.
            val ending = b.recipe == "comment" || prev?.recipe == "comment"
            if (prev != null && !ending && start(b.at!!) - start(prev.at!!) < CLASH) {
                if (rank(b) > rank(prev) || (rank(b) == rank(prev) && b.text.orEmpty().length > prev.text.orEmpty().length)) out[out.lastIndex] = b
                continue
            }
            out += b
        }
        return out.mapIndexed { i, b ->
            val next = out.getOrNull(i + 1)?.at
            val own = b.until ?: b.at!!
            val held = next?.let { n -> (n - 1).coerceAtLeast(own) }?.let { u -> if (start(u) - start(own) <= HOLD_UNTIL_NEXT) u else own } ?: own
            b.copy(until = held, hold = null, place = "top")
        }
    }

    private fun rank(b: BeatScore): Int = when (b.recipe) {
        "board-tiles", "board-versus", "board-steps", "board-chat", "comment", "endcard" -> RANK_STATE
        "board-chips", "board-price", "board-quote", "ui-browser", "ui-chat", "url" -> RANK_CARD
        in TITLES -> RANK_TITLE
        else -> RANK_PILL
    }

    /**
     * Every sentence gets its card: a line with nothing on screen while it is said, and none landing within
     * [GAP_BEFORE] seconds before it, gets a pill with its title (the reading's, else its key phrase).
     */
    private fun filled(cards: MutableList<BeatScore>, words: List<Word>, lines: List<Lines.Line>, reads: List<LineRead>) {
        fun start(i: Int) = words.getOrNull(i)?.range?.startMs?.div(MS) ?: 0f
        val landed = cards.filter { it.recipe != "pop-captions" }.map { b -> b.at!!.let { a -> a to (b.until ?: a) } }.toMutableList()
        for ((k, line) in lines.withIndex()) {
            val busy = landed.any { (a, z) -> a <= line.last && z >= line.first } ||
                landed.any { (a, _) -> start(a) in (start(line.first) - GAP_BEFORE)..start(line.first) }
            if (busy || line.size < MIN_LINE_WORDS) continue
            // The reading's title, else the line's key phrase; a raw stretch of speech is no title (the card before stays up).
            val title = listOfNotNull(reads.getOrNull(k)?.title, Phrases.title(words, line, alone = false))
                .map { it.trim() }.firstOrNull { it.isNotBlank() && says(it) }
            title?.let { pill(BeatScore(recipe = "board-pill", at = line.first, until = line.last), it) }?.let { p ->
                cards += p
                landed += line.first to line.last
            }
        }
    }

    /** [b] on the first word of its quote at or after [from] (its first two words matched), else as it is. */
    private fun located(b: BeatScore, from: Int, texts: List<String>): BeatScore {
        val quote = b.text?.split(' ')?.map(StoryCut::normal)?.filter { it.isNotEmpty() }.orEmpty()
        if (quote.isEmpty()) return b
        val said = texts.map(StoryCut::normal)
        val i = (from until said.size).firstOrNull { i -> quote.take(2).withIndex().all { (j, q) -> said.getOrNull(i + j)?.startsWith(q.take(QUOTE_STEM)) == true } }
        return i?.let { b.copy(at = it) } ?: b
    }

    /** A figure as a card shows it: whole thousands grouped («۲٬۷۰۰٬۰۰۰»), Persian digits in a Persian piece. */
    fun money(value: Double, persian: Boolean): String {
        if (value != kotlin.math.floor(value) || value < THOUSAND) return Quantities.digits(value, persian)
        val grouped = value.toLong().toString().reversed().chunked(3).joinToString(",").reversed()
        return if (persian) io.trimio.core.model.text.Numerals.toPersian(grouped).replace(',', '٬') else grouped
    }

    /**
     * [revised] when it keeps every card that holds state in [old] (a grid, a versus, the steps, an assistant's window,
     * chips, a price, a quote: what the piece is drawn with), else [old]: a judge's fix may move, retime or add, never
     * take the storyboard apart. A score not told in cards is [revised] as it is.
     */
    fun kept(old: Score, revised: Score): Score {
        if (!told(old)) return revised
        fun rich(s: Score) = s.scenes.flatMap { it.beats }.filter { it.recipe in RICH }.map { it.recipe to it.at }.toSet()
        return if (rich(revised).containsAll(rich(old))) revised else old
    }

    /** Whether [score] is told in cards (its captions mark meaning): what the critic and the jury add to it must be too. */
    fun told(score: Score): Boolean = score.captions.mark == MEANING

    /**
     * A beat the critic or the jury adds to (or puts in place of one in) a [told] score, said in cards: a title is a
     * pill unless the piece has no title yet, a figure a price card, and so on ([card]); null when it says nothing as one.
     */
    fun translated(score: Score, b: BeatScore): BeatScore? {
        if (!told(score)) return b
        // The ask is made once, at the end, as steps: a comment card before them (a keyword said in passing) is no ask.
        val steps = score.scenes.flatMap { it.beats }.firstOrNull { it.recipe == "board-steps" }
        if (b.recipe == "comment" && steps != null && (b.at ?: Int.MAX_VALUE) < (steps.at ?: 0)) return null
        val titled = score.scenes.any { s -> s.beats.any { it.recipe in TITLES } }
        return if (b.recipe in TITLES && !titled) b.copy(place = "top") else card(b, emptyList())
    }

    /** [b] as a card, or null when it has nothing to say on one. */
    private fun card(b: BeatScore, texts: List<String>): BeatScore? {
        val text = b.text?.trim().orEmpty()
        // The creator's own picture or clip is shown as it is: it is theirs, not a card of ours.
        if (b.media != null) return b.copy(place = b.place ?: "top")
        return when (b.recipe) {
            in KEEP -> b.copy(place = b.place ?: "top")
            in TITLES, "stamp", "headline", "lower-third" -> pill(b, text)
            // A site is a pill with the browser's mark: one interface language.
            "url" -> pill(b.copy(icon = "browser"), text.ifBlank { b.label.orEmpty() })
            in FIGURES -> b.value?.let { v -> price(b, v, texts) } ?: pill(b, text)
            "versus", "pros-cons", "before-after" -> versus(b) ?: pill(b, text)
            "list", "chips", "logos", "objects", "ranking", "timeline", "checklist" -> chips(b) ?: pill(b, text)
            "steps" -> b.items.takeIf { it.size >= 2 }?.let { BeatScore(recipe = "board-steps", at = b.at, until = b.until, items = it.take(STEPS), place = "top") } ?: pill(b, text)
            "message", "bubble", "quote", "dialogue", "poll" -> text.takeIf { it.isNotBlank() }?.let { BeatScore(recipe = "board-quote", at = b.at, until = b.until, text = it, place = "top") }
            else -> pill(b, text.ifBlank { b.label.orEmpty() })
        }
    }

    /** A pill says something: two content words at least («شفاف‌سازی کامل», not «کردن حساب» or «فارسی»), or a site or a figure. */
    private fun says(text: String): Boolean {
        val words = text.split(' ').filter { it.isNotBlank() }
        val named = words.any { w -> w.any { it == '.' || it in 'a'..'z' || it in 'A'..'Z' || it.isDigit() } }
        val content = StoryCut.content(words.map { w -> Word(w, io.trimio.core.model.time.TimeRange(0, 1), language = io.trimio.core.model.text.Language.Persian) }).size
        return named || content >= 2
    }

    private fun pill(b: BeatScore, text: String): BeatScore? {
        val words = text.split(' ').filter { it.isNotBlank() }
        if (words.isEmpty() || !says(text)) return null
        val shown = words.take(PILL_WORDS).joinToString(" ")
        return BeatScore(
            recipe = "board-pill", at = b.at, until = b.until, time = b.time, hold = b.hold ?: PILL_HOLD, text = shown, place = "top",
            icon = b.icon ?: iconFor(words), label = Meaning.name(Meaning.tone(words)),
        )
    }

    private fun price(b: BeatScore, value: Float, texts: List<String>): BeatScore {
        val persian = texts.any { w -> w.any { it in '؀'..'ۿ' } }
        val figure = listOfNotNull(b.prefix?.trim()?.takeIf { it.isNotBlank() }, money(value.toDouble(), persian), b.suffix?.trim()?.takeIf { it.isNotBlank() }).joinToString(" ")
        // Ours («ورودیمون», «ما») reads green; theirs, or what it simply costs, red.
        val around = b.at?.let { a -> texts.subList((a - OWNER_BEFORE).coerceAtLeast(0), (a + OWNER_AFTER).coerceAtMost(texts.size)) }.orEmpty()
        val ours = around.any { w -> w.trim('،', ',', '.').replace("\u200C", "").let { it == "ما" || OURS.any(it::endsWith) } }
        return BeatScore(
            recipe = "board-price", at = b.at, until = b.until, time = b.time, hold = b.hold, text = figure, value = value, label = b.label?.takeIf { it.isNotBlank() },
            mark = if (ours) "gain" else "loss", place = "top",
        )
    }

    private fun versus(b: BeatScore): BeatScore? {
        if (b.items.size < 2) return null
        return BeatScore(recipe = "board-versus", at = b.at, until = b.until, items = listOf("gain:${b.items[0]}", "loss:${b.items[1]}"), place = "top")
    }

    private fun chips(b: BeatScore): BeatScore? {
        val items = b.items.ifEmpty { b.text?.split('|')?.map { it.trim() }.orEmpty() }.filter { it.isNotBlank() }
        if (items.size < 2) return null
        return BeatScore(recipe = "board-chips", at = b.at, until = b.until, items = items.take(CHIPS), place = "top")
    }

    /** A grid of tiles: the beat and the words it stands for (the other beats on them go). */
    class Grid(val beat: BeatScore, val words: IntRange)

    /**
     * Counts said and then broken down: a whole (3 to 12) followed, within [REACH] words, by parts
     * smaller than it each next to a loss or a gain word («نه تاش استاپ می‌خوره، یه دونه‌ش سود داره»).
     */
    fun tiles(texts: List<String>): List<Grid> {
        val qs = Quantities.read(texts)
        val out = mutableListOf<Grid>()
        for (whole in qs) {
            if (whole.at <= (out.lastOrNull()?.words?.last ?: -1)) continue
            grid(whole, qs, texts)?.let { out += it }
        }
        return out
    }

    /** The grid [whole] opens, when parts of it follow. */
    private fun grid(whole: Quantities.Quantity, qs: List<Quantities.Quantity>, texts: List<String>): Grid? {
        val n = whole.value.toInt()
        val counted = n in WHOLE && whole.value == n.toDouble()
        if (!counted || whole.kind != Quantities.Kind.Plain) return null
        val parts = qs.filter { it.at > whole.last && it.at <= whole.last + REACH && it.value >= 1 && it.value < n }.mapNotNull { q ->
            val near = (q.at + q.count until minOf(texts.size, q.at + q.count + NEAR)).map { texts[it] }
            val tone = Meaning.tone(near).takeIf { it != Meaning.Tone.Mark } ?: return@mapNotNull null
            val said = near.firstOrNull { Meaning.tone(it) == tone } ?: return@mapNotNull null
            Triple(q, tone, said)
        }.distinctBy { it.second }
        if (parts.isEmpty() || parts.sumOf { it.first.value.toInt() } > n) return null
        val noun = (whole.last + 1 until minOf(texts.size, whole.last + 1 + NOUN)).map { texts[it].trim('،', ',', '.') }
            .firstOrNull { w -> w.length >= 3 && w !in COUNTERS && !w.startsWith("می") && Meaning.tone(w) == Meaning.Tone.Mark && w !in FILLERS }
        // A count of things (signals, trades, students), never a stretch of time («شش ماه», "six months").
        if (noun == null || TIME.any { noun.lowercase().startsWith(it) }) return null
        val persian = texts.any { w -> w.any { it in '\u0600'..'\u06FF' } }
        fun num(v: Int) = Quantities.digits(v.toDouble(), persian)
        val items = parts.map { (q, tone, said) -> "${Meaning.name(tone)}:${q.value.toInt()}:${num(q.value.toInt())} ${said.trim('،', ',', '.')}" }
        val last = parts.maxOf { (q, _, _) -> q.at + q.count + NEAR - 1 }.coerceAtMost(texts.lastIndex)
        val beat = BeatScore(
            recipe = "board-tiles", at = whole.at, until = last, value = n.toFloat(), text = listOfNotNull(num(n), noun).joinToString(" "),
            items = items, itemAt = parts.map { (q, _, _) -> q.at }, place = "top",
        )
        return Grid(beat, whole.at..last)
    }

    private fun iconFor(words: List<String>): String {
        val said = words.joinToString(" ").replace("‌", "")
        return ICONS.entries.firstOrNull { (k, _) -> k in said }?.value ?: when (Meaning.tone(words)) {
            Meaning.Tone.Gain -> "trend-up"
            Meaning.Tone.Loss -> "trend-down"
            Meaning.Tone.Mark -> "spark"
        }
    }

    private const val CAPTION_WORDS = 4
    private const val THOUSAND = 1000.0
    private const val QUOTE_STEM = 3
    private const val MEANING = "meaning"
    private const val MS = 1000f
    private const val CLASH = 1.5f
    private const val HOLD_UNTIL_NEXT = 4f
    private const val RANK_STATE = 3
    private const val RANK_CARD = 2
    private const val RANK_TITLE = 1
    private const val RANK_PILL = 0
    private const val GAP_BEFORE = 1.5f
    private const val MIN_LINE_WORDS = 3
    private const val PILL_WORDS = 5
    private const val PILL_HOLD = 2.2f
    private const val STEPS = 4
    private const val CHIPS = 3
    private const val OWNER_BEFORE = 2
    private const val OWNER_AFTER = 6
    private const val REACH = 14
    private const val NEAR = 4
    private const val NOUN = 7
    private val WHOLE = 3..12

    /** What the brief says when it wants full-frame graphics after all. */
    private val STAGE_WORDS = listOf("تمام صفحه", "فول اسکرین", "صحنه کامل", "full screen", "full-screen", "full frame", "full-frame")
    private val RICH = setOf("board-tiles", "board-versus", "board-steps", "board-chat", "board-chips", "board-price", "board-quote")
    private val TITLES = setOf("slam", "stack", "spread", "mask-rise", "blur-in", "flip", "type-on", "glitch")
    private val FIGURES = setOf("counter", "meter", "ring", "stat-stack", "burst", "countdown", "progress", "stats", "voucher", "price-tag", "receipt", "chart", "bars")
    private val KEEP = setOf(
        "comment", "endcard", "ui-browser", "ui-chat", "logo-mark", "pop-captions", "subtitles", "callout",
        "board-tiles", "board-pill", "board-quote", "board-price", "board-versus", "board-steps", "board-chips", "board-chat",
    )
    private val OURS = listOf("مونه", "مون", "مان")
    private val COUNTERS = setOf("تا", "دونه", "عدد", "دانه", "نفر")
    private val TIME = listOf("ماه", "سال", "ساعت", "دقیقه", "هفته", "ثانیه", "month", "year", "hour", "minute", "week", "second", "day")
    private val FILLERS = setOf("کلن", "کلا", "اصلا", "در", "روز")
    private val ICONS = linkedMapOf(
        "سیگنال" to "chart", "کانال" to "send", "تلگرام" to "send", "سایت" to "browser", ".com" to "browser", "ربات" to "bolt", "چت" to "chat",
        "کامنت" to "chat", "دایرکت" to "send", "دایریکت" to "send", "پیج" to "user", "شفاف" to "eye", "تتر" to "coin", "دلار" to "coin", "تومن" to "coin",
        "تومان" to "coin", "پول" to "coin", "رایگان" to "gift", "امن" to "shield", "زمان" to "clock", "روز" to "calendar",
    )
}
