package io.trimio.engine.autopilot.explainer

import io.trimio.engine.autopilot.Phrases
import io.trimio.engine.autopilot.Quantities
import io.trimio.engine.autopilot.Rng
import io.trimio.engine.motion.explainer.Beat
import io.trimio.engine.motion.explainer.BeatContent
import io.trimio.engine.motion.explainer.Chapter
import io.trimio.engine.motion.explainer.ExplainerFilm
import io.trimio.engine.motion.explainer.Primitive
import io.trimio.engine.motion.explainer.StageOp

/**
 * The illustrated news film by the rules: one fast scene per sentence (2–3 s; a long one splits at
 * its comma), each a headline — a kicker on top, the main line said, the punchline («ولی پولش هنوز
 * واریز نشده!!») on the dark box when it is said — a hero illustration of who or what the sentence is
 * about, and at most one device: a speech bubble for a status or a quote, a dashed annotation for a
 * rule or a deadline's terms, a countdown calendar tile for days, a stat for a figure, stars as
 * punctuation. Scenes arrive by a circle wipe, a push or a peel, never the same twice running.
 */
class NewsWriter(private val visuals: Visuals = Visuals.Lexicon) {
    /** The last hero drawn: a scene about nobody new keeps it in view. */
    private var last: IllustrationRequest? = null

    /** One scene: its words and time. */
    data class Scene(val first: Int, val last: Int, val start: Double, val end: Double) {
        val words: IntRange get() = first..last
    }

    fun film(n: Narration, seed: Long): ExplainerFilm {
        val art = ArtDirector.news(n, seed)
        val scenes = scenes(n)
        val rng = Rng(seed).fork(SALT)
        var previous = ""
        val name = names(n)
        val chapters = scenes.mapIndexed { i, s ->
            val start = if (i == 0) 0.0 else s.start - EDGE
            val end = scenes.getOrNull(i + 1)?.let { it.start - EDGE } ?: n.duration
            val transition = if (i == 0) "cut" else rng.weighted(TRANSITIONS.filter { it.first != previous })
            previous = transition
            scene(n, s, i, StageWriter.round2(start), StageWriter.round2(maxOf(end, start + MIN_SCENE)), name, transition)
        }
        val captions = if (art.captionStyle == ArtDirector.NO_CAPTIONS) emptyList() else Captions.of(n.words, n.persian)
        return ExplainerFilm(art, chapters, captions, n.language)
    }

    /** Scenes: sentences, a long one split at its comma or its longest pause, a short one joined to the next. */
    fun scenes(n: Narration): List<Scene> {
        val out = mutableListOf<Scene>()
        var first = 0
        for (i in n.words.indices) {
            val ends = i == n.words.lastIndex || n.texts[i].trimEnd().last() in ENDS
            val soft = n.texts[i].trimEnd().last() in SOFT && n.endOf(i) - n.startOf(first) >= MIN_SCENE
            // A long run splits before a content word, never after a preposition («بعد از | تایید»).
            val long = i + 1 <= n.words.lastIndex && n.endOf(i + 1) - n.startOf(first) > MAX_SCENE && n.endOf(i) - n.startOf(first) >= MIN_SCENE &&
                Metaphors.bare(n.texts[i]) !in Said.FUNCTION
            if (ends || soft || long) {
                out += Scene(first, i, n.startOf(first), n.endOf(i))
                first = i + 1
            }
        }
        // A scrap (under a second and a half) joins its neighbour.
        val merged = mutableListOf<Scene>()
        for (s in out) {
            val prev = merged.lastOrNull()
            val questions = prev != null && question(n, prev.last) && question(n, s.last)
            val scrap = prev != null && (s.end - s.start < SCRAP || prev.end - prev.start < SCRAP)
            val join = (scrap || questions) && prev != null && s.end - prev.start <= MAX_SCENE + SCRAP
            if (join) merged[merged.lastIndex] = Scene(prev!!.first, s.last, prev.start, s.end) else merged += s
        }
        return merged
    }

