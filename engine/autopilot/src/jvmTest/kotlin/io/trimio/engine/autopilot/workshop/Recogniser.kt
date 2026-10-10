package io.trimio.engine.autopilot.workshop

import io.trimio.core.model.text.Language
import io.trimio.core.model.text.ScriptDetector
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Word

/**
 * What whisper does to a Persian (or English) talk, as seen on real runs: sound-alike letters
 * (س/ص/ث, ت/ط, ز/ذ/ض/ظ, ه/ح, ق/غ), half-spaces lost or turned into spaces («می کنه»), numbers
 * spelled as said («پنجا», «سی سد»), short words run together, names misheard («Cloud» for
 * Claude), fillers dropped, and word timings stretched across the pause that follows. Seeded, so
 * a scenario always carries the same mistakes; the script's own `{clean|heard}` marks come first.
 */
internal class Recogniser(seed: Long, private val language: String, private val noise: Float) {
    private val dice = Dice(seed)

    class Heard(val words: List<Word>, val truth: List<Word>, val errors: List<String>)

    fun hear(tokens: List<ScriptReader.Token>, timeline: Timeline): Heard {
        val heard = mutableListOf<Pair<String, LongRange>>()
        val errors = mutableListOf<String>()
        val byToken = timeline.words.groupBy { it.token }
        for ((k, tok) in tokens.withIndex()) {
            val said = byToken[k].orEmpty()
            if (said.isEmpty()) continue
            val span = said.first().startMs..said.last().endMs
            when {
                tok.heard != null -> {
                    if (tok.heard != tok.clean) errors += "${tok.clean.joinToString(" ")} → ${tok.heard.joinToString(" ").ifEmpty { "(dropped)" }} (script)"
                    heard += spread(tok.heard, span)
                }
                tok.filler -> if (dice.chance(0.4f)) heard += (if (language == "en") "um," else "اِم") to span
                else -> heard += said.flatMap { w -> mishear(w.text, w.startMs..w.endMs, errors) }
            }
        }
        val merged = merge(heard, errors)
        val stretched = stretch(merged, errors)
        val words = stretched.map { (t, r) -> Word(t, TimeRange(r.first, r.last), confidence = dice.range(0.55f, 1f), language = lang(t)) }
        val truth = timeline.words.filter { !it.filler }.map { Word(it.text, TimeRange(it.startMs, it.endMs), language = lang(it.text)) }
        return Heard(words, truth, errors)
    }

    private fun lang(t: String) = ScriptDetector.detect(t, if (language == "en") Language.English else Language.Persian)

    /** The words of [texts] across [span], each as long as its letters. */
    private fun spread(texts: List<String>, span: LongRange): List<Pair<String, LongRange>> {
        if (texts.isEmpty()) return emptyList()
        val total = texts.sumOf { it.length.coerceAtLeast(1) }.toFloat()
        var t = span.first
        val length = span.last - span.first
        return texts.mapIndexed { i, w ->
            val d = (length * w.length.coerceAtLeast(1) / total).toLong()
            val end = if (i == texts.lastIndex) span.last else t + d - 30
            (w to (t..end.coerceAtLeast(t + 60))).also { t += d }
        }
    }

    private fun mishear(word: String, span: LongRange, errors: MutableList<String>): List<Pair<String, LongRange>> {
        if (language == "en" || word.none { it in '\u0600'..'\u06FF' }) return english(word, span, errors)
        val core = word.trimEnd('،', '.', '!', '؟', ':', '»').trimStart('«')
        val tail = word.substring(word.indexOf(core) + core.length)
        val head = word.substring(0, word.indexOf(core))
        NUMBERS[core]?.takeIf { dice.chance(0.55f) }?.let { odd ->
            errors += "$core → $odd (number)"
            return spread((head + odd + tail).split(' '), span)
        }
        halfSpace(core, head, tail, span, errors)?.let { return it }
        if (core.length >= 3 && core !in COMMON && dice.chance(noise)) {
            val spots = core.indices.filter { i -> SOUND_ALIKE.containsKey(core[i]) && !(core[i] == 'ه' && i == core.lastIndex) }
            if (spots.isNotEmpty()) {
                val i = dice.pick(spots)
                val swapped = core.substring(0, i) + dice.pick(SOUND_ALIKE.getValue(core[i])) + core.substring(i + 1)
                errors += "$core → $swapped (sound-alike)"
                return listOf(head + swapped + tail to span)
            }
        }
        return listOf(word to span)
    }

