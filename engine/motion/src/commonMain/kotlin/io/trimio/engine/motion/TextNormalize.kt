package io.trimio.engine.motion

/**
 * Persian text as motion text needs it: the Arabic codepoints a recogniser or a copy-paste leaves
 * behind folded to Persian letters, the ZWNJ defects repaired where the fix is certain, and the
 * punctuation a caption line should not carry stripped from its ends. Pure and deterministic — the
 * transcript keeps its own text; only what is drawn goes through here.
 */
object TextNormalize {

    /** The join a Persian verb prefix needs («می‌رود»): invisible and never omitted. */
    private const val ZWNJ = '\u200C'
    private const val ZWJ = '\u200D'
    private const val LRM = '\u200E'
    private const val RLM = '\u200F'
    private const val ALEF = '\u0627'
    private const val FA_YEH = '\u06CC'
    private const val FA_KEHEH = '\u06A9'
    private const val AR_YEH = '\u064A'
    private const val AR_KAF = '\u0643'
    private const val ALEF_MAKSURA = '\u0649'
    private const val HAMZA_ABOVE = '\u0623'
    private const val HAMZA_BELOW = '\u0625'
    private const val ALEF_WASLA = '\u0671'
    private const val MI = "می"
    private const val NEMI = "نمی"

    /**
     * [s] as a caption or a title: invisible remnants gone, Persian letters and digits in their own
     * shapes, hamza folded conservatively, the «می»/«نمی» ZWNJ defect repaired, runs of spaces
     * collapsed and sentence-final marks stripped from the ends.
     */
    fun normalizeFa(s: String): String {
        val folded = foldHamza(toPersianDigits(stripDiacritics(mapLetters(dropInvisible(s)))))
        return stripSentenceEnds(collapseSpaces(normalizeZwnj(folded)))
    }

    /**
     * ZWNJ hygiene for [s]: spaces around a ZWNJ go and runs of ZWNJ collapse to one; the one
     * space-based prefix defect a recogniser reliably produces is repaired — a word-initial
     * «می»/«نمی» (any yeh spelling) followed by spaces and a Persian letter joins with ZWNJ and
     * comes out in its Persian spelling («می شود» → «می‌شود»). Word-initial only: «کمی رود»
     * ("a little river") is two words and keeps its space.
     */
    fun normalizeZwnj(s: String): String = joinVerbPrefixes(tightenZwnj(s))

    /**
     * True when [s] still carries that defect — a space where the ZWNJ belongs («می رود»), never
     * the joined form («می‌رود») nor a word that merely ends in «می» («کمی رود»).
     */
    fun hasZwnjDefect(s: String): Boolean {
        var i = 0
        while (i < s.length) {
            if (i == 0 || !s[i - 1].isLetterOrDigit()) {
                val prefix = prefixAt(s, i)
                if (prefix != null && spacedLetterAfter(s, i + prefix.length) >= 0) return true
            }
            i++
        }
        return false
    }

    /** Arabic-Indic digits (U+0660–U+0669) to Persian digits (U+06F0–U+06F9); Latin digits stay as they are. */
    fun toPersianDigits(s: String): String = buildString(s.length) {
        for (c in s) append(if (c in '\u0660'..'\u0669') c + ('\u06F0' - '\u0660') else c)
    }

    /**
     * Vowel marks (U+064B–U+0652) off: motion text is set without them. Separate because a caller
     * that reads the marks (filler detection tells «اِم» from «ام») must skip this step.
     */
    fun stripDiacritics(s: String): String = s.filterNot { it in '\u064B'..'\u0652' }

    /**
     * Sentence-final marks off both ends of [s]: a caption reads as speech («می‌رود!» → «می‌رود»),
     * not as typed prose. Interior punctuation — the commas that pace a line — stays.
     */
    fun stripSentenceEnds(s: String): String = s.trim { it in SENTENCE_ENDS || it.isWhitespace() }

    private val SENTENCE_ENDS = charArrayOf('،', '؛', '؟', '!', '.', ',', ';', '?')

    private val SPACES_RUN = Regex(" {3,}")

    /** Bidi and joiner remnants: invisible, and never meaningful in what is drawn. */
    private fun dropInvisible(s: String): String = s.filterNot { it == ZWJ || it == LRM || it == RLM }

    /** Presentation forms decomposed, then the Arabic letters Persian spells differently. */
    private fun mapLetters(s: String): String = buildString(s.length) {
        for (c in s) {
            when {
                c in '\uFE80'..'\uFEFC' -> append(presentationBase(c))
                c == AR_YEH || c == ALEF_MAKSURA -> append(FA_YEH)
                c == AR_KAF -> append(FA_KEHEH)
                else -> append(c)
            }
        }
    }

