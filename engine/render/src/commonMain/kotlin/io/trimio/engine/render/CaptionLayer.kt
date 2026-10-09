package io.trimio.engine.render

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import io.trimio.core.model.style.BoxStyle
import io.trimio.core.model.style.CaptionMode
import io.trimio.core.model.style.StyleSpec
import io.trimio.core.model.text.Language
import io.trimio.core.model.timeline.Anchor
import io.trimio.core.model.timeline.CaptionClip

/**
 * Kinetic captions. Words are grouped into on-screen phrases, laid out in their own reading
 * direction (Persian right-to-left even inside an English UI), and animated word by word.
 *
 * Persian is never animated letter by letter: that would break the cursive joins. Reveals use
 * whole-word transforms or clip masks that sweep in reading direction instead.
 */
internal class CaptionLayer(
    clips: List<CaptionClip>,
    style: StyleSpec,
    private val palette: ResolvedPalette,
    private val measurer: TextMeasurer,
    private val fontFamily: FontFamily,
) {
    private val spec = style.captions
    private val energy = style.motion.energy
    private val groups: List<Group> = clips.groupBy { it.group }.values
        .map { cs -> Group(cs.sortedBy { it.wordIndex }) }
        .sortedBy { it.startMs }
    private val layouts = HashMap<Pair<Int, Size>, GroupLayout>()
    private val measured = HashMap<String, TextLayoutResult>()

    private class Group(val words: List<CaptionClip>) {
        val id = words.first().group
        val startMs = words.first().range.startMs
        val endMs = words.maxOf { it.range.endMs }
        val rtl = words.count { it.language.isRtl } * 2 >= words.size
    }

    private class Placed(val clip: CaptionClip, val text: TextLayoutResult, val center: Offset, val line: Int) {
        val width get() = text.size.width.toFloat()
        val height get() = text.size.height.toFloat()
        fun rect(scale: Float = 1f) = Rect(center.x - width * scale / 2, center.y - height * scale / 2, center.x + width * scale / 2, center.y + height * scale / 2)
    }

    private class GroupLayout(val words: List<Placed>, val fontPx: Float)

    // --- Timing ------------------------------------------------------------------------------

    private val leadMs get() = if (spec.mode == CaptionMode.BuildUp || spec.mode == CaptionMode.SingleWord) 0L else 120L
    private val holdMs = 350L
    private val exitMs = 170f

    private fun windowEnd(index: Int): Long {
        val g = groups[index]
        val next = groups.getOrNull(index + 1)?.let { it.startMs - leadMs } ?: Long.MAX_VALUE
        return minOf(g.endMs + holdMs, next)
    }

    fun DrawScope.draw(timeMs: Long) {
        groups.forEachIndexed { i, g ->
            val start = g.startMs - leadMs
            val end = windowEnd(i)
            if (timeMs in start until end) drawGroup(g, timeMs, end)
        }
    }

    // --- Layout ------------------------------------------------------------------------------

    private fun DrawScope.layout(group: Group): GroupLayout = layouts.getOrPut(group.id to size) {
        val unit = FrameRenderer.unit(size)
        val single = spec.mode == CaptionMode.SingleWord
        val fontPx = unit * spec.size * (if (single) 1.9f else 1f)
        val maxWidth = size.width * SafeZones.captionWidth(size, spec.anchor)
        // Single-word mode shrinks any word (plus its emphasis punch) that would overflow the screen.
        val texts = group.words.map { word ->
            val first = measure(word, fontPx)
            val needed = first.size.width * emphasisScaleFor(word)
            if (single && needed > maxWidth) measure(word, fontPx * maxWidth / needed) else first
        }
        val gap = fontPx * 0.34f

        // Greedy wrap in reading order, by width and word count.
        val lines = mutableListOf<MutableList<Int>>()
        var width = 0f
        texts.forEachIndexed { i, t ->
            val w = t.size.width * emphasisScaleFor(group.words[i])
            val current = lines.lastOrNull()
            if (current == null || single || current.size >= spec.maxWordsPerLine || width + gap + w > maxWidth) {
                lines += mutableListOf(i)
                width = w
            } else {
                current += i
                width += gap + w
            }
        }

        // Boxed lines need room for their padding (10% above and below), or the boxes overlap.
        val boxed = spec.box == BoxStyle.Pill || spec.box == BoxStyle.Glass || spec.box == BoxStyle.Brutal
        // Emphasised words are drawn larger, so lines are spaced by their scaled height.
        val tallest = texts.indices.maxOfOrNull { texts[it].size.height * emphasisScaleFor(group.words[it]) } ?: 0f
        val lineHeight = tallest * (if (boxed) 1.3f else 1.08f)
        val blockHeight = lineHeight * (if (single) 1 else lines.size)
        val anchorY = when (spec.anchor) {
            Anchor.TopStart, Anchor.TopCenter, Anchor.TopEnd -> size.height * 0.22f
            Anchor.CenterStart, Anchor.Center, Anchor.CenterEnd -> size.height * 0.5f
            else -> size.height * (if (size.width > size.height) 0.80f else 0.70f)
        }
        val top = (if (single) size.height * 0.5f else anchorY) - blockHeight / 2

        val placed = mutableListOf<Placed>()
        lines.forEachIndexed { li, indices ->
            val y = top + (if (single) 0f else li * lineHeight) + lineHeight / 2
            val widths = indices.map { texts[it].size.width * emphasisScaleFor(group.words[it]) }
            val total = widths.sum() + gap * (indices.size - 1)
            // Reading direction decides which edge the first word sits on.
            var cursor = if (group.rtl) (size.width + total) / 2 else (size.width - total) / 2
            indices.forEachIndexed { k, i ->
                val w = widths[k]
                val cx = if (group.rtl) cursor - w / 2 else cursor + w / 2
                cursor = if (group.rtl) cursor - w - gap else cursor + w + gap
                placed += Placed(group.words[i], texts[i], Offset(if (single) size.width / 2 else cx, y), if (single) 0 else li)
            }
        }
        GroupLayout(placed, fontPx)
    }

    private fun measure(clip: CaptionClip, fontPx: Float): TextLayoutResult {
        val latin = clip.language == Language.English
        val text = if (spec.uppercase && latin) clip.text.uppercase() else clip.text
        val key = "$text|$fontPx|${spec.weight}"
        return measured.getOrPut(key) {
            measurer.measure(
                text = text,
                style = TextStyle(
                    fontFamily = fontFamily,
                    fontWeight = FontWeight(spec.weight),
                    fontSize = fontPx.sp,
                    // Letter-spacing would tear Persian cursive joins apart; Latin only.
                    letterSpacing = if (latin) spec.letterSpacingEm.em else 0.em,
                    textDirection = if (clip.language.isRtl) TextDirection.Rtl else TextDirection.Ltr,
                ),
                softWrap = false,
                density = Density(1f),
            )
        }
    }

    private fun isEmphasised(clip: CaptionClip) = clip.emphasis >= spec.emphasis.threshold
    /** Space reserved for an emphasised word: its scale plus the spring's overshoot. */
    private fun emphasisScaleFor(clip: CaptionClip) = if (isEmphasised(clip)) spec.emphasis.scale * 1.1f else 1f

    // --- Drawing -----------------------------------------------------------------------------

    private fun DrawScope.drawGroup(group: Group, t: Long, windowEnd: Long) {
        val layout = layout(group)
        val unit = FrameRenderer.unit(size)
        val exitP = Motion.progress((t - (windowEnd - exitMs.toLong())).toFloat(), exitMs)
        val exit = exitTransform(exitP, layout.fontPx)

        val visible = layout.words.mapIndexedNotNull { i, w ->
            val entryT = entryTime(group, w.clip, i, t) ?: return@mapIndexedNotNull null
            Triple(w, entryT, i)
        }
        if (visible.isEmpty()) return

        drawLineBoxes(visible.map { it.first to entry(it.second) }, unit, exit)

        for ((w, entryT, _) in visible) {
            val a = entry(entryT)
            val emphasised = isEmphasised(w.clip)
            val punch = if (emphasised) 1f + (spec.emphasis.scale - 1f) * Motion.spring(entryT, 0.45f, 420f * speed()) else 1f
            val active = t in w.clip.range
            val phraseLift = if (spec.mode == CaptionMode.Phrase && active) 1.06f else 1f
            val scale = a.scale * punch * phraseLift * exit.scale
            val alpha = (a.alpha * exit.alpha).coerceIn(0f, 1f)
            if (alpha <= 0.001f) continue

            val highlighter = spec.emphasis.box == BoxStyle.Highlight
            val color = when {
                // With a highlighter the marker carries the role colour and the word stays readable on it.
                highlighter && (emphasised || (spec.mode == CaptionMode.Phrase && active)) -> palette.emphasisText
                emphasised -> palette.role(spec.emphasis.colorRole)
                spec.mode == CaptionMode.Phrase && active -> palette.accent
                else -> palette.text
            }

            withTransform({
                translate(w.center.x, w.center.y + a.offsetY + exit.offsetY)
                rotate(a.rotation, Offset.Zero)
                scale(scale, scale * a.scaleY, Offset.Zero)
            }) {
                val topLeft = Offset(-w.width / 2, -w.height / 2)
                // Styles with a highlighter mark emphasis, and the spoken word in Phrase mode, with the
                // marker rather than a colour change (keeps contrast on bold boxes).
                val marked = spec.emphasis.box == BoxStyle.Highlight && (emphasised || (spec.mode == CaptionMode.Phrase && active))
                if (marked) {
                    val markerT = if (emphasised) entryT else (t - w.clip.range.startMs).toFloat()
                    drawHighlight(w, topLeft, markerT, group.rtl, unit)
                }
                if (spec.mode == CaptionMode.Karaoke) {
                    drawKaraokeWord(w, topLeft, t, group.rtl, alpha, unit)
                } else {
                    drawWord(w.text, topLeft, color, alpha, unit, reveal = a.reveal, rtl = w.clip.language.isRtl)
                }
            }
        }
    }

    /** When this word starts animating (ms since its entry began), or null if not yet visible. */
    private fun entryTime(group: Group, clip: CaptionClip, index: Int, t: Long): Float? = when (spec.mode) {
        CaptionMode.BuildUp -> (t - clip.range.startMs).takeIf { it >= 0 }?.toFloat()
        CaptionMode.SingleWord -> {
            val next = group.words.getOrNull(index + 1)?.range?.startMs ?: Long.MAX_VALUE
            if (t >= clip.range.startMs && t < next) (t - clip.range.startMs).toFloat() else null
        }
        CaptionMode.Phrase, CaptionMode.Karaoke -> {
            // The phrase enters together with a short stagger in reading order.
            val start = group.startMs - leadMs + index * 35L
            (t - start).takeIf { it >= 0 }?.toFloat()
        }
    }

    private class WordAnim(
        val scale: Float = 1f,
        val scaleY: Float = 1f,
        val offsetY: Float = 0f,
        val alpha: Float = 1f,
        val rotation: Float = 0f,
        /** 0..1 portion revealed in reading direction (wipe). */
        val reveal: Float = 1f,
    )

    private fun speed() = 0.7f + 0.6f * energy

    private fun DrawScope.entry(tMs: Float): WordAnim {
        val unit = FrameRenderer.unit(size)
        val k = speed()
        return when (spec.entry) {
            "pop" -> {
                val s = Motion.spring(tMs, 0.55f, 600f * k)
                WordAnim(scale = 0.35f + 0.65f * s, alpha = Motion.progress(tMs, 70f))
            }
            "rise" -> {
                val s = Motion.spring(tMs, 0.8f, 520f * k)
                WordAnim(offsetY = (1f - s) * unit * 0.06f, alpha = Motion.progress(tMs, 110f))
            }
            "slam" -> {
                val s = Motion.spring(tMs, 0.62f, 950f * k)
                WordAnim(scale = 1f + 1.3f * (1f - s), rotation = -7f * (1f - s), alpha = Motion.progress(tMs, 40f))
            }
            "wipe" -> WordAnim(reveal = Motion.easeOutExpo(Motion.progress(tMs, 280f / k)))
            "flip" -> {
                val s = Motion.spring(tMs, 0.6f, 650f * k)
                WordAnim(scaleY = s.coerceAtLeast(0.01f), alpha = Motion.progress(tMs, 60f))
            }
            else -> WordAnim(alpha = Motion.progress(tMs, 200f))
        }
    }

    private fun exitTransform(p: Float, fontPx: Float): WordAnim = when {
        p <= 0f -> WordAnim()
        spec.exit == "fall" -> WordAnim(offsetY = Motion.easeIn(p) * fontPx * 0.6f, alpha = 1f - p)
        spec.exit == "shrink" -> WordAnim(scale = 1f - 0.15f * Motion.easeIn(p), alpha = 1f - p)
        else -> WordAnim(alpha = 1f - p)
    }

    private fun DrawScope.drawWord(text: TextLayoutResult, topLeft: Offset, color: Color, alpha: Float, unit: Float, reveal: Float = 1f, rtl: Boolean) {
        val shadow = if (spec.shadowBlur > 0f) Shadow(palette.shadow, Offset(0f, unit * 0.004f), unit * spec.shadowBlur) else null
        val body = {
            if (spec.strokeWidth > 0f) {
                drawText(text, color = parseColor(spec.strokeColor), topLeft = topLeft, alpha = alpha, drawStyle = Stroke(unit * spec.strokeWidth, join = StrokeJoin.Round))
            }
            drawText(text, color = color, topLeft = topLeft, alpha = alpha, shadow = shadow)
        }
        if (reveal >= 1f) {
            body()
        } else {
            val w = text.size.width.toFloat()
            val visible = w * reveal
            // Sweep from the reading-start edge: right for Persian, left for English.
            val left = if (rtl) topLeft.x + w - visible else topLeft.x
            clipRect(left, topLeft.y - unit, left + visible, topLeft.y + text.size.height + unit) { body() }
        }
    }

    /** Dim word with the spoken portion filled in reading direction. */
    private fun DrawScope.drawKaraokeWord(w: Placed, topLeft: Offset, t: Long, rtl: Boolean, alpha: Float, unit: Float) {
        drawWord(w.text, topLeft, palette.text.copy(alpha = 0.42f), alpha, unit, rtl = rtl)
        val p = Motion.progress((t - w.clip.range.startMs).toFloat(), w.clip.range.durationMs.toFloat().coerceAtLeast(1f))
        if (p <= 0f) return
        val fill = if (isEmphasised(w.clip)) palette.role(spec.emphasis.colorRole) else palette.accent
        drawWord(w.text, topLeft, fill, alpha, unit, reveal = p, rtl = rtl)
    }

    /** Marker-pen highlight that sweeps behind an emphasised word. */
    private fun DrawScope.drawHighlight(w: Placed, topLeft: Offset, entryT: Float, rtl: Boolean, unit: Float) {
        val p = Motion.easeOutExpo(Motion.progress(entryT, 240f))
        val padX = w.height * 0.12f
        val width = (w.width + padX * 2) * p
        val left = if (rtl) topLeft.x + w.width + padX - width else topLeft.x - padX
        drawRoundRect(
            color = palette.role(spec.emphasis.colorRole),
            topLeft = Offset(left, topLeft.y + w.height * 0.18f),
            size = Size(width, w.height * 0.7f),
            cornerRadius = CornerRadius(unit * 0.008f),
        )
    }

    /** Pill, glass or brutal box behind each line, growing with the words revealed so far. */
    private fun DrawScope.drawLineBoxes(words: List<Pair<Placed, WordAnim>>, unit: Float, exit: WordAnim) {
        if (spec.box == BoxStyle.None || spec.box == BoxStyle.Highlight) return
        words.groupBy { it.first.line }.forEach { (_, ws) ->
            // The entry overshoot animates the words, not the box: a bouncing box would collide with its neighbours.
            val rects = ws.map { (w, a) -> w.rect(a.scale.coerceIn(0f, 1f) * emphasisScaleFor(w.clip)) }
            val bounds = rects.reduce { acc, r -> Rect(minOf(acc.left, r.left), minOf(acc.top, r.top), maxOf(acc.right, r.right), maxOf(acc.bottom, r.bottom)) }
            val padX = bounds.height * 0.32f
            val padY = bounds.height * 0.10f
            val box = Rect(bounds.left - padX, bounds.top - padY + exit.offsetY, bounds.right + padX, bounds.bottom + padY + exit.offsetY)
            val alpha = ws.maxOf { it.second.alpha } * exit.alpha
            val fill = spec.boxColor?.let(::parseColor)
            when (spec.box) {
                BoxStyle.Pill -> drawRoundRect(
                    (fill ?: Color.Black.copy(alpha = 0.55f)), box.topLeft, box.size, CornerRadius(box.height / 2), alpha = alpha,
                )
                BoxStyle.Glass -> {
                    drawRoundRect(Color.White.copy(alpha = 0.14f), box.topLeft, box.size, CornerRadius(unit * 0.03f), alpha = alpha)
                    drawRoundRect(
                        Brush.linearGradient(listOf(Color.White.copy(alpha = 0.6f), Color.White.copy(alpha = 0.08f)), box.topLeft, box.bottomRight),
                        box.topLeft, box.size, CornerRadius(unit * 0.03f), alpha = alpha, style = Stroke(unit * 0.003f),
                    )
                }
                BoxStyle.Brutal -> {
                    val off = unit * 0.014f
                    val r = CornerRadius(unit * 0.006f)
                    drawRoundRect(Color.Black, box.topLeft + Offset(off, off), box.size, r, alpha = alpha)
                    drawRoundRect(fill ?: palette.accent, box.topLeft, box.size, r, alpha = alpha)
                    drawRoundRect(Color.Black, box.topLeft, box.size, r, alpha = alpha, style = Stroke(unit * 0.006f))
                }
                else -> Unit
            }
        }
    }
}
