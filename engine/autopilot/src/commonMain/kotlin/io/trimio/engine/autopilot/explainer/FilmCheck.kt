package io.trimio.engine.autopilot.explainer

import io.trimio.core.model.text.Numerals
import io.trimio.engine.autopilot.PersianSpelling
import io.trimio.engine.autopilot.ScreenSpelling
import io.trimio.engine.motion.explainer.Beat
import io.trimio.engine.motion.explainer.BeatContent
import io.trimio.engine.motion.explainer.Chapter
import io.trimio.engine.motion.explainer.ExplainerFilm
import io.trimio.engine.motion.explainer.Item
import io.trimio.engine.motion.explainer.Primitive
import io.trimio.engine.motion.explainer.StageOp

/**
 * The film's checks, and their repair. Times sit inside the narration, in order, chapters end to
 * end; an element is worked on only after it is put on stage (and before it is cleared); a stage
 * holds at most [MAX_LIVE] elements; each op suits its picture (a meter fills, tiles tick, any
 * picture highlights); on-screen text is short; a Persian film writes Persian (names said in
 * Latin keep their spelling) with Persian digits, and every Persian word is one the viewer heard,
 * the brief wrote or the lexicon knows; no company is named unless the narration names it.
 *
 * [problems] says what is wrong; [repair] returns a film with none of it (whatever a director,
 * model or rules, wrote).
 */
class FilmCheck(private val n: Narration, private val lexicon: PersianSpelling? = n.lexicon) {

    enum class Kind { Time, Reference, Crowded, Op, Long, Script, Spelling, Brand, Empty, Heading, Accent, Value }

    data class Problem(val kind: Kind, val at: Double, val detail: String)

    private val screen: ScreenSpelling? = lexicon?.let { ScreenSpelling(it, n.texts, n.prompt) }
    private val promptLatin: Set<String> = n.prompt.split(Regex("[^A-Za-z0-9.+-]+")).filter { it.length >= 2 }.map { it.lowercase() }.toSet()

    fun problems(f: ExplainerFilm): List<Problem> = buildList {
        addAll(times(f))
        for ((ci, ch) in f.chapters.withIndex()) {
            if (ch.title.isBlank() || words(ch.title) > MAX_TITLE_WORDS) add(Problem(Kind.Heading, ch.start, "chapter heading «${ch.title}»"))
            if (ci > 0 && ch.accent == f.chapters[ci - 1].accent) add(Problem(Kind.Accent, ch.start, "same accent as the chapter before"))
            addAll(stage(ch))
            for (b in ch.beats) addAll(content(b))
            texts(ch.title).let { if (it.isNotEmpty()) addAll(script(ch.start, it)) }
        }
    }

    /** Problems of the film's timing: inside the narration, in order, chapters end to end. */
    private fun times(f: ExplainerFilm): List<Problem> = buildList {
        var t = 0.0
        for (ch in f.chapters) {
            if (ch.start < t - EPS || ch.end <= ch.start || ch.end > n.duration + EPS) add(Problem(Kind.Time, ch.start, "chapter «${ch.title}» ${ch.start}–${ch.end}"))
            var b0 = ch.start
            for (b in ch.beats) {
                if (b.at < b0 - EPS || b.at < ch.start - EPS || b.until > ch.end + EPS || b.until <= b.at) add(Problem(Kind.Time, b.at, "beat ${b.op} ${b.element} ${b.at}–${b.until}"))
                b0 = b.at
            }
            t = ch.end
        }
        if (f.chapters.isEmpty()) add(Problem(Kind.Empty, 0.0, "no chapters"))
    }

    /** What is on stage, beat by beat: references, crowding, ops that fit their picture. */
    private fun stage(ch: Chapter): List<Problem> = buildList {
        val live = linkedMapOf<String, Primitive>()
        for (b in ch.beats) {
            val p = live[b.element]
            when {
                b.op == StageOp.Add && p != null -> add(Problem(Kind.Reference, b.at, "${b.element} added twice"))
                b.op == StageOp.Add && b.primitive == null -> add(Problem(Kind.Reference, b.at, "${b.element} added without a picture"))
                b.op != StageOp.Add && p == null -> add(Problem(Kind.Reference, b.at, "${b.op} on ${b.element} before it is on stage"))
                p != null && !fits(b.op, b.primitive ?: p) -> add(Problem(Kind.Op, b.at, "${b.op} does not suit ${p.name}"))
            }
            when (b.op) {
                StageOp.Clear -> live.remove(b.element)
                StageOp.Add, StageOp.Replace, StageOp.Morph -> b.primitive?.let { live[b.element] = it }
                else -> Unit
            }
            if (live.size > MAX_LIVE) add(Problem(Kind.Crowded, b.at, "${live.size} elements on stage"))
        }
    }

