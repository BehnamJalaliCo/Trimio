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
     * A cold open: stretches of the source (the payoff, a pause inside it tightened) played first,
     * back to back, before the piece starts from its beginning. They sit outside the timeline
     * mapping — the body's words keep one place each — and the body's segments start after them.
     */
    val opening: List<Segment> = emptyList(),
    /** A held tail after the last word (picture and music, no voice): room for a late ask's end card. */
    val outro: Outro? = null,
) {

    /**
     * The cold open as one stretch of the source, its first cut to its last, from 0 on the output
     * clock (null without one). What actually plays is [opening]; [preludeLength] is its length.
     */
    val prelude: Segment? get() = if (opening.isEmpty()) null else Segment(opening.first().sourceStart, opening.last().sourceEnd, 0f)

    /** [sourceStart, sourceEnd) of the source, played from [outStart] on the output clock. */
    data class Segment(val sourceStart: Float, val sourceEnd: Float, val outStart: Float) {
        val length: Float get() = sourceEnd - sourceStart
        val outEnd: Float get() = outStart + length
    }

    /**
     * The end hold: [length] seconds after the body's last frame. The footage runs on from where
     * the cut ended ([sourceStart]) for [running] seconds — as long as the source has picture —
     * then holds its last frame. The voice never plays here; the music carries and fades.
     */
    data class Outro(val length: Float, val sourceStart: Float, val running: Float) {
        /** The source time of the frame held once the footage runs out. */
        val heldFrame: Float get() = sourceStart + running
    }

    /** Output time the body (and every word) ends: where an [outro] starts. */
    val bodyEnd: Float get() = segments.lastOrNull()?.outEnd ?: 0f

    val duration: Float get() = bodyEnd + (outro?.length ?: 0f)

    /** Output time of a source time; inside a removed gap it lands on the cut. */
    fun toOutput(source: Float): Float {
        for (s in segments) {
            if (source < s.sourceStart) return s.outStart
            if (source < s.sourceEnd) return s.outStart + (source - s.sourceStart)
        }
        return bodyEnd
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

    val cutCount: Int get() = (segments.size - 1).coerceAtLeast(0) + opening.size

    /** Everything that plays, in output order (the cold open first): what footage and voice follow. */
    val played: List<Segment> get() = opening + segments

    /** Output length of the cold open (0 without one): where the piece restarts. */
    val preludeLength: Float get() = opening.lastOrNull()?.outEnd ?: 0f

    /** Output time the body's first frame plays (after the cold open and its transition). */
    val bodyStart: Float get() = segments.firstOrNull()?.outStart ?: preludeLength

    /** The words heard in the cold open, on the output clock (captions for it). */
    fun preludeWords(transcript: Transcript): List<Word> = transcript.words.mapNotNull { w ->
        val s = w.range.startMs / 1000f
        val e = w.range.endMs / 1000f
        // Heard when the cold open plays its middle; a recognised edge outside the cut is clamped to it.
        if (opening.none { (s + e) / 2f in it.sourceStart..<it.sourceEnd }) return@mapNotNull null
        val a = preludeTime(s)
        w.copy(range = TimeRange((a * 1000).toLong(), (maxOf(preludeTime(e), a + 0.05f) * 1000).toLong()))
    }

    /** Output time of a source time within the cold open; inside a removed pause it lands on the cut. */
    private fun preludeTime(source: Float): Float {
        for (p in opening) {
            if (source < p.sourceStart) return p.outStart
            if (source < p.sourceEnd) return p.outStart + (source - p.sourceStart)
        }
        return preludeLength
    }

    /**
     * The same cut with [source] (seconds of the source) played first as a cold open, followed by
     * a [gap] of transition time; the body shifts later by the prelude's length.
     */
    fun withColdOpen(source: ClosedFloatingPointRange<Float>, gap: Float = 0f): EditPlan = withColdOpen(listOf(source), gap)

    /**
     * The cold open as [stretches] of the source played back to back (a payoff whose inner pause was
     * tightened), then a [gap] for the restart transition, then the body. With [restart] (a source
     * time just before the first word's real onset, see [CutPoints.cutIn]) the body's first
     * segment begins exactly there: the first word lands as the transition resolves, no dead air.
     */
    fun withColdOpen(stretches: List<ClosedFloatingPointRange<Float>>, gap: Float, restart: Float? = null): EditPlan {
        val kept = stretches.filter { it.endInclusive > it.start }
        if (kept.isEmpty()) return this
        var out = 0f
        val pieces = kept.map { r -> Segment(r.start, r.endInclusive, out).also { out += it.length } }
        val first = segments.firstOrNull()
        val from = restart?.takeIf { first != null && it in 0f..<first.sourceEnd } ?: first?.sourceStart ?: 0f
        // Re-anchoring the first segment moves everything after it by the same amount.
        val grown = (first?.sourceStart ?: 0f) - from
        val shift = out + gap - (first?.outStart ?: 0f)
        val body = segments.mapIndexed { i, s ->
            if (i == 0) s.copy(sourceStart = from, outStart = s.outStart + shift) else s.copy(outStart = s.outStart + shift + grown)
        }
        return EditPlan(body, pieces, outro)
    }

    /**
     * The same cut held [seconds] (up to [MAX_OUTRO]) past its last frame, for an ask said too
     * late for its card to be read. The footage runs on while the source ([sourceDuration]
     * seconds) has more, then freezes on its last frame; no voice plays in the hold.
     */
    fun withOutro(seconds: Float, sourceDuration: Float): EditPlan {
        val last = segments.lastOrNull() ?: return this
        val length = seconds.coerceIn(0f, MAX_OUTRO)
        if (length <= 0f) return EditPlan(segments, opening)
        val running = (sourceDuration - last.sourceEnd).coerceIn(0f, length)
        return EditPlan(segments, opening, Outro(length, last.sourceEnd, running))
    }

    companion object {
        /** The longest end hold: past it a frozen frame reads as a mistake. */
        const val MAX_OUTRO = 2.5f

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