    private fun question(n: Narration, i: Int) = n.texts[i].trimEnd().lastOrNull()?.let { it == '?' || it == '؟' } == true

    /** Names the narration says (for a sign on an object): the understanding's names and Latin words, in the order said. */
    private fun names(n: Narration): Map<Int, String> {
        val said = n.clauses.flatMap { c ->
            val names = Chapters.subjectsOf(n, c)
            c.words.mapNotNull { i -> names.firstOrNull { nm -> nm.split(' ').first().let { Metaphors.bare(it) == Metaphors.bare(n.texts[i]) } }?.let { i to it } }
        }.toMap()
        // «فروشگاه طلای نیلوفر», "Northwind Bank": the name that follows (or precedes) a venue is what its sign says.
        val venues = n.words.indices.filter { i -> VENUES.any { Metaphors.bare(n.texts[i]).startsWith(it) } }.associate { i ->
            val after = (i + 1..minOf(n.words.lastIndex, i + VENUE_REACH)).takeWhile { Phrases.nounish(n.texts[it]) }.lastOrNull()
            i to after?.let { Phrases.clean(n.texts[it]) }
        }.filterValues { it != null && Said.visualOf(it) == null }.mapValues { it.value!! }
        return venues + said
    }

    @Suppress("LongParameterList")
    private fun scene(n: Narration, s: Scene, index: Int, start: Double, end: Double, names: Map<Int, String>, transition: String): Chapter {
        val beats = mutableListOf<Beat>()
        val line = s.words.joinToString(" ") { n.texts[it] }
        val id = "s${index + 1}"
        fun at(word: Int) = maxOf(start + HEAD, n.startOf(word) - LEAD).coerceAtMost(end - MIN_STEP)
        val head = headline(n, s)
        beats += Beat(start + HEAD, end, line, StageOp.Add, "$id-headline", Primitive.Headline, BeatContent(text = head.main, caption = if (head.secondAt == null) head.second else "",
            kicker = head.kicker), "the sentence as a headline")
        val sign = s.words.firstNotNullOfOrNull { names[it] }.orEmpty()
        (Illustrations.request(n, s.words, sign) ?: last)?.also { last = it }?.let { r ->
            beats += Beat(at(s.first) + HERO_AFTER, end, line, StageOp.Add, "$id-hero", Primitive.Hero, BeatContent(illustration = r.encode(), visual = r.query),
                "who or what the sentence is about, drawn")
        }
        head.punchAt?.let { p -> beats += Beat(at(p), end, line, StageOp.Highlight, "$id-headline", content = BeatContent(punch = head.punch),
            metaphor = "the punchline on the dark box as it is said") }
        devices(n, s, id, line).forEach { (word, b) -> beats += b.copy(at = at(word)) }
        if (head.punch.isNotBlank() && beats.none { it.primitive == Primitive.Stars }) {
            beats += Beat(at(head.punchAt ?: s.last) + STARS_AFTER, end, line, StageOp.Add, "$id-stars", Primitive.Stars, metaphor = "accent stars punctuate the punchline")
        }
        // A new idea every second or two: the grey line arrives with its sentence, the hero draws closer in a long stretch.
        head.secondAt?.let { w -> beats += Beat(at(w), end, line, StageOp.Build, "$id-headline", content = BeatContent(caption = head.second), metaphor = "the second line arrives as it is said") }
        repeat(LIVEN) {
            val times = (listOf(start) + beats.map { it.at } + end).sorted()
            val (a, b) = times.zipWithNext().maxByOrNull { (x, y) -> y - x }!!
            if (b - a > IDEA) {
                val target = beats.firstOrNull { it.primitive == Primitive.Hero } ?: beats.first()
                beats += Beat((a + b) / 2, end, line, StageOp.Highlight, target.element, metaphor = "a closer look keeps the scene moving")
            }
        }
        // Readable and in order: each beat until the next, a quarter second apart at least.
        val sorted = beats.sortedBy { it.at }
        var t = start
        val placed = sorted.map { b -> b.copy(at = StageWriter.round2(maxOf(b.at, t).coerceAtMost(end - MIN_STEP))).also { t = it.at + GAP } }
        val timed = placed.mapIndexed { k, b -> b.copy(until = StageWriter.round2(placed.getOrNull(k + 1)?.at?.coerceAtLeast(b.at + GAP) ?: end)) }
        return Chapter(head.kicker.ifBlank { head.main.split(' ').take(2).joinToString(" ") }.ifBlank { "…" }, start, end, index % ArtDirector.ACCENTS, timed, transition)
    }

