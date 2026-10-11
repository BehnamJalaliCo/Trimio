package io.trimio.engine.autopilot.judges

import io.trimio.engine.autopilot.Jury
import io.trimio.engine.autopilot.Names
import io.trimio.engine.autopilot.Quantities
import io.trimio.engine.motion.score.NumberWords

/**
 * The proofreader's English: a Latin word on screen is an everyday English word (the English
 * frequency list, inflections included), an acronym, or a name the piece knows — spelled in the
 * brief, a mark in the brand library, a name the speaker said (a capital mid-sentence, see
 * [Names]) or one the understanding resolved. Casing is sane: lower, Capitalised or ALL CAPS, and a name the brief spells keeps the
 * brief's casing («Nobitex», not «nobitex»; «iPhone», not «Iphone»).
 */
internal class EnglishWords(private val c: Jury.Context) {
    private val english: Map<String, Int> = c.lexicon?.english.orEmpty()

    /** Latin words of the brief, by lower case: their spelling as the creator typed it. */
    private val brief: Map<String, String> = c.prompt.split(SEPARATORS).map(::core).filter(::latin).associateBy { it.lowercase() }

    /**
     * Names the piece knows (lower case): Latin names the speaker said, names the understanding
     * resolved («Tether»), and the brands of coins said in Persian («تتر» → Tether).
     */
    private val said: Set<String> = (c.prelude + c.words).map { it.text }.let { texts ->
        val spoken = texts.indices.filter { latin(core(texts[it])) && Names.isName(texts, it, c.prompt) }.map { core(texts[it]) }
        val resolved = c.understanding.entities.flatMap { it.name.split(' ') }
        val coins = texts.mapNotNull { Quantities.unitOf(NumberWords.normalize(it))?.brand }.flatMap { it.split(' ') }
        (spoken + resolved + coins).map { core(it).lowercase() }.toSet()
    }

    /** Whether the list is here to judge with. */
    val active: Boolean get() = english.isNotEmpty()

    /** A problem with Latin word [w] (unknown, or cased oddly), with its fix; null when it is fine. */
    fun check(token: String): Pair<String, String>? {
        val w = core(token)
        if (!latin(w) || w.any { it.isDigit() }) return null
        val low = w.lowercase()
        val typed = brief[low]
        // A site the brief writes (nobitex.ir) is its glossary, as written.
        val site = io.trimio.engine.autopilot.BriefNames.DOMAIN.matches(w) && c.prompt.contains(w, ignoreCase = true)
        val name = typed != null || low in said || c.brand(w) || site
        return when {
            !name && !known(low) && !acronym(w) -> "«$w» is not an English word or a name the piece knows" to (close(low)?.let { "write «$it»" } ?: "the word as said, or leave it out")
            // Only a name's casing is the brief's («iPhone»); an everyday word may open a sentence («And»).
            typed != null && typed != w && !upper(w) && typed.any { it.isUpperCase() } -> "«$w» is cased differently from the brief" to "write «$typed»"
            !name && odd(w) -> "«$w» is oddly cased" to "lower case, Capitalised or ALL CAPS"
            else -> null
        }
    }

    /** [s] with brief spellings and casing put back. */
    fun fix(s: String): String = s.split(' ').joinToString(" ") { token ->
        val w = core(token)
        if (!latin(w)) return@joinToString token
        val low = w.lowercase()
        val right = brief[low]?.takeIf { !upper(w) } ?: close(low).takeIf { !known(low) && low !in said && !c.brand(w) }
        if (right == null || right == w) token else token.replace(w, right)
    }

    /** The everyday list, through common inflections and contractions ("tuned", "they're", "cities"). */
    private fun known(low: String): Boolean {
        val bare = CONTRACTIONS.firstOrNull { low.endsWith(it) }?.let { low.dropLast(it.length) } ?: low
        if (bare in english) return true
        return SUFFIXES.any { (suffix, replace) -> bare.endsWith(suffix) && bare.length - suffix.length >= 2 && (bare.dropLast(suffix.length) + replace) in english }
    }

    /** The brief's word one or two letters away (a misheard name), when exactly one is. */
    private fun close(low: String): String? =
        brief.entries.filter { (k, _) -> low.length >= MIN_CLOSE && k.length >= MIN_CLOSE && distance(k, low) in 1..2 }.map { it.value }.singleOrNull()

    private fun acronym(w: String) = upper(w) && w.count { it.isLetter() } <= MAX_ACRONYM

    private fun upper(w: String) = w.filter { it.isLetter() }.all { it.isUpperCase() }

    /** Capitals inside a word ("NeEd", "iMportant"): only names are cased like that. */
    private fun odd(w: String): Boolean {
        val letters = w.filter { it.isLetter() }
        return letters.drop(1).any { it.isUpperCase() } && !upper(w)
    }

    private companion object {
        val SEPARATORS = Regex("[\\s,،.!?؟:;()\\[\\]{}«»\"“”/]+")
        val CONTRACTIONS = listOf("'s", "’s", "n't", "n’t", "'re", "’re", "'ll", "’ll", "'ve", "’ve", "'d", "’d", "'m", "’m")
        val SUFFIXES = listOf(
            "ies" to "y", "es" to "", "s" to "", "ied" to "y", "ed" to "", "ed" to "e", "d" to "", "ing" to "", "ing" to "e",
            "ly" to "", "er" to "", "er" to "e", "est" to "", "est" to "e",
        )
        const val MIN_CLOSE = 4
        const val MAX_ACRONYM = 4

        fun core(w: String) = w.trim { !it.isLetterOrDigit() && it != '\'' && it != '’' }.trim('\'', '’')
        fun latin(w: String) = w.isNotEmpty() && w.any { it in 'A'..'Z' || it in 'a'..'z' } && w.all { it.code < 128 || it == '’' }

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
