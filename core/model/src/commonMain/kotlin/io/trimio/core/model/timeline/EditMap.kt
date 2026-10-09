package io.trimio.core.model.timeline

import io.trimio.core.model.time.TimeRange

/**
 * Maps between **source** time (the user's original file) and **output** time (the edited video)
 * given the source ranges removed by cuts. Every clip in a [Timeline] lives in output time except
 * [CutClip], whose range is in source time.
 *
 * Kept segments play back to back, so output time is source time minus everything cut before it.
 */
class EditMap(val sourceDurationMs: Long, cuts: List<TimeRange>) {

    /** Merged, sorted, clamped cut ranges. */
    val cuts: List<TimeRange> = cuts
        .map { TimeRange(it.startMs.coerceIn(0, sourceDurationMs), it.endMs.coerceIn(0, sourceDurationMs)) }
        .filter { it.durationMs > 0 }
        .sortedBy { it.startMs }
        .fold(mutableListOf<TimeRange>()) { acc, r ->
            val last = acc.lastOrNull()
            if (last != null && r.startMs <= last.endMs) acc[acc.lastIndex] = TimeRange(last.startMs, maxOf(last.endMs, r.endMs)) else acc += r
            acc
        }

    /** Source segments that survive, in order. */
    val kept: List<TimeRange> = buildList {
        var cursor = 0L
        for (c in this@EditMap.cuts) {
            if (c.startMs > cursor) add(TimeRange(cursor, c.startMs))
            cursor = c.endMs
        }
        if (cursor < sourceDurationMs) add(TimeRange(cursor, sourceDurationMs))
    }

    val outputDurationMs: Long = kept.sumOf { it.durationMs }

    /** Source time shown at [outputMs]. */
    fun toSource(outputMs: Long): Long {
        var remaining = outputMs.coerceIn(0, outputDurationMs)
        for (k in kept) {
            if (remaining < k.durationMs) return k.startMs + remaining
            remaining -= k.durationMs
        }
        return kept.lastOrNull()?.endMs ?: 0L
    }

    /** Output time of [sourceMs], or null if that moment was cut. */
    fun toOutput(sourceMs: Long): Long? {
        var offset = 0L
        for (k in kept) {
            if (sourceMs < k.startMs) return null
            if (sourceMs < k.endMs) return offset + (sourceMs - k.startMs)
            offset += k.durationMs
        }
        return if (sourceMs == sourceDurationMs) outputDurationMs else null
    }

    /**
     * Maps a source range (e.g. a word) to output time. A range that straddles a cut is clipped to
     * its surviving part; null if it was cut entirely.
     */
    fun toOutput(range: TimeRange): TimeRange? {
        val start = toOutput(range.startMs) ?: kept.firstOrNull { it.startMs in range.startMs until range.endMs }?.let { toOutput(it.startMs) } ?: return null
        val end = toOutput(range.endMs) ?: kept.lastOrNull { it.endMs in (range.startMs + 1)..range.endMs }?.let { toOutput(it.endMs - 1)?.plus(1) } ?: return null
        return if (end > start) TimeRange(start, end) else null
    }

    /** Output times where one kept segment ends and the next begins (for cut transitions). */
    val joinPointsMs: List<Long> = kept.dropLast(1).runningFold(0L) { acc, k -> acc + k.durationMs }.drop(1)

    companion object {
        fun of(timeline: Timeline, sourceDurationMs: Long) = EditMap(sourceDurationMs, timeline.clipsOf<CutClip>().map { it.range })
    }
}