    /** A headline: the kicker (the subject the sentence opens with), the main line, a grey second line, and the punchline with when it is said. */
    class Headline(val kicker: String, val main: String, val second: String, val punch: String, val punchAt: Int?, val secondAt: Int? = null)

    fun headline(n: Narration, s: Scene): Headline {
        val words = s.words.toList()
        val turn = words.firstOrNull { it > s.first && Metaphors.bare(n.texts[it]) in TURNS }
        val bang = n.texts[s.last].contains('!')
        val punchFrom = turn ?: if (bang) maxOf(s.first + 1, s.last - 2) else null
        val boxed = terms(n, s)
        val body = words.filter { (punchFrom == null || it < punchFrom) && (boxed == null || it < boxed || boxed == s.first) }.filter { Metaphors.bare(n.texts[it]) !in FILLERS }
        // A kicker: the people or thing the sentence opens with («کاربرا», «گزارش»), when the line goes on after it.
        val kickerAt = body.firstOrNull()?.takeIf { body.size >= KICKER_MIN && KICKERS.any { k -> Metaphors.bare(n.texts[it]).startsWith(k) } }
        // Two sentences in one scene: the first is the main line, the second the grey line under it.
        val split = body.firstOrNull { it < s.last && n.texts[it].trimEnd().last() in ENDS }
        fun shown(i: Int) = Phrases.clean(n.texts[i]) + if (question(n, i)) (if (n.persian) "؟" else "?") else ""
        fun loose(i: Int) = Metaphors.bare(n.texts[i]).let { it in TURNS || it in Said.FUNCTION || it in FILLERS }
        val main = body.filter { it != kickerAt && (split == null || it <= split) }.dropWhile(::loose).dropLastWhile(::loose).map(::shown).filter { it.isNotBlank() }
        val rest = if (split == null) emptyList() else body.filter { it > split }.map(::shown).filter { it.isNotBlank() }
        val punch = punchFrom?.let { p -> (p..s.last).take(PUNCH_WORDS).joinToString(" ") { Phrases.clean(n.texts[it]) } + if (bang) "!!" else "" }.orEmpty()
        return Headline(
            kickerAt?.let { Phrases.clean(n.texts[it]) }.orEmpty(),
            main.take(MAIN_WORDS).joinToString(" "),
            (rest.ifEmpty { main.drop(MAIN_WORDS) }).take(SECOND_WORDS).joinToString(" "),
            punch, punchFrom, split?.let { it + 1 }?.takeIf { rest.isNotEmpty() },
        )
    }

    /** At most one device for the scene, with the word it lands on. */
    /** Where the scene's terms start («مهلت برداشت…», "withdrawals will…"): the annotation's first word, or null. */
    private fun terms(n: Narration, s: Scene): Int? =
        RULES.firstNotNullOfOrNull { r -> s.words.firstOrNull { Metaphors.bare(n.texts[it]).startsWith(r) } }

