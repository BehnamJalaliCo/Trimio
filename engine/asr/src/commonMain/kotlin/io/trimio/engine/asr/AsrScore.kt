package io.trimio.engine.asr

import io.trimio.core.model.text.TextKey

/**
 * Speech recognition quality against a gold transcript, the way Persian is fairly compared:
 * letters folded (ي/ى→ی, ك→ک, digits unified, diacritics and tatweel dropped), half-spaces joined
 * («می‌کنه» = «میکنه»), punctuation ignored, Latin lower-cased. [glossary] terms (names, jargon,
 * English brands) are counted separately: those are the errors creators notice first.
 */
data class AsrScore(
    val words: Int,
    val substitutions: Int,
    val deletions: Int,
    val insertions: Int,
    /** Glossary terms in the gold text, and how many the hypothesis got exactly. */
    val terms: Int,
    val termsRight: Int,
    val missedTerms: List<String>,
) {
    val wer: Float get() = if (words == 0) 0f else (substitutions + deletions + insertions).toFloat() / words
    val termErrors: Int get() = terms - termsRight

    fun summary(): String =
        "WER ${(wer * 1000).toInt() / 10f}% (S $substitutions, D $deletions, I $insertions of $words); glossary $termsRight/$terms" +
            if (missedTerms.isEmpty()) "" else " missed ${missedTerms.joinToString()}"

    companion object {
        fun of(gold: String, hypothesis: String, glossary: List<String> = emptyList()): AsrScore {
            val g = tokens(gold)
            val h = tokens(hypothesis)
            val (s, d, i) = edits(g, h)
            val joinedHyp = h.joinToString(" ")
            val joinedGold = g.joinToString(" ")
            // (term, wanted, got) for every glossary term the gold text uses.
            val counts = glossary.mapNotNull { term ->
                val t = tokens(term).joinToString(" ")
                val want = if (t.isEmpty()) 0 else occurrences(joinedGold, t)
                if (want == 0) null else Triple(term, want, minOf(want, occurrences(joinedHyp, t)))
            }
            return AsrScore(
                g.size, s, d, i, counts.sumOf { it.second }, counts.sumOf { it.third },
                counts.filter { it.third < it.second }.map { it.first },
            )
        }

        /** Normalised word tokens. */
        fun tokens(text: String): List<String> =
            text.split(' ', '\n', '\t', ' ').map { w -> TextKey.of(w.lowercase()).filter { it.isLetterOrDigit() } }.filter { it.isNotEmpty() }

        private fun occurrences(text: String, term: String): Int {
            var n = 0
            var from = 0
            val padded = " $text "
            val needle = " $term "
            while (true) {
                val at = padded.indexOf(needle, from)
                if (at < 0) return n
                n++
                from = at + needle.length - 1
            }
        }

        /** Levenshtein alignment of word lists: (substitutions, deletions, insertions). */
        private fun edits(g: List<String>, h: List<String>): Triple<Int, Int, Int> {
            val n = g.size
            val m = h.size
            val cost = Array(n + 1) { IntArray(m + 1) }
            for (a in 0..n) cost[a][0] = a
            for (b in 0..m) cost[0][b] = b
            for (a in 1..n) for (b in 1..m) {
                cost[a][b] = minOf(cost[a - 1][b - 1] + if (g[a - 1] == h[b - 1]) 0 else 1, cost[a - 1][b] + 1, cost[a][b - 1] + 1)
            }
            var a = n
            var b = m
            var s = 0
            var d = 0
            var i = 0
            while (a > 0 || b > 0) {
                when {
                    a > 0 && b > 0 && cost[a][b] == cost[a - 1][b - 1] + if (g[a - 1] == h[b - 1]) 0 else 1 -> {
                        if (g[a - 1] != h[b - 1]) s++
                        a--
                        b--
                    }
                    a > 0 && cost[a][b] == cost[a - 1][b] + 1 -> {
                        d++
                        a--
                    }
                    else -> {
                        i++
                        b--
                    }
                }
            }
            return Triple(s, d, i)
        }
    }
}
