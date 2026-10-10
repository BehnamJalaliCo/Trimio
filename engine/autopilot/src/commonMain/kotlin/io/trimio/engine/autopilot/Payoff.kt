package io.trimio.engine.autopilot

/**
 * Which number pays a piece off, by what it means: a result (her score of seven, twelve kilos
 * down, the money saved), a price or a gift — not how often something happens («هفته‌ای سه جلسه»,
 * "three times a week") or a year. The hook shows it first.
 */
object Payoff {

    /** How strongly [line] pays the piece off, from its numbers ([sense]) and the words around them; negative when it holds none. */
    fun score(sense: Quantities.Sense, texts: List<String>, line: Lines.Line): Double {
        if (sense.quantities.isEmpty()) return -1.0
        val w = line.range.map { Phrases.key(texts[it]) }
        val result = w.any { x -> RESULT.any { x == it || (it.length >= 4 && x.startsWith(it)) } }
        val often = w.any { x -> OFTEN.any { x == it || (it.length >= 4 && x.startsWith(it)) } } ||
            w.zipWithNext().any { (a, b) -> (a == "a" || a == "per" || a == "every") && b in setOf("week", "day", "month", "year") }
        val years = sense.quantities.all { it.value in YEARS && it.decimals == 0 }
        val base = sense.weight()
        return when {
            years -> base * WEAK
            often && !result -> base * WEAK
            result -> base + RESULT_BONUS
            else -> base
        }
    }

    /** A number that may pay off even when small (a score of 7, 12 kilos): said as a result. */
    fun isResult(texts: List<String>, line: Lines.Line): Boolean =
        line.range.any { i -> Phrases.key(texts[i]).let { x -> RESULT.any { x == it || (it.length >= 4 && x.startsWith(it)) } } }

    private val RESULT = setOf(
        "گرفتم", "گرفت", "رسیدم", "رسید", "شدم", "نمره", "نمرم", "نمرهام", "بالاخره", "پسانداز", "صرفهجویی", "سود", "درآمد",
        "got", "scored", "score", "down", "lost", "saved", "earned", "result", "results", "finally", "reached", "doubled",
    )
    private val OFTEN = setOf("هفتهای", "روزی", "ماهی", "سالی", "هرروز", "هفتگی", "روزانه", "daily", "weekly", "monthly", "times")
    private val YEARS = 1300.0..2100.0
    private const val WEAK = 0.2
    private const val RESULT_BONUS = 60.0
}
