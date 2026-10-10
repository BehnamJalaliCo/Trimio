package io.trimio.engine.motion.score

import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word

/**
 * The cut list of a talking piece: which stretches of the source survive, in order. Dead air is
 * the first thing an editor removes; [tighten] keeps a breath at every cut so speech stays human.
 */
class EditPlan(
    val segments: List<Segment>,
    /**
     * A cold open: a stretch of the source (the payoff) played first, before the piece starts
     * from its beginning. It sits outside the timeline mapping — the body's words keep one place
     * each — and the body's segments start after it.
     */
    val prelude: Segment? = null,
) {

    /** [sourceStart, sourceEnd) of the source, played from [outStart] on the output clock. */
    data class Segment(val sourceStart: Float, val sourceEnd: Float, val outStart: Float) {
        val length: Float get() = sourceEnd - sourceStart
        val outEnd: Float get() = outStart + length
    }

    val duration: Float get() = segments.lastOrNull()?.outEnd ?: 0f

    /** Output time of a source time; inside a removed gap it lands on the cut. */
    fun toOutput(source: Float): Float {
        for (s in segments) {
            if (source < s.sourceStart) return s.outStart
            if (source < s.sourceEnd) return s.outStart + (source - s.sourceStart)
        }
        return duration
    }

    /** The transcript on the output clock (cut points become shared word boundaries). */
    fun remap(transcript: Transcript): Transcript = Transcript(
        transcript.language,
        transcript.words.map { w ->
            val a = toOutput(w.range.startMs / 1000f)
            val b = maxOf(a + 0.02f, toOutput(w.range.endMs / 1000f))
            w.copy(range = TimeRange((a * 1000).toLong(), (b * 1000).toLong()))
        },
    )

    val cutCount: Int get() = (segments.size - 1).coerceAtLeast(0) + (if (prelude != null) 1 else 0)

    /** Everything that plays, in output order (the cold open first): what footage and voice follow. */
    val played: List<Segment> get() = listOfNotNull(prelude) + segments

    /** Output length of the cold open (0 without one). */
    val preludeLength: Float get() = prelude?.length ?: 0f

    /** The words heard in the cold open, on the output clock (captions for it). */
    fun preludeWords(transcript: Transcript): List<io.trimio.core.model.transcript.Word> {
        val p = prelude ?: return emptyList()
        return transcript.words.filter { w -> w.range.startMs / 1000f >= p.sourceStart - 0.02f && w.range.endMs / 1000f <= p.sourceEnd + 0.02f }.map { w ->
            val a = p.outStart + (w.range.startMs / 1000f - p.sourceStart).coerceAtLeast(0f)
            val b = minOf(p.outEnd, p.outStart + (w.range.endMs / 1000f - p.sourceStart))
            w.copy(range = TimeRange((a * 1000).toLong(), (maxOf(b, a + 0.05f) * 1000).toLong()))
        }
    }

    /**
     * The same cut with [source] (seconds of the source) played first as a cold open, followed by
     * a [gap] of silence-free transition time; the body shifts later by the prelude's length.
     */
    fun withColdOpen(source: ClosedFloatingPointRange<Float>, gap: Float = 0f): EditPlan {
        val length = source.endInclusive - source.start
        if (length <= 0f) return this
        val shift = length + gap
        return EditPlan(segments.map { it.copy(outStart = it.outStart + shift) }, Segment(source.start, source.endInclusive, 0f))
    }

    companion object {
        /**
         * Recognisers stretch a word across the pause before or after it. Words are clipped to
         * detected speech: a word starting in (or spanning) a silence starts when speech resumes,
         * a word ending in one ends where speech stopped.
         */
        fun alignToSpeech(words: List<Word>, silences: List<ClosedFloatingPointRange<Float>>): List<Word> = words.map { w ->
            var a = w.range.startMs / 1000f
            var b = w.range.endMs / 1000f
            for (s in silences) {
                when {
                    a >= s.start && a < s.endInclusive -> a = s.endInclusive
                    a < s.start && b > s.endInclusive -> a = s.endInclusive
                    b > s.start && b <= s.endInclusive -> b = s.start
                }
            }
            if (b <= a + 0.04f) b = a + 0.12f
            w.copy(range = TimeRange((a * 1000).toLong(), (b * 1000).toLong()))
        }

        fun identity(duration: Float) = EditPlan(listOf(Segment(0f, duration, 0f)))

        /**
         * Removes silence: every pause longer than [minGap] shrinks to [keep] (half a breath on
         * each side of the cut); the head before the first word and the tail after the last are
         * trimmed to [lead] and [tail].
         */
        fun tighten(
            words: List<Word>,
            silences: List<ClosedFloatingPointRange<Float>>,
            duration: Float,
            minGap: Float = 0.25f,
            keep: Float = 0.12f,
            lead: Float = 0.08f,
            tail: Float = 0.45f,
        ): EditPlan {
            if (words.isEmpty()) return identity(duration)
            val first = words.first().range.startMs / 1000f
            val last = words.last().range.endMs / 1000f
            val cuts = silences
                .filter { it.endInclusive - it.start >= minGap }
                .map { (it.start + keep / 2f)..(it.endInclusive - keep / 2f) }
                .filter { it.start > first && it.endInclusive < last }
                .sortedBy { it.start }
            val keepFrom = (first - lead).coerceAtLeast(0f)
            val keepTo = (last + tail).coerceAtMost(duration)
            val segments = mutableListOf<Segment>()
            var cursor = keepFrom
            var out = 0f
            for (c in cuts) {
                if (c.start <= cursor) continue
                segments += Segment(cursor, c.start, out)
                out += c.start - cursor
                cursor = c.endInclusive
            }
            segments += Segment(cursor, keepTo, out)
            return EditPlan(segments)
        }
    }
}

/** Where the person is in the frame, as fractions of its width and height. */
data class Subject(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val centerX: Float get() = (left + right) / 2f
    val centerY: Float get() = (top + bottom) / 2f
}