    private fun content(b: Beat): List<Problem> = buildList {
        val c = b.content
        if (words(c.text) > MAX_TEXT_WORDS || c.text.length > MAX_TEXT_CHARS) add(Problem(Kind.Long, b.at, "text «${c.text}»"))
        if (words(c.caption) > MAX_CAPTION_WORDS) add(Problem(Kind.Long, b.at, "caption «${c.caption}»"))
        if (c.items.size > MAX_ITEMS || c.items.any { words(it.label) > MAX_ITEM_WORDS || it.label.length > MAX_ITEM_CHARS }) add(Problem(Kind.Long, b.at, "items ${c.items.map { it.label }}"))
        if (b.op in CREATES && b.primitive in WORDS_ONLY && c.text.isBlank()) add(Problem(Kind.Empty, b.at, "${b.primitive} with no words"))
        if (b.primitive in UNIT_VALUES && (c.value ?: 0.0) !in 0.0..1.0) add(Problem(Kind.Value, b.at, "${b.primitive} at ${c.value}"))
        addAll(script(b.at, texts(c)))
    }

    /** Script, spelling and names of the texts shown at [at]. */
    private fun script(at: Double, texts: List<String>): List<Problem> = buildList {
        for (s in texts) {
            for (w in s.split(' ')) {
                val core = core(w)
                if (core.isEmpty()) continue
                when {
                    brand(core) && !said(core) -> add(Problem(Kind.Brand, at, "«$core» is not said"))
                    n.persian && latin(core) && !said(core) -> add(Problem(Kind.Script, at, "Latin «$core» on a Persian film"))
                    n.persian && core.any { it in '0'..'9' } -> add(Problem(Kind.Script, at, "Latin digits «$core»"))
                }
            }
            val checked = screen?.text(s) {}
            if (n.persian && checked != null && checked != s.trim()) add(Problem(Kind.Spelling, at, "«$s» → «$checked»"))
        }
    }

    // ---- repair ------------------------------------------------------------------------------

    /** [f] with every problem repaired: times, references, crowding, ops, lengths, script, spelling, names. */
    fun repair(f: ExplainerFilm, log: (String) -> Unit = {}): ExplainerFilm {
        val chapters = timed(f.chapters.filter { it.beats.isNotEmpty() || it.title.isNotBlank() })
        val fixed = chapters.mapIndexed { i, ch ->
            val accent = if (i > 0 && ch.accent == chapters[i - 1].accent) (ch.accent + 1) % ArtDirector.ACCENTS else ch.accent
            val title = cleanTitle(ch, log)
            ch.copy(title = title, accent = accent, beats = staged(ch.beats.mapNotNull { b -> cleaned(b, log) }, ch, log))
        }
        // An accent bumped forward may now equal the next one: walk once more.
        val accents = fixed.map { it.accent }.toMutableList()
        for (i in 1 until accents.size) if (accents[i] == accents[i - 1]) accents[i] = (accents[i] + 1) % ArtDirector.ACCENTS
        val chaptersOut = fixed.mapIndexed { i, c -> c.copy(accent = accents[i]) }
        return f.copy(chapters = chaptersOut, captions = f.captions.filter { it.end > it.start && it.start < n.duration + EPS }, language = n.language)
    }

    /** Chapters in order, end to end inside the narration; each one's beats inside it, in order, each lasting until the next. */
    private fun timed(chapters: List<Chapter>): List<Chapter> {
        val sorted = chapters.sortedBy { it.start }
        return sorted.mapIndexed { i, ch ->
            val start = if (i == 0) 0.0 else sorted[i].start.coerceIn(0.0, n.duration)
            val end = (sorted.getOrNull(i + 1)?.start ?: n.duration).coerceIn(start + EPS, maxOf(n.duration, start + EPS))
            val beats = ch.beats.map { b -> b.copy(at = b.at.coerceIn(start, maxOf(start, end - MIN_BEAT))) }.sortedBy { it.at }
            val out = beats.mapIndexed { k, b ->
                val until = (beats.getOrNull(k + 1)?.at ?: end).coerceAtMost(end)
                b.copy(at = round(b.at), until = round(maxOf(until, minOf(end, b.at + TOGETHER))))
            }
            ch.copy(start = round(start), end = round(end), beats = out)
        }
    }

