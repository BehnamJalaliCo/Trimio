package io.trimio.engine.autopilot

/**
 * Fixes what the recogniser misheard before a caption is drawn, without ever rewriting speech:
 *
 * - [fromBrief]: words the creator spelled in their brief win over sound-alike transcript words
 *   (Persian letters that sound the same — س/ص/ث, ز/ذ/ض/ظ, ت/ط, ه/ح, ق/غ — and Latin names);
 * - [align]: the director model rewrites each line with correct spelling; its words are aligned
 *   to the recognised ones and a correction is kept only when it is plausibly the same word.
 *
 * Both return word index → corrected spelling; timings never change.
 */
object Proofreader {

    fun fromBrief(words: List<String>, brief: String): Map<Int, String> {
        val vocabulary = brief.split(Regex("[\\s,،.!?؟:;()\\[\\]{}«»\"“”/]+"))
            .flatMap { t -> listOf(t) + t.split('‌') }.filter { it.length >= 2 }.toSet()
        val byKey = vocabulary.groupBy { key(it) }
        // Words the creator quoted («کد») are deliberate spellings: a one-vowel slip still matches.
        val quoted = Regex("[«\"“]([^»\"”]{2,24})[»\"”]").findAll(brief).map { it.groupValues[1].trim() }.toList()
        val out = mutableMapOf<Int, String>()
        for ((i, w) in words.withIndex()) {
            val core = w.trimEnd('.', '،', ',', '!', '?', '؟')
            if (core.length < 2 || core in vocabulary) continue
            val k = key(core)
            val match = byKey[k]?.firstOrNull() ?: byKey[k + "ه"]?.firstOrNull()?.takeIf { core.length >= 3 }
                ?: latinName(core, vocabulary) ?: quoted.firstOrNull { q -> core.length >= 3 && vowelEdit(core, q) } ?: continue
            if (match != core) out[i] = match + w.substring(core.length)
        }
        return out
    }

    /** "Cloud" → "Claude" when the brief spells the name: same first letter, two edits at most, not a prefix. */
    private fun latinName(w: String, vocabulary: Set<String>): String? {
        if (w.length < 4 || w.none { it in 'A'..'Z' || it in 'a'..'z' }) return null
        return vocabulary.firstOrNull { v ->
            v.length >= 4 && v.first().equals(w.first(), ignoreCase = true) && distance(v.lowercase(), w.lowercase()) <= 2 &&
                !v.startsWith(w, ignoreCase = true) && !w.startsWith(v, ignoreCase = true)
        }
    }

    /** Aligns a corrected line to the recognised [original] words (index offset [first]). */
    fun align(original: List<String>, first: Int, fixed: String): Map<Int, String> {
        val b = fixed.split(' ').filter { it.isNotBlank() }
        if (b.isEmpty()) return emptyMap()
        val a = original
        // Word-level edit distance with substitution cost from letter similarity.
        val n = a.size
        val m = b.size
        val cost = Array(n + 1) { IntArray(m + 1) }
        for (i in 0..n) cost[i][0] = i * 2
        for (j in 0..m) cost[0][j] = j * 2
        for (i in 1..n) for (j in 1..m) {
            val sub = if (strip(a[i - 1]) == strip(b[j - 1])) 0 else if (accept(a[i - 1], b[j - 1])) 1 else 3
            cost[i][j] = minOf(cost[i - 1][j - 1] + sub, cost[i - 1][j] + 2, cost[i][j - 1] + 2)
        }
        val out = mutableMapOf<Int, String>()
        var i = n
        var j = m
        while (i > 0 && j > 0) {
            val sub = if (strip(a[i - 1]) == strip(b[j - 1])) 0 else if (accept(a[i - 1], b[j - 1])) 1 else 3
            when {
                cost[i][j] == cost[i - 1][j - 1] + sub -> {
                    // A spelling fix, or the same letters with half-spaces put right.
                    val halfSpace = sub == 0 && a[i - 1].trimEnd { !it.isLetterOrDigit() } != b[j - 1].trimEnd { !it.isLetterOrDigit() }
                    if (sub == 1 || halfSpace) out[first + i - 1] = b[j - 1].trimEnd { !it.isLetterOrDigit() } + trailing(a[i - 1])
                    i--; j--
                }
                cost[i][j] == cost[i - 1][j] + 2 -> i--
                else -> j--
            }
        }
        return out
    }

    /** Same word, better spelled: a small letter edit, a sound-alike letter, or a Latin name fixed. */
    fun accept(original: String, fixed: String): Boolean {
        val a = strip(original)
        val c = strip(fixed)
        if (c.isEmpty() || a == c) return false
        if (fixed.any { !it.isLetterOrDigit() && it != '‌' && it !in original && it !in ".،,!?؟" }) return false
        if (key(a) == key(c)) return true
        val latin = a.any { it in 'A'..'Z' || it in 'a'..'z' } && c.any { it in 'A'..'Z' || it in 'a'..'z' }
        if (latin) return distance(a, c) <= maxOf(2, a.length / 2) && a.first().equals(c.first(), ignoreCase = true)
        return a.length >= 3 && vowelEdit(a, c)
    }

    /**
     * Persian: one long vowel or final «ه» added or dropped, never the first letter ("کود" → "کد",
     * "تیه" → "تهیه", but not "بکنی" → "کنی").
     */
    private fun vowelEdit(a: String, b: String): Boolean {
        val (long, short) = if (a.length > b.length) a to b else b to a
        if (long.length - short.length != 1) return false
        val i = long.indices.firstOrNull { it >= short.length || long[it] != short[it] } ?: return false
        return i > 0 && long[i] in "اویه" && long.removeRange(i, i + 1) == short
    }

    private fun strip(s: String) = s.filter { it.isLetterOrDigit() }

    private fun trailing(s: String) = s.takeLastWhile { !it.isLetterOrDigit() && it != '‌' }

    /** Letters that sound alike map to one; half-spaces and case are ignored. */
    fun key(s: String): String = buildString {
        for (ch in s.lowercase()) {
            if (!ch.isLetterOrDigit()) continue
            append(SOUND[ch] ?: ch)
        }
    }

    private val SOUND: Map<Char, Char> = buildMap {
        "سصث".forEach { put(it, 'س') }
        "زذضظ".forEach { put(it, 'ز') }
        "تط".forEach { put(it, 'ت') }
        "هح".forEach { put(it, 'ه') }
        "قغ".forEach { put(it, 'ق') }
        "يی".forEach { put(it, 'ی') }
        "كک".forEach { put(it, 'ک') }
        "أإآا".forEach { put(it, 'ا') }
    }

    private fun distance(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = tmp
            }
        }
        return dp[b.length]
    }
}
