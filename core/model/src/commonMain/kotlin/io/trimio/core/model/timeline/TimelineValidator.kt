package io.trimio.core.model.timeline

import io.trimio.core.model.input.InputSource

/**
 * Structural and readability checks on a [Timeline]. Runs on every Director output
 * (rules engine or LLM) before anything is rendered; errors trigger the deterministic fallback.
 */
class TimelineValidator(private val rules: Rules = Rules()) {

    data class Rules(
        /** A caption shorter than this cannot be read comfortably. */
        val minCaptionMs: Long = 180,
        val maxGainDb: Float = 6f,
        val minGainDb: Float = -60f,
    )

    enum class Severity { Warning, Error }

    data class Issue(val severity: Severity, val code: String, val message: String, val clipIndex: Int? = null)

    fun validate(timeline: Timeline, input: InputSource? = null): List<Issue> = buildList {
        if (timeline.version > Timeline.CURRENT_VERSION) {
            add(Issue(Severity.Error, "version", "Unsupported schema version ${timeline.version}"))
        }
        if (timeline.durationMs <= 0) {
            add(Issue(Severity.Error, "duration", "Timeline duration must be positive"))
        }

        timeline.clips.forEachIndexed { index, clip ->
            if (!clip.range.isWithin(timeline.durationMs)) {
                add(Issue(Severity.Error, "out-of-bounds", "Clip ends after the timeline", index))
            }
            when (clip) {
                is CaptionClip -> {
                    if (clip.text.isBlank()) add(Issue(Severity.Error, "caption-empty", "Caption text is blank", index))
                    if (clip.range.durationMs < rules.minCaptionMs) {
                        add(Issue(Severity.Warning, "caption-too-short", "Caption shown for ${clip.range.durationMs}ms", index))
                    }
                    if (clip.emphasis !in 0f..1f) add(Issue(Severity.Error, "emphasis-range", "Emphasis must be 0..1", index))
                }
                is SfxClip -> checkGain(clip.gainDb, index)
                is MusicClip -> checkGain(clip.gainDb, index)
                is ElementClip -> if (clip.scale <= 0f) add(Issue(Severity.Error, "scale", "Element scale must be positive", index))
                is BackgroundClip, is CutClip -> Unit
            }
        }

        addCaptionOverlaps(timeline)

        if (input is InputSource.AudioOnly && !backgroundCoversTimeline(timeline)) {
            add(Issue(Severity.Error, "audio-only-background", "Audio-only input needs a background for the whole duration"))
        }
    }

    fun isRenderable(timeline: Timeline, input: InputSource? = null): Boolean =
        validate(timeline, input).none { it.severity == Severity.Error }

    private fun MutableList<Issue>.checkGain(gainDb: Float, index: Int) {
        if (gainDb !in rules.minGainDb..rules.maxGainDb) {
            add(Issue(Severity.Error, "gain-range", "Gain ${gainDb}dB outside ${rules.minGainDb}..${rules.maxGainDb}", index))
        }
    }

    /** Two captions on the same layer and anchor at the same time would draw on top of each other. */
    private fun MutableList<Issue>.addCaptionOverlaps(timeline: Timeline) {
        val captions = timeline.clips.withIndex()
            .filter { it.value is CaptionClip }
            .groupBy { (it.value as CaptionClip).let { c -> c.layer to c.anchor } }
        for (group in captions.values) {
            val sorted = group.sortedBy { it.value.range.startMs }
            sorted.zipWithNext().forEach { (a, b) ->
                if (a.value.range.overlaps(b.value.range)) {
                    add(Issue(Severity.Error, "caption-overlap", "Captions overlap at the same anchor", b.index))
                }
            }
        }
    }

    private fun backgroundCoversTimeline(timeline: Timeline): Boolean {
        var covered = 0L
        timeline.clipsOf<BackgroundClip>().sortedBy { it.range.startMs }.forEach { bg ->
            if (bg.range.startMs > covered) return false
            covered = maxOf(covered, bg.range.endMs)
        }
        return covered >= timeline.durationMs
    }
}
