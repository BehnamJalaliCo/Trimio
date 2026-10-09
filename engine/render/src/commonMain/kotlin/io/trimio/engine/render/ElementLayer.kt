package io.trimio.engine.render

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import io.trimio.core.model.style.StyleSpec
import io.trimio.core.model.text.Numerals
import io.trimio.core.model.timeline.Anchor
import io.trimio.core.model.timeline.ElementClip
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * Procedural motion-graphics elements, drawn in the style's card treatment. Asset ids:
 * `counter/…`, `arrow/up|down`, `chart/candles`, `badge/…`, `progress/…`, `icon/coin|check|star|bolt|heart`.
 * Parameters come from [ElementClip.params]. Lottie and 3D assets plug in here in phase 6.
 */
internal class ElementLayer(
    private val clips: List<ElementClip>,
    private val style: StyleSpec,
    private val palette: ResolvedPalette,
    private val measurer: TextMeasurer,
    private val fontFamily: FontFamily,
) {
    private val success = Color(0xFF3DFFA0)
    private val danger = Color(0xFFFF5470)

    /**
     * @param middleY vertical position (fraction of height) for centre-anchored elements. The renderer
     *   lowers it into the top third when captions or a visualiser occupy the middle of the frame.
     */
    fun DrawScope.draw(timeMs: Long, middleY: Float = 0.4f) {
        for (clip in clips) {
            if (timeMs !in clip.range) continue
            val t = (timeMs - clip.range.startMs).toFloat()
            val remaining = (clip.range.endMs - timeMs).toFloat()
            val enter = Motion.spring(t, 0.6f, 520f)
            val exit = 1f - Motion.progress(220f - remaining, 220f)
            val unit = FrameRenderer.unit(size)
            val base = unit * 0.24f * clip.scale
            val center = anchorPoint(clip.anchor, middleY)

            withTransform({
                translate(center.x, center.y + (1f - enter) * unit * 0.05f)
                val s = (0.6f + 0.4f * enter) * (0.9f + 0.1f * exit)
                scale(s, s, Offset.Zero)
            }) {
                val alpha = (Motion.progress(t, 90f) * exit).coerceIn(0f, 1f)
                val kind = clip.assetId.substringBefore('/')
                val name = clip.assetId.substringAfter('/', "")
                when (kind) {
                    "counter" -> counter(clip, t, base, alpha)
                    "arrow" -> arrow(name != "down", t, base, alpha)
                    "chart" -> candles(clip, t, base, alpha)
                    "badge" -> badge(clip.params["text"] ?: name, base, alpha)
                    "progress" -> progress(clip, t, base, alpha)
                    "icon" -> icon(name, t, base, alpha)
                    else -> badge(clip.assetId, base, alpha)
                }
            }
        }
    }

    private fun DrawScope.anchorPoint(anchor: Anchor, middle: Float): Offset {
        val (fx, fy) = when (anchor) {
            Anchor.TopStart -> 0.25f to 0.2f
            Anchor.TopCenter -> 0.5f to 0.18f
            Anchor.TopEnd -> 0.75f to 0.2f
            Anchor.CenterStart -> 0.25f to middle + 0.02f
            Anchor.Center -> 0.5f to middle
            Anchor.CenterEnd -> 0.75f to middle + 0.02f
            Anchor.BottomStart -> 0.25f to 0.86f
            Anchor.BottomCenter -> 0.5f to 0.88f
            Anchor.BottomEnd -> 0.75f to 0.86f
        }
        return Offset(size.width * fx, size.height * fy)
    }

    // --- Cards -------------------------------------------------------------------------------

    private fun DrawScope.card(rect: Rect, alpha: Float) {
        val unit = FrameRenderer.unit(size)
        val r = CornerRadius(unit * style.elements.cornerRadius)
        when (style.elements.card) {
            "solid" -> drawRoundRect(palette.background(1), rect.topLeft, rect.size, r, alpha = alpha)
            "brutal" -> {
                val off = unit * 0.014f
                drawRoundRect(Color.Black, rect.topLeft + Offset(off, off), rect.size, r, alpha = alpha)
                drawRoundRect(palette.accent2, rect.topLeft, rect.size, r, alpha = alpha)
                drawRoundRect(Color.Black, rect.topLeft, rect.size, r, alpha = alpha, style = Stroke(unit * 0.006f))
            }
            "outline" -> drawRoundRect(palette.text, rect.topLeft, rect.size, r, alpha = alpha, style = Stroke(unit * 0.004f))
            else -> {
                drawRoundRect(Color.Black.copy(alpha = 0.25f), rect.topLeft + Offset(0f, unit * 0.01f), rect.size, r, alpha = alpha)
                drawRoundRect(Color.White.copy(alpha = 0.16f), rect.topLeft, rect.size, r, alpha = alpha)
                drawRoundRect(
                    Brush.linearGradient(listOf(Color.White.copy(alpha = 0.7f), Color.White.copy(alpha = 0.05f)), rect.topLeft, rect.bottomRight),
                    rect.topLeft, rect.size, r, alpha = alpha, style = Stroke(unit * 0.003f),
                )
            }
        }
    }

    private fun onCardText(): Color = if (style.elements.card == "brutal") Color.Black else palette.text

    private fun DrawScope.text(value: String, px: Float, color: Color, alpha: Float, center: Offset, weight: Int = 900) {
        val layout = measurer.measure(
            value,
            TextStyle(fontFamily = fontFamily, fontWeight = FontWeight(weight), fontSize = px.sp),
            softWrap = false,
            density = Density(1f),
        )
        drawText(layout, color = color, alpha = alpha, topLeft = center - Offset(layout.size.width / 2f, layout.size.height / 2f))
    }

    // --- Elements ----------------------------------------------------------------------------

    /** Number rolling up to its target with expo ease-out; counts in Persian digits for Persian content. */
    private fun DrawScope.counter(clip: ElementClip, t: Float, base: Float, alpha: Float) {
        val from = clip.params["from"]?.toDoubleOrNull() ?: 0.0
        val to = clip.params["to"]?.toDoubleOrNull() ?: 100.0
        val decimals = clip.params["decimals"]?.toIntOrNull() ?: 0
        val duration = minOf(clip.range.durationMs * 0.6f, 1300f)
        val value = from + (to - from) * Motion.easeOutExpo(Motion.progress(t, duration))
        val formatted = formatNumber(value, decimals)
        val raw = (clip.params["prefix"] ?: "") + formatted + (clip.params["suffix"] ?: "")
        val persian = clip.params["digits"] == "fa"
        val label = if (persian) Numerals.toPersian(raw) else raw

        val rect = Rect(Offset(-base * 1.2f, -base * 0.55f), Size(base * 2.4f, base * 1.1f))
        card(rect, alpha)
        text(label, base * 0.55f, if (style.elements.card == "brutal") Color.Black else palette.accent, alpha, Offset(0f, -base * 0.06f))
        clip.params["label"]?.let { text(it, base * 0.16f, onCardText().copy(alpha = 0.8f), alpha, Offset(0f, base * 0.34f), 600) }
    }

    private fun formatNumber(v: Double, decimals: Int): String {
        if (decimals <= 0) return kotlin.math.round(v).toLong().toString()
        var factor = 1L
        repeat(decimals) { factor *= 10 }
        val scaled = kotlin.math.round(v * factor).toLong()
        val whole = scaled / factor
        val frac = kotlin.math.abs(scaled % factor).toString().padStart(decimals, '0')
        return "$whole.$frac"
    }

    /** Arrow drawn on from its tail, with a soft glow: green up, red down. */
    private fun DrawScope.arrow(up: Boolean, t: Float, base: Float, alpha: Float) {
        val color = if (up) success else danger
        val p = Motion.easeOutExpo(Motion.progress(t, 420f))
        val dir = if (up) -1f else 1f
        val path = Path().apply {
            moveTo(0f, -dir * base * 0.7f)
            lineTo(0f, dir * base * 0.55f)
            moveTo(-base * 0.42f, dir * base * 0.18f)
            lineTo(0f, dir * base * 0.62f)
            lineTo(base * 0.42f, dir * base * 0.18f)
        }
        val reveal = base * 1.6f * p
        val top = if (up) base * 0.8f - reveal else -base * 0.8f
        clipRect(-base, top, base, top + reveal) {
            drawPath(path, color.copy(alpha = 0.3f), alpha = alpha, style = Stroke(base * 0.32f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            drawPath(path, color, alpha = alpha, style = Stroke(base * 0.14f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }

    /** Candlestick chart from a seeded random walk, revealed left to right (charts read LTR). */
    private fun DrawScope.candles(clip: ElementClip, t: Float, base: Float, alpha: Float) {
        val n = 14
        val trendUp = clip.params["trend"] != "down"
        val rnd = Random(clip.params["seed"]?.toIntOrNull() ?: 7)
        var price = 100f
        val data = List(n) {
            val open = price
            price += (rnd.nextFloat() - (if (trendUp) 0.35f else 0.65f)) * 6f
            Candle(open, price, maxOf(open, price) + rnd.nextFloat() * 2.5f, minOf(open, price) - rnd.nextFloat() * 2.5f)
        }
        val hi = data.maxOf { it.high }
        val lo = data.minOf { it.low }
        val rect = Rect(Offset(-base * 1.5f, -base * 0.9f), Size(base * 3f, base * 1.8f))
        card(rect, alpha)
        val inner = Rect(rect.left + base * 0.2f, rect.top + base * 0.2f, rect.right - base * 0.2f, rect.bottom - base * 0.2f)
        val slot = inner.width / n
        fun y(v: Float) = inner.bottom - (v - lo) / (hi - lo) * inner.height
        val visible = (n * Motion.progress(t, 900f)).toInt().coerceAtMost(n)
        for (i in 0 until visible) {
            val k = data[i]
            val c = if (k.close >= k.open) success else danger
            val x = inner.left + slot * (i + 0.5f)
            drawLine(c, Offset(x, y(k.high)), Offset(x, y(k.low)), slot * 0.1f, alpha = alpha)
            val top = y(maxOf(k.open, k.close))
            drawRect(c, Offset(x - slot * 0.3f, top), Size(slot * 0.6f, (y(minOf(k.open, k.close)) - top).coerceAtLeast(2f)), alpha = alpha)
        }
    }

    private class Candle(val open: Float, val close: Float, val high: Float, val low: Float)

    private fun DrawScope.badge(label: String, base: Float, alpha: Float) {
        val layout = measurer.measure(label, TextStyle(fontFamily = fontFamily, fontWeight = FontWeight(800), fontSize = (base * 0.28f).sp), softWrap = false, density = Density(1f))
        val w = layout.size.width + base * 0.6f
        val h = layout.size.height + base * 0.3f
        card(Rect(Offset(-w / 2, -h / 2), Size(w, h)), alpha)
        drawText(layout, color = onCardText(), alpha = alpha, topLeft = Offset(-layout.size.width / 2f, -layout.size.height / 2f))
    }

    /** Bar filling to `value` (0..1) with a bright leading edge. */
    private fun DrawScope.progress(clip: ElementClip, t: Float, base: Float, alpha: Float) {
        val target = clip.params["value"]?.toFloatOrNull()?.coerceIn(0f, 1f) ?: 1f
        val p = target * Motion.spring(t, 0.85f, 120f)
        val rect = Rect(Offset(-base * 1.6f, -base * 0.22f), Size(base * 3.2f, base * 0.44f))
        card(rect, alpha)
        val track = Rect(rect.left + base * 0.1f, rect.top + base * 0.1f, rect.right - base * 0.1f, rect.bottom - base * 0.1f)
        val fillW = track.width * p
        drawRoundRect(
            Brush.horizontalGradient(listOf(palette.accent, palette.accent2), track.left, track.left + track.width),
            track.topLeft, Size(fillW, track.height), CornerRadius(track.height / 2), alpha = alpha,
        )
        drawCircle(Color.White, track.height * 0.32f, Offset(track.left + fillW - track.height / 2, track.center.y), alpha = alpha * 0.9f)
    }

    private fun DrawScope.icon(name: String, t: Float, base: Float, alpha: Float) {
        val spin = 1f - Motion.spring(t, 0.5f, 300f)
        withTransform({ rotate(spin * -40f, Offset.Zero) }) {
            when (name) {
                "coin" -> {
                    drawCircle(Brush.radialGradient(listOf(Color(0xFFFFD36B), Color(0xFFF7931A)), Offset(-base * 0.2f, -base * 0.2f), base), base * 0.62f, Offset.Zero, alpha = alpha)
                    drawCircle(Color(0xFFB8650F), base * 0.62f, Offset.Zero, alpha = alpha, style = Stroke(base * 0.06f))
                    text("₿", base * 0.7f, Color.White, alpha, Offset(0f, -base * 0.02f))
                }
                "check" -> {
                    drawCircle(success, base * 0.6f, Offset.Zero, alpha = alpha)
                    val path = Path().apply { moveTo(-base * 0.28f, 0f); lineTo(-base * 0.06f, base * 0.22f); lineTo(base * 0.3f, -base * 0.2f) }
                    drawPath(path, Color.Black, alpha = alpha, style = Stroke(base * 0.12f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                }
                "star" -> drawPath(star(base * 0.62f, base * 0.27f), palette.accent2, alpha = alpha)
                "bolt" -> {
                    val path = Path().apply {
                        moveTo(base * 0.1f, -base * 0.7f); lineTo(-base * 0.4f, base * 0.08f); lineTo(-base * 0.02f, base * 0.08f)
                        lineTo(-base * 0.12f, base * 0.7f); lineTo(base * 0.4f, -base * 0.12f); lineTo(base * 0.02f, -base * 0.12f); close()
                    }
                    drawPath(path, palette.accent2, alpha = alpha)
                }
                "heart" -> {
                    val s = base * 0.62f
                    val path = Path().apply {
                        moveTo(0f, s * 0.9f)
                        cubicTo(-s * 1.4f, -s * 0.1f, -s * 0.6f, -s * 1.2f, 0f, -s * 0.45f)
                        cubicTo(s * 0.6f, -s * 1.2f, s * 1.4f, -s * 0.1f, 0f, s * 0.9f)
                        close()
                    }
                    drawPath(path, danger, alpha = alpha)
                }
                else -> badge(name, base, alpha)
            }
        }
    }

    private fun star(outer: Float, inner: Float) = Path().apply {
        for (i in 0 until 10) {
            val r = if (i % 2 == 0) outer else inner
            val a = -PI / 2 + i * PI / 5
            val x = (r * cos(a)).toFloat()
            val y = (r * sin(a)).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }
}
