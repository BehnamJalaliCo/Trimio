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

    private const val COMPOUND_LENGTH = 7
    private const val COMPOUND_BONES = 4
    private val ENDS = charArrayOf('.', '،', ',', '!', '?', '؟')
    private val SEPARATORS = Regex("[\\s,،.!?؟:;()\\[\\]{}«»\"“”/]+")

    /**
     * Words the brief spells: Persian sound-alikes and slips of its words, its compounds heard in halves,
     * and its Latin names ([BriefNames]) heard misspelled, in halves or said in Persian. [spelling] tells
     * names from everyday English and common Persian words (without it, only case and shape do).
     */
    fun fromBrief(words: List<String>, brief: String, spelling: PersianSpelling? = PersianSpelling.shared): Map<Int, String> {
        // «سرمایهٔ» in the brief is «سرمایه» with an ezafe: the same word, never a reason to add the mark.
        val vocabulary = brief.split(SEPARATORS).map(::withoutEzafe)
            .flatMap { t -> listOf(t) + t.split('‌') }.filter { it.length >= 2 }.toSet()
        val byKey = vocabulary.groupBy { key(it) }
        // Words the creator quoted («کد») are deliberate spellings: a one-vowel slip still matches.
        val quoted = Regex("[«\"“]([^»\"”]{2,24})[»\"”]").findAll(brief).map { it.groupValues[1].trim() }.toList()
        val compounds = vocabulary.filter { '‌' in it && it.length >= COMPOUND_LENGTH }.groupBy { skeleton(it) }
        val names = BriefNames(brief, spelling?.english.orEmpty())
        val out = mutableMapOf<Int, String>()
        var i = 0
        while (i < words.size) {
            val w = words[i]
            val core = w.trimEnd(*ENDS)
            // A Persian word the brief itself writes in Persian («تتر (Tether)») stays Persian: the Latin is the brief's gloss, not a correction.
            val written = core.any { it in '\u0600'..'\u06FF' } && withoutEzafe(core) in vocabulary
            val name = if (written) null else names.at(words, i) { spelling?.count(it) ?: 0 }
            val compound = briefCompound(core, w, words.getOrNull(i + 1), compounds)
            val span = name?.second ?: if (compound != null) 2 else 1
            val whole = name?.let { it.first + words[i + span - 1].takeLastWhile { c -> !c.isLetterOrDigit() } } ?: compound
            if (whole != null) {
                out[i] = whole
                for (k in 1 until span) out[i + k] = ""
            } else {
                val match = if (core.length < 2 || core in vocabulary) null else briefMatch(core, byKey, names, quoted)
                if (match != null && match != core) out[i] = match + w.substring(core.length)
            }
            i += span
        }
        return out
    }

    /** Two heard words that are one compound of the brief, vowels aside: «صرف جوی» → «صرفه‌جویی». */
    private fun briefCompound(core: String, word: String, next: String?, compounds: Map<String, List<String>>): String? {
        if (next == null || core != word) return null
        val nextCore = next.trimEnd(*ENDS)
        val bones = skeleton(core + nextCore)
        val found = compounds[bones]?.singleOrNull()?.takeIf { bones.length >= COMPOUND_BONES } ?: return null
        return found + next.substring(nextCore.length)
    }

    /** Consonants only, sound-alikes merged: what survives a recogniser dropping or adding vowels. */
    private fun skeleton(s: String) = key(s).filter { it !in "اویه" }

    /**
     * The word-list pass, run after [fromBrief]: words the brief already [fixed] keep its spelling and
     * the brief's words count as evidence. A joined pair («سی سد» → «سیصد») empties its second word;
     * apply the result with [withSpelling], which merges the pair into one word.
     */
    suspend fun fromLexicon(words: List<String>, brief: String, fixed: Map<Int, String> = emptyMap()): Map<Int, String> {
        val current = words.mapIndexed { i, w -> fixed[i] ?: w }
        val persian = PersianSpelling.load().correct(current, brief.split(SEPARATORS).filter { it.length >= 2 })
        val fixes = persian + latinJoins(current).filterKeys { it !in persian && (it + 1) !in persian }
        // The other half of a joined pair, if [i] is one.
        fun partner(i: Int) = if (fixes[i] == "") i - 1 else (i + 1).takeIf { fixes[it] == "" }
        return fixes.filterKeys { i -> i !in fixed && partner(i)?.let { it in fixed } != true }
    }

    /**
     * Latin names the recogniser splits in two ("Chat GPT", "Open AI", "Air Pods") and a size said with its
     * letter ("10 K" → 10K): joined as written. The brief's own names are handled by [fromBrief].
     */
    private fun latinJoins(words: List<String>): Map<Int, String> {
        val out = mutableMapOf<Int, String>()
        var i = 0
        while (i < words.size - 1) {
            val a = words[i]
            val b = words[i + 1].trimEnd(*ENDS)
            val tail = words[i + 1].substring(b.length)
            val name = JOINED_NAMES[(a + b).lowercase()]?.takeIf { a.isNotEmpty() && a.last().isLetterOrDigit() }
            val size = (a + b).takeIf { a.isNotEmpty() && a.all { it.isDigit() } && b in SIZE_LETTERS }
            val whole = name ?: size
            if (whole != null) {
                out[i] = whole + tail
                out[i + 1] = ""
            }
            i += if (whole != null) 2 else 1
        }
        return out
    }

    /** Product names whisper writes in two words, as their makers spell them. */
    private val JOINED_NAMES = listOf(
        "ChatGPT", "OpenAI", "AirPods", "iPhone", "iPad", "MacBook", "YouTube", "TikTok", "WhatsApp", "LinkedIn", "PayPal", "GitHub",
        "PlayStation", "PowerPoint", "OneDrive", "DeepSeek", "CapCut", "AirDrop", "FaceTime", "HubSpot", "MailChimp", "WordPress",
    ).associateBy { it.lowercase() }
    private val SIZE_LETTERS = setOf("K", "k", "M", "B")

    /** «ۀ» and «هٔ» (the written ezafe) as a plain «ه». */
    private fun withoutEzafe(w: String) = w.replace("\u0654", "").replace('\u06C0', 'ه')

    private fun briefMatch(core: String, byKey: Map<String, List<String>>, names: BriefNames, quoted: List<String>): String? {
        // A Latin word takes only a name's spelling ("Cloud" → Claude), never another English word's ("need" → News).
        if (core.any { it in 'A'..'Z' || it in 'a'..'z' }) return names.respell(core.trim { !it.isLetterOrDigit() })
        val k = key(core)
        return byKey[k]?.firstOrNull() ?: byKey[k + "ه"]?.firstOrNull()?.takeIf { core.length >= 3 }
            ?: quoted.firstOrNull { q -> core.length >= 3 && vowelEdit(core, q) }
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
