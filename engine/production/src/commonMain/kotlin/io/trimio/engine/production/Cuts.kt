package io.trimio.engine.production

import io.trimio.core.model.transcript.Word
import io.trimio.engine.assets.BeatGrid
import io.trimio.engine.autopilot.Workbench
import io.trimio.engine.motion.score.EditPlan
import io.trimio.engine.motion.score.NumberWords
import io.trimio.engine.motion.score.Words

/**
 * The cut list as the creator leaves it: the director's body with the pauses kept or cut, the
 * creator's deleted words and (when asked) the hesitations and verbal tics taken out, and cuts
 * moved onto the music's beats. The cold open and the end hold are the director's and stay.
 */
object Cuts {
    /** Each side of a cut word: a breath, no more (seconds). */
    private const val PAD = 0.06f

    /** Two removals closer than this are one: no sliver of room tone between them. */
    private const val MERGE = 0.15f

    /** Pieces shorter than this are not kept (a click, not speech). */
    private const val MIN_PIECE = 0.04f

    /** A word's tail is never cut into by a beat snap: it keeps this much after it. */
    private const val TAIL = 0.03f

    /** A tic («یعنی», «ببین») only counts as one when a pause this long stands on one side of it. */
    private const val TIC_PAUSE = 0.25f

    private const val MS = 1000f

    /** Always a filler when said alone in Persian speech. */
    private val ALWAYS = setOf("خب", "خُب", "خوب؟")

    /** Fillers when set off by a pause; meaningful words otherwise. */
    private val TICS = setOf("یعنی", "ببین", "ببینید", "مثلا", "مثلاً", "دیگه", "اصلا", "اصلاً")

    /** The words cut from the edit: the creator's deletions, and the fillers when they are to go. */
    fun removed(bench: Workbench, o: EditOverrides): Set<Int> =
        if (o.fillers == true) o.deleted + fillers(bench.transcript.words) else o.deleted

    /** Hesitations («اِم», «ااا») and verbal tics («خب», a «یعنی» between pauses), by word index. */
    fun fillers(words: List<Word>): List<Int> = words.indices.filter { i ->
        val w = words[i]
        val n = NumberWords.normalize(w.text)
        val before = words.getOrNull(i - 1)?.let { (w.range.startMs - it.range.endMs) / MS } ?: Float.MAX_VALUE
        val after = words.getOrNull(i + 1)?.let { (it.range.startMs - w.range.endMs) / MS } ?: Float.MAX_VALUE
        Words.isFiller(w.text) || n in ALWAYS || (n in TICS && maxOf(before, after) >= TIC_PAUSE)
    }

    /** The silences the director cut: how many, and how many seconds. */
    fun pauses(director: EditPlan): Pair<Int, Float> {
        val body = director.segments
        val gaps = body.zipWithNext { a, b -> b.sourceStart - a.sourceEnd }.filter { it > 0f }
        return gaps.size to gaps.sum()
    }

    /** The director's cut with the creator's pauses, deletions and fillers applied. */
    fun of(director: EditPlan, bench: Workbench, o: EditOverrides): EditPlan {
        val body = director.segments
        if (body.isEmpty()) return director
        val keep = if (o.silences == false) listOf(body.first().sourceStart..body.last().sourceEnd) else body.map { it.sourceStart..it.sourceEnd }
        val gone = removed(bench, o)
        if (o.silences != false && gone.isEmpty()) return director
        val kept = subtract(keep, removals(bench.transcript.words, gone)).filter { it.endInclusive - it.start >= MIN_PIECE }
        if (kept.isEmpty()) return director
        var out = body.first().outStart
        val segments = kept.map { r -> EditPlan.Segment(r.start, r.endInclusive, out).also { out += it.length } }
        val plan = EditPlan(segments, director.opening)
        return director.outro?.let { plan.withOutro(it.length, bench.sourceDuration) } ?: plan
    }

    /** Source stretches to cut for [words]: each word with a breath either side, never into its neighbours. */
    fun removals(words: List<Word>, indices: Set<Int>): List<ClosedFloatingPointRange<Float>> {
        val ranges = indices.filter { it in words.indices }.sorted().map { i ->
            val w = words[i]
            val prevEnd = words.getOrNull(i - 1)?.range?.endMs?.div(MS) ?: 0f
            val nextStart = words.getOrNull(i + 1)?.range?.startMs?.div(MS) ?: Float.MAX_VALUE
            maxOf(w.range.startMs / MS - PAD, prevEnd)..minOf(w.range.endMs / MS + PAD, nextStart)
        }
        val merged = mutableListOf<ClosedFloatingPointRange<Float>>()
        for (r in ranges) {
            val last = merged.lastOrNull()
            if (last != null && r.start - last.endInclusive < MERGE) merged[merged.lastIndex] = last.start..maxOf(last.endInclusive, r.endInclusive) else merged += r
        }
        return merged
    }

    /** [keep] without [cut]. */
    fun subtract(keep: List<ClosedFloatingPointRange<Float>>, cut: List<ClosedFloatingPointRange<Float>>): List<ClosedFloatingPointRange<Float>> =
        keep.flatMap { k ->
            var pieces = listOf(k)
            for (c in cut) {
                pieces = pieces.flatMap { p ->
                    if (c.endInclusive <= p.start || c.start >= p.endInclusive) {
                        listOf(p)
                    } else {
                        listOfNotNull((p.start..c.start).takeIf { c.start > p.start }, (c.endInclusive..p.endInclusive).takeIf { c.endInclusive < p.endInclusive })
                    }
                }
            }
            pieces
        }

    /**
     * Every cut between two body pieces moved onto the nearest beat of [grid] when it is within
     * [Retouch.BEAT_SNAP]: the outgoing piece runs on into the pause it was cut from, or ends a
     * little sooner, never into its last word. The pieces after it move with it.
     */
    fun snap(edit: EditPlan, grid: BeatGrid, words: List<Word>): EditPlan {
        val body = edit.segments
        if (body.size < 2) return edit
        val out = mutableListOf<EditPlan.Segment>()
        var at = body.first().outStart
        for ((k, s) in body.withIndex()) {
            var end = s.sourceEnd
            val next = body.getOrNull(k + 1)
            if (next != null) {
                val t = at + s.length
                val d = (grid.nearestBeat(t.toDouble()) - t).toFloat()
                val lastWord = words.filter { it.range.startMs / MS in s.sourceStart..<s.sourceEnd }.maxOfOrNull { it.range.endMs / MS } ?: s.sourceStart
                val longest = maxOf(s.sourceEnd, next.sourceStart)
                val shortest = minOf(s.sourceEnd, lastWord + TAIL)
                if (kotlin.math.abs(d) <= Retouch.BEAT_SNAP) end = (s.sourceEnd + d).coerceIn(shortest, longest)
            }
            out += EditPlan.Segment(s.sourceStart, end, at)
            at += end - s.sourceStart
        }
        val plan = EditPlan(out, edit.opening)
        return edit.outro?.let { o -> EditPlan(out, edit.opening, o.copy(sourceStart = out.last().sourceEnd)) } ?: plan
    }

    /** Source stretches the edit does not play (cut pauses, words and the trimmed head and tail), within [duration]. */
    fun unplayed(edit: EditPlan, duration: Float): List<ClosedFloatingPointRange<Float>> {
        val played = edit.segments.map { it.sourceStart..it.sourceEnd }.sortedBy { it.start }
        return subtract(listOf(0f..duration), played).filter { it.endInclusive - it.start >= MIN_PIECE }
    }
}