    /** References, crowding and ops repaired, walking the stage beat by beat. */
    private fun staged(beats: List<Beat>, ch: Chapter, log: (String) -> Unit): List<Beat> {
        val live = linkedMapOf<String, Primitive>()
        val counts = mutableMapOf<String, Int>()
        val out = mutableListOf<Beat>()
        for (raw in beats) {
            val b = reference(raw, live, log) ?: continue
            val p = b.primitive ?: live[b.element]
            val fitted = if (p != null && !fits(b.op, p)) b.copy(op = StageOp.Highlight).also { log("repair: ${b.op} → highlight on ${p.name} at ${b.at}s") } else b
            val count = counts[b.element] ?: 0
            val picks = fitted.content.picks.filter { it >= 0 && (count == 0 || it < maxOf(count, fitted.content.items.size)) }
            val final = if (picks != fitted.content.picks) fitted.copy(content = fitted.content.copy(picks = picks)) else fitted
            if (final.op == StageOp.Add && live.size >= MAX_LIVE) {
                val old = live.keys.first()
                live.remove(old)
                out += Beat(final.at, final.at, final.line, StageOp.Clear, old, metaphor = "makes room")
                log("repair: cleared $old at ${final.at}s to keep ${MAX_LIVE} on stage")
            }
            when (final.op) {
                StageOp.Clear -> live.remove(final.element)
                StageOp.Add, StageOp.Replace, StageOp.Morph -> {
                    live[final.element] = final.primitive ?: live[final.element] ?: Primitive.Kicker
                    counts[final.element] = final.content.items.size
                }
                StageOp.Build -> counts[final.element] = count + final.content.items.size
                else -> Unit
            }
            out += final
        }
        // A clear inserted at a beat's time lasts until that beat.
        return out.mapIndexed { k, b -> if (b.until <= b.at) b.copy(until = minOf(ch.end, (out.getOrNull(k + 1)?.at ?: ch.end).coerceAtLeast(b.at + TOGETHER))) else b }
    }

    /** The beat with a sound reference: an op on nothing becomes an add when it carries a picture, else it goes. */
    private fun reference(b: Beat, live: Map<String, Primitive>, log: (String) -> Unit): Beat? {
        val known = b.element in live
        return when {
            b.op == StageOp.Add && b.primitive == null -> null.also { log("repair: dropped add of ${b.element} without a picture") }
            b.op == StageOp.Add && known -> b.copy(op = if (b.primitive == live[b.element]) StageOp.Highlight else StageOp.Replace)
            b.op != StageOp.Add && !known && b.primitive != null && b.op != StageOp.Clear -> b.copy(op = StageOp.Add)
            b.op != StageOp.Add && !known -> null.also { log("repair: dropped ${b.op} on ${b.element} (not on stage)") }
            else -> b
        }
    }

    /** The beat's words short and in the film's script; a picture that is only words and has none left goes. */
    private fun cleaned(b: Beat, log: (String) -> Unit): Beat? {
        val c = b.content
        val items = c.items.take(MAX_ITEMS).map { it.copy(label = text(it.label, MAX_ITEM_WORDS, log), visual = visual(it.visual)) }
        val value = c.value?.let { v -> if (b.primitive in UNIT_VALUES && v > 1.0 && v <= PERCENT) v / PERCENT else v }?.let { v -> if (b.primitive in UNIT_VALUES) v.coerceIn(0.0, 1.0) else v }
        val content = BeatContent(
            text = text(c.text, MAX_TEXT_WORDS, log), caption = text(c.caption, MAX_CAPTION_WORDS, log), items = items, value = value,
            unit = text(c.unit, 1, log), picks = c.picks.distinct().sorted(), visual = visual(c.visual),
        )
        if (b.op in CREATES && b.primitive in WORDS_ONLY && content.text.isBlank()) {
            log("repair: dropped ${b.primitive} with no words left at ${b.at}s")
            return null
        }
        return b.copy(content = content, line = b.line.take(MAX_LINE), metaphor = b.metaphor.take(MAX_LINE))
    }

    private fun cleanTitle(ch: Chapter, log: (String) -> Unit): String {
        val t = text(ch.title, MAX_TITLE_WORDS, log)
        return t.ifBlank { ch.beats.firstNotNullOfOrNull { b -> b.content.text.takeIf { it.isNotBlank() }?.let { text(it, MAX_TITLE_WORDS, log) } }.orEmpty().ifBlank { "…" } }
    }

    /** [s] in at most [max] words, in the film's script, spelled as heard, with no unsaid company name. */
    private fun text(s: String, max: Int, log: (String) -> Unit): String {
        if (s.isBlank()) return ""
        val kept = s.trim().split(Regex("\\s+")).filter { w ->
            val core = core(w)
            val ok = core.isEmpty() || said(core) || !(brand(core) || (n.persian && latin(core)))
            if (!ok) log("repair: «$core» left out (not said)")
            ok
        }
        val short = kept.take(max).joinToString(" ").take(MAX_TEXT_CHARS).trim()
        val digits = if (n.persian) Numerals.toPersian(short) else Numerals.toLatin(short)
        return (screen?.takeIf { n.persian }?.text(digits) { log("repair: $it") } ?: digits).trim()
    }

