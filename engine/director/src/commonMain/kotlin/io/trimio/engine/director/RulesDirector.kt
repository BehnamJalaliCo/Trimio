package io.trimio.engine.director

import io.trimio.core.model.style.DesignStyle
import io.trimio.core.model.text.Language
import io.trimio.core.model.text.Numerals
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.styles.StylePack
import kotlin.math.abs
import kotlin.math.roundToInt

/** Picks the style pack for a job: explicit choice, then a style named in the prompt, then tag/mood fit. */
object StyleMatcher {

    fun choose(packs: List<StylePack>, brief: Brief, explicitId: String? = null): StylePack {
        require(packs.isNotEmpty()) { "No style packs available" }
        packs.firstOrNull { it.id == explicitId }?.let { return it }
        brief.styleId?.let { id ->
            packs.firstOrNull { it.id == id }?.let { return it }
            // A style that is not installed yet: the closest installed one of the same family.
            DesignStyle.fromId(id)?.family?.let { family -> packs.firstOrNull { it.family == family }?.let { return it } }
        }
        val energy = brief.energy
        fun score(p: StylePack): Float {
            val tags = p.tags.map(Lexicon::norm).toSet()
            val overlap = brief.tags.count { Lexicon.norm(it) in tags }
            val fit = if (energy == null) 0f else 1f - abs(p.spec.motion.energy - energy)
            return overlap * 2f + fit
        }
        val best = packs.maxBy(::score)
        return if (score(best) <= 1f && energy == null) packs.firstOrNull { it.id == DEFAULT_STYLE } ?: best else best
    }

    const val DEFAULT_STYLE = "liquid-glass"
}

/**
 * The deterministic director. Produces a complete, good edit with no model at all (offline, low-end,
 * or when an LLM fails), and the draft every LLM refines:
 *  - emphasis from prosody + numbers + topic vocabulary + words the user quoted, spaced by energy;
 *  - motion-graphics cues: counters for spoken figures, charts/arrows for market moves, icons, badges;
 *  - hook (opening line) and call to action (closing "follow/subscribe" line);
 *  - music mood from topic and energy.
 */
object RulesDirector {

    fun plan(transcript: Transcript, brief: Brief, pack: StylePack, durationMs: Long): EditPlan {
        val words = transcript.words
        val energy = (brief.energy ?: pack.spec.motion.energy).coerceIn(0f, 1f)
        val texts = words.map { it.text }
        val numbers = words.indices.associateWith { NumberWords.at(texts, it) }.filterValues { it != null }.mapValues { it.value!! }

        val emphasis = emphasis(words, brief, numbers.keys, energy)
        val elements = elements(words, brief, numbers, energy, durationMs)
        val hookEnd = hookEnd(transcript)
        val ctaStart = ctaStart(transcript).takeIf { it > hookEnd } ?: -1

        return EditPlan(
            styleId = pack.id,
            energy = energy,
            captionMode = brief.captionMode?.name ?: "auto",
            cutSilences = brief.cutSilences,
            cutFillers = brief.cutFillers,
            music = music(brief, energy),
            hookEnd = hookEnd,
            ctaStart = ctaStart,
            emphasis = emphasis,
            elements = elements,
            headline = brief.headline.orEmpty(),
            summaryFa = "سبک «${pack.nameFa}»، ${fa(emphasis.size)} تأکید و ${fa(elements.size)} المان متحرک",
            summaryEn = "${pack.nameEn} style, ${emphasis.size} emphasised words, ${elements.size} animated elements",
        )
    }

    private fun fa(n: Int) = Numerals.toPersian(n.toString())

    // --- Emphasis ----------------------------------------------------------------------------------

    private fun emphasis(words: List<Word>, brief: Brief, numberStarts: Set<Int>, energy: Float): List<Int> {
        val scored = words.mapIndexedNotNull { i, w ->
            val key = Lexicon.norm(w.text)
            val forced = key in brief.emphasize
            val latinInPersian = w.language == Language.English && words.count { it.language == Language.Persian } > words.size / 2
            if (!forced && (key in Lexicon.stopwords || key.length < 2 && !latinInPersian)) return@mapIndexedNotNull null
            var score = w.emphasis
            if (i in numberStarts) score += 0.45f
            if (Lexicon.topics.values.any { key in it } || key in Lexicon.rise || key in Lexicon.fall || key in Lexicon.signal || key in Lexicon.warning || key in Lexicon.star) score += 0.35f
            if (latinInPersian) score += 0.15f
            if (forced) score += 2f
            Triple(i, score, forced)
        }
        val threshold = 0.75f - 0.2f * energy
        val minGapMs = (1100 - 500 * energy).toLong()
        val cap = (words.size * (0.12f + 0.18f * energy)).roundToInt().coerceAtLeast(1)

        val chosen = mutableListOf<Int>()
        for ((i, score, forced) in scored.filter { it.second >= threshold || it.third }.sortedByDescending { it.second }) {
            if (chosen.size >= cap && !forced) continue
            val t = words[i].range.startMs
            if (!forced && chosen.any { abs(words[it].range.startMs - t) < minGapMs }) continue
            chosen += i
        }
        return chosen.sorted()
    }

