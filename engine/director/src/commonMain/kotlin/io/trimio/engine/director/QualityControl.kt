package io.trimio.engine.director

import io.trimio.core.model.input.InputSource
import io.trimio.core.model.style.BoxStyle
import io.trimio.core.model.style.CaptionMode
import io.trimio.core.model.style.StyleSpec
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.timeline.CaptionClip
import io.trimio.core.model.timeline.Timeline
import io.trimio.core.model.timeline.TimelineValidator
import kotlin.math.pow

/**
 * The last gate before rendering. Fixes what can be fixed automatically and reports it:
 *  - legibility: WCAG contrast of caption text and emphasis against what they sit on;
 *  - footage safety: captions with no box over unknown video always get a shadow;
 *  - readability: no caption flashes by faster than the eye can read it;
 *  - structure: [TimelineValidator] errors are repaired by dropping the offending clips, and a
 *    timeline that still fails is rejected (the caller then falls back to the rules plan).
 */
class QualityControl(private val validator: TimelineValidator = TimelineValidator()) {

    data class Note(val textFa: String, val textEn: String)

    /** Applies plan-level overrides (caption mode, energy) and contrast fixes to the pack's style. */
    fun tuneStyle(spec: StyleSpec, plan: EditPlan, input: InputSource): Pair<StyleSpec, List<Note>> {
        val notes = mutableListOf<Note>()
        var style = spec
        CaptionMode.entries.firstOrNull { it.name == plan.captionMode }?.let { style = style.copy(captions = style.captions.copy(mode = it)) }
        style = style.copy(motion = style.motion.copy(energy = plan.energy))

        val p = style.palette
        val captionBg: String? = when {
            style.captions.box == BoxStyle.Pill -> style.captions.boxColor ?: "#1C1C1C" // renderer default: 55% black
            style.captions.box == BoxStyle.Brutal -> style.captions.boxColor ?: p.accent
            input is InputSource.AudioOnly -> average(p.background)
            style.captions.box == BoxStyle.Glass -> "#1A1A22" // frosted dark glass over footage
            else -> null // unknown footage: handled with a shadow below
        }
        if (captionBg != null) {
            if (contrast(p.text, captionBg) < 4.5) {
                val fixed = bestOf(listOf("#FFFFFF", "#111111"), captionBg)
                style = style.copy(palette = style.palette.copy(text = fixed))
                notes += Note("رنگ متن برای خوانایی اصلاح شد", "Caption colour adjusted for legibility")
            }
            val emphasisColor = role(style, style.captions.emphasis.colorRole)
            if (style.captions.emphasis.box != BoxStyle.Highlight && contrast(emphasisColor, captionBg) < 3.0) {
                val roles = listOf("accent", "accent2", "text")
                val best = roles.maxBy { contrast(role(style, it), captionBg) }
                style = style.copy(captions = style.captions.copy(emphasis = style.captions.emphasis.copy(colorRole = best)))
                notes += Note("رنگ تأکید برای کنتراست بهتر تغییر کرد", "Emphasis colour changed for contrast")
            }
        }
        // The highlighter marker is drawn in the emphasis role colour, with emphasisText on top.
        val marker = role(style, style.captions.emphasis.colorRole)
        if (style.captions.emphasis.box == BoxStyle.Highlight && contrast(style.palette.emphasisText, marker) < 4.5) {
            style = style.copy(palette = style.palette.copy(emphasisText = bestOf(listOf("#000000", "#FFFFFF"), marker)))
        }
        if (input is InputSource.Video && style.captions.box == BoxStyle.None && style.captions.strokeWidth == 0f && style.captions.shadowBlur < 0.012f) {
            style = style.copy(captions = style.captions.copy(shadowBlur = 0.014f))
        }
        return style to notes
    }

    /**
     * Readability and structural repair. Returns null when the timeline cannot be made renderable.
     */
    fun finalize(timeline: Timeline, input: InputSource): Timeline? {
        var t = extendShortCaptions(timeline)
        repeat(2) {
            val errors = validator.validate(t, input).filter { it.severity == TimelineValidator.Severity.Error }
            if (errors.isEmpty()) return t
            val bad = errors.mapNotNull { it.clipIndex }.toSet()
            if (bad.isEmpty()) return null // timeline-level error (duration, background): not repairable here
            t = t.copy(clips = t.clips.filterIndexed { i, _ -> i !in bad })
        }
        return t.takeIf { validator.isRenderable(it, input) }
    }

    /** Gives every caption at least [MIN_CAPTION_MS] on screen, borrowing from the silence after it. */
    private fun extendShortCaptions(timeline: Timeline): Timeline {
        val captions = timeline.clips.withIndex().filter { it.value is CaptionClip }.sortedBy { it.value.range.startMs }
        val replaced = mutableMapOf<Int, CaptionClip>()
        captions.forEachIndexed { k, (index, clip) ->
            clip as CaptionClip
            if (clip.range.durationMs >= MIN_CAPTION_MS) return@forEachIndexed
            val limit = captions.getOrNull(k + 1)?.value?.range?.startMs ?: timeline.durationMs
            val end = minOf(clip.range.startMs + MIN_CAPTION_MS, limit, timeline.durationMs)
            if (end > clip.range.endMs) replaced[index] = clip.copy(range = TimeRange(clip.range.startMs, end))
        }
        return if (replaced.isEmpty()) timeline else timeline.copy(clips = timeline.clips.mapIndexed { i, c -> replaced[i] ?: c })
    }

    companion object {
        const val MIN_CAPTION_MS = 220L

        /** WCAG 2.x contrast ratio between two `#RRGGBB[AA]` colours (alpha ignored). */
        fun contrast(a: String, b: String): Double {
            val la = luminance(a)
            val lb = luminance(b)
            return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
        }

        fun luminance(hex: String): Double {
            val (r, g, b) = rgb(hex)
            fun lin(c: Int): Double = (c / 255.0).let { if (it <= 0.03928) it / 12.92 else ((it + 0.055) / 1.055).pow(2.4) }
            return 0.2126 * lin(r) + 0.7152 * lin(g) + 0.0722 * lin(b)
        }

        private fun rgb(hex: String): Triple<Int, Int, Int> {
            val h = hex.removePrefix("#")
            return Triple(h.substring(0, 2).toInt(16), h.substring(2, 4).toInt(16), h.substring(4, 6).toInt(16))
        }

        private fun average(colors: List<String>): String {
            val parts = colors.map(::rgb)
            fun avg(sel: (Triple<Int, Int, Int>) -> Int) = parts.sumOf(sel) / parts.size
            fun hex2(v: Int) = v.toString(16).padStart(2, '0')
            return "#" + hex2(avg { it.first }) + hex2(avg { it.second }) + hex2(avg { it.third })
        }

        private fun bestOf(options: List<String>, background: String) = options.maxBy { contrast(it, background) }

        private fun role(style: StyleSpec, role: String) = when (role) {
            "accent2" -> style.palette.accent2
            "text" -> style.palette.text
            else -> style.palette.accent
        }
    }
}
