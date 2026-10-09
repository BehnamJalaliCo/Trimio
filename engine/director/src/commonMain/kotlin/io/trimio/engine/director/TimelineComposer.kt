package io.trimio.engine.director

import io.trimio.core.model.input.CanvasSpec
import io.trimio.core.model.input.InputSource
import io.trimio.core.model.style.CaptionMode
import io.trimio.core.model.style.StyleSpec
import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.timeline.BackgroundClip
import io.trimio.core.model.timeline.CaptionClip
import io.trimio.core.model.timeline.Clip
import io.trimio.core.model.timeline.CutClip
import io.trimio.core.model.timeline.CutReason
import io.trimio.core.model.timeline.EditMap
import io.trimio.core.model.timeline.ElementClip
import io.trimio.core.model.timeline.MusicClip
import io.trimio.core.model.timeline.SfxClip
import io.trimio.core.model.timeline.Timeline
import io.trimio.core.model.transcript.Transcript
import io.trimio.engine.asr.FillerDetector

/**
 * Turns an [EditPlan] into a frame-exact [Timeline]: cuts in source time, then every caption,
 * element, sound effect and music bed in output time through the resulting [EditMap].
 * Deterministic: the same plan and transcript always give the same timeline.
 */
object TimelineComposer {

    /** Silence kept around speech at a cut, so words never sound clipped. */
    private const val PAD_BEFORE_MS = 140L
    private const val PAD_AFTER_MS = 160L

    fun compose(plan: EditPlan, transcript: Transcript, style: StyleSpec, input: InputSource, seed: Long): Timeline {
        val words = transcript.words
        val cuts = cuts(plan, transcript, input.durationMs)
        val edit = EditMap(input.durationMs, cuts.map { it.range })
        val duration = edit.outputDurationMs.coerceAtLeast(1)
        val emphasis = plan.emphasis.toSet()
        val fillers = if (plan.cutFillers) FillerDetector.fillerIndices(words).toSet() else emptySet()

        val clips = mutableListOf<Clip>()
        clips += cuts
        clips += BackgroundClip(TimeRange(0, duration), preset = BackgroundClip.STYLE_PRESET, audioReactive = style.background.audioReactive)

        // --- Captions: one clip per spoken word, grouped into on-screen lines.
        val anchor = if (input is InputSource.AudioOnly) style.audioOnly.captionAnchor else style.captions.anchor
        val maxWords = if (style.captions.mode == CaptionMode.SingleWord) 1 else style.captions.maxWordsPerLine
        val lines = transcript.lines(maxWords = maxWords)
        val outRanges = words.map { edit.toOutput(it.range) }
        var cursor = 0
        val captions = mutableListOf<CaptionClip>()
        lines.forEachIndexed { lineIndex, line ->
            for (w in line) {
                val i = cursor++
                if (i in fillers) continue
                val range = outRanges[i] ?: continue
                val preset = when {
                    i <= plan.hookEnd && plan.energy >= 0.6f -> "slam"
                    plan.ctaStart in 0..i -> "rise"
                    else -> style.captions.entry
                }
                val strength = if (i in emphasis) maxOf(w.emphasis, 0.9f) else minOf(w.emphasis, style.captions.emphasis.threshold - 0.05f).coerceAtLeast(0f)
                captions += CaptionClip(
                    range = range, text = w.text, language = w.language, preset = preset,
                    emphasis = strength, wordIndex = i, anchor = anchor, group = lineIndex,
                )
            }
        }
        // Recogniser timings can overlap by a few ms; captions sharing an anchor must not.
        for (k in 0 until captions.size - 1) {
            val a = captions[k]
            val b = captions[k + 1]
            if (a.range.endMs > b.range.startMs) captions[k] = a.copy(range = TimeRange(a.range.startMs, maxOf(a.range.startMs, b.range.startMs)))
        }
        clips += captions.filter { it.range.durationMs > 0 }

        // --- Motion-graphics elements, on the word they illustrate.
        val persianDigits = transcript.language == Language.Persian
        var lastEnd = -1L
        for (cue in plan.elements.sortedBy { it.word }) {
            val at = outRanges.getOrNull(cue.word)?.startMs ?: continue
            val start = (at - 80).coerceAtLeast(0).coerceAtLeast(lastEnd)
            val length = when (cue.kind) {
                "chart-up", "chart-down" -> 2_600L
                "counter", "progress", "ticker" -> 2_000L
                else -> 1_500L
            }
            val end = minOf(start + length, duration)
            if (end - start < 600) continue
            clips += element(cue, TimeRange(start, end), persianDigits)
            lastEnd = end
        }
        if (plan.headline.isNotBlank() && plan.hookEnd >= 0) {
            val hookOut = outRanges.getOrNull(plan.hookEnd)?.endMs ?: 0L
            val end = minOf(maxOf(hookOut, 1_600L), duration)
            if (clips.filterIsInstance<ElementClip>().none { it.range.startMs < end }) {
                clips += ElementClip(TimeRange(0, end), assetId = "badge/headline", preset = "pop", params = mapOf("text" to plan.headline))
            }
        }

        // --- Sound design from the style's event map.
        val sfxEnabled = plan.energy > 0f && style.sfx.isNotEmpty()
        if (sfxEnabled) clips += sfx(style, captions, clips.filterIsInstance<ElementClip>(), edit, plan, duration)

        // --- Music bed, ducked under speech by the mixer.
        if (plan.music != "none") clips += MusicClip(TimeRange(0, duration), assetId = "music/${plan.music}", gainDb = -20f, duckUnderSpeech = true)

        return Timeline(styleId = plan.styleId, seed = seed, canvas = canvasFor(input), durationMs = duration, clips = clips)
    }