    // --- Motion-graphics cues ----------------------------------------------------------------------

    private fun elements(words: List<Word>, brief: Brief, numbers: Map<Int, NumberWords.Match>, energy: Float, durationMs: Long): List<ElementCue> {
        val market = brief.topics.any { it == Lexicon.Topic.Crypto || it == Lexicon.Topic.Forex || it == Lexicon.Topic.Trading } ||
            words.any { Lexicon.norm(it.text).let { k -> Lexicon.topics.getValue(Lexicon.Topic.Crypto).contains(k) || Lexicon.topics.getValue(Lexicon.Topic.Trading).contains(k) } }
        val persianDigits = words.count { it.language == Language.Persian } >= words.size / 2

        data class Candidate(val cue: ElementCue, val priority: Int)
        val candidates = mutableListOf<Candidate>()
        var skipUntil = -1
        words.forEachIndexed { i, w ->
            if (i < skipUntil) return@forEachIndexed
            val key = Lexicon.norm(w.text)
            numbers[i]?.let { m ->
                val label = words.getOrNull(i + m.length)?.text?.takeIf { Lexicon.norm(it) !in Lexicon.stopwords }?.trimEnd('.', '،', ',', '!', '؟', '?').orEmpty()
                candidates += Candidate(ElementCue(i, "counter", counterValue(m), label), 3)
                skipUntil = i + m.length
                return@forEachIndexed
            }
            val cue = when (key) {
                in Lexicon.rise -> ElementCue(i, if (market) "chart-up" else "arrow-up")
                in Lexicon.fall -> ElementCue(i, if (market) "chart-down" else "arrow-down")
                in Lexicon.signal -> ElementCue(i, "badge", w.text.trimEnd('.', '،', ',', '!', '؟').let { if (persianDigits) it else it.uppercase() })
                in Lexicon.coin -> ElementCue(i, "icon", "coin")
                in Lexicon.success -> ElementCue(i, "icon", "check")
                in Lexicon.warning -> ElementCue(i, "icon", "bolt")
                in Lexicon.star -> ElementCue(i, "icon", "star")
                in Lexicon.love -> ElementCue(i, "icon", "heart")
                else -> null
            } ?: return@forEachIndexed
            candidates += Candidate(cue, if (cue.kind.startsWith("chart") || cue.kind == "badge") 2 else 1)
        }

        val gapMs = (2600 - 1200 * energy).toLong()
        val cap = (durationMs / 3_000).toInt().coerceAtLeast(1)
        val accepted = mutableListOf<ElementCue>()
        for (c in candidates.sortedWith(compareByDescending<Candidate> { it.priority }.thenBy { it.cue.word })) {
            if (accepted.size >= cap) break
            val t = words[c.cue.word].range.startMs
            if (accepted.none { abs(words[it.word].range.startMs - t) < gapMs }) accepted += c.cue
        }
        return accepted.sortedBy { it.word }
    }

    /** "5%", "$1200", "40 تومان" — the composer splits prefix/suffix back out. */
    private fun counterValue(m: NumberWords.Match): String {
        val n = if (m.value == kotlin.math.floor(m.value) && abs(m.value) < 1e12) m.value.toLong().toString() else ((m.value * 100).roundToInt() / 100.0).toString()
        return when (m.unit) {
            NumberWords.Unit.Percent -> "$n%"
            NumberWords.Unit.Dollar -> "$$n"
            NumberWords.Unit.Toman -> "$n تومان"
            null -> n
        }
    }

    // --- Structure --------------------------------------------------------------------------------

    private fun hookEnd(transcript: Transcript): Int {
        val words = transcript.words
        if (words.isEmpty()) return -1
        val firstLine = transcript.lines(maxWords = 8).first()
        val lastOfLine = words.indexOf(firstLine.last())
        if (firstLine.last().range.endMs <= 4_500) return lastOfLine
        return words.indexOfLast { it.range.startMs < 3_000 }.coerceAtLeast(0)
    }

    private fun ctaStart(transcript: Transcript): Int {
        val words = transcript.words
        if (words.size < 6) return -1
        val from = (words.size * 0.65f).toInt()
        val hit = (from until words.size).firstOrNull { Lexicon.norm(words[it].text) in Lexicon.cta } ?: return -1
        // Start the CTA at the beginning of the line containing it.
        var start = hit
        while (start > from && !words[start - 1].endsSentence && words[start].range.startMs - words[start - 1].range.endMs < 450) start--
        return start
    }

    private fun music(brief: Brief, energy: Float): String = when {
        !brief.music -> "none"
        energy >= 0.75f -> "energetic"
        Lexicon.Topic.Tech in brief.topics -> "cinematic"
        brief.topics.any { it == Lexicon.Topic.Crypto || it == Lexicon.Topic.Forex || it == Lexicon.Topic.Trading || it == Lexicon.Topic.Business } ->
            if (energy >= 0.55f) "uplifting" else "corporate"
        energy < 0.45f -> "chill"
        else -> "uplifting"
    }
}
