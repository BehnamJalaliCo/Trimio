package io.trimio.engine.autopilot

/**
 * The names a brief writes in Latin letters (products, brands, people: Nobitex, "Google Calendar",
 * "Galaxy S24 Ultra", "Coach Maya's"), told apart from the everyday English the brief also uses by
 * case and by the English word list ([PersianSpelling.english]). A misheard name takes the brief's
 * spelling ("Cloud" → Claude, "Chat GPT" → ChatGPT, «نوبیتکس» → Nobitex, «گوگل کلندر» → Google
 * Calendar); a common word of the brief ("News", "make", "with", "charts") is never a target, so
 * "need", "made" and "watch" stay what was said.
 */
internal class BriefNames(brief: String, private val english: Map<String, Int>) {

    /** Every name, one to four words long, as the brief spells it. */
    val phrases: List<List<String>> = brief.lines().flatMap(::runs).flatMap { run ->
        (1..minOf(MAX_WORDS, run.size)).flatMap { k -> run.windowed(k) }
    }.distinct()

    private val bySkeleton: Map<String, List<String>> = phrases.groupBy { p -> p.joinToString("") { latinSkeleton(it) } }
        .mapValues { (_, ps) -> ps.first() }.filterKeys { it.isNotEmpty() }

    private val single: List<String> = phrases.filter { it.size == 1 }.map { it.first() }

    /**
     * A Latin word heard for a single-word name: the same word in another case ("coach" → Coach), or a
     * close spelling of it ("Cloud" → Claude, "Nation" → Notion, "Mya's" → Maya's). An everyday English
     * word must be very close (one letter, or two with the first two letters the same).
     */
    fun respell(heard: String): String? {
        if (!latin(heard)) return null
        single.firstOrNull { it.equals(heard, ignoreCase = true) }?.let { return it.takeIf { n -> n != heard } }
        return single.firstOrNull { close(heard.lowercase(), it.lowercase()) }
    }

    /**
     * The name heard at word [i] over [words] (cores, punctuation off): Latin halves of one name
     * ("Chat GPT" → ChatGPT) or a name said in Persian («لومینا», «گلکسی اس ۲۴ اولترا»), matched on
     * consonants. Returns the name and how many words it takes, or null.
     */
    fun at(raw: List<String>, i: Int, persianCount: (String) -> Int): Pair<String, Int>? {
        // A name runs on only across plain spaces: «گوگل، کلندر» is two things.
        val open = (i until raw.size).takeWhile { j -> j == i || raw[j - 1].last().isLetterOrDigit() }.size
        val words = raw.subList(i, i + minOf(open, MAX_WORDS)).map { w -> w.trim { !it.isLetterOrDigit() } }
        val a = words[0]
        val b = words.getOrNull(1)
        if (b != null && latin(a) && latin(b)) single.firstOrNull { it.equals(a + b, ignoreCase = true) }?.let { return it to 2 }
        for (k in words.size downTo 1) {
            val span = words.subList(0, k)
            if (!span.all(::spoken) || span.none { w -> w.any { it.isLetter() } }) continue
            val key = span.joinToString("") { persianSkeleton(it) }
            val name = bySkeleton[key] ?: continue
            // A common Persian word is itself, unless its consonants say much: «نیک» (good) is no Nike, «مقایسه» no "Max";
            // «سامسونگ» is Samsung. Of several words, one must be rare («گوگل کلندر»).
            val rare = span.any { w -> w.any { it.isLetter() } && persianCount(w) < COMMON_PERSIAN }
            val sure = key.length >= LONG_KEY + k || rare
            if (sure) return name.joinToString(" ") to k
        }
        return null
    }

    // ------------------------------------------------------------------ names

    /** Runs of name words in one line of the brief; a comma or a sentence end closes a run. */
    private fun runs(line: String): List<List<String>> {
        val out = mutableListOf<List<String>>()
        var run = mutableListOf<String>()
        var start = true
        // Persian has no capitals: a capitalised Latin word in a Persian line is a name, sentence start or not.
        val persian = line.any { it in '\u0600'..'\u06FF' }
        for (raw in line.split(' ', '\t').filter { it.isNotEmpty() }) {
            val core = raw.trim { !it.isLetterOrDigit() }
            val digits = core.isNotEmpty() && core.all { it.isDigit() }
            val name = core.isNotEmpty() && latin(core) && (if (digits) run.isNotEmpty() else isName(core, start && !persian))
            if (name) run += core
            if (!name || raw.last() in ",;:.!?،؛؟") {
                if (run.isNotEmpty()) out += run
                run = mutableListOf()
            }
            start = raw.last() in ".!?:؟"
        }
        if (run.isNotEmpty()) out += run
        return out
    }