    /** Silences and fillers to remove, in source time. */
    fun cuts(plan: EditPlan, transcript: Transcript, sourceMs: Long): List<CutClip> {
        val words = transcript.words
        if (words.isEmpty()) return emptyList()
        val result = mutableListOf<CutClip>()
        if (plan.cutSilences) {
            // Higher energy → tighter pacing.
            val minGap = (900 - 450 * plan.energy).toLong()
            for (gap in transcript.silences(minGap, sourceMs)) {
                val start = if (gap.startMs == 0L) 0L else gap.startMs + PAD_AFTER_MS
                val end = if (gap.endMs == sourceMs) sourceMs else gap.endMs - PAD_BEFORE_MS
                if (end - start >= 250) result += CutClip(TimeRange(start, end), CutReason.Silence)
            }
        }
        if (plan.cutFillers) {
            for (i in FillerDetector.fillerIndices(words)) {
                val r = words[i].range
                result += CutClip(TimeRange((r.startMs - 40).coerceAtLeast(0), minOf(r.endMs + 40, sourceMs)), CutReason.Filler)
            }
        }
        // Never cut everything: keep at least the speech itself.
        return if (EditMap(sourceMs, result.map { it.range }).outputDurationMs < 500) emptyList() else result.sortedBy { it.range.startMs }
    }

    private fun element(cue: ElementCue, range: TimeRange, persianDigits: Boolean): ElementClip = when (cue.kind) {
        "counter" -> {
            val m = Regex("^(\\$)?\\s*(-?[0-9]+(?:\\.[0-9]+)?)\\s*(%| تومان)?$").find(cue.value.trim())
            val prefix = m?.groupValues?.get(1).orEmpty()
            val number = m?.groupValues?.get(2) ?: cue.value
            val suffix = when (m?.groupValues?.get(3)) {
                "%" -> if (persianDigits) "٪" else "%"
                " تومان" -> " تومان"
                else -> ""
            }
            val decimals = number.substringAfter('.', "").length
            ElementClip(
                range, assetId = "counter/number", preset = "pop",
                params = buildMap {
                    put("from", "0"); put("to", number); put("decimals", decimals.toString())
                    if (prefix.isNotEmpty()) put("prefix", prefix)
                    if (suffix.isNotEmpty()) put("suffix", suffix)
                    if (persianDigits) put("digits", "fa")
                    if (cue.label.isNotBlank()) put("label", cue.label)
                },
            )
        }
        "ticker" -> ElementClip(
            range, assetId = "ticker/${cue.value.lowercase()}", preset = "rise", scale = 1.05f,
            params = buildMap {
                put("symbol", cue.value); put("change", cue.label.trim().removePrefix("+"))
                if (persianDigits) put("digits", "fa")
            },
        )
        "progress" -> ElementClip(range, assetId = "progress/bar", preset = "rise", params = mapOf("value" to ((cue.value.toFloatOrNull() ?: 100f) / 100f).toString()) + labelOf(cue))
        "chart-up", "chart-down" -> ElementClip(range, assetId = "chart/candles", preset = "rise", params = mapOf("trend" to if (cue.kind == "chart-up") "up" else "down") + labelOf(cue))
        "arrow-up" -> ElementClip(range, assetId = "arrow/up", preset = "pop", scale = 1.15f)
        "arrow-down" -> ElementClip(range, assetId = "arrow/down", preset = "pop", scale = 1.15f)
        "badge" -> ElementClip(range, assetId = "badge/text", preset = "pop", params = mapOf("text" to cue.value.ifBlank { cue.label }))
        else -> ElementClip(range, assetId = "icon/${cue.value.ifBlank { "star" }}", preset = "pop", scale = 1.1f)
    }

    private fun labelOf(cue: ElementCue) = if (cue.label.isNotBlank()) mapOf("label" to cue.label) else emptyMap()

    private fun sfx(style: StyleSpec, captions: List<CaptionClip>, elements: List<ElementClip>, edit: EditMap, plan: EditPlan, duration: Long): List<SfxClip> {
        val events = mutableListOf<Pair<Long, Pair<String, Float>>>()
        style.sfx["caption.emphasis"]?.let { id ->
            captions.filter { it.emphasis >= style.captions.emphasis.threshold }.forEach { events += it.range.startMs to (id to -9f) }
        }
        style.sfx["element.enter"]?.let { id -> elements.forEach { events += it.range.startMs to (id to -12f) } }
        if (plan.energy >= 0.8f) style.sfx["caption.word"]?.let { id -> captions.forEach { events += it.range.startMs to (id to -20f) } }
        style.sfx["cut"]?.let { id -> edit.joinPointsMs.forEach { events += it to (id to -14f) } }

        // One sound at a time, and never a machine-gun of ticks.
        val out = mutableListOf<SfxClip>()
        var last = -10_000L
        for ((t, ev) in events.sortedBy { it.first }) {
            if (t - last < 220 || t >= duration) continue
            out += SfxClip(TimeRange(t, minOf(t + 600, duration)), assetId = ev.first, gainDb = ev.second)
            last = t
        }
        return out
    }

    private fun canvasFor(input: InputSource): CanvasSpec = when (input) {
        is InputSource.AudioOnly -> input.canvas
        is InputSource.Video -> {
            val fps = CanvasSpec.SUPPORTED_FRAME_RATES.minBy { kotlin.math.abs(it - input.format.frameRate) }
            CanvasSpec(aspect = input.format.closestAspect(), frameRate = fps)
        }
    }
}
