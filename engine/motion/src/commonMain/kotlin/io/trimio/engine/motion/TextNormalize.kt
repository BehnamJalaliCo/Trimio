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

    /** Persian thousands separator; the lookalikes a keypad leaves behind fold into it. */
    private const val PERSIAN_THOUSANDS = '\u066C'
    private const val ARABIC_COMMA = '\u060C'
    private const val ASCII_COMMA = ','

    /**
     * [s] as a caption or a title: invisible remnants gone, Persian letters and digits in their own
     * shapes (presentation forms-A and -B both folded), hamza folded conservatively, the
     * «می»/«نمی» ZWNJ defect repaired, runs of spaces collapsed and sentence-final marks stripped
     * from the ends.
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

    /**
     * Arabic-Indic digits (U+0660–U+0669) to Persian digits (U+06F0–U+06F9); Latin digits stay as
     * they are. A thousands-separator lookalike that sits between digits becomes the Persian
     * separator (U+066C): «۱،۰۰۰» and «۱,۰۰۰» both set as «۱٬۰۰۰». The decimal separator (U+066B)
     * and a comma outside a number are left alone.
     */
    fun toPersianDigits(s: String): String = buildString(s.length * 2) {
        var i = 0
        while (i < s.length) {
            val c = s[i]
            when {
                c in '\u0660'..'\u0669' -> append(c + ('\u06F0' - '\u0660'))
                isThousandsLookalike(c) && betweenDigits(s, i) -> append(PERSIAN_THOUSANDS)
                else -> append(c)
            }
            i++
        }
    }

    /**
     * Vowel marks (U+064B–U+0652) and the nukta/hamza diacritics (U+0653–U+0655, U+0658) off:
     * motion text is set without them. A nukta that *is* a letter — the dots of «پ چ ژ گ», or a
     * presentation form that folds to one — is not a mark and stays; only the loose combining
     * form goes. Separate because a caller that reads the marks (filler detection tells «اِم» from
     * «ام») must skip this step.
     */
    fun stripDiacritics(s: String): String = s.filterNot { isMarkDiacritic(it) }

    /**
     * Sentence-final marks off both ends of [s]: a caption reads as speech («می‌رود!» → «می‌رود»),
     * not as typed prose. Interior punctuation — the commas that pace a line — stays.
     */
    fun stripSentenceEnds(s: String): String = s.trim { it in SENTENCE_ENDS || it.isWhitespace() }

    private val SENTENCE_ENDS = charArrayOf('،', '؛', '؟', '!', '.', ',', ';', '?')

    private val SPACES_RUN = Regex(" {3,}")

    /** Bidi and joiner remnants: invisible, and never meaningful in what is drawn. */
    private fun dropInvisible(s: String): String = s.filterNot { it == ZWJ || it == LRM || it == RLM }

    /**
     * Presentation forms (A and B) decomposed, then the Arabic letters Persian spells differently.
     * Forms-A (U+FB50–U+FDFF) are dotted letters, ligatures and contextual shapes of the extended
     * alphabet; they fold exactly like Forms-B: to their base letter(s), then into Persian shape.
     */
    private fun mapLetters(s: String): String = buildString(s.length * 2) {
        for (c in s) {
            when {
                c in '\uFB50'..'\uFDFF' -> for (b in presentationA(c)) append(persianForm(b))
                // The decomposition yields base letters that must fold like any other (yeh of U+FEF3
                // lands as Persian yeh, not the Arabic one).
                c in '\uFE80'..'\uFEFC' -> for (b in presentationBase(c)) append(persianForm(b))
                else -> append(persianForm(c))
            }
        }
    }

    /** The Persian spelling of a letter the recogniser or a presentation form left in Arabic shape. */
    private fun persianForm(c: Char): Char = when (c) {
        AR_YEH, ALEF_MAKSURA -> FA_YEH
        AR_KAF -> FA_KEHEH
        else -> c
    }

    /**
     * Presentation Forms-A (U+FB50..U+FDFF) compatibility bases in codepoint order.
     * Entries are '|'-separated; an empty entry keeps that codepoint as it is.
     */
    private const val FORMS_A_BASES =
        "ٱ|ٱ|ٻ|ٻ|ٻ|ٻ|پ|پ|پ|پ|ڀ|ڀ|ڀ|ڀ|ٺ|ٺ|ٺ|ٺ|ٿ|ٿ|ٿ|ٿ|ٹ|ٹ|ٹ|ٹ|ڤ|ڤ|ڤ|ڤ|ڦ|ڦ|ڦ|ڦ|ڄ|ڄ|ڄ|ڄ|ڃ|ڃ|ڃ|ڃ|چ|چ|چ|چ|ڇ|ڇ|ڇ|ڇ|ڍ|ڍ|ڌ|ڌ|ڎ|" +
            "ڎ|ڈ|ڈ|ژ|ژ|ڑ|ڑ|ک|ک|ک|ک|گ|گ|گ|گ|ڳ|ڳ|ڳ|ڳ|ڱ|ڱ|ڱ|ڱ|ں|ں|ڻ|ڻ|ڻ|ڻ|ۀ|ۀ|ہ|ہ|ہ|ہ|ھ|ھ|ھ|ھ|ے|ے|ۓ|ۓ|||||||||||||||||||||||||" +
            "|||||||||ڭ|ڭ|ڭ|ڭ|ۇ|ۇ|ۆ|ۆ|ۈ|ۈ|ٷ|ۋ|ۋ|ۅ|ۅ|ۉ|ۉ|ې|ې|ې|ې|ى|ى|ئا|ئا|ئە|ئە|ئو|ئو|ئۇ|ئۇ|ئۆ|ئۆ|ئۈ|ئۈ|ئې|ئې|ئې|ئى|ئى|ئى|ی" +
            "|ی|ی|ی|ئج|ئح|ئم|ئى|ئي|بج|بح|بخ|بم|بى|بي|تج|تح|تخ|تم|تى|تي|ثج|ثم|ثى|ثي|جح|جم|حج|حم|خج|خح|خم|سج|سح|سخ|سم|صح|صم|ض" +
            "ج|ضح|ضخ|ضم|طح|طم|ظم|عج|عم|غج|غم|فج|فح|فخ|فم|فى|في|قح|قم|قى|قي|كا|كج|كح|كخ|كل|كم|كى|كي|لج|لح|لخ|لم|لى|لي|مج|مح|" +
            "مخ|مم|مى|مي|نج|نح|نخ|نم|نى|ني|هج|هم|هى|هي|يج|يح|يخ|يم|يى|يي|ذٰ|رٰ|ىٰ| ٌّ| ٍّ| َّ| ُّ| ِّ| ّٰ|ئر|ئز|ئم|ئن|ئى|ئي" +
            "|بر|بز|بم|بن|بى|بي|تر|تز|تم|تن|تى|تي|ثر|ثز|ثم|ثن|ثى|ثي|فى|في|قى|قي|كا|كل|كم|كى|كي|لم|لى|لي|ما|مم|نر|نز|نم|نن|ن" +
            "ى|ني|ىٰ|ير|يز|يم|ين|يى|يي|ئج|ئح|ئخ|ئم|ئه|بج|بح|بخ|بم|به|تج|تح|تخ|تم|ته|ثم|جح|جم|حج|حم|خج|خم|سج|سح|سخ|سم|صح|صخ|" +
            "صم|ضج|ضح|ضخ|ضم|طح|ظم|عج|عم|غج|غم|فج|فح|فخ|فم|قح|قم|كج|كح|كخ|كل|كم|لج|لح|لخ|لم|له|مج|مح|مخ|مم|نج|نح|نخ|نم|نه|هج" +
            "|هم|هٰ|يج|يح|يخ|يم|يه|ئم|ئه|بم|به|تم|ته|ثم|ثه|سم|سه|شم|شه|كل|كم|لم|نم|نه|يم|يه|ـَّ|ـُّ|ـِّ|طى|طي|عى|عي|غى|غي|س" +
            "ى|سي|شى|شي|حى|حي|جى|جي|خى|خي|صى|صي|ضى|ضي|شج|شح|شخ|شم|شر|سر|صر|ضر|طى|طي|عى|عي|غى|غي|سى|سي|شى|شي|حى|حي|جى|جي|خى|" +
            "خي|صى|صي|ضى|ضي|شج|شح|شخ|شم|شر|سر|صر|ضر|شج|شح|شخ|شم|سه|شه|طم|سج|سح|سخ|شج|شح|شخ|طم|ظم|اً|اً|||||||||||||||||||تج" +
            "م|تحج|تحج|تحم|تخم|تمج|تمح|تمخ|جمح|جمح|حمي|حمى|سحج|سجح|سجى|سمح|سمح|سمج|سمم|سمم|صحح|صحح|صمم|شحم|شحم|شجي|شمخ|شمخ|" +
            "شمم|شمم|ضحى|ضخم|ضخم|طمح|طمح|طمم|طمي|عجم|عمم|عمم|عمى|غمم|غمي|غمى|فخم|فخم|قمح|قمم|لحم|لحي|لحى|لجج|لجج|لخم|لخم|لم" +
            "ح|لمح|محج|محم|محي|مجح|مجم|مخج|مخم|||مجخ|همج|همم|نحم|نحى|نجم|نجم|نجى|نمي|نمى|يمم|يمم|بخي|تجي|تجى|تخي|تخى|تمي|تم" +
            "ى|جمي|جحى|جمى|سخى|صحي|شحي|ضحي|لجي|لمي|يحي|يجي|يمي|ممي|قمي|نحي|قمح|لحم|عمي|كمي|نجح|مخي|لجم|كمم|لجم|نجح|جحي|حجي|" +
            "مجي|فمي|بحي|كمم|عجم|صمم|سخي|نجي|||||||||||||||||||||||||||||||||||||||||صلے|قلے|الله|اكبر|محمد|صلعم|رسول|عليه|" +
            "وسلم|صلى|صلى الله عليه وسلم|جل جلاله|ریال|||"

    private val formsABases: List<String> = FORMS_A_BASES.split('|')

    /**
     * The base letter(s) of a Presentation Forms-A character: its compatibility decomposition when
     * one exists (dotted letters keep their identity via the precomposed letter — «پ» of U+FB56 is
     * still peh, not bare beh), or the character itself when Unicode assigns none (the ornate
     * ligatures and dot symbols).
     */
    private fun presentationA(c: Char): String {
        val base = formsABases.getOrElse(c - '\uFB50') { "" }
        return if (base.isEmpty()) c.toString() else base
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

    /** Loose nukta/hamza marks — not the dots of a letter, only the combining form. */
    private fun isMarkDiacritic(c: Char): Boolean = c in '\u064B'..'\u0655' || c == '\u0658'

    private fun isThousandsLookalike(c: Char): Boolean =
        c == PERSIAN_THOUSANDS || c == ARABIC_COMMA || c == ASCII_COMMA

    private fun betweenDigits(s: String, i: Int): Boolean =
        i > 0 && i + 1 < s.length && isAnyDigit(s[i - 1]) && isAnyDigit(s[i + 1])

    private fun isAnyDigit(c: Char): Boolean =
        c in '0'..'9' || c in '\u0660'..'\u0669' || c in '\u06F0'..'\u06F9'
}