    /** "iPhone", "S24", "ChatGPT", "Notion" mid-sentence, or a word the English list does not know. */
    private fun isName(w: String, sentenceStart: Boolean): Boolean {
        val letters = w.filter { it.isLetter() }
        if (letters.isEmpty()) return false
        val inner = letters.drop(1).any { it.isUpperCase() }
        val mixed = w.any { it.isDigit() } || inner
        val capital = letters.first().isUpperCase() && !sentenceStart
        val unknown = english.isNotEmpty() && w.lowercase() !in english && '-' !in w && w.length >= MIN_NAME
        return mixed || capital || unknown
    }

    private fun close(heard: String, name: String): Boolean {
        if (heard.length < MIN_HEARD || name.length < MIN_NAME || heard.first() != name.first()) return false
        if (name.startsWith(heard) || heard.startsWith(name)) return false
        val d = distance(heard, name)
        val everyday = heard in english
        return when {
            !everyday -> d <= 2
            heard.length < MIN_EVERYDAY -> false
            else -> d == 1 || (d == 2 && heard[1] == name[1])
        }
    }

    companion object {
        private const val MAX_WORDS = 4
        private const val LONG_KEY = 4
        private const val COMMON_PERSIAN = 1000
        private const val MIN_NAME = 3
        private const val MIN_HEARD = 4
        private const val MIN_EVERYDAY = 5

        private fun latin(w: String) = w.isNotEmpty() && w.any { it.isLetterOrDigit() } &&
            w.all { it in 'a'..'z' || it in 'A'..'Z' || it.isDigit() || it in "'-.&+" }

        /** Persian letters and digits only (a name said in Persian, its numbers in either script). */
        private fun spoken(w: String) = w.isNotEmpty() && w.all { it in '\u0600'..'\u06FF' || it == '\u200C' || it.isDigit() }

        /**
         * Consonants as a Persian speaker writes them, the way both scripts meet: «لومینا» and Lumina are
         * "lmn", «نوبیتکس» and Nobitex "nbtks", «گلکسی» and Galaxy "glks"; vowels, «ه» and doubled letters
         * dropped, digits kept.
         */
        fun latinSkeleton(w: String): String {
            var t = w.lowercase()
            for ((a, b) in LATIN_DIGRAPHS) t = t.replace(a, b)
            val out = StringBuilder()
            for ((i, ch) in t.withIndex()) {
                val c = when {
                    ch == 'c' -> if (t.getOrNull(i + 1)?.let { it in "eiy" } == true) 's' else 'k'
                    ch in "aeiouyh" -> null
                    ch.isLetterOrDigit() -> ch
                    else -> null
                }
                if (c != null && out.lastOrNull() != c) out.append(c)
            }
            return out.toString()
        }

        fun persianSkeleton(w: String): String {
            val out = StringBuilder()
            for ((i, ch) in w.withIndex()) {
                val c = if (ch == 'و' && i == 0) 'v' else PERSIAN_SOUNDS[ch] ?: if (ch in '۰'..'۹') '0' + (ch - '۰') else if (ch.isDigit()) ch else null
                if (c != null && out.lastOrNull() != c) out.append(c)
            }
            return out.toString()
        }

        private val LATIN_DIGRAPHS = listOf(
            "ph" to "f", "sh" to "S", "ch" to "C", "kh" to "K", "zh" to "Z", "th" to "t", "ck" to "k", "qu" to "k", "x" to "ks", "w" to "v", "q" to "k",
        )

        private val PERSIAN_SOUNDS: Map<Char, Char> = buildMap {
            put('ب', 'b'); put('پ', 'p'); put('ت', 't'); put('ط', 't'); put('ث', 's'); put('س', 's'); put('ص', 's')
            put('ج', 'j'); put('چ', 'C'); put('خ', 'K'); put('د', 'd'); put('ذ', 'z'); put('ز', 'z'); put('ض', 'z'); put('ظ', 'z')
            put('ر', 'r'); put('ژ', 'Z'); put('ش', 'S'); put('غ', 'g'); put('ق', 'k'); put('ف', 'f'); put('ک', 'k'); put('ك', 'k')
            put('گ', 'g'); put('ل', 'l'); put('م', 'm'); put('ن', 'n')
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
}
