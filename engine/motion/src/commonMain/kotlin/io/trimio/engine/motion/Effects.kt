package io.trimio.engine.motion

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PointMode
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.ImageShader
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.graphics.TileMode
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.withTransform
import kotlin.math.max
import kotlin.math.sqrt

/** Procedural full-frame effects. Deterministic in time: the same t always draws the same frame. */
internal fun DrawScope.drawEffect(effect: Effect, t: Float, alpha: Float, size: Size) {
    when (effect) {
        // Smooth fields render at 1/8 resolution and scale up: identical to the eye, a fraction of the cost.
        is Effect.Aurora -> drawSoft(Soft.draw(this, size, key = null) { s -> aurora(effect, t, 1f, s) }, size, alpha)
        is Effect.Grain -> grain(effect, t, alpha, size)
        is Effect.Vignette -> {
            val a = (effect.amount.at(t) * alpha).coerceIn(0f, 1f)
            if (a > 0f) Soft.draw(this, size, key = "vignette") { s -> vignette(s) }.let { drawSoft(it, size, a) }
        }
        is Effect.LightLeak -> {
            val p = effect.progress.at(t)
            val s = effect.strength.at(t) * alpha
            if (s <= 0f) return
            val d = max(size.width, size.height)
            val c = Offset(size.width * (-0.3f + 1.6f * p), size.height * (0.15f + 0.3f * p))
            drawRect(
                Brush.radialGradient(listOf(effect.color.copy(alpha = s), effect.color.copy(alpha = s * 0.35f), Color.Transparent), center = c, radius = d * 0.55f),
                size = size, blendMode = BlendMode.Screen,
            )
            drawRect(
                Brush.radialGradient(listOf(Color(0xFFFFF2D6).copy(alpha = s * 0.5f), Color.Transparent), center = c + Offset(d * 0.08f, d * 0.05f), radius = d * 0.22f),
                size = size, blendMode = BlendMode.Screen,
            )
        }
        is Effect.Flash -> {
            val a = effect.opacity.at(t) * alpha
            if (a > 0f) drawRect(effect.color.copy(alpha = a.coerceIn(0f, 1f)), size = size)
        }
        is Effect.Scrim -> {
            val a = effect.opacity.at(t) * alpha
            val h = size.height * effect.coverage
            val top = if (effect.fromBottom) size.height - h else 0f
            val ramp = listOf(Color.Black.copy(alpha = a), Color.Black.copy(alpha = a * 0.55f), Color.Transparent)
            drawRect(
                Brush.verticalGradient(if (effect.fromBottom) ramp.reversed() else ramp, startY = top, endY = top + h),
                topLeft = Offset(0f, top), size = Size(size.width, h),
            )
        }
        is Effect.Grid -> {
            val a = effect.opacity.at(t) * alpha
            var x = 0f
            while (x <= size.width) { drawLine(effect.color, Offset(x, 0f), Offset(x, size.height), 1f, alpha = a); x += effect.step }
            var y = 0f
            while (y <= size.height) { drawLine(effect.color, Offset(0f, y), Offset(size.width, y), 1f, alpha = a); y += effect.step }
        }
    }
}

private fun DrawScope.vignette(size: Size) = drawRect(
    Brush.radialGradient(
        0f to Color.Transparent, 0.55f to Color.Transparent, 1f to Color.Black,
        center = Offset(size.width / 2, size.height / 2), radius = sqrt(size.width * size.width + size.height * size.height) / 2,
    ),
    size = size,
)

private fun DrawScope.drawSoft(image: ImageBitmap, size: Size, alpha: Float) = drawImage(
    image, dstSize = androidx.compose.ui.unit.IntSize(size.width.toInt(), size.height.toInt()), alpha = alpha, filterQuality = androidx.compose.ui.graphics.FilterQuality.Low,
)

/** Low-resolution offscreens for smooth fields; static ones (with a key) are drawn once. */
private object Soft {
    private const val SCALE = 8
    private val cache = HashMap<String, ImageBitmap>()
    private var scratch: ImageBitmap? = null