    /** The scene's devices with the words they land on: a note of its terms, then a countdown, a bubble or a figure (two at most). */
    private fun devices(n: Narration, s: Scene, id: String, line: String): List<Pair<Int, Beat>> {
        val keys = s.words.map { it to Metaphors.bare(n.texts[it]) }
        val q = Quantities.sense(s.words.map { n.texts[it] }, s.first).quantities.firstOrNull { it.value > 0 && !it.year }
        val out = mutableListOf<Pair<Int, Beat>>()
        terms(n, s)?.let { at ->
            val words = (at..s.last).takeWhile { q == null || it < q.at }.take(ANNOTATION_WORDS).map { Phrases.clean(n.texts[it]) }
            if (words.size >= 2) out += at to Beat(0.0, 0.0, line, StageOp.Add, "$id-annotation", Primitive.Annotation, BeatContent(text = words.joinToString(" ")),
                "the terms, boxed like a designer's note")
        }
        val days = q?.takeIf { it.kind == Quantities.Kind.Duration || keys.any { (_, k) -> k in DAY_WORDS } }
        if (days != null) {
            val unit = keys.firstOrNull { (_, k) -> k in DAY_WORDS }?.let { (i, _) -> (i..minOf(s.last, i + 1)).joinToString(" ") { Phrases.clean(n.texts[it]) } } ?: days.unit
            out += days.at to Beat(0.0, 0.0, line, StageOp.Add, "$id-calendar", Primitive.Calendar, BeatContent(text = unit, value = days.value), "a countdown tile for the days")
            return out
        }
        if (out.isNotEmpty()) return out
        device(n, s, id, line, keys, q)?.let { out += it }
        return out
    }

    private fun device(n: Narration, s: Scene, id: String, line: String, keys: List<Pair<Int, String>>, q: Quantities.Quantity?): Pair<Int, Beat>? {
        val status = STATUS.firstNotNullOfOrNull { st -> keys.firstOrNull { (_, k) -> k.startsWith(st) } }
        if (status != null) {
            val text = Said.from(n, Clause(0, s.first, s.last, 0.0, 0.0, ""), status.first, BUBBLE_WORDS)
            if (text.isNotBlank()) return status.first to Beat(0.0, 0.0, line, StageOp.Add, "$id-bubble", Primitive.Bubble, BeatContent(text = "$text…"),
                "what they are told or wait for, in a speech bubble")
        }
        if (q != null) {
            return q.at to Beat(0.0, 0.0, line, StageOp.Add, "$id-stat", Primitive.Stat, BeatContent(value = q.value, unit = q.unit), "the figure, counted up")
        }
        return null
    }

    private companion object {
        const val SALT = 23
        const val EDGE = 0.12
        const val HEAD = 0.05
        const val LEAD = 0.1
        const val HERO_AFTER = 0.35
        const val STARS_AFTER = 0.5
        const val GAP = 0.25
        const val MIN_STEP = 0.3
        const val MIN_SCENE = 1.6
        const val MAX_SCENE = 4.2
        const val SCRAP = 2.0
        const val KICKER_MIN = 3
        const val MAIN_WORDS = 5
        const val SECOND_WORDS = 3
        const val PUNCH_WORDS = 5
        const val ANNOTATION_WORDS = 6
        const val BUBBLE_WORDS = 3
        const val ENDS = ".!?؟…"
        const val IDEA = 2.0
        const val LIVEN = 3
        const val SOFT = "،,;؛"
        val TRANSITIONS = listOf("circle" to 0.4f, "push" to 0.35f, "peel" to 0.25f)
        val TURNS = setOf("ولی", "اما", "ولیکن", "but", "however", "yet")
        val FILLERS = setOf("خب", "یعنی", "حالا", "اینکه", "so", "well", "now", "و", "and")
        val DAY_WORDS = setOf("روز", "روزکاری", "روزه", "ساعت", "هفته", "day", "days", "hours", "weeks")
        val RULES = listOf("مهلت", "شرایط", "قانون", "طبق", "deadline", "withdrawal", "terms", "policy", "according")
        val STATUS = listOf("انتظار", "هنوز", "تسویه", "waiting", "pending", "still", "frozen", "میگن", "گفته", "says", "said")
        val KICKERS = listOf("کاربر", "مردم", "مشتری", "گزارش", "خبر", "شرکت", "پلیس", "دولت", "users", "customers", "people", "report", "police", "company")
        val VENUES = listOf("فروشگاه", "شرکت", "صرافی", "بانک", "برند", "پلتفرم", "store", "shop", "company", "exchange", "platform")
        const val VENUE_REACH = 3
    }
}
