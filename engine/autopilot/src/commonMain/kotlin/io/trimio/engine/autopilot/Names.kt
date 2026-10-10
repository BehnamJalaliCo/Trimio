package io.trimio.engine.autopilot

/**
 * Which Latin words are names (brands, products, people) worth a logo tile. In Persian speech an
 * English word with a capital is a name («Claude Code»، «Galaxy»); in English speech a capital
 * only says "a sentence starts here", so a name must sit mid-sentence ("built by Nova Buds Pro")
 * or be in the creator's brief. Weekdays, months and shouted words («SHEET», "SUM") never are.
 */
object Names {

    /** Whether word [i] of [texts] reads as a name, given the creator's [brief]. */
    fun isName(texts: List<String>, i: Int, brief: String = ""): Boolean {
        val w = bare(texts[i])
        if (w.length < 2) return false
        // "Galaxy", "ChatGPT", and camel-case names ("iPhone").
        val capital = w.first().isUpperCase() || w.drop(1).any { it.isUpperCase() }
        if (!w.first().isLetter() || !capital || !w.all { it.isLetterOrDigit() || it in "-+." }) return false
        val low = w.lowercase()
        if (low in COMMON || low in CALENDAR || low in PLACES) return false
        val inBrief = brief.isNotBlank() && Regex("(?i)(^|[^\\p{L}])${Regex.escape(w)}([^\\p{L}]|$)").containsMatchIn(brief)
        if (w.all { !it.isLetter() || it.isUpperCase() } && w.count { it.isLetter() } >= 2 && !inBrief) return false
        val persian = texts.count { t -> t.any { it in '؀'..'ۿ' } } * 2 > texts.size
        val sentenceStart = i == 0 || texts[i - 1].trimEnd().lastOrNull()?.let { c -> c in ".!?؟:…" } == true || texts[i - 1].trimEnd().endsWith("--")
        // A run of capitals carries a name across ("Meet Nova Buds Pro": Nova is mid-sentence).
        return persian || inBrief || !sentenceStart
    }

    /** The word without trailing punctuation or a possessive ("Maya's" → "Maya"). */
    fun bare(w: String) = w.trimEnd('،', ',', '.', '?', '!', ':', ';', '؛', '؟').removeSuffix("'s").removeSuffix("’s")

    /** English words capitalised only because they start a sentence ("I", "The", "Someone"): not names. */
    fun isCommonWord(w: String) = bare(w).lowercase() in COMMON

    private val COMMON = setOf(
        "i", "the", "a", "an", "and", "but", "so", "or", "someone", "somebody", "this", "that", "these", "those", "it", "we", "you", "he", "she", "they",
        "my", "your", "our", "his", "her", "their", "if", "when", "why", "what", "how", "where", "yesterday", "today", "now", "then", "also", "just",
        "no", "yes", "there", "here", "not", "do", "don't", "is", "are", "was", "in", "on", "for", "with", "after", "before", "one", "every", "all",
        "okay", "ok", "get", "while", "fines", "comment", "follow", "save", "share", "link", "go", "meet", "number", "first", "second", "third",
        "type", "add", "toss", "done", "handful", "under", "lift", "walk", "sleep", "six", "seven", "three", "two", "four", "five", "ten",
        "active", "launch", "tuned", "fines", "cheaper", "sum", "control", "alt", "hey", "hi", "hello", "well", "look", "listen", "please",
    )
    private val CALENDAR = setOf(
        "monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday", "january", "february", "march", "april", "may", "june", "july",
        "august", "september", "october", "november", "december",
    )
    private val PLACES = setOf("europe", "america", "asia", "africa", "iran", "tehran", "london", "dubai", "eu", "usa", "uk")
}
