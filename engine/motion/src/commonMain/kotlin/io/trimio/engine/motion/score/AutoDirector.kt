package io.trimio.engine.motion.score

import io.trimio.core.model.transcript.Word

/**
 * Direction the compiler adds on its own when a scene arrives empty — the floor under the smallest
 * model. It reads the transcript like an editor: a hook in the first seconds, numbers become
 * counters, charged words get an icon, and the most stressed phrase of each scene becomes a
 * headline. A model's own beats always replace this for their scene.
 */
internal object AutoDirector {

    private val icons = mapOf(
        "بیتکوین" to "coin", "بیت" to "coin", "bitcoin" to "coin", "btc" to "coin", "اتریوم" to "coin", "ethereum" to "coin", "کریپتو" to "coin", "crypto" to "coin",
        "رشد" to "trend-up", "صعود" to "trend-up", "پامپ" to "trend-up", "growth" to "trend-up", "pump" to "trend-up", "up" to "trend-up",
        "ریزش" to "trend-down", "سقوط" to "trend-down", "دامپ" to "trend-down", "crash" to "trend-down", "dump" to "trend-down",
        "سیگنال" to "bell", "signal" to "bell", "هشدار" to "warning", "خطر" to "warning", "warning" to "warning",
        "تارگت" to "target", "هدف" to "target", "target" to "target", "سود" to "trend-up", "profit" to "trend-up", "ضرر" to "trend-down", "loss" to "trend-down",
        "سریع" to "bolt", "fast" to "bolt", "داغ" to "fire", "hot" to "fire", "جدید" to "spark", "new" to "spark", "امن" to "lock", "secure" to "lock",
    )

    private val growth = setOf("رشد", "سود", "افزایش", "up", "growth")

    fun beats(words: List<Word>, norm: List<String>, range: IntRange, first: Boolean, notes: MutableList<String>): List<BeatScore> {
        if (range.isEmpty() || words.isEmpty()) return emptyList()
        val texts = words.map { it.text }
        val sceneWords = texts.subList(range.first, range.last + 1)
        // Numbers worth a counter: percentages, multi-word or large numbers, any digits.
        val numbers = NumberWords.findAll(sceneWords).filter { f ->
            f.percent || f.count >= 2 || f.value >= 10 || sceneWords[f.start].any { it.isDigit() }
        }
        val out = mutableListOf<BeatScore>()
        numbers.firstOrNull()?.let { out += counter(it, texts, range, notes) }
        val numberWords = numbers.flatMap { f -> (range.first + f.start) until (range.first + f.start + f.count) }.toSet()
        headline(words, range, numberWords, first, numbers.isEmpty(), notes)?.let { out += it }
        icon(norm, range, out, notes)?.let { out += it }
        return out
    }

    /** A counter for a spoken number (no label: the caption already says the words). */
    private fun counter(f: NumberWords.Found, texts: List<String>, range: IntRange, notes: MutableList<String>): BeatScore {
        val at = range.first + f.start
        val after = (at + f.count until minOf(range.last + 1, at + f.count + 2)).map { texts[it] }
        notes += "auto counter at word $at (${f.value})"
        return BeatScore(
            recipe = "counter", at = at, place = "center", until = minOf(range.last, at + f.count + 1),
            value = f.value.toFloat(), decimals = f.decimals, suffix = if (f.percent) "٪" else "",
            prefix = if (f.percent && after.any { NumberWords.normalize(it) in growth }) "+" else "",
        )
    }

    /** The hook (first scene) or headline: the most stressed short phrase away from any counter. */
    private fun headline(words: List<Word>, range: IntRange, numberWords: Set<Int>, first: Boolean, alone: Boolean, notes: MutableList<String>): BeatScore? {
        val free = range.filter { it !in numberWords }
        val peak = free.maxByOrNull { words[it].emphasis } ?: return null
        val anchor = if (first && words[peak].emphasis < 0.5f) free.first() else peak
        val (a, b) = grow(words, range, numberWords, anchor)
        // A lone, unstressed word is not a headline.
        if (!first && a == b && words[peak].emphasis < 0.6f) return null
        val phrase = (a..b).joinToString(" ") { words[it].text }
        notes += "auto ${if (first) "hook" else "headline"}: $phrase"
        return BeatScore(
            recipe = if (first) "slam" else "mask-rise", at = a, until = b, text = phrase,
            emphasis = listOf(words[peak].text).filter { peak in a..b && words[peak].emphasis >= 0.5f },
            energy = if (first) 0.9f else 0.6f, place = if (alone) "center" else "top",
        )
    }

    /** Up to three words around [anchor], never crossing into a number or past a sentence end. */
    private fun grow(words: List<Word>, range: IntRange, numberWords: Set<Int>, anchor: Int): Pair<Int, Int> {
        var a = anchor
        var b = anchor
        fun ok(i: Int) = i in range && i !in numberWords
        while (b - a < 2) {
            val canLeft = ok(a - 1) && !words[a - 1].endsSentence
            val canRight = ok(b + 1) && !words[b].endsSentence
            when {
                canLeft && (!canRight || words[a - 1].emphasis >= words[b + 1].emphasis) -> a--
                canRight -> b++
                else -> break
            }
        }
        return a to b
    }

    /** One icon per scene, on the first charged word no other beat covers. */
    private fun icon(norm: List<String>, range: IntRange, taken: List<BeatScore>, notes: MutableList<String>): BeatScore? {
        val at = range.firstOrNull { i -> icons.containsKey(norm[i]) && taken.none { b -> b.at != null && i in b.at..(b.until ?: b.at) } } ?: return null
        notes += "auto icon ${icons[norm[at]]} at word $at"
        return BeatScore(recipe = "icon", at = at, icon = icons[norm[at]], place = "top", hold = 1.6f)
    }
}
