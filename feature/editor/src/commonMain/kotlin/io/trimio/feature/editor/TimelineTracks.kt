package io.trimio.feature.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.trimio.core.designsystem.theme.Trimio
import io.trimio.core.designsystem.theme.tr
import io.trimio.core.model.timeline.CaptionClip
import io.trimio.core.model.timeline.EditMap
import io.trimio.core.model.timeline.ElementClip
import io.trimio.core.model.timeline.MusicClip
import io.trimio.core.model.timeline.SfxClip
import io.trimio.core.model.timeline.Timeline
import io.trimio.engine.render.PreviewClock

/**
 * Multi-track timeline in the CapCut idiom: a fixed playhead in the centre, the edit slides under
 * it. Tracks: waveform, captions (emphasis lit), motion elements, music and sound effects.
 * Drag to scrub (a light haptic tick on every word boundary), tap to jump, pinch to zoom.
 * Media timelines stay left-to-right in RTL layouts (docs/DESIGN.md §9). The clock is read only in
 * the draw phase, so playback never recomposes the editor.
 */
@Composable
fun TimelineTracks(
    timeline: Timeline,
    clock: PreviewClock,
    sourceDurationMs: Long,
    waveform: List<Float>,
    waveformStepMs: Long,
    modifier: Modifier = Modifier,
    label: String = "",
) {
    val colors = Trimio.colors
    val haptics = Trimio.haptics
    val measurer = rememberTextMeasurer()
    var pxPerSecond by remember { mutableFloatStateOf(140f) }
    val edit = remember(timeline, sourceDurationMs) { EditMap.of(timeline, sourceDurationMs) }
    val captions = remember(timeline) { timeline.clipsOf<CaptionClip>().sortedBy { it.range.startMs } }
    val elements = remember(timeline) { timeline.clipsOf<ElementClip>() }
    val names = elementNames()
    val elementLabels = remember(elements, names) { elements.map { names(it) } }
    val music = remember(timeline) { timeline.clipsOf<MusicClip>() }
    val sfx = remember(timeline) { timeline.clipsOf<SfxClip>() }
    val boundaries = remember(captions) { captions.map { it.range.startMs } }
    val textStyle = Trimio.type.caption.copy(fontSize = 10.sp, color = Color.White)

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Canvas(
            modifier
                .fillMaxWidth()
                .height(168.dp)
                .semantics { contentDescription = label }
                .pointerInput(timeline) {
                    detectTransformGestures { _, _, zoom, _ -> pxPerSecond = (pxPerSecond * zoom).coerceIn(30f, 900f) }
                }
                .pointerInput(timeline) {
                    var wasPlaying = false
                    detectHorizontalDragGestures(
                        onDragStart = {
                            wasPlaying = clock.playing
                            clock.playing = false
                        },
                        onDragEnd = { clock.playing = wasPlaying },
                    ) { _, dx ->
                        val before = clock.positionMs
                        val after = (before - (dx / pxPerSecond * 1000f).toLong()).coerceIn(0, timeline.durationMs)
                        clock.seekTo(after)
                        if (boundaries.any { it in minOf(before, after)..maxOf(before, after) && before != after }) haptics.tick()
                    }
                }
                .pointerInput(timeline) {
                    detectTapGestures { offset ->
                        val ms = clock.positionMs + ((offset.x - size.width / 2f) / pxPerSecond * 1000f).toLong()
                        clock.seekTo(ms.coerceIn(0, timeline.durationMs))
                        haptics.snap()
                    }
                },
        ) {
            val now = clock.positionMs // draw-phase read
            val pxPerMs = pxPerSecond / 1000f
            fun x(ms: Long) = size.width / 2f + (ms - now) * pxPerMs
            val visibleFrom = now - (size.width / 2f / pxPerMs).toLong()
            val visibleTo = now + (size.width / 2f / pxPerMs).toLong()

            val ruler = 18.dp.toPx()
            val lane = (size.height - ruler) / 4f
            val gap = 4.dp.toPx()

            // Ruler: a tick every second, a label every five.
            val firstSecond = (visibleFrom / 1000).coerceAtLeast(0)
            for (s in firstSecond..(visibleTo / 1000 + 1)) {
                if (s * 1000 > timeline.durationMs) break
                val tx = x(s * 1000)
                drawLine(colors.textTertiary, Offset(tx, ruler * 0.55f), Offset(tx, ruler), 1f)
                if (s % 5 == 0L) {
                    val layout = measurer.measure("${s / 60}:${(s % 60).toString().padStart(2, '0')}", textStyle.copy(color = colors.textTertiary))
                    drawText(layout, topLeft = Offset(tx + 3f, 0f))
                }
            }
            // The edit's extent.
            drawRect(colors.glassFill, Offset(x(0), ruler), Size(timeline.durationMs * pxPerMs, size.height - ruler))

            clipRect(top = ruler) {
                // Lane 0: waveform in output time (sampled through the cuts).
                val top0 = ruler + gap
                val h0 = lane - gap * 2
                if (waveform.isNotEmpty()) {
                    val step = 4.dp.toPx()
                    var px = maxOf(x(0), 0f)
                    while (px < minOf(x(timeline.durationMs), size.width)) {
                        val outMs = now + ((px - size.width / 2f) / pxPerMs).toLong()
                        val src = edit.toSource(outMs)
                        val level = waveform.getOrElse((src / waveformStepMs).toInt()) { 0f }
                        val bar = (h0 * (0.08f + 0.92f * level)).coerceAtLeast(2f)
                        drawRoundRect(colors.accentCyan.copy(alpha = 0.7f), Offset(px, top0 + (h0 - bar) / 2), Size(step * 0.6f, bar), CornerRadius(2f))
                        px += step
                    }
                }
                // Cut markers at every join.
                edit.joinPointsMs.forEach { j -> if (j in visibleFrom..visibleTo) drawLine(colors.accentAmber, Offset(x(j), ruler), Offset(x(j), size.height), 2f) }

                // Lane 1: captions.
                val top1 = ruler + lane + gap
                for (c in captions) {
                    if (c.range.endMs < visibleFrom || c.range.startMs > visibleTo) continue
                    val left = x(c.range.startMs)
                    val w = (c.range.durationMs * pxPerMs - 2f).coerceAtLeast(3f)
                    val emphasized = c.emphasis >= 0.6f
                    drawRoundRect(
                        if (emphasized) Brush.horizontalGradient(listOf(colors.primary, colors.accentMagenta)) else Brush.horizontalGradient(listOf(colors.canvasRaised, colors.canvasRaised)),
                        Offset(left, top1), Size(w, lane - gap * 2), CornerRadius(6f),
                    )
                    if (w > 26.dp.toPx()) {
                        val layout = measurer.measure(c.text, textStyle, maxLines = 1, softWrap = false)
                        if (layout.size.width < w - 6f) drawText(layout, topLeft = Offset(left + (w - layout.size.width) / 2, top1 + (lane - gap * 2 - layout.size.height) / 2))
                    }
                }

                // Lane 2: motion elements.
                val top2 = ruler + lane * 2 + gap
                for ((k, e) in elements.withIndex()) {
                    if (e.range.endMs < visibleFrom || e.range.startMs > visibleTo) continue
                    val left = x(e.range.startMs)
                    val w = e.range.durationMs * pxPerMs - 2f
                    drawRoundRect(colors.accentMagenta.copy(alpha = 0.35f), Offset(left, top2), Size(w, lane - gap * 2), CornerRadius(6f))
                    drawRoundRect(colors.accentMagenta, Offset(left, top2), Size(3f, lane - gap * 2), CornerRadius(2f))
                    val layout = measurer.measure(elementLabels[k], textStyle, maxLines = 1, softWrap = false)
                    if (layout.size.width < w - 10f) drawText(layout, topLeft = Offset(left + 8f, top2 + (lane - gap * 2 - layout.size.height) / 2))
                }

                // Lane 3: music bed and sound effects.
                val top3 = ruler + lane * 3 + gap
                for (m in music) drawRoundRect(colors.success.copy(alpha = 0.22f), Offset(x(m.range.startMs), top3), Size(m.range.durationMs * pxPerMs, lane - gap * 2), CornerRadius(6f))
                for (s in sfx) if (s.range.startMs in visibleFrom..visibleTo) drawCircle(colors.accentAmber, 4.dp.toPx(), Offset(x(s.range.startMs), top3 + (lane - gap * 2) / 2))
            }

            drawPlayhead(colors.textPrimary, ruler)
        }
    }
}

private fun DrawScope.drawPlayhead(color: Color, top: Float) {
    val cx = size.width / 2f
    drawLine(color, Offset(cx, top - 6f), Offset(cx, size.height), 2.dp.toPx())
    drawCircle(color, 5.dp.toPx(), Offset(cx, top - 4f))
}

/** Short human label for an element on the timeline (its value where it has one). */
@Composable
private fun elementNames(): (ElementClip) -> String {
    val counter = tr("شمارنده", "Counter")
    val chart = tr("نمودار", "Chart")
    val arrow = tr("فلش", "Arrow")
    val icon = tr("آیکون", "Icon")
    val progress = tr("پیشرفت", "Progress")
    return { e ->
        when (e.assetId.substringBefore('/')) {
            "counter" -> listOfNotNull(e.params["prefix"], e.params["to"], e.params["suffix"]).joinToString("").ifBlank { counter }
            "ticker" -> e.params["symbol"] ?: "Ticker"
            "chart" -> chart
            "arrow" -> arrow
            "badge" -> e.params["text"] ?: e.assetId.substringAfter('/')
            "progress" -> progress
            else -> icon + " · " + e.assetId.substringAfter('/')
        }
    }
}
