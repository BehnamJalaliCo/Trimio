package io.trimio.engine.autopilot

import io.trimio.core.model.transcript.Word
import io.trimio.engine.motion.recipe.Meaning
import io.trimio.engine.motion.score.BeatScore
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

    fun board(score: Score, words: List<Word>, log: (String) -> Unit = {}): Score {
        val texts = words.map { it.text }
        val grids = tiles(texts)
        grids.forEach { log("storyboard: tiles «${it.beat.text}» ${it.beat.items.joinToString()}") }
        val covered = grids.flatMap { it.words }.toSet()
        // The one title that stays a title: the most energetic text beat (the punchline).
        val title = score.scenes.flatMap { it.beats }.filter { it.recipe in TITLES && !it.text.isNullOrBlank() }.maxByOrNull { it.energy ?: 0f }
        val scenes = score.scenes.map { s ->
            val beats = s.beats.mapNotNull { b ->
                when {
                    b === title -> b.copy(place = "top")
                    b.at != null && b.at in covered && b.recipe != "pop-captions" -> null
                    else -> card(b, texts)
                }
            }
            s.copy(bg = null, inset = null, transition = if (s.bg != null) null else s.transition, beats = beats)
        }.toMutableList()
        // Each grid joins the scene its first word is in.
        for (g in grids) {
            val k = scenes.indexOfLast { (it.from ?: 0) <= g.beat.at!! }.coerceAtLeast(0)
            scenes[k] = scenes[k].copy(beats = scenes[k].beats + g.beat)
        }
        return score.copy(scenes = scenes, captions = score.captions.copy(maxWords = CAPTION_WORDS, mark = "meaning"))
    }

    /** [b] as a card, or null when it has nothing to say on one. */
    private fun card(b: BeatScore, texts: List<String>): BeatScore? {
        val text = b.text?.trim().orEmpty()
        return when (b.recipe) {
            in KEEP -> b.copy(place = b.place ?: "top")
            in TITLES, "stamp", "headline", "lower-third" -> pill(b, text)
            in FIGURES -> b.value?.let { v -> price(b, v, texts) } ?: pill(b, text)
            "versus", "pros-cons", "before-after" -> versus(b) ?: pill(b, text)
            "list", "chips", "logos", "objects", "ranking", "timeline", "checklist" -> chips(b) ?: pill(b, text)
            "steps" -> b.items.takeIf { it.size >= 2 }?.let { BeatScore(recipe = "board-steps", at = b.at, until = b.until, items = it.take(STEPS), place = "top") } ?: pill(b, text)
            "message", "bubble", "quote", "dialogue", "poll" -> text.takeIf { it.isNotBlank() }?.let { BeatScore(recipe = "board-quote", at = b.at, until = b.until, text = it, place = "top") }
            else -> pill(b, text.ifBlank { b.label.orEmpty() })
        }
    }

    private fun pill(b: BeatScore, text: String): BeatScore? {
        val words = text.split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return null
        val shown = words.take(PILL_WORDS).joinToString(" ")
        return BeatScore(
            recipe = "board-pill", at = b.at, until = b.until, time = b.time, hold = b.hold ?: PILL_HOLD, text = shown, place = "top",
            icon = b.icon ?: iconFor(words), label = Meaning.name(Meaning.tone(words)),
        )
    }

    private fun price(b: BeatScore, value: Float, texts: List<String>): BeatScore {
        val persian = texts.any { w -> w.any { it in '؀'..'ۿ' } }
        val figure = listOfNotNull(b.prefix?.takeIf { it.isNotBlank() }, Quantities.digits(value.toDouble(), persian), b.suffix?.takeIf { it.isNotBlank() }).joinToString(" ")
        // Ours («ورودیمون», «ما») reads green; theirs, or what it simply costs, red.
        val around = b.at?.let { a -> texts.subList((a - OWNER_BEFORE).coerceAtLeast(0), (a + OWNER_AFTER).coerceAtMost(texts.size)) }.orEmpty()
        val ours = around.any { w -> w.trim('،', ',', '.').replace("\u200C", "").let { it == "ما" || OURS.any(it::endsWith) } }
        return BeatScore(
            recipe = "board-price", at = b.at, until = b.until, time = b.time, hold = b.hold, text = figure, label = b.label?.takeIf { it.isNotBlank() },
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
        var after = -1
        for (whole in qs) {
            val n = whole.value.toInt()
            if (whole.at <= after || n !in WHOLE || whole.value != n.toDouble() || whole.kind != Quantities.Kind.Plain) continue
            val parts = qs.filter { it.at > whole.last && it.at <= whole.last + REACH && it.value >= 1 && it.value < n }.mapNotNull { q ->
                val near = (q.at + q.count until minOf(texts.size, q.at + q.count + NEAR)).map { texts[it] }
                val tone = Meaning.tone(near).takeIf { it != Meaning.Tone.Mark } ?: return@mapNotNull null
                val said = near.firstOrNull { Meaning.tone(it) == tone } ?: return@mapNotNull null
                Triple(q, tone, said)
            }.distinctBy { it.second }
            if (parts.isEmpty() || parts.sumOf { it.first.value.toInt() } > n) continue
            val noun = (whole.last + 1 until minOf(texts.size, whole.last + 1 + NOUN)).map { texts[it].trim('،', ',', '.') }
                .firstOrNull { w -> w.length >= 3 && w !in COUNTERS && !w.startsWith("می") && Meaning.tone(w) == Meaning.Tone.Mark && w !in FILLERS }
            val persian = texts.any { w -> w.any { it in '؀'..'ۿ' } }
            fun num(v: Int) = Quantities.digits(v.toDouble(), persian)
            val items = parts.map { (q, tone, said) -> "${Meaning.name(tone)}:${q.value.toInt()}:${num(q.value.toInt())} ${said.trim('،', ',', '.')}" }
            val last = parts.maxOf { (q, _, _) -> q.at + q.count + NEAR - 1 }.coerceAtMost(texts.lastIndex)
            val beat = BeatScore(
                recipe = "board-tiles", at = whole.at, until = last, value = n.toFloat(), text = listOfNotNull(num(n), noun).joinToString(" "),
                items = items, itemAt = parts.map { (q, _, _) -> q.at }, place = "top",
            )
            out += Grid(beat, whole.at..last)
            after = last
        }
        return out
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
    private val TITLES = setOf("slam", "stack", "spread", "mask-rise", "blur-in", "flip", "type-on", "glitch")
    private val FIGURES = setOf("counter", "meter", "ring", "stat-stack", "burst", "countdown", "progress", "stats", "voucher", "price-tag", "receipt", "chart", "bars")
    private val KEEP = setOf("comment", "endcard", "ui-browser", "ui-chat", "url", "logo-mark", "pop-captions", "subtitles", "callout", "board-tiles", "board-pill", "board-quote", "board-price", "board-versus", "board-steps", "board-chips")
    private val OURS = listOf("مون", "مان")
    private val COUNTERS = setOf("تا", "دونه", "عدد", "دانه", "نفر")
    private val FILLERS = setOf("کلن", "کلا", "اصلا", "در", "روز")
    private val ICONS = linkedMapOf(
        "سیگنال" to "chart", "کانال" to "send", "تلگرام" to "send", "سایت" to "browser", ".com" to "browser", "ربات" to "bolt", "چت" to "chat",
        "کامنت" to "chat", "دایرکت" to "send", "دایریکت" to "send", "پیج" to "user", "شفاف" to "eye", "تتر" to "coin", "دلار" to "coin", "تومن" to "coin",
        "تومان" to "coin", "پول" to "coin", "رایگان" to "gift", "امن" to "shield", "زمان" to "clock", "روز" to "calendar",
    )
}
