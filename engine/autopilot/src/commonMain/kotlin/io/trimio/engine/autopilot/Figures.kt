package io.trimio.engine.autopilot

import io.trimio.engine.motion.score.BeatScore
import io.trimio.engine.motion.score.Words

/**
 * The planner's graphics for numbers with a meaning ([Quantities]): a voucher for money given, a
 * stats board for views and comments, a countdown for time left, a progress bar for a quota. Each
 * returns null when the line does not hold what it needs, and the planner falls back to a counter.
 * Labels come from the line's own words («سرمایه اولیه», «تا پایان کمپین», «۱۰۰۰ نفر اول»).
 */
internal class Figures(
    private val texts: List<String>,
    /** [texts], normalised ([io.trimio.engine.motion.score.NumberWords.normalize]). */
    private val norm: List<String>,
    /** The piece is in Persian: Persian labels and digits. */
    private val rtl: Boolean,
    private val locate: (String, Lines.Line) -> IntRange?,
) {

    /** What a counted thing is, for the label under its number («نفر اول», «ظرفیت جدید», «بازدید»). */
    fun unitLabel(q: Quantities.Quantity): String? = when (q.kind) {
        Quantities.Kind.People -> if (q.first) (if (rtl) "${q.unit} اول" else "first ${q.unit}") else q.unit
        Quantities.Kind.Capacity -> if (q.opened) (if (rtl) "ظرفیت جدید" else "new ${q.unit}") else q.unit
        Quantities.Kind.Percent, Quantities.Kind.Plain -> null
        else -> q.unit.ifBlank { null }
    }

    /** Money viewers get: the amount, its unit, what it is, and the coin's mark. */
    fun voucher(line: Lines.Line, sense: Quantities.Sense, read: LineRead, e: Float, place: String): BeatScore? {
        val q = sense.voucher ?: sense.money ?: return null
        return voucherBeat(q, read, voucherLabel(line, q, read), place).copy(until = line.last, energy = maxOf(e, 0.8f))
    }

    fun voucherBeat(q: Quantities.Quantity, read: LineRead, label: String?, place: String): BeatScore {
        // The compiler attaches the brand's mark to the first item: the coin's canonical name.
        val brand = q.brand ?: read.items.firstOrNull { name -> name.any { it in 'A'..'Z' || it in 'a'..'z' } }
        return BeatScore(
            recipe = "voucher", at = q.at, value = q.value.toFloat(), decimals = q.decimals, suffix = q.unit.ifBlank { null },
            label = label, items = listOfNotNull(brand), place = place,
        )
    }

    /** What the money is: the words right after the unit («سرمایه اولیه»), else "voucher" if said, else the line's title. */
    fun voucherLabel(line: Lines.Line, q: Quantities.Quantity, read: LineRead): String? {
        val after = mutableListOf<String>()
        var i = q.last + 1
        fun labelWord(j: Int) = Words.isContent(texts[j]) && texts[j].none { it.isDigit() } && Quantities.unitOf(texts[j]) == null
        while (i <= line.last && after.size < 2 && labelWord(i)) {
            after += texts[i].trimEnd('.', '،', ',')
            if (texts[i].last() in ".،,") break
            i++
        }
        if (after.isNotEmpty()) return after.joinToString(" ")
        val named = line.range.firstOrNull { norm[it].trimEnd('.', '،', ',') in VOUCHER_WORDS }
        if (named != null) return texts[named].trimEnd('.', '،', ',')
        return read.title.takeIf { t -> t.isNotBlank() && t.none { it.isDigit() } && locate(t, Lines.Line(q.at, q.last)) == null }
    }

    /** Views and comments (or any two or three numbers said together) on one board. */
    fun stats(line: Lines.Line, sense: Quantities.Sense, read: LineRead, e: Float, place: String): BeatScore? {
        val qs = sense.social.ifEmpty { sense.quantities.filter { it.value >= 1 && it.kind != Quantities.Kind.Duration } }.take(MAX_STATS)
        if (qs.size < 2) return null
        val labels = qs.mapIndexed { i, q -> q.unit.ifBlank { read.items.getOrNull(i).orEmpty() } }
        // The time it took, when said («کمتر از دوازده ساعت»), rides along as the board's caption.
        val span = sense.quantities.firstOrNull { it.kind == Quantities.Kind.Duration && !it.remaining }?.let { d ->
            val less = (maxOf(line.first, d.at - 2) until d.at).any { norm[it] in LESS }
            val n = Quantities.digits(d.value, rtl)
            if (rtl) (if (less) "کمتر از $n ${d.unit}" else "در $n ${d.unit}") else (if (less) "in under $n ${d.unit}" else "in $n ${d.unit}")
        }
        return BeatScore(
            recipe = "stats", at = qs.first().at, until = line.last, items = labels, points = qs.map { it.value.toFloat() },
            label = span, energy = maxOf(e, 0.75f), place = place,
        )
    }

    /** Time left: hours or minutes ticking down to what ends. */
    fun countdown(line: Lines.Line, sense: Quantities.Sense, e: Float, place: String): BeatScore? {
        val q = sense.deadline ?: sense.quantities.firstOrNull { it.kind == Quantities.Kind.Duration } ?: return null
        return BeatScore(
            recipe = "countdown", at = q.at, until = line.last, value = q.value.toFloat(), decimals = q.decimals, suffix = q.unit.ifBlank { null },
            label = deadlineLabel(line), energy = maxOf(e, 0.8f), place = place,
        )
    }

    /** «تا پایان کمپین»: what the time runs out on, from the words of the line. */
    private fun deadlineLabel(line: Lines.Line): String {
        val noun = line.range.firstNotNullOfOrNull { i -> DEADLINES.entries.firstOrNull { norm[i].startsWith(it.key) }?.value }
        return when {
            noun != null && rtl -> "تا پایان $noun"
            noun != null -> "until the $noun ends"
            rtl -> "زمان باقی‌مانده"
            else -> "left"
        }
    }

    /** A quota filling up (the first thousand people), stamped full when it is. */
    fun progress(line: Lines.Line, sense: Quantities.Sense, read: LineRead, e: Float, place: String): BeatScore? {
        val q = sense.quota ?: sense.quantities.firstOrNull { it.kind in COUNTED }
        val filled = sense.filled
        val at = q?.at ?: filled?.first ?: return null
        val value = if (q?.kind == Quantities.Kind.Percent) q.value.toFloat().coerceIn(0f, 100f) else 100f
        val counted = q?.takeIf { it.kind != Quantities.Kind.Percent } ?: sense.quantities.firstOrNull { it.kind in COUNTED }
        val label = counted?.let { c ->
            val n = Quantities.digits(c.value, rtl)
            when {
                rtl -> listOfNotNull(n, c.unit.ifBlank { null }, "اول".takeIf { c.first }).joinToString(" ")
                else -> listOfNotNull("first".takeIf { c.first }, n, c.unit.ifBlank { null }).joinToString(" ")
            }
        } ?: read.title.ifBlank { null }
        return BeatScore(
            recipe = "progress", at = at, until = line.last, value = value, from = 0f, label = label,
            text = if (value >= 100f) (if (rtl) "تکمیل" else "FULL") else null, energy = maxOf(e, 0.7f), place = place,
        )
    }

    private companion object {
        const val MAX_STATS = 3
        val COUNTED = setOf(Quantities.Kind.People, Quantities.Kind.Capacity)
        val VOUCHER_WORDS = setOf("ووچر", "وچر", "voucher", "هدیه", "جایزه", "بونوس", "bonus", "gift")
        val LESS = setOf("کمتر", "زیر", "under", "less")
        val DEADLINES = mapOf(
            "کمپین" to "کمپین", "جشنواره" to "جشنواره", "تخفیف" to "تخفیف", "حراج" to "حراج", "ثبتنام" to "ثبت‌نام", "مهلت" to "مهلت",
            "مسابقه" to "مسابقه", "چالش" to "چالش", "پیشنهاد" to "پیشنهاد", "campaign" to "campaign", "sale" to "sale", "offer" to "offer", "deal" to "deal",
        )
    }
}
