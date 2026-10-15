package io.trimio.engine.autopilot

import io.trimio.core.model.text.Numerals
import io.trimio.engine.motion.score.NumberWords

/**
 * A director's headline in its own words. The model rewrites a heard line as a short written title
 * («ده تا دونه در روز کلن سیگنال می‌ده» → «ده سیگنال در روز», «پنجاه دولار کف ورودیمونه» → «ورود با
 * پنجاه دلار»), which reads far better than any piece cut out of the speech; it is shown when it says
 * only what was said. Every word that carries meaning stands for a word said in the line or the lines
 * beside it, or written in the brief: the same stem («سیگنال‌ها» for «سیگنال», «ورود» for «ورودیمونه»),
 * a spoken form or a recogniser's slip of one letter («کدوم» for «کدام», «شارج» heard for «شارژ»), or
 * the same number («۵۰» for «پنجاه»); at least one of them is the line's own. A word nobody said
 * («اطمینان», «بی‌نظیر», «ضرر») is the model's, and the title is not shown.
 */
internal object Headlines {

    /** Whether [title] says only what [line], the lines [around] it or the [brief] say. */
    fun grounded(title: String, line: List<String>, around: List<String>, brief: List<String>): Boolean {
        val words = title.split(' ').map(::bare).filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        val numbers = NumberWords.findAll(words)
        val numbered = numbers.flatMap { it.start until it.start + it.count }.toSet()
        val own = NumberWords.findAll(line).map { it.value }.toSet()
        val heard = own + NumberWords.findAll(around).map { it.value }
        if (numbers.any { it.value !in heard }) return false
        val content = words.filterIndexed { i, w -> i !in numbered && key(w) !in FUNCTION }.map(::key)
        val lineKeys = line.map(::key).filter { it.isNotEmpty() }
        val near = lineKeys + (around + brief).map(::key).filter { it.isNotEmpty() }
        if (content.any { w -> near.none { same(w, it) } }) return false
        // The title is this line's, not the next one's.
        return numbers.any { it.value in own } || content.any { w -> lineKeys.any { same(w, it) } }
    }

    /** A headline is two to six words; a single one only when it is a name written in Latin letters. */
    fun fits(title: String): Boolean {
        val words = title.split(' ').filter { it.isNotBlank() }
        return words.size in MIN_WORDS..MAX_WORDS || (words.size == 1 && words[0].none { it in '؀'..'ۿ' } && words[0].any { it.isLetter() })
    }

    /** [title] as it is shown: a number said in words in figures («پنجاه دلار» → «۵۰ دلار»), in the piece's digits. */
    fun written(title: String, persian: Boolean): String {
        val words = title.split(' ').filter { it.isNotBlank() }
        val numbers = NumberWords.findAll(words).filter { n -> !n.year && n.value < MAX_FIGURE && (n.start until n.start + n.count).any { i -> words[i].none(Char::isDigit) } }
        if (numbers.isEmpty()) return title
        val out = mutableListOf<String>()
        var i = 0
        while (i < words.size) {
            val n = numbers.firstOrNull { it.start == i }
            if (n == null) { out += words[i]; i++; continue }
            val whole = n.value == kotlin.math.floor(n.value)
            val figure = if (whole) n.value.toLong().toString() else n.value.toString()
            out += (if (persian) Numerals.toPersian(figure) else figure) + if (n.percent) "٪" else ""
            i += n.count
        }
        return out.joinToString(" ")
    }

    /** The same word apart from its endings, its spoken form, or a slip of one letter. */
    private fun same(a: String, b: String): Boolean {
        if (a == b) return true
        val stem = a.zip(b).takeWhile { (x, y) -> x == y }.size
        // «سیگنال» and «سیگنالها», «ورود» and «ورودیمونه», «کاربران» and «کاربراشم»: a shared stem, endings of a few letters.
        val ending = if (stem > MIN_STEM) LONG_ENDING else SHORT_ENDING
        if (stem >= MIN_STEM && a.length - stem <= ending && b.length - stem <= ending) return true
        return minOf(a.length, b.length) >= MIN_SLIP && distance(a, b) <= 1
    }

    private fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    private fun bare(w: String) = w.trim { !it.isLetterOrDigit() && it != '‌' }

    private fun key(w: String) = NumberWords.normalize(bare(w)).replace("ٔ", "").replace('ة', 'ه')

    /** Words that only join the others: a title may use them freely. */
    private val FUNCTION = setOf(
        "در", "با", "و", "از", "به", "برای", "را", "رو", "که", "تا", "یا", "این", "آن", "اون", "ما", "من", "شما", "او", "است", "هست", "هستیم",
        "هستند", "هستن", "یک", "یه", "the", "a", "an", "of", "to", "in", "on", "for", "and", "is", "are", "with", "your", "our",
    )

    private const val MIN_WORDS = 2
    private const val MAX_WORDS = 6
    private const val MIN_STEM = 3
    private const val SHORT_ENDING = 3
    private const val LONG_ENDING = 5
    private const val MIN_SLIP = 4
    private const val MAX_FIGURE = 10_000.0
}
