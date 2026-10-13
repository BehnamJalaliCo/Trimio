package io.trimio.engine.autopilot.explainer

import io.trimio.engine.autopilot.PersianSpelling
import io.trimio.engine.autopilot.Proofreader

/**
 * The explainer film's spelling gate, the same rule the edit's screen gate keeps: every Persian
 * word on screen is one the viewer heard (as spelled), one the brief writes, or one the lexicon
 * knows. A word that is none of these is put back to the heard word it sounds like (the same
 * sound key, or one slip away when only one heard word is), or left out. Latin names and numbers
 * pass untouched.
 */
class FilmSpelling(private val lexicon: PersianSpelling, spoken: List<String>, prompt: String) {
    private val heard: Map<String, String> = spoken.map(::core).filter { it.isNotEmpty() }.associateBy(::plain)
    private val brief: Set<String> = prompt.split(SEPARATORS).map(::core).filter { it.isNotEmpty() }.map(::plain).toSet()
    private val bySound: Map<String, String> = heard.values.associateBy { Proofreader.key(it) }

    /** [s] with every Persian word checked; [log] hears each change. "" when nothing is left. */
    fun text(s: String, log: (String) -> Unit = {}): String = s.split(' ').mapNotNull { token ->
        val c = core(token)
        when {
            c.isEmpty() || !persian(c) || known(c) -> token
            else -> soundsLike(c)?.let { fix -> token.replace(c, fix) }
                .also { log("spelling: «$c» → ${it?.let { f -> "«${core(f)}»" } ?: "left out"}") }
        }
    }.joinToString(" ").trim()

    private fun soundsLike(c: String): String? {
        val k = Proofreader.key(c)
        bySound[k]?.let { return it }
        return bySound.filterKeys { k.length >= MIN_SLIP && distance(it, k) == 1 }.values.singleOrNull()
    }

    private fun known(c: String) = plain(c) in heard || plain(c) in brief || lexicon.count(c) > 0

    private companion object {
        val SEPARATORS = Regex("[\\s,،.!?؟:;()\\[\\]{}«»\"“”/]+")
        const val ZWNJ = '‌'
        const val MIN_SLIP = 3

        fun core(w: String) = w.trim { !it.isLetterOrDigit() && it != ZWNJ }.trim(ZWNJ)
        fun plain(w: String) = w.replace(ZWNJ.toString(), "").replace("ٔ", "")
        fun persian(w: String) = w.any { it in 'ؠ'..'ۿ' && it.isLetter() }

        fun distance(a: String, b: String): Int {
            var prev = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val cur = IntArray(b.length + 1)
                cur[0] = i
                for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = cur
            }
            return prev[b.length]
        }
    }
}
