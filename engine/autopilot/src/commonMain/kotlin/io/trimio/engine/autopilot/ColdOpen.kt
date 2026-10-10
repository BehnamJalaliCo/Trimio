package io.trimio.engine.autopilot

import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.transcript.Word
import io.trimio.engine.motion.score.CutPoints
import io.trimio.engine.motion.score.EditPlan
import io.trimio.engine.motion.score.Words

/**
 * The editor's oldest hook: the payoff said first. When the piece's strongest line comes late, its
 * figure and the phrase that names it («سیصد و پنجاه تتر سرمایهٔ اولیه») play before the piece
 * starts from its beginning, so the first second is the reason to keep watching. Words keep their
 * indices; only the clock moves (EditPlan.withColdOpen).
 *
 * Finesse is in the cuts: with the voice at hand every edge is placed on the waveform, not on the
 * recogniser's clock ([CutPoints]) — the opening starts in the valley before its first onset, ends
 * after its last tail has decayed, a pause inside it is tightened to a breath like the body's, and
 * the body restarts just before its first word's real onset.
 */
data class ColdOpen(
    /** Source seconds the opening spans (first cut to last). */
    val source: ClosedFloatingPointRange<Float>,
    /** The hook line and the words heard in the opening (indices into the transcript). */
    val line: Int,
    val words: IntRange,
    /** What plays, in order: [source] with any pause inside it tightened to a breath. */
    val pieces: List<ClosedFloatingPointRange<Float>> = listOf(source),
    /** Source time the body restarts at (just before its first word's onset); null keeps the cut list's own start. */
    val restart: Float? = null,
    /** The opening's in and out cuts on the voice, for the report (null without the voice). */
    val cuts: Pair<CutPoints.Cut, CutPoints.Cut>? = null,
) {
    /** Output seconds of the opening. */
    val length: Float get() = pieces.fold(0f) { sum, r -> sum + (r.endInclusive - r.start) }

    /** [edit] with this opening first and the body restarting [gap] seconds (the transition) after it. */
    fun applyTo(edit: EditPlan, gap: Float): EditPlan = edit.withColdOpen(pieces, gap, restart)

    companion object {
        /**
         * The opening for this piece, or none: only when the hook line comes after the first few
         * seconds and holds a quantity (money, views, a deadline). A brief asking for the payoff
         * "in the first second" always gets one; otherwise it is one of the seed's choices. With the
         * [voice] (on the transcript's clock) the cuts land on the waveform; without it, on the words.
         */
        fun choose(
            words: List<Word>,
            lines: List<Lines.Line>,
            understanding: Understanding,
            prompt: String,
            seed: Long,
            voice: PcmAudio? = null,
        ): ColdOpen? {
            // The model's hook line when it holds a late figure, else the line whose figure weighs most.
            val k = understanding.hook?.line?.takeIf { h -> lines.getOrNull(h)?.let { payoff(words, it) } != null }
                ?: strongest(words, lines) ?: return null
            val line = lines[k]
            if (!asked(prompt) && !Rng(seed).fork(SALT).chance(CHANCE)) return null
            val span = payoff(words, line)?.let { q -> span(words, q) }?.takeIf { seconds(words, it.first, it.last) >= MIN_S } ?: return null
            return if (voice == null) onTheWords(words, k, span) else onTheVoice(words, k, span, CutPoints(voice))
        }

        /** A breath either side of the recognised edges, but never into the neighbouring words. */
        private fun onTheWords(words: List<Word>, k: Int, span: IntRange): ColdOpen {
            val a = maxOf(words[span.first].range.startMs - LEAD_MS, (words.getOrNull(span.first - 1)?.range?.endMs ?: 0L) + 1) / 1000f
            val b = minOf(words[span.last].range.endMs + TAIL_MS, words.getOrNull(span.last + 1)?.range?.startMs?.minus(1) ?: Long.MAX_VALUE) / 1000f
            return ColdOpen(a.coerceAtLeast(0f)..b, k, span)
        }

        /** Every edge on the waveform: the opening's ends, its inner pauses, the body's restart. */
        private fun onTheVoice(words: List<Word>, k: Int, span: IntRange, voice: CutPoints): ColdOpen {
            val first = voice.cutIn(start(words, span.first), words.getOrNull(span.first - 1)?.let { end(words, span.first - 1) })
            val last = voice.cutOut(end(words, span.last), words.getOrNull(span.last + 1)?.let { start(words, span.last + 1) })
            val pieces = mutableListOf<ClosedFloatingPointRange<Float>>()
            var from = first.at
            // A pause inside the payoff shrinks to the body's breath, cut in its quiet on both sides.
            for (i in (span.first until span.last).filter { start(words, it + 1) - end(words, it) >= PAUSE_S }) {
                val stop = voice.cutOut(end(words, i), start(words, i + 1)).at + BREATH_S / 2f
                val resume = voice.cutIn(start(words, i + 1), end(words, i)).at - BREATH_S / 2f
                if (resume - stop < MIN_REMOVED_S || stop <= from) continue
                pieces += from..stop
                from = resume
            }
            pieces += from..last.at
            val restart = words.firstOrNull()?.let { voice.cutIn(start(words, 0)).at }
            return ColdOpen(first.at..last.at, k, span, pieces, restart, first to last)
        }

        private fun start(words: List<Word>, i: Int) = words[i].range.startMs / 1000f
        private fun end(words: List<Word>, i: Int) = words[i].range.endMs / 1000f

        private fun strongest(words: List<Word>, lines: List<Lines.Line>): Int? = lines.indices
            .filter { payoff(words, lines[it]) != null }
            .maxByOrNull { k -> Quantities.sense(words.subList(lines[k].first, lines[k].last + 1).map { it.text }, lines[k].first).weight() }

        /** The line's figure, when the line comes late enough to be worth bringing forward. */
        private fun payoff(words: List<Word>, line: Lines.Line): Quantities.Quantity? {
            if (words[line.first].range.startMs < LATE_MS) return null
            val sense = Quantities.sense(words.subList(line.first, line.last + 1).map { it.text }, line.first)
            return sense.voucher ?: sense.money ?: sense.quantities.maxByOrNull { it.value }
        }

        /**
         * The figure and the phrase that names it, as a complete thought: content words follow the
         * figure until the phrase ends — a function word or conjunction next («برای», «که», «و»), a
         * sentence end, a real pause. A breath inside the phrase (which may close a caption line:
         * «… سرمایه | اولیه») does not end it: «سیصد و پنجاه تتر سرمایهٔ اولیه», never «… سرمایه»
         * and never «… اولیه برای». A complete phrase may run to [MAX_S]; one that cannot complete
         * stops within [SHORT_S] on a content word.
         */
        private fun span(words: List<Word>, q: Quantities.Quantity): IntRange {
            var last = q.last
            while (grows(words, q, last)) last++
            if (!phraseEnds(words, last)) {
                while (last > q.last && seconds(words, q.at, last) > SHORT_S) last--
            }
            while (last > q.last && !Words.isContent(words[last].text)) last--
            return q.at..last
        }

        private fun grows(words: List<Word>, q: Quantities.Quantity, last: Int) =
            !phraseEnds(words, last) && seconds(words, q.at, last + 1) <= MAX_S && last + 1 - q.last <= TAIL_WORDS

        /** After word [i] the phrase is over. */
        private fun phraseEnds(words: List<Word>, i: Int): Boolean {
            if (i >= words.lastIndex || words[i].endsSentence) return true
            return pause(words, i) >= PHRASE_PAUSE_MS || !Words.isContent(words[i + 1].text)
        }

        private fun seconds(words: List<Word>, a: Int, b: Int) = (words[b].range.endMs - words[a].range.startMs) / 1000f

        private fun pause(words: List<Word>, i: Int) = words.getOrNull(i + 1)?.let { it.range.startMs - words[i].range.endMs } ?: Long.MAX_VALUE

        private fun asked(prompt: String): Boolean {
            val p = prompt.lowercase().replace("ٔ", "").replace("‌", " ")
            return ASKED.any { it in p }
        }

        private const val SALT = 41
        private const val CHANCE = 0.7f
        private const val LATE_MS = 4000L
        private const val MIN_S = 1.0f
        /** An opening that completes its phrase may run this long; one that cannot, [SHORT_S]. */
        private const val MAX_S = 4.0f
        private const val SHORT_S = 3.2f
        /** A pause this long ends the thought (a breath inside a phrase is shorter). */
        private const val PHRASE_PAUSE_MS = 650L
        private const val TAIL_WORDS = 4
        private const val LEAD_MS = 60L
        private const val TAIL_MS = 120L
        /** Inner pauses: tightened from [PAUSE_S] up to the body's breath ([BREATH_S], as EditPlan.tighten). */
        private const val PAUSE_S = 0.25f
        private const val BREATH_S = 0.12f
        private const val MIN_REMOVED_S = 0.06f
        private val ASKED = listOf("ثانیه اول", "ثانیه ی اول", "ثانیهی اول", "اول ویدیو", "اول ویدئو", "کلد اوپن", "first second", "cold open", "open with")
    }
}
