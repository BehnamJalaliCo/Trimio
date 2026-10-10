package io.trimio.engine.autopilot

import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word

/**
 * Fixes what the recogniser misheard before a caption is drawn, without ever rewriting speech:
 *
 * - [fromBrief]: words the creator spelled in their brief win over sound-alike transcript words
 *   (Persian letters that sound the same — س/ص/ث, ز/ذ/ض/ظ, ت/ط, ه/ح, ق/غ — and Latin names);
 * - [fromLexicon]: the offline word list ([PersianSpelling]) fixes unknown words one recogniser slip
 *   from a far more frequent word (سبتنام → ثبت‌نام, سی سد → سیصد, میتونید → می‌تونید);
 * - [align]: the director model rewrites each line with correct spelling; its words are aligned
 *   to the recognised ones and a correction is kept only when it is plausibly the same word.
 *
 * All return word index → corrected spelling; timings never change.
 */
object Proofreader {

    private val SEPARATORS = Regex("[\\s,،.!?؟:;()\\[\\]{}«»\"“”/]+")

    fun fromBrief(words: List<String>, brief: String): Map<Int, String> {
        // «سرمایهٔ» in the brief is «سرمایه» with an ezafe: the same word, never a reason to add the mark.
        val vocabulary = brief.split(SEPARATORS).map(::withoutEzafe)
            .flatMap { t -> listOf(t) + t.split('‌') }.filter { it.length >= 2 }.toSet()
        val byKey = vocabulary.groupBy { key(it) }
        // Words the creator quoted («کد») are deliberate spellings: a one-vowel slip still matches.
        val quoted = Regex("[«\"“]([^»\"”]{2,24})[»\"”]").findAll(brief).map { it.groupValues[1].trim() }.toList()
        val out = mutableMapOf<Int, String>()
        for ((i, w) in words.withIndex()) {
            val core = w.trimEnd('.', '،', ',', '!', '?', '؟')
            val match = if (core.length < 2 || core in vocabulary) null else briefMatch(core, byKey, vocabulary, quoted)
            if (match != null && match != core) out[i] = match + w.substring(core.length)
        }
        return out
    }

    /**
     * The word-list pass, run after [fromBrief]: words the brief already [fixed] keep its spelling and
     * the brief's words count as evidence. A joined pair («سی سد» → «سیصد») empties its second word;
     * apply the result with [withSpelling], which merges the pair into one word.
     */
    suspend fun fromLexicon(words: List<String>, brief: String, fixed: Map<Int, String> = emptyMap()): Map<Int, String> {
        val current = words.mapIndexed { i, w -> fixed[i] ?: w }
        val fixes = PersianSpelling.load().correct(current, brief.split(SEPARATORS).filter { it.length >= 2 })
        // The other half of a joined pair, if [i] is one.
        fun partner(i: Int) = if (fixes[i] == "") i - 1 else (i + 1).takeIf { fixes[it] == "" }
        return fixes.filterKeys { i -> i !in fixed && partner(i)?.let { it in fixed } != true }
    }

    /** «ۀ» and «هٔ» (the written ezafe) as a plain «ه». */
    private fun withoutEzafe(w: String) = w.replace("\u0654", "").replace('\u06C0', 'ه')

    private fun briefMatch(core: String, byKey: Map<String, List<String>>, vocabulary: Set<String>, quoted: List<String>): String? {
        val k = key(core)
        return byKey[k]?.firstOrNull() ?: byKey[k + "ه"]?.firstOrNull()?.takeIf { core.length >= 3 }
            ?: latinName(core, vocabulary) ?: quoted.firstOrNull { q -> core.length >= 3 && vowelEdit(core, q) }
    }

    /** "Cloud" → "Claude" when the brief spells the name: same first letter, two edits at most, not a prefix. */
    private fun latinName(w: String, vocabulary: Set<String>): String? {
        if (w.length < 4 || w.none { it in 'A'..'Z' || it in 'a'..'z' }) return null
        return vocabulary.firstOrNull { v -> v.length >= 4 && closeName(v, w) }
    }

    private fun closeName(v: String, w: String): Boolean {
        val prefix = v.startsWith(w, ignoreCase = true) || w.startsWith(v, ignoreCase = true)
        return !prefix && v.first().equals(w.first(), ignoreCase = true) && distance(v.lowercase(), w.lowercase()) <= 2
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
                    // A word already respelled as several («چهل و») is left whole: one aligned token would drop the rest.
                    val single = a[i - 1].trim().none { it.isWhitespace() }
                    if ((sub == 1 || halfSpace) && single) out[first + i - 1] = b[j - 1].trimEnd { !it.isLetterOrDigit() } + trailing(a[i - 1])
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
        if (fixed.any { foreign(it, original) }) return false
        if (key(a) == key(c)) return true
        val latin = a.any { it in 'A'..'Z' || it in 'a'..'z' } && c.any { it in 'A'..'Z' || it in 'a'..'z' }
        if (latin) return distance(a, c) <= maxOf(2, a.length / 2) && a.first().equals(c.first(), ignoreCase = true)
        // An unknown word the director respells as a known one two slips away ("کارورد" → "کاربر").
        return a.length >= 3 && vowelEdit(a, c) || PersianSpelling.shared?.confirms(a, c) == true
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

    /** A character the fix brought in that is neither a letter, a half-space nor the original's punctuation. */
    private fun foreign(ch: Char, original: String) = !ch.isLetterOrDigit() && ch != '\u200C' && ch !in original && ch !in ".،,!?؟"

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

/**
 * [withFixes] for spelling, keeping every word a single word:
 * - a word fixed to "" was joined into the word before it («سی سد» → «سیصد»): the two become one word
 *   spanning both timings, so no empty word reaches lines or captions;
 * - a fix of several words («چلا» → «چهل و», «ساتم» → «ساعت هم») becomes that many words, sharing the
 *   heard word's time by letter count, so no word ever contains a space.
 */
fun Transcript.withSpelling(fixes: Map<Int, String>): Transcript {
    if (fixes.isEmpty()) return this
    val out = ArrayList<Word>(words.size)
    for ((i, w) in words.withIndex()) {
        val text = fixes[i] ?: w.text
        val prev = out.lastOrNull()
        if (text.isEmpty() && prev != null) {
            out[out.lastIndex] = prev.copy(range = TimeRange(prev.range.startMs, maxOf(prev.range.endMs, w.range.endMs)), emphasis = maxOf(prev.emphasis, w.emphasis))
        } else {
            out += w.split(text.ifEmpty { w.text })
        }
    }
    return Transcript(language, out)
}

/** [text] as one word per space-separated part, each taking a share of this word's time by its length. */
private fun Word.split(text: String): List<Word> {
    val parts = text.split(' ').filter { it.isNotEmpty() }
    if (parts.size <= 1) return listOf(copy(text = text.trim()))
    val letters = parts.sumOf { it.length }.toDouble()
    var start = range.startMs
    var used = 0
    return parts.mapIndexed { k, part ->
        used += part.length
        val end = if (k == parts.lastIndex) range.endMs else range.startMs + (range.durationMs * used / letters).toLong()
        copy(text = part, range = TimeRange(start, end)).also { start = end }
    }
}
