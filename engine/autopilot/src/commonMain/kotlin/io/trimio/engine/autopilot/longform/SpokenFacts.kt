package io.trimio.engine.autopilot.longform

import io.trimio.core.model.transcript.Word
import io.trimio.engine.motion.recipe.BrandLibrary
import io.trimio.engine.motion.score.BeatScore
import io.trimio.engine.motion.score.NumberWords

/**
 * The pro kind's spoken facts on screen: a number worth writing (a percentage, digits, a large or multi-word
 * number, never a year) pops as a counter when it is said, and a brand named in Latin letters that the library
 * knows by its real mark shows it. Persian names are left alone: «سیگنال» in a trading talk is not the messenger.
 * One at a time, at most one every [EVERY] seconds, never while another top device ([busy]) is up and never on
 * the end screen.
 */
internal object SpokenFacts {
    fun beats(words: List<Word>, brands: BrandLibrary, busy: List<ClosedFloatingPointRange<Float>>, endScreen: Float): List<BeatScore> {
        val texts = words.map { it.text }
        val candidates = (numbers(words, texts) + names(words, texts, brands)).sortedBy { it.time }
        val taken = busy.toMutableList()
        var next = 0f
        return candidates.filter { b ->
            val from = b.time ?: return@filter false
            val span = from..(from + (b.hold ?: HOLD) + GAP)
            val free = from >= next && span.endInclusive < endScreen && taken.none { it.start < span.endInclusive && span.start < it.endInclusive }
            if (free) {
                taken += span
                next = from + EVERY
            }
            free
        }
    }

    private fun numbers(words: List<Word>, texts: List<String>): List<BeatScore> = NumberWords.findAll(texts)
        .filter { f -> !f.year && (f.percent || f.count >= 2 || f.value >= BIG || texts[f.start].any { it.isDigit() }) }
        .map { f ->
            BeatScore(
                recipe = "counter", time = words[f.start].range.startMs / MS, hold = HOLD, place = "top",
                value = f.value.toFloat(), decimals = f.decimals, suffix = if (f.percent) "٪" else "",
            )
        }

    private fun names(words: List<Word>, texts: List<String>, brands: BrandLibrary): List<BeatScore> = texts.indices.mapNotNull { i ->
        val name = texts[i].trimEnd('،', ',', '.', '!', '?', '؟')
        val latin = name.length >= MIN_NAME && name.first().isLetter() && name.all { it.isLetterOrDigit() && it.code < LATIN_END || it in "-+." }
        val mark = if (latin) brands.find(name)?.takeIf { !it.isMonogram } else null
        mark?.let { BeatScore(recipe = "logos", time = words[i].range.startMs / MS, hold = HOLD, place = "top", items = listOf(it.title)) }
    }.distinctBy { it.items }

    private const val MS = 1000f
    private const val HOLD = 2.6f
    private const val GAP = 1f
    private const val EVERY = 30f
    private const val BIG = 100.0
    private const val MIN_NAME = 3
    private const val LATIN_END = 0x250
}
