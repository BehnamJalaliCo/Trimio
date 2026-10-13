package io.trimio.engine.motion.explainer

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PaintMode
import org.jetbrains.skia.PaintStrokeCap
import org.jetbrains.skia.Path
import org.jetbrains.skia.PathBuilder
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import kotlin.math.cos
import kotlin.math.sin

/**
 * Placeholder hero art for tests and review sheets: pencil-like monochrome drawings (a safe, people,
 * a storefront, a phone) made of jittered strokes and hatching, standing in for the illustration
 * agent's real sources.
 */
class SketchArt : IllustrationSource {
    private val cache = HashMap<String, ImageBitmap>()

    override fun find(query: String, style: IllustrationStyle): Illustration? =
        query.takeIf { it.isNotBlank() }?.let { Illustration.Raster(it, W.toFloat(), H.toFloat()) }

    override fun image(id: String): ImageBitmap = cache.getOrPut(id) { draw(id) }

    private fun draw(q: String): ImageBitmap {
        val s = Surface.makeRasterN32Premul(W, H)
        val c = s.canvas
        c.clear(0xFFFFFFFF.toInt())
        val rng = Rng(q.hashCode().toLong())
        val pencil = Paint().apply { mode = PaintMode.STROKE; strokeWidth = 3f; color = 0xCC2A2A2A.toInt(); isAntiAlias = true; strokeCap = PaintStrokeCap.ROUND }
        val shapes = when {
            "safe" in q || "lock" in q -> listOf(box(110f, 160f, 380f, 470f), box(150f, 200f, 300f, 300f), circle(300f, 520f, 70f))
            "people" in q || "store" in q -> listOf(box(40f, 40f, 520f, 220f), figure(150f, 300f), figure(300f, 280f), figure(450f, 310f))
            "phone" in q || "man" in q || "person" in q -> listOf(figure(300f, 230f, 1.7f), box(250f, 470f, 90f, 150f))
            else -> listOf(circle(300f, 380f, 220f))
        }
        for (shape in shapes) {
            c.save()
            c.clipPath(shape)
            val hatch = Paint().apply { mode = PaintMode.STROKE; strokeWidth = 1.6f; color = 0x552A2A2A; isAntiAlias = true }
            var x = -H.toFloat()
            while (x < W) {
                c.drawLine(x, H.toFloat(), x + H * 0.8f + rng.range(-8f, 8f), 0f, hatch)
                x += rng.range(9f, 15f)
            }
            c.restore()
            repeat(3) { c.save(); c.translate(rng.range(-2.5f, 2.5f), rng.range(-2.5f, 2.5f)); c.drawPath(shape, pencil); c.restore() }
        }
        return s.makeImageSnapshot().toComposeImageBitmap()
    }

    private fun box(x: Float, y: Float, w: Float, h: Float): Path = PathBuilder().addRRect(org.jetbrains.skia.RRect.makeXYWH(x, y, w, h, 18f)).detach()

    private fun circle(cx: Float, cy: Float, r: Float): Path = PathBuilder().addOval(Rect.makeXYWH(cx - r, cy - r, r * 2, r * 2)).detach()

    /** A head and shoulders. */
    private fun figure(cx: Float, top: Float, k: Float = 1f): Path {
        val p = PathBuilder().addOval(Rect.makeXYWH(cx - 55f * k, top, 110f * k, 130f * k))
        p.moveTo(cx - 150f * k, H.toFloat())
        val steps = 24
        for (i in 0..steps) {
            val a = Math.PI.toFloat() * i / steps
            p.lineTo(cx - cos(a) * 150f * k, top + 150f * k + 120f * k - sin(a) * 110f * k)
        }
        p.lineTo(cx + 150f * k, H.toFloat())
        p.closePath()
        return p.detach()
    }

    companion object {
        const val W = 600
        const val H = 760
    }
}
