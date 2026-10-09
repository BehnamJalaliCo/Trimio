package io.trimio.engine.render

import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * The procedural icon set of [io.trimio.core.model.asset.IconCatalog] beyond the original five
 * (coin, check, star, bolt, heart live in [ElementLayer]). Every icon is drawn around the origin
 * within a radius of about `0.7 × base`, in flat colours with one highlight, so it reads at phone
 * size and matches every style's card treatment.
 */
internal object VectorIcons {

    private val gold = listOf(Color(0xFFFFE58A), Color(0xFFD4A017))
    private val green = Color(0xFF2ECC71)
    private val red = Color(0xFFFF4D5E)
    private val amber = Color(0xFFFFC107)

    /** Draws [id]; returns false when the id is unknown so the caller can fall back to a badge. */
    fun DrawScope.drawIcon(
        id: String,
        base: Float,
        alpha: Float,
        accent: Color,
        accent2: Color,
        text: DrawScope.(String, Float, Color, Offset) -> Unit,
    ): Boolean {
        val s = base * 0.62f
        when (id) {
            "eth" -> {
                val top = Path().apply { moveTo(0f, -s * 1.05f); lineTo(s * 0.62f, s * 0.05f); lineTo(0f, s * 0.38f); lineTo(-s * 0.62f, s * 0.05f); close() }
                val bottom = Path().apply { moveTo(0f, s * 0.52f); lineTo(s * 0.62f, s * 0.18f); lineTo(0f, s * 1.05f); lineTo(-s * 0.62f, s * 0.18f); close() }
                val facet = Path().apply { moveTo(0f, -s * 1.05f); lineTo(s * 0.62f, s * 0.05f); lineTo(0f, s * 0.38f); close() }
                drawPath(top, Color(0xFF8C8CF7), alpha = alpha)
                drawPath(facet, Color(0xFF5B5BD6), alpha = alpha)
                drawPath(bottom, Color(0xFFC5C5FF), alpha = alpha)
            }
            "dollar" -> {
                drawCircle(Brush.radialGradient(listOf(Color(0xFF6CF0A6), green), Offset(-s * 0.3f, -s * 0.3f), s * 1.6f), s, Offset.Zero, alpha = alpha)
                drawCircle(Color(0xFF168A49), s, Offset.Zero, alpha = alpha, style = Stroke(s * 0.09f))
                text("$", s * 1.15f, Color.White, Offset(0f, -s * 0.02f))
            }
            "gold" -> {
                fun bar(dy: Float) {
                    val front = Path().apply {
                        moveTo(-s * 0.95f, dy + s * 0.35f); lineTo(s * 0.95f, dy + s * 0.35f)
                        lineTo(s * 0.68f, dy - s * 0.25f); lineTo(-s * 0.68f, dy - s * 0.25f); close()
                    }
                    drawPath(front, Brush.verticalGradient(gold, dy - s * 0.25f, dy + s * 0.35f), alpha = alpha)
                    drawLine(Color.White.copy(alpha = 0.7f), Offset(-s * 0.55f, dy - s * 0.12f), Offset(s * 0.3f, dy - s * 0.12f), s * 0.06f, StrokeCap.Round, alpha = alpha)
                }
                bar(s * 0.45f)
                bar(-s * 0.2f)
            }
            "cross" -> {
                drawCircle(red, s, Offset.Zero, alpha = alpha)
                val k = s * 0.36f
                drawLine(Color.White, Offset(-k, -k), Offset(k, k), s * 0.2f, StrokeCap.Round, alpha = alpha)
                drawLine(Color.White, Offset(k, -k), Offset(-k, k), s * 0.2f, StrokeCap.Round, alpha = alpha)
            }
            "rocket" -> {
                val flame = Path().apply { moveTo(-s * 0.22f, s * 0.55f); quadraticTo(0f, s * 1.35f, s * 0.22f, s * 0.55f); close() }
                drawPath(flame, Brush.verticalGradient(listOf(Color(0xFFFFE066), Color(0xFFFF6A00)), s * 0.55f, s * 1.3f), alpha = alpha)
                val finL = Path().apply { moveTo(-s * 0.3f, s * 0.1f); lineTo(-s * 0.62f, s * 0.62f); lineTo(-s * 0.3f, s * 0.5f); close() }
                val finR = Path().apply { moveTo(s * 0.3f, s * 0.1f); lineTo(s * 0.62f, s * 0.62f); lineTo(s * 0.3f, s * 0.5f); close() }
                drawPath(finL, accent2, alpha = alpha)
                drawPath(finR, accent2, alpha = alpha)
                val body = Path().apply {
                    moveTo(0f, -s * 1.05f)
                    cubicTo(s * 0.55f, -s * 0.6f, s * 0.38f, s * 0.3f, s * 0.3f, s * 0.6f)
                    lineTo(-s * 0.3f, s * 0.6f)
                    cubicTo(-s * 0.38f, s * 0.3f, -s * 0.55f, -s * 0.6f, 0f, -s * 1.05f)
                    close()
                }
                drawPath(body, Brush.horizontalGradient(listOf(Color(0xFFE9EDF5), Color.White, Color(0xFFB8C0CF)), -s * 0.4f, s * 0.4f), alpha = alpha)
                drawCircle(accent, s * 0.17f, Offset(0f, -s * 0.28f), alpha = alpha)
                drawCircle(Color.White.copy(alpha = 0.6f), s * 0.06f, Offset(-s * 0.05f, -s * 0.33f), alpha = alpha)
            }
            "fire" -> {
                val outer = Path().apply {
                    moveTo(0f, s * 1.0f)
                    cubicTo(-s * 0.85f, s * 0.95f, -s * 0.85f, -s * 0.05f, -s * 0.3f, -s * 0.55f)
                    cubicTo(-s * 0.25f, -s * 0.2f, -s * 0.1f, -s * 0.1f, 0f, -s * 0.05f)
                    cubicTo(-s * 0.05f, -s * 0.5f, s * 0.15f, -s * 0.85f, s * 0.2f, -s * 1.05f)
                    cubicTo(s * 0.9f, -s * 0.5f, s * 0.95f, s * 0.9f, 0f, s * 1.0f)
                    close()
                }
                drawPath(outer, Brush.verticalGradient(listOf(Color(0xFFFF3D00), Color(0xFFFF8A00)), -s, s), alpha = alpha)
                val inner = Path().apply {
                    moveTo(0f, s * 0.95f)
                    cubicTo(-s * 0.45f, s * 0.9f, -s * 0.4f, s * 0.3f, 0f, -s * 0.05f)
                    cubicTo(s * 0.4f, s * 0.3f, s * 0.45f, s * 0.9f, 0f, s * 0.95f)
                    close()
                }
                drawPath(inner, Color(0xFFFFD54F), alpha = alpha)
            }
            "trophy" -> {
                val cup = Path().apply {
                    moveTo(-s * 0.6f, -s * 0.8f); lineTo(s * 0.6f, -s * 0.8f)
                    cubicTo(s * 0.6f, s * 0.05f, s * 0.25f, s * 0.3f, 0f, s * 0.3f)
                    cubicTo(-s * 0.25f, s * 0.3f, -s * 0.6f, s * 0.05f, -s * 0.6f, -s * 0.8f)
                    close()
                }
                val handles = Stroke(s * 0.12f, cap = StrokeCap.Round)
                drawArc(gold[1], 90f, 180f, false, Offset(-s * 0.95f, -s * 0.65f), Size(s * 0.7f, s * 0.6f), alpha = alpha, style = handles)
                drawArc(gold[1], -90f, 180f, false, Offset(s * 0.25f, -s * 0.65f), Size(s * 0.7f, s * 0.6f), alpha = alpha, style = handles)
                drawPath(cup, Brush.verticalGradient(gold, -s * 0.8f, s * 0.3f), alpha = alpha)
                drawRect(gold[1], Offset(-s * 0.1f, s * 0.3f), Size(s * 0.2f, s * 0.35f), alpha = alpha)
                drawRoundRect(gold[1], Offset(-s * 0.45f, s * 0.62f), Size(s * 0.9f, s * 0.28f), CornerRadius(s * 0.06f), alpha = alpha)
                drawLine(Color.White.copy(alpha = 0.6f), Offset(-s * 0.32f, -s * 0.62f), Offset(-s * 0.25f, -s * 0.05f), s * 0.08f, StrokeCap.Round, alpha = alpha)
            }
            "target" -> {
                listOf(1f to red, 0.74f to Color.White, 0.48f to red, 0.22f to Color.White).forEach { (r, c) -> drawCircle(c, s * r, Offset.Zero, alpha = alpha) }
                drawCircle(red, s * 0.1f, Offset.Zero, alpha = alpha)
                drawLine(accent, Offset(s * 0.95f, -s * 0.95f), Offset(s * 0.08f, -s * 0.08f), s * 0.1f, StrokeCap.Round, alpha = alpha)
            }
            "bell" -> {
                val bell = Path().apply {
                    moveTo(-s * 0.75f, s * 0.5f)
                    cubicTo(-s * 0.55f, s * 0.3f, -s * 0.6f, -s * 0.75f, 0f, -s * 0.8f)
                    cubicTo(s * 0.6f, -s * 0.75f, s * 0.55f, s * 0.3f, s * 0.75f, s * 0.5f)
                    close()
                }
                drawPath(bell, Brush.verticalGradient(gold, -s * 0.8f, s * 0.5f), alpha = alpha)
                drawCircle(gold[1], s * 0.16f, Offset(0f, s * 0.68f), alpha = alpha)
                drawCircle(gold[1], s * 0.1f, Offset(0f, -s * 0.88f), alpha = alpha)
                drawCircle(red, s * 0.2f, Offset(s * 0.58f, -s * 0.6f), alpha = alpha)
            }
            "lock" -> {
                drawArc(Color(0xFFB8C0CF), 180f, 180f, false, Offset(-s * 0.45f, -s * 0.95f), Size(s * 0.9f, s * 0.9f), alpha = alpha, style = Stroke(s * 0.16f))
                drawLine(Color(0xFFB8C0CF), Offset(-s * 0.45f, -s * 0.5f), Offset(-s * 0.45f, -s * 0.1f), s * 0.16f, alpha = alpha)
                drawLine(Color(0xFFB8C0CF), Offset(s * 0.45f, -s * 0.5f), Offset(s * 0.45f, -s * 0.1f), s * 0.16f, alpha = alpha)
                drawRoundRect(Brush.verticalGradient(listOf(accent, accent2), -s * 0.15f, s), Offset(-s * 0.72f, -s * 0.15f), Size(s * 1.44f, s * 1.1f), CornerRadius(s * 0.18f), alpha = alpha)
                drawCircle(Color.Black.copy(alpha = 0.6f), s * 0.14f, Offset(0f, s * 0.3f), alpha = alpha)
                drawLine(Color.Black.copy(alpha = 0.6f), Offset(0f, s * 0.3f), Offset(0f, s * 0.6f), s * 0.1f, StrokeCap.Round, alpha = alpha)
            }
            "clock" -> {
                drawCircle(Color.White, s, Offset.Zero, alpha = alpha)
                drawCircle(accent, s, Offset.Zero, alpha = alpha, style = Stroke(s * 0.12f))
                for (i in 0 until 12) {
                    val a = i * PI / 6
                    val inner = if (i % 3 == 0) 0.72f else 0.8f
                    drawLine(Color(0xFF222222), Offset((cos(a) * s * inner).toFloat(), (sin(a) * s * inner).toFloat()), Offset((cos(a) * s * 0.88f).toFloat(), (sin(a) * s * 0.88f).toFloat()), s * 0.06f, alpha = alpha)
                }
                drawLine(Color(0xFF222222), Offset.Zero, Offset(0f, -s * 0.55f), s * 0.1f, StrokeCap.Round, alpha = alpha)
                drawLine(accent2, Offset.Zero, Offset(s * 0.42f, s * 0.2f), s * 0.07f, StrokeCap.Round, alpha = alpha)
                drawCircle(Color(0xFF222222), s * 0.08f, Offset.Zero, alpha = alpha)
            }
            "bulb" -> {
                for (i in 0 until 7) {
                    val a = PI + i * PI / 6
                    drawLine(amber, Offset((cos(a) * s * 0.85f).toFloat(), (sin(a) * s * 0.85f - s * 0.2f).toFloat()), Offset((cos(a) * s * 1.1f).toFloat(), (sin(a) * s * 1.1f - s * 0.2f).toFloat()), s * 0.08f, StrokeCap.Round, alpha = alpha)
                }
                drawCircle(Brush.radialGradient(listOf(Color(0xFFFFF7C2), amber), Offset(-s * 0.15f, -s * 0.35f), s), s * 0.62f, Offset(0f, -s * 0.2f), alpha = alpha)
                drawRoundRect(Color(0xFF9AA3B2), Offset(-s * 0.28f, s * 0.38f), Size(s * 0.56f, s * 0.42f), CornerRadius(s * 0.08f), alpha = alpha)
                drawLine(Color(0xFF6B7280), Offset(-s * 0.26f, s * 0.53f), Offset(s * 0.26f, s * 0.53f), s * 0.05f, alpha = alpha)
                drawLine(Color(0xFF6B7280), Offset(-s * 0.26f, s * 0.66f), Offset(s * 0.26f, s * 0.66f), s * 0.05f, alpha = alpha)
            }
            "crown" -> {
                val crown = Path().apply {
                    moveTo(-s * 0.95f, s * 0.55f); lineTo(-s * 0.95f, -s * 0.45f); lineTo(-s * 0.45f, 0f)
                    lineTo(0f, -s * 0.75f); lineTo(s * 0.45f, 0f); lineTo(s * 0.95f, -s * 0.45f); lineTo(s * 0.95f, s * 0.55f); close()
                }
                drawPath(crown, Brush.verticalGradient(gold, -s * 0.75f, s * 0.55f), alpha = alpha)
                drawRect(gold[1], Offset(-s * 0.95f, s * 0.42f), Size(s * 1.9f, s * 0.2f), alpha = alpha)
                listOf(-s * 0.95f to -s * 0.45f, 0f to -s * 0.75f, s * 0.95f to -s * 0.45f).forEach { (x, y) -> drawCircle(accent2, s * 0.13f, Offset(x, y), alpha = alpha) }
                drawCircle(accent, s * 0.12f, Offset(0f, s * 0.15f), alpha = alpha)
            }
            "gift" -> {
                drawRoundRect(accent, Offset(-s * 0.8f, -s * 0.2f), Size(s * 1.6f, s * 1.1f), CornerRadius(s * 0.08f), alpha = alpha)
                drawRoundRect(accent, Offset(-s * 0.92f, -s * 0.5f), Size(s * 1.84f, s * 0.38f), CornerRadius(s * 0.08f), alpha = alpha)
                drawRect(accent2, Offset(-s * 0.13f, -s * 0.5f), Size(s * 0.26f, s * 1.4f), alpha = alpha)
                val bow = Stroke(s * 0.14f, cap = StrokeCap.Round, join = StrokeJoin.Round)
                drawOval(accent2, Offset(-s * 0.62f, -s * 0.95f), Size(s * 0.55f, s * 0.45f), alpha = alpha, style = bow)
                drawOval(accent2, Offset(s * 0.07f, -s * 0.95f), Size(s * 0.55f, s * 0.45f), alpha = alpha, style = bow)
            }
            "warning" -> {
                val tri = Path().apply { moveTo(0f, -s * 0.95f); lineTo(s * 1.05f, s * 0.8f); lineTo(-s * 1.05f, s * 0.8f); close() }
                drawPath(tri, amber, alpha = alpha)
                drawPath(tri, Color(0xFFB98900), alpha = alpha, style = Stroke(s * 0.1f, join = StrokeJoin.Round))
                drawLine(Color.Black, Offset(0f, -s * 0.35f), Offset(0f, s * 0.25f), s * 0.16f, StrokeCap.Round, alpha = alpha)
                drawCircle(Color.Black, s * 0.1f, Offset(0f, s * 0.55f), alpha = alpha)
            }
            else -> return false
        }
        return true
    }
}
