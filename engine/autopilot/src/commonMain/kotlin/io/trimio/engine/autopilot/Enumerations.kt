package io.trimio.engine.autopilot

import io.trimio.engine.motion.score.NumberWords

/**
 * Lists people say out loud: «مواد لازم: نیم کیلو سبزی، پنج تا تخم‌مرغ، …», «سه تا علامتش رو بگم:
 * خستگی، درد استخون و ریزش مو», "parmesan, black pepper, parsley", and steps counted off one per
 * line («حرکت اول، پلانک», "Number two, Control plus T"). Items are spans of the spoken words, so
 * a list on screen reads exactly what was said, row by row as it is said.
 */
object Enumerations {

    /** A list said in one breath: its items as word spans, starting on line [line]. */
    data class Said(val line: Int, val items: List<IntRange>)

    /** Every list said in the piece (at most one per line), by the line it starts on. */
    fun lists(texts: List<String>, lines: List<Lines.Line>): Map<Int, Said> {
        val out = mutableMapOf<Int, Said>()
        for ((k, line) in lines.withIndex()) {
            val said = listAt(texts, lines, k) ?: continue
            if (out.values.none { s -> s.items.any { it.first in line.range } }) out[k] = Said(k, said)
        }
        return out
    }

    /**
     * A step counted off in [line] («حرکت اول، پلانک», «اشتباه دوم», "Number one: Control plus E"):
     * the words that name it, as said (the counter and the thing), or null.
     */
    fun step(texts: List<String>, line: Lines.Line): IntRange? {
        val n = line.range.map { Phrases.key(texts[it]) }
        for ((j, w) in n.withIndex()) {
            val i = line.first + j
            val persian = w in ORDINALS && j > 0 && n[j - 1] in STEP_NOUNS
            val english = (w in ENGLISH_ORDINALS && j > 0 && n[j - 1] == "number") || (w in ENGLISH_FIRST && j == 0)
            if (!persian && !english) continue
            val from = if (persian || n.getOrNull(j - 1) == "number") i - 1 else i
            // The thing named after the counter, up to two words, never a verb; a counter alone is no title.
            var end = i
            fun names(j: Int) = j <= line.last && Phrases.nounish(texts[j]) && NumberWords.at(texts, j) == null
            while (end - i < 2 && names(end + 1)) end++
            return (from..end).takeIf { end > i }
        }
        return null
    }

    private fun listAt(texts: List<String>, lines: List<Lines.Line>, k: Int): List<IntRange>? {
        val line = lines[k]
        val stop = lines.getOrNull(k + 1)?.last ?: line.last
        // The colon may end the line before («مواد لازم: | نیم کیلو سبزی، …»).
        val before = lines.getOrNull(k - 1)?.last?.takeIf { texts[it].endsWith(':') && Phrases.key(texts[it]) !in ENGLISH_ORDINALS + ORDINALS }
        // A colon, or the words that announce a list when a pause or a respelling lost the colon («مواد لازم»، "ingredients").
        val colon = line.range.firstOrNull { (texts[it].endsWith(':') && Phrases.key(texts[it]) !in ENGLISH_ORDINALS + ORDINALS) || Phrases.key(texts[it]) in CUES } ?: before
        val from = colon?.plus(1) ?: line.first
        if (from > stop) return null
        // The list runs to the end of its sentence (within this line and the next).
        val end = (from..stop).firstOrNull { texts[it].last() in ".!?؟…" } ?: if (colon != null) stop else line.last
        val items = split(texts, from, end)
        val things = items.filter { r -> r.any { Phrases.nounish(texts[it]) && NumberWords.at(texts, it) == null } }
        // Speech is not a list: no verb anywhere in it, no part too long to be an item.
        val clean = (from..end).none { Phrases.verb(Phrases.key(texts[it])) } && items.size == parts(texts, from, end)
        return when {
            colon != null && items.size >= 2 && things.size >= 2 && clean -> items
            // Without a colon, a list is things named in a row ("parmesan, black pepper, parsley"), each opening on the thing or its amount.
            colon == null && items.size >= MIN_PLAIN && things.size == items.size && clean && items.all { r -> opens(texts, r) } -> items
            else -> null
        }
    }

    /** An item that opens on a thing or an amount ("parsley", "lots of pepper", «یه قاشق آرد»), not "so", "They're", «که». */
    private fun opens(texts: List<String>, r: IntRange): Boolean {
        val w = texts[r.first]
        return r.count() <= MAX_PLAIN_WORDS && (Phrases.nounish(w) || NumberWords.at(texts, r.first) != null || Phrases.key(w) in AMOUNTS)
    }

    /** How many comma-separated parts [from]..[to] has (a part too long to be an item is not kept by [split]). */
    private fun parts(texts: List<String>, from: Int, to: Int): Int = split(texts, from, to, keepLong = true).size

    /** Items at commas, and at «و»/"and" between short ones («نمک و فلفل و زردچوبه»), never inside a number («نود و هفت»). */
    private fun split(texts: List<String>, from: Int, to: Int, keepLong: Boolean = false): List<IntRange> {
        val parts = mutableListOf<IntRange>()
        var a = from
        for (i in from..to) {
            if (texts[i].last() in ",،;؛.!?؟…" || i == to) {
                if (i >= a) parts += a..i
                a = i + 1
            }
        }
        return parts.flatMap { p ->
            val joins = p.filter { Phrases.key(texts[it]) in AND && NumberWords.at(texts, it + 1) == null }
            val pieces = mutableListOf<IntRange>()
            var s = p.first
            for (j in joins) {
                pieces += s until j
                s = j + 1
            }
            pieces += s..p.last
            val short = pieces.all { !it.isEmpty() && it.count() <= SHORT_PART }
            if (joins.isNotEmpty() && short) pieces else listOf(p)
        }.map { r -> (r.first..r.last).dropWhile { Phrases.key(texts[it]) in AND }.let { if (it.isEmpty()) IntRange.EMPTY else it.first()..it.last() } }
            .filter { !it.isEmpty() && (keepLong || it.count() <= MAX_ITEM_WORDS) }
    }

    private val AND = setOf("و", "and", "&")
    private val CUES = setOf("لازم", "ingredients", "علامتش", "علامتاش", "علائمش", "symptoms")
    private val ORDINALS = setOf("اول", "دوم", "سوم", "چهارم", "پنجم", "آخر")
    private val STEP_NOUNS = setOf("حرکت", "اشتباه", "نکته", "نکتهی", "نکتهٔ", "قدم", "مرحله", "روش", "دلیل", "علامت", "ترفند", "سوال", "قانون")
    private val ENGLISH_ORDINALS = setOf("one", "two", "three", "four", "five")
    private val ENGLISH_FIRST = setOf("first", "second", "third", "fourth", "fifth", "finally")
    private const val MIN_PLAIN = 3
    private const val MAX_PLAIN_WORDS = 4
    private val AMOUNTS = setOf("lots", "a", "some", "handful", "pinch", "cup", "یه", "یک", "نیم", "دو", "سه", "کمی", "یهکم")
    private const val SHORT_PART = 2
    private const val MAX_ITEM_WORDS = 5
}