    /** A half-space lost («میکنه») or turned into a space («می کنه»); null when heard right. */
    private fun halfSpace(core: String, head: String, tail: String, span: LongRange, errors: MutableList<String>): List<Pair<String, LongRange>>? {
        if ('\u200C' !in core) return null
        val r = dice.next()
        return when {
            r < 0.4f -> {
                errors += "$core → ${core.replace("\u200C", "")} (half-space lost)"
                listOf(head + core.replace("\u200C", "") + tail to span)
            }
            r < 0.55f -> {
                errors += "$core → ${core.replace('\u200C', ' ')} (split)"
                spread((head + core.replace('\u200C', ' ') + tail).split(' '), span)
            }
            else -> null
        }
    }

    private fun english(word: String, span: LongRange, errors: MutableList<String>): List<Pair<String, LongRange>> {
        val core = word.trimEnd(',', '.', '!', '?', ':', ';')
        val tail = word.substring(core.length)
        HOMOPHONES[core.lowercase()]?.takeIf { dice.chance(noise * 1.5f) }?.let { h ->
            errors += "$core → $h (homophone)"
            return listOf(h + tail to span)
        }
        return listOf(word to span)
    }

    /** Two short Persian words now and then run together («که این» → «کهاین»). */
    private fun merge(words: List<Pair<String, LongRange>>, errors: MutableList<String>): List<Pair<String, LongRange>> {
        if (language == "en") return words
        val out = mutableListOf<Pair<String, LongRange>>()
        var i = 0
        while (i < words.size) {
            val (a, ra) = words[i]
            val b = words.getOrNull(i + 1)
            val joinable = b != null && a in JOINERS && b.first.length in 2..5 && b.first.all { it in '\u0600'..'\u06FF' }
            if (joinable && dice.chance(0.25f)) {
                errors += "$a ${b!!.first} → ${a + b.first} (merged)"
                out += (a + b.first) to (ra.first..b.second.last)
                i += 2
                continue
            }
            out += words[i]
            i++
        }
        return out
    }

    /** The recogniser's word ends run into the pause after them; some starts begin early. */
    private fun stretch(words: List<Pair<String, LongRange>>, errors: MutableList<String>): List<Pair<String, LongRange>> {
        val out = words.toMutableList()
        var stretched = 0
        for (i in 0 until out.lastIndex) {
            val (a, ra) = out[i]
            val (b, rb) = out[i + 1]
            val gap = rb.first - ra.last
            if (gap < 300) continue
            val r = dice.next()
            if (r < 0.35f) {
                out[i] = a to (ra.first..rb.first - 30)
                stretched++
            } else if (r < 0.47f) {
                out[i + 1] = b to (rb.first - gap * 6 / 10..rb.last)
                stretched++
            }
        }
        if (stretched > 0) errors += "$stretched word timings stretched across pauses"
        return out
    }

    private companion object {
        val SOUND_ALIKE = mapOf(
            'س' to listOf('ص', 'ث'), 'ص' to listOf('س'), 'ث' to listOf('س'),
            'ت' to listOf('ط'), 'ط' to listOf('ت'),
            'ز' to listOf('ذ', 'ض', 'ظ'), 'ذ' to listOf('ز'), 'ض' to listOf('ز'), 'ظ' to listOf('ز'),
            'ه' to listOf('ح'), 'ح' to listOf('ه'),
            'ق' to listOf('غ'), 'غ' to listOf('ق'),
        )

        /** Numbers as whisper writes what it hears. */
        val NUMBERS = mapOf(
            "پنجاه" to "پنجا", "سیصد" to "سی سد", "صد" to "سد", "شصت" to "شست", "هفتاد" to "هفتاد", "هشتاد" to "هشتا",
            "بیست" to "بیس", "دویست" to "دویس", "چهل" to "چل", "پانصد" to "پونصد", "هزار" to "هزار", "دوازده" to "دوازه",
            "شش" to "شیش", "نود" to "نود", "هجده" to "هیجده", "چهارصد" to "چارصد", "پونزده" to "پونزه",
        ).filter { it.key != it.value }

        /** Function words too short and common for whisper to misspell. */
        val COMMON = setOf("که", "این", "اون", "رو", "از", "به", "با", "تو", "هم", "یه", "برای", "است", "هست", "بود", "می\u200C", "من", "ما", "شما")

        /** Short words that whisper runs into the next one. */
        val JOINERS = setOf("که", "بعد", "از", "به", "یه", "تو", "این", "همه", "هر")

        val HOMOPHONES = mapOf(
            "their" to "there", "there" to "their", "your" to "you're", "its" to "it's", "to" to "too", "then" to "than", "weekly" to "weakly",
            "write" to "right", "four" to "for", "whole" to "hole", "break" to "brake",
        )
    }
}