    /** A picture query: English words only, a few of them, no company. */
    private fun visual(s: String): String =
        s.split(Regex("\\s+")).filter { w -> w.isNotBlank() && w.all { it.code < ASCII } && !brand(core(w)) }.take(MAX_VISUAL_WORDS).joinToString(" ").lowercase()

    private fun texts(c: BeatContent): List<String> = listOf(c.text, c.caption) + c.items.map { it.label }
    private fun texts(s: String): List<String> = listOf(s).filter { it.isNotBlank() }

    private fun said(core: String): Boolean {
        val b = Metaphors.bare(core)
        return b in n.keys.toSet() || core.lowercase() in n.spokenLatin || core.lowercase() in promptLatin
    }

    private fun brand(core: String): Boolean = Metaphors.bare(core) in BRANDS

    companion object {
        const val MAX_LIVE = StageWriter.MAX_LIVE + 1
        const val MAX_TEXT_WORDS = 6
        const val MAX_TEXT_CHARS = 40
        const val MAX_CAPTION_WORDS = 4
        const val MAX_ITEMS = 10
        const val MAX_ITEM_WORDS = 3
        const val MAX_ITEM_CHARS = 22
        const val MAX_TITLE_WORDS = 3
        private const val MAX_VISUAL_WORDS = 3
        private const val MAX_LINE = 300
        private const val PERCENT = 100.0
        private const val ASCII = 128
        const val MIN_BEAT = 0.3
        private const val TOGETHER = 0.05
        private const val EPS = 1e-3

        val CREATES = setOf(StageOp.Add, StageOp.Replace, StageOp.Morph)
        val WORDS_ONLY = setOf(Primitive.Heading, Primitive.Kicker, Primitive.BigWord, Primitive.Quote, Primitive.Note)
        val UNIT_VALUES = setOf(Primitive.Meter, Primitive.Ring)

        private val BUILDS = Families.ITEMS + setOf(Primitive.Profile, Primitive.Chat, Primitive.Bars, Primitive.Versus, Primitive.BeforeAfter, Primitive.Scale, Primitive.Map)
        private val CHECKS = setOf(
            Primitive.TaskTiles, Primitive.Checklist, Primitive.Steps, Primitive.Branches, Primitive.Blocks, Primitive.Timeline, Primitive.IconTiles,
            Primitive.Chips, Primitive.Grid, Primitive.Dots, Primitive.Flow, Primitive.Ladder, Primitive.Funnel, Primitive.Cycle,
        )
        private val FILLS = setOf(Primitive.Meter, Primitive.Bars, Primitive.Ring, Primitive.Stat, Primitive.Dots, Primitive.Scale, Primitive.Clock, Primitive.Ladder)

        /** Whether [op] suits an element drawn as [p]. */
        fun fits(op: StageOp, p: Primitive): Boolean = when (op) {
            StageOp.Build -> p in BUILDS
            StageOp.Check -> p in CHECKS
            StageOp.Fill -> p in FILLS
            else -> true
        }

        /** Companies and products a film must not name unless the narration does (Latin and Persian spellings). */
        val BRANDS: Set<String> = setOf(
            "google", "گوگل", "apple", "اپل", "microsoft", "مایکروسافت", "openai", "chatgpt", "چتجیپیتی", "anthropic", "انتروپیک", "claude", "کلود", "کلاد",
            "gemini", "جمینای", "meta", "متا", "facebook", "فیسبوک", "instagram", "اینستاگرام", "amazon", "آمازون", "tesla", "تسلا", "samsung", "سامسونگ",
            "nvidia", "انویدیا", "youtube", "یوتیوب", "tiktok", "تیکتاک", "telegram", "تلگرام", "whatsapp", "واتساپ", "netflix", "نتفلیکس", "twitter", "توییتر",
            "nike", "نایکی", "adidas", "آدیداس", "coca-cola", "کوکاکولا", "mcdonalds", "bitcoin", "بیتکوین", "binance", "بایننس", "nobitex", "نوبیتکس",
            "haiku", "sonnet", "opus", "fable", "gpt", "copilot", "midjourney", "deepseek", "grok", "llama", "mistral", "qwen",
        )

        fun core(w: String) = w.trim { !it.isLetterOrDigit() && it != '‌' && it != '.' && it != '%' && it != '٪' }.trim('.')
        fun latin(w: String) = w.any { it in 'A'..'Z' || it in 'a'..'z' }
        fun words(s: String) = s.split(Regex("\\s+")).count { it.isNotBlank() }
        fun round(v: Double) = kotlin.math.round(v * 100.0) / 100.0
    }
}