    /**
     * The base letters of the contextual forms U+FE80..U+FEF4, in codepoint order (Unicode
     * nameslist, Arabic Presentation Forms-B): hamza and the alefs (FE80..FE8E), beh..theh
     * (FE8F..FE9C), jeem..thal (FE9D..FEAC), reh..sheen (FEAD..FEB8), sad..zah (FEB9..FEC8),
     * ain..qaf (FEC9..FED8), kaf..noon (FED9..FEE8), heh..yeh (FEE9..FEF4).
     */
    private const val CONTEXTUAL_BASES =
        "\u0621\u0622\u0622\u0623\u0623\u0624\u0624\u0625\u0625\u0626\u0626\u0626\u0626\u0627\u0627" +
            "\u0628\u0628\u0628\u0628\u0629\u0629\u062A\u062A\u062A\u062A\u062B\u062B\u062B\u062B" +
            "\u062C\u062C\u062C\u062C\u062D\u062D\u062D\u062D\u062E\u062E\u062E\u062E\u062F\u062F\u0630\u0630" +
            "\u0631\u0631\u0632\u0632\u0633\u0633\u0633\u0633\u0634\u0634\u0634\u0634" +
            "\u0635\u0635\u0635\u0635\u0636\u0636\u0636\u0636\u0637\u0637\u0637\u0637\u0638\u0638\u0638\u0638" +
            "\u0639\u0639\u0639\u0639\u063A\u063A\u063A\u063A\u0641\u0641\u0641\u0641\u0642\u0642\u0642\u0642" +
            "\u0643\u0643\u0643\u0643\u0644\u0644\u0644\u0644\u0645\u0645\u0645\u0645\u0646\u0646\u0646\u0646" +
            "\u0647\u0647\u0647\u0647\u0648\u0648\u0649\u0649\u064A\u064A\u064A\u064A"

    /** The lam-alef ligatures decompose to two letters; the rest of the forms to one. */
    private fun presentationBase(c: Char): String = when (c) {
        '\uFEF5', '\uFEF6' -> "\u0644\u0622"
        '\uFEF7', '\uFEF8' -> "\u0644\u0623"
        '\uFEF9', '\uFEFA' -> "\u0644\u0625"
        '\uFEFB', '\uFEFC' -> "\u0644\u0627"
        in '\uFE80'..'\uFEF4' -> CONTEXTUAL_BASES[c - '\uFE80'].toString()
        else -> c.toString()
    }

    /**
     * Hamza on an alef folds to the alef («أ إ ٱ» → «ا»): the recogniser slips between the forms and
     * Persian sets them all plain. «ؤ» and «ئ» stay — letters of their own.
     */
    private fun foldHamza(s: String): String = buildString(s.length) {
        for (c in s) append(if (c == HAMZA_ABOVE || c == HAMZA_BELOW || c == ALEF_WASLA) ALEF else c)
    }

    /**
     * Three or more spaces collapse to one and the ends trim. Two spaces stay: an intentional
     * interword gap ([TextLayoutEngine] widens tight gaps with spaces of its own), not noise.
     */
    private fun collapseSpaces(s: String): String = s.replace(SPACES_RUN, " ").trim()

    private fun tightenZwnj(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c == ZWNJ -> {
                    while (i < s.length && s[i] == ZWNJ) i++
                    while (i < s.length && s[i] == ' ') i++
                    out.append(ZWNJ)
                }
                c == ' ' -> {
                    var k = i
                    while (k < s.length && s[k] == ' ') k++
                    if (k < s.length && s[k] == ZWNJ) i = k else { out.append(c); i++ }
                }
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    private fun joinVerbPrefixes(s: String): String {
        val out = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val atStart = i == 0 || !s[i - 1].isLetterOrDigit()
            val prefix = if (atStart) prefixAt(s, i) else null
            if (prefix == null) {
                out.append(s[i])
                i++
            } else {
                val letter = spacedLetterAfter(s, i + prefix.length)
                if (letter >= 0) {
                    // The join emits the canonical prefix: «مى رود» lands as «می‌رود».
                    out.append(prefix).append(ZWNJ)
                    i = letter
                } else {
                    out.append(s, i, i + prefix.length)
                    i += prefix.length
                }
            }
        }
        return out.toString()
    }

    /** The canonical spelling of the verb prefix starting at [i], or null. */
    private fun prefixAt(s: String, i: Int): String? = when {
        hasPrefix(s, i, NEMI) -> NEMI
        hasPrefix(s, i, MI) -> MI
        else -> null
    }

    /** [s] carries [prefix] at [i], with any yeh spelling (ی, ى, ي) standing in for the Persian one. */
    private fun hasPrefix(s: String, i: Int, prefix: String): Boolean {
        if (i + prefix.length > s.length) return false
        for (k in prefix.indices) {
            val c = s[i + k]
            val matches = c == prefix[k] ||
                (prefix[k] == FA_YEH && (c == ALEF_MAKSURA || c == AR_YEH))
            if (!matches) return false
        }
        return true
    }

    /** Index of the Persian letter after the spaces following [i], or -1 when there are none. */
    private fun spacedLetterAfter(s: String, i: Int): Int {
        var k = i
        while (k < s.length && s[k] == ' ') k++
        return if (k > i && k < s.length && isPersianLetter(s[k])) k else -1
    }

    private fun isPersianLetter(c: Char): Boolean = c.isLetter() && c in '\u0600'..'\u06FF'
}
