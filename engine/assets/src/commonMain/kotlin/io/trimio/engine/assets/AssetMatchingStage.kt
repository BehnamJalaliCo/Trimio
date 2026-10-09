package io.trimio.engine.assets

import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.timeline.Clip
import io.trimio.core.model.timeline.ElementClip
import io.trimio.core.model.timeline.MusicClip
import io.trimio.core.model.timeline.SfxClip
import io.trimio.core.model.timeline.Timeline
import io.trimio.core.pipeline.LiveSignal
import io.trimio.core.pipeline.PipelineStage
import io.trimio.core.pipeline.StageContext
import io.trimio.core.pipeline.StageId
import io.trimio.core.pipeline.StandardArtifacts
import kotlin.math.abs

/**
 * Resolves the Director's asset ids to real audio and locks the motion to the music:
 *  - renders the music bed for the chosen mood and gets its beat grid;
 *  - snaps element entrances (and the sounds that announce them) onto the nearest beat when one is
 *    close enough to feel intentional, never onto another element;
 *  - synthesises every sound effect up front so rendering never waits on audio;
 *  - shows each chosen asset on the build screen.
 */
class AssetMatchingStage(
    private val library: AssetLibrary,
    private val snapWindowMs: Long = 120,
) : PipelineStage {
    override val id = StageId.AssetMatching

    override suspend fun run(context: StageContext) {
        var timeline = context.artifacts.require(StandardArtifacts.Timeline)

        val musicClip = timeline.clipsOf<MusicClip>().firstOrNull()
        val mood = musicClip?.assetId?.substringAfter("music/")?.let(MusicMood::fromId)
        if (mood != null) {
            val track = library.track(mood)
            context.emit(LiveSignal.AssetChosen(musicClip.assetId, "موسیقی ${mood.nameFa} (${mood.bpm} BPM)", "${mood.nameEn} music (${mood.bpm} BPM)"))
            timeline = beatSync(timeline, track.beats(timeline.durationMs))
        }
        context.progress(0.5f)

        val effects = timeline.clipsOf<SfxClip>().map { it.assetId }.distinct()
        effects.forEachIndexed { i, id ->
            library.load(id)
            val effect = library.effects.firstOrNull { "sfx/${it.id}" == id }
            if (effect != null) context.emit(LiveSignal.AssetChosen(id, "افکت ${effect.nameFa}", "${effect.nameEn} effect"))
            context.progress(0.5f + 0.5f * (i + 1) / effects.size)
        }

        context.artifacts[StandardArtifacts.Timeline] = timeline
        context.progress(1f)
    }

    /** Moves element entrances onto beats within [snapWindowMs]; their entrance sounds move with them. */
    fun beatSync(timeline: Timeline, beats: List<Long>): Timeline {
        if (beats.isEmpty()) return timeline
        val elements = timeline.clipsOf<ElementClip>().sortedBy { it.range.startMs }
        val moved = mutableMapOf<Long, Long>() // old start → new start
        val placed = mutableListOf<TimeRange>()
        for (e in elements) {
            val beat = beats.minBy { abs(it - e.range.startMs) }
            val delta = beat - e.range.startMs
            val candidate = if (abs(delta) <= snapWindowMs && delta != 0L) shifted(e.range, delta, timeline.durationMs) else e.range
            val range = if (candidate != null && placed.none { it.overlaps(candidate) }) candidate else e.range
            placed += range
            if (range != e.range) moved[e.range.startMs] = range.startMs
        }
        if (moved.isEmpty()) return timeline
        val clips: List<Clip> = timeline.clips.map { clip ->
            when (clip) {
                is ElementClip -> moved[clip.range.startMs]?.let { clip.copy(range = shifted(clip.range, it - clip.range.startMs, timeline.durationMs)!!) } ?: clip
                is SfxClip -> moved[clip.range.startMs]?.let { new -> shifted(clip.range, new - clip.range.startMs, timeline.durationMs)?.let { clip.copy(range = it) } } ?: clip
                else -> clip
            }
        }
        return timeline.copy(clips = clips)
    }

    private fun shifted(range: TimeRange, delta: Long, durationMs: Long): TimeRange? {
        val start = range.startMs + delta
        val end = minOf(range.endMs + delta, durationMs)
        return if (start >= 0 && end > start) TimeRange(start, end) else null
    }
}