    fun draw(scope: DrawScope, size: Size, key: String?, block: DrawScope.(Size) -> Unit): ImageBitmap {
        val w = (size.width / SCALE).toInt().coerceAtLeast(1)
        val h = (size.height / SCALE).toInt().coerceAtLeast(1)
        key?.let { k -> cache["$k-$w-$h"]?.let { return it } }
        val image = if (key == null) scratch?.takeIf { it.width == w && it.height == h } ?: ImageBitmap(w, h).also { scratch = it } else ImageBitmap(w, h)
        val small = Size(w.toFloat(), h.toFloat())
        CanvasDrawScope().draw(scope, scope.layoutDirection, Canvas(image), small) {
            drawRect(Color.Transparent, size = small, blendMode = BlendMode.Clear)
            block(small)
        }
        key?.let { cache["$it-$w-$h"] = image }
        return image
    }
}

/** Large soft colour fields drifting on smooth noise: a living, mesh-like gradient. */
private fun DrawScope.aurora(e: Effect.Aurora, t: Float, alpha: Float, size: Size) {
    val colors = e.colors
    drawRect(colors.first(), size = size, alpha = alpha)
    val d = max(size.width, size.height)
    for (i in 1 until colors.size) {
        val nx = Anim.Noise(e.seed * 31 + i * 7, e.speed, Anim.One)
        val ny = Anim.Noise(e.seed * 17 + i * 13, e.speed, Anim.One)
        val c = Offset(size.width * (0.5f + 0.55f * nx.at(t + i * 3.1f)), size.height * (0.5f + 0.5f * ny.at(t + i * 5.7f)))
        drawRect(
            Brush.radialGradient(listOf(colors[i].copy(alpha = 0.85f * alpha), colors[i].copy(alpha = 0.25f * alpha), Color.Transparent), center = c, radius = d * (0.55f + 0.1f * i)),
            size = size,
        )
    }
}

/**
 * Film grain: a dense noise tile made once, re-positioned every 1/24 s (grain "boils" like film),
 * blended with overlay so it lives in the midtones instead of floating on top as dust.
 */
private fun DrawScope.grain(e: Effect.Grain, t: Float, alpha: Float, size: Size) {
    val tile = GrainTile.get()
    val frame = (t * 24f).toInt()
    var seed = (frame * 7919 + e.seed * 104729) or 1
    fun rnd(): Float {
        seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17); seed = seed xor (seed shl 5)
        return (seed ushr 8 and 0xFFFF) / 65535f
    }
    val brush = ShaderBrush(ImageShader(tile, TileMode.Repeated, TileMode.Repeated))
    val dx = rnd() * GrainTile.SIZE
    val dy = rnd() * GrainTile.SIZE
    withTransform({ scale(GRAIN_SCALE, GRAIN_SCALE, Offset.Zero); translate(-dx, -dy) }) {
        drawRect(brush, topLeft = Offset(dx, dy), size = Size(size.width / GRAIN_SCALE, size.height / GRAIN_SCALE), alpha = (e.amount * 9f * alpha).coerceIn(0f, 1f), blendMode = BlendMode.Overlay)
    }
}

private const val GRAIN_SCALE = 1.4f

/** A 256² tile of grey noise around mid-grey (so overlay leaves average brightness unchanged). */
private object GrainTile {
    const val SIZE = 256
    private var tile: ImageBitmap? = null

    fun get(): ImageBitmap = tile ?: make().also { tile = it }

    private fun make(): ImageBitmap {
        val image = ImageBitmap(SIZE, SIZE)
        val canvas = Canvas(image)
        canvas.drawRect(0f, 0f, SIZE.toFloat(), SIZE.toFloat(), Paint().apply { color = Color(0xFF808080) })
        var seed = 0x2545F491
        fun rnd(): Float {
            seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17); seed = seed xor (seed shl 5)
            return (seed ushr 8 and 0xFFFF) / 65535f
        }
        val levels = 6
        val buckets = List(levels * 2) { mutableListOf<Offset>() }
        for (y in 0 until SIZE) for (x in 0 until SIZE) {
            // Roughly gaussian: the sum of three uniforms.
            val g = (rnd() + rnd() + rnd()) / 3f - 0.5f
            val level = (kotlin.math.abs(g) * 2f * levels).toInt().coerceAtMost(levels - 1)
            buckets[(if (g < 0) 0 else levels) + level] += Offset(x + 0.5f, y + 0.5f)
        }
        for ((i, points) in buckets.withIndex()) {
            if (points.isEmpty()) continue
            val level = i % levels
            val strength = (level + 1f) / levels
            val c = if (i < levels) 0.5f - 0.5f * strength else 0.5f + 0.5f * strength
            canvas.drawPoints(PointMode.Points, points, Paint().apply { color = Color(c, c, c); strokeWidth = 1f; strokeCap = StrokeCap.Square })
        }
        return image
    }
}
