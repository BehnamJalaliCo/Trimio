package io.trimio.engine.autopilot

import io.trimio.engine.motion.score.NumberWords

/**
 * The graphics a creator asks for by name in the brief: "the $79 launch price in the first
 * second", "the Friday deadline as a countdown", "the 5,000 units as a progress bar", «۸۰ درصد رو با
 * گرافیک عددی نشون بده», «سه علامت رو لیست کن», «درصدها رو با نمودار». The planner gives each to the
 * line where the speech says it — the brief chooses the form, the speech the moment.
 */
object BriefAsks {

    /** One request: the graphic [kind], the number it shows (when the brief names one) and the brief's words around it. */
    data class Ask(val kind: String, val value: Double?, val words: Set<String>)

    fun read(brief: String): List<Ask> {
        val out = mutableListOf<Ask>()
        for (sentence in brief.split('\n', '.', '!', '؟', '?', '؛', ';').map { it.trim() }.filter { it.isNotEmpty() }) {
            val general = kindOf(sentence)
            // ", " and «، » part one request from the next; "5,000" stays one number.
            val clauses = sentence.split(", ", "، ", " and ", " و ").map { it.trim() }.filter { it.isNotEmpty() }
            for (c in clauses) {
                val tokens = c.split(' ').filter { it.isNotBlank() }
                val number = NumberWords.findAll(tokens).firstOrNull { it.value > 0 }
                val kind = kindOf(c) ?: when {
                    // «تخفیف چهل درصد … رو برجسته کن», «متراژ و قیمت رو شیک نشون بده»: show the number.
                    (number != null || measured(c)) && highlights(sentence) -> "counter"
                    number != null -> general
                    else -> null
                } ?: continue
                val words = tokens.map { Phrases.key(it) }.filter { it.length >= 3 && it !in KIND_WORDS }.toSet()
                out += Ask(kind, number?.value, words)
                if (kind != "hook" && hook(c)) out += Ask("hook", number?.value, words)
            }
        }
        return out.distinct()
    }

    /**
     * The asks placed on the lines that say them: an ask with a number goes to the line saying
     * it, a deadline to the line naming its day. Returns the new readings and the lines changed
     * (they are the piece's requested moments). Lines in [keep] (the ask, a chat thread) are left alone.
     */
    fun place(asks: List<Ask>, reads: List<LineRead>, texts: List<String>, lines: List<Lines.Line>, senses: List<Quantities.Sense>, keep: Set<Int>): Pair<List<LineRead>, Set<Int>> {
        val out = reads.toMutableList()
        val placed = mutableSetOf<Int>()
        for (a in asks) {
            val k = lineFor(a, texts, lines, senses)?.takeIf { it !in keep && out[it].show != "comment" }
            val show = k?.let { j ->
                when (a.kind) {
                    "counter" -> "counter".takeIf { out[j].show !in setOf("voucher", "stats", "countdown", "progress") }
                    "countdown", "progress", "voucher" -> a.kind
                    else -> null
                }
            }
            if (k != null && show != null) {
                out[k] = out[k].copy(show = show)
                placed += k
            }
        }
        return out to placed
    }

    private fun lineFor(a: Ask, texts: List<String>, lines: List<Lines.Line>, senses: List<Quantities.Sense>): Int? {
        val v = a.value
        if (v != null) return lines.indices.firstOrNull { k -> senses[k].quantities.any { kotlin.math.abs(it.value - v) < 0.01 && it.at in lines[k].range } }
        if (a.kind != "countdown") return null
        // "the Friday deadline": the line naming that day, said as a deadline.
        val day = a.words.filter { it in DAYS }
        return lines.indices.firstOrNull { k -> lines[k].range.any { i -> Phrases.key(texts[i]).let { w -> w in day || (day.isEmpty() && w in DAYS) } } }
    }

    /** The graphic a clause names, if any. */
    private fun kindOf(c: String): String? {
        val t = c.lowercase().replace("‌", " ")
        return when {
            hook(c) -> "hook"
            listOf("countdown", "شمارش معکوس", "deadline", "مهلت").any { it in t } -> "countdown"
            listOf("progress", "نوار ظرفیت", "نوار").any { it in t } -> "progress"
            listOf("chart", "نمودار", "graph").any { it in t } -> "chart"
            listOf("لیست", "list").any { it in t } -> "list"
            listOf("voucher", "ووچر").any { it in t } -> "voucher"
            listOf("as a number", "counter", "با عدد", "عددی", "عدد", "شمارش", "numbers").any { it in t } -> "counter"
            else -> null
        }
    }

    private fun hook(c: String): Boolean {
        val t = c.lowercase().replace("‌", " ").replace("ٔ", "")
        return listOf("hook", "هوک", "first second", "ثانیه اول", "ثانیه ی اول", "ثانیهی اول", "اول ویدیو").any { it in t }
    }

    /** The clause names a measure of something said («متراژ», «قیمت», "price", «درصد»). */
    private fun measured(c: String) = listOf("متراژ", "قیمت", "price", "درصد", "percent", "size", "battery", "باتری").any { it in c.lowercase() }

    private fun highlights(s: String) = listOf("برجسته", "نشون بده", "نشان بده", "show", "highlight", "شیک").any { it in s.lowercase() }

    /** Days and times a deadline is named by. */
    val DAYS = setOf(
        "friday", "saturday", "sunday", "monday", "tuesday", "wednesday", "thursday", "midnight", "tonight", "tomorrow",
        "جمعه", "شنبه", "یکشنبه", "دوشنبه", "سهشنبه", "چهارشنبه", "پنجشنبه", "امشب", "فردا",
    )
    private val KIND_WORDS = setOf("countdown", "deadline", "progress", "chart", "list", "voucher", "counter", "number", "hook", "show", "with")
}
