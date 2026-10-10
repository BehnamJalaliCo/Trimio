package io.trimio.engine.autopilot

import io.trimio.core.model.transcript.Word
import io.trimio.engine.motion.score.Words

/**
 * The editor's oldest hook: the payoff said first. When the piece's strongest line comes late, its
 * figure and the few words that name it («سیصد و پنجاه تتر سرمایهٔ اولیه») play before the piece
 * starts from its beginning, so the first second is the reason to keep watching. Words keep their
 * indices; only the clock moves (EditPlan.withColdOpen).
 */
data class ColdOpen(
    /** Source seconds played first. */
    val source: ClosedFloatingPointRange<Float>,
    /** The hook line and the words heard in the opening (indices into the transcript). */
    val line: Int,
    val words: IntRange,
) {
    val length: Float get() = source.endInclusive - source.start

    companion object {
        /**
         * The opening for this piece, or none: only when the hook line comes after the first few
         * seconds and holds a quantity (money, views, a deadline). A brief asking for the payoff
         * "in the first second" always gets one; otherwise it is one of the seed's choices.
         */
        fun choose(words: List<Word>, lines: List<Lines.Line>, understanding: Understanding, prompt: String, seed: Long): ColdOpen? {
            val k = understanding.hook?.line ?: return null
            val line = lines.getOrNull(k) ?: return null
            if (!asked(prompt) && !Rng(seed).fork(SALT).chance(CHANCE)) return null
            val span = payoff(words, line)?.let { q -> span(words, q, line) }?.takeIf { seconds(words, it.first, it.last) >= MIN_S } ?: return null
            // A breath either side, but never into the neighbouring words.
            val a = maxOf(words[span.first].range.startMs - LEAD_MS, (words.getOrNull(span.first - 1)?.range?.endMs ?: 0L) + 1) / 1000f
            val b = minOf(words[span.last].range.endMs + TAIL_MS, words.getOrNull(span.last + 1)?.range?.startMs?.minus(1) ?: Long.MAX_VALUE) / 1000f
            return ColdOpen(a.coerceAtLeast(0f)..b, k, span)
        }

        /** The line's figure, when the line comes late enough to be worth bringing forward. */
        private fun payoff(words: List<Word>, line: Lines.Line): Quantities.Quantity? {
            if (words[line.first].range.startMs < LATE_MS) return null
            val sense = Quantities.sense(words.subList(line.first, line.last + 1).map { it.text }, line.first)
            return sense.voucher ?: sense.money ?: sense.quantities.maxByOrNull { it.value }
        }

        /** The figure and the words that name it («تتر سرمایهٔ اولیه»), within one breath and a few seconds. */
        private fun span(words: List<Word>, q: Quantities.Quantity, line: Lines.Line): IntRange {
            var last = q.last
            while (last < line.last && grows(words, q, last)) last++
            // Never stop on «برای», «و», «در»: the opening ends on a word that means something.
            while (last > q.last && !Words.isContent(words[last].text)) last--
            return q.at..last
        }

        private fun grows(words: List<Word>, q: Quantities.Quantity, last: Int) =
            seconds(words, q.at, last + 1) <= MAX_S && pause(words, last) < BREATH_MS && last - q.last < TAIL_WORDS

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
        private const val MAX_S = 3.2f
        private const val BREATH_MS = 350L
        private const val TAIL_WORDS = 3
        private const val LEAD_MS = 60L
        private const val TAIL_MS = 120L
        private val ASKED = listOf("ثانیه اول", "ثانیه ی اول", "ثانیهی اول", "اول ویدیو", "اول ویدئو", "کلد اوپن", "first second", "cold open", "open with")
    }
}
