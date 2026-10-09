package io.trimio.engine.director

import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.timeline.BackgroundClip
import io.trimio.core.model.timeline.CaptionClip
import io.trimio.core.model.timeline.CutClip
import io.trimio.core.model.timeline.CutReason
import io.trimio.core.model.timeline.EditMap
import io.trimio.core.model.timeline.ElementClip
import io.trimio.core.model.timeline.MusicClip
import io.trimio.core.model.timeline.SfxClip
import io.trimio.core.model.timeline.Timeline
import io.trimio.core.model.transcript.Transcript

/**
 * User edits on a finished timeline, as pure functions (the editor keeps the history for undo).
 * Editing text edits the video: removing a word cuts it from the footage and every later clip
 * slides back so captions, elements, sound and music stay in sync.
 */
object TimelineEditor {

    fun setWordText(timeline: Timeline, wordIndex: Int, text: String): Timeline = timeline.copy(
        clips = timeline.clips.map { if (it is CaptionClip && it.wordIndex == wordIndex) it.copy(text = text.trim().ifEmpty { it.text }) else it },
    )

    fun setEmphasis(timeline: Timeline, wordIndex: Int, emphasized: Boolean): Timeline = timeline.copy(
        clips = timeline.clips.map { if (it is CaptionClip && it.wordIndex == wordIndex) it.copy(emphasis = if (emphasized) 0.95f else 0.2f) else it },
    )

    fun setStyle(timeline: Timeline, styleId: String): Timeline = timeline.copy(styleId = styleId)

    /** Replaces the music bed: a mood id from [EditPlanSchema.MUSIC_MOODS], or "none". */
    fun setMusic(timeline: Timeline, mood: String, gainDb: Float = -20f): Timeline {
        val others = timeline.clips.filterNot { it is MusicClip }
        val music = if (mood == "none") emptyList() else listOf(MusicClip(TimeRange(0, timeline.durationMs), "music/$mood", gainDb))
        return timeline.copy(clips = others + music)
    }

    fun setSoundEffects(timeline: Timeline, enabled: Boolean, original: Timeline): Timeline =
        if (enabled) timeline.copy(clips = timeline.clips.filterNot { it is SfxClip } + original.clipsOf<SfxClip>().filter { it.range.isWithin(timeline.durationMs) })
        else timeline.copy(clips = timeline.clips.filterNot { it is SfxClip })

    /** Cuts the spoken words [wordIndices] out of the edit (with a little air kept around their neighbours). */
    fun removeWords(timeline: Timeline, transcript: Transcript, wordIndices: Set<Int>, sourceDurationMs: Long): Timeline {
        val ranges = wordIndices.mapNotNull { transcript.words.getOrNull(it)?.range }
        if (ranges.isEmpty()) return timeline
        val cuts = ranges.map { r -> CutClip(TimeRange(r.startMs, minOf(r.endMs + 30, sourceDurationMs)), CutReason.Manual) }
        return applyCuts(timeline, timeline.clipsOf<CutClip>() + cuts, sourceDurationMs)
            .let { t -> t.copy(clips = t.clips.filterNot { it is CaptionClip && it.wordIndex in wordIndices }) }
    }

    /** Brings back words previously removed by [removeWords]. */
    fun restoreWords(timeline: Timeline, transcript: Transcript, wordIndices: Set<Int>, sourceDurationMs: Long, original: Timeline): Timeline {
        val ranges = wordIndices.mapNotNull { transcript.words.getOrNull(it)?.range }
        val kept = timeline.clipsOf<CutClip>().filterNot { c -> c.reason == CutReason.Manual && ranges.any { it.overlaps(c.range) } }
        val retimed = applyCuts(timeline, kept, sourceDurationMs)
        // Captions come back from the original edit, mapped into the new output time.
        val originalMap = EditMap.of(original, sourceDurationMs)
        val newMap = EditMap(sourceDurationMs, kept.map { it.range })
        val restored = original.clipsOf<CaptionClip>().filter { it.wordIndex in wordIndices }.mapNotNull { c ->
            remap(c.range, originalMap, newMap)?.let { c.copy(range = it) }
        }
        return retimed.copy(clips = retimed.clips + restored)
    }

    /**
     * Rebuilds the timeline for a new set of source cuts: every output-time clip is mapped back to
     * source time through the old cuts and forward through the new ones. Clips whose moment was cut
     * disappear; the background and music stretch to the new duration.
     */
    fun applyCuts(timeline: Timeline, cuts: List<CutClip>, sourceDurationMs: Long): Timeline {
        val old = EditMap.of(timeline, sourceDurationMs)
        val new = EditMap(sourceDurationMs, cuts.map { it.range })
        val duration = new.outputDurationMs.coerceAtLeast(1)
        val clips = timeline.clips.mapNotNull { clip ->
            when (clip) {
                is CutClip -> null
                is BackgroundClip -> clip.copy(range = TimeRange(0, duration))
                is MusicClip -> clip.copy(range = TimeRange(0, duration))
                is CaptionClip -> remap(clip.range, old, new)?.let { clip.copy(range = it) }
                is ElementClip -> remapStart(clip.range, old, new, duration)?.let { clip.copy(range = it) }
                is SfxClip -> remapStart(clip.range, old, new, duration)?.let { clip.copy(range = it) }
            }
        }
        return timeline.copy(durationMs = duration, clips = cuts + clips)
    }

    private fun remap(range: TimeRange, old: EditMap, new: EditMap): TimeRange? {
        val source = TimeRange(old.toSource(range.startMs), old.toSource((range.endMs - 1).coerceAtLeast(range.startMs)) + 1)
        return new.toOutput(source)
    }

    /** Elements and sounds keep their length; only their entrance moment is remapped. */
    private fun remapStart(range: TimeRange, old: EditMap, new: EditMap, duration: Long): TimeRange? {
        val start = new.toOutput(old.toSource(range.startMs)) ?: return null
        val end = minOf(start + range.durationMs, duration)
        return if (end - start >= 200) TimeRange(start, end) else null
    }

    /** Every clip that would draw or sound at least partially inside the timeline. */
    fun isConsistent(timeline: Timeline): Boolean = timeline.clips.all { it is CutClip || it.range.isWithin(timeline.durationMs) }
}
