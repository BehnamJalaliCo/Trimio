package io.trimio.engine.autopilot.workshop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.trimio.engine.autopilot.Media
import io.trimio.engine.autopilot.UserAsset
import io.trimio.engine.motion.MediaSource
import io.trimio.engine.motion.StillMedia
import org.jetbrains.skia.BlendMode
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File
import org.jetbrains.skia.Color as SkColor

/**
 * Media a creator attaches beside the prompt, drawn from scratch so the workshop owns every pixel:
 * a transparent PNG logo, a product photo on a studio sweep, a phone app screenshot and a short
 * B-roll clip. Each comes as the [UserAsset] the director reads and the [MediaSource] the renderer
 * draws it from; the logo is also written as a real PNG file (its [UserAsset.source]).
 */
internal object SyntheticAssets {
    class Attached(val asset: UserAsset, val source: MediaSource)

    private fun draw(w: Int, h: Int, paint: (Canvas) -> Unit): Image = Surface.makeRasterN32Premul(w, h).also { paint(it.canvas) }.makeImageSnapshot()

    private fun fill(c: Int) = Paint().apply { color = c; isAntiAlias = true }

    private fun still(id: String, kind: UserAsset.Kind, image: Image, file: String, caption: String? = null): Attached {
        val path = File(Workshop.out, "assets/$file").apply { parentFile.mkdirs(); writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes) }
        val (rgb, alpha) = Media.thumbnail(image)
        return Attached(UserAsset(id, kind, rgb, alpha, caption = caption, source = path.path), StillMedia(id, image.toComposeImageBitmap()))
    }

    /** A gold monogram ring on a clear background (a PNG with transparency, as brands send them). */
    fun logo(id: String, file: String, gold: Int = SkColor.makeRGB(201, 162, 39)): Attached = still(
        id, UserAsset.Kind.Image,
        draw(512, 512) { c ->
            c.drawCircle(256f, 256f, 210f, fill(gold))
            c.drawCircle(256f, 256f, 176f, Paint().apply { blendMode = BlendMode.CLEAR })
            val stroke = Paint().apply { color = gold; isAntiAlias = true; mode = org.jetbrains.skia.PaintMode.STROKE; strokeWidth = 44f; strokeCap = org.jetbrains.skia.PaintStrokeCap.ROUND }
            c.drawLine(170f, 165f, 256f, 360f, stroke)
            c.drawLine(342f, 165f, 256f, 360f, stroke)
        },
        file,
    )

    /** A perfume bottle on a warm studio sweep: soft light, glass, a cap and a label. */
    fun product(id: String, file: String): Attached = still(
        id, UserAsset.Kind.Image,
        draw(900, 1100) { c ->
            for (k in 0 until 110) c.drawRect(Rect.makeXYWH(0f, k * 10f, 900f, 11f), fill(SkColor.makeRGB(248 - k / 2, 226 - k / 2, 214 - k / 3)))
            c.drawOval(Rect.makeXYWH(240f, 880f, 420f, 70f), fill(SkColor.makeARGB(70, 60, 30, 20)))
            c.drawRRect(RRect.makeXYWH(270f, 420f, 360f, 480f, 70f), fill(SkColor.makeARGB(235, 214, 120, 150)))
            c.drawRRect(RRect.makeXYWH(300f, 450f, 70f, 400f, 35f), fill(SkColor.makeARGB(90, 255, 255, 255)))
            c.drawRect(Rect.makeXYWH(400f, 330f, 100f, 100f), fill(SkColor.makeRGB(196, 160, 60)))
            c.drawRRect(RRect.makeXYWH(370f, 210f, 160f, 130f, 22f), fill(SkColor.makeRGB(30, 26, 24)))
            c.drawRRect(RRect.makeXYWH(330f, 600f, 240f, 130f, 12f), fill(SkColor.makeRGB(250, 245, 236)))
            c.drawRect(Rect.makeXYWH(370f, 650f, 160f, 14f), fill(SkColor.makeRGB(170, 130, 40)))
        },
        file,
    )

    /** A budgeting app's home screen, phone-shaped: header with a balance, spending cards, a button. */
    fun screenshot(id: String, file: String): Attached = still(
        id, UserAsset.Kind.Image,
        draw(590, 1280) { c ->
            c.drawRect(Rect.makeXYWH(0f, 0f, 590f, 1280f), fill(SkColor.makeRGB(246, 247, 250)))
            c.drawRect(Rect.makeXYWH(0f, 0f, 590f, 300f), fill(SkColor.makeRGB(22, 163, 74)))
            c.drawRRect(RRect.makeXYWH(40f, 120f, 300f, 60f, 12f), fill(SkColor.makeARGB(200, 255, 255, 255)))
            val colors = listOf(SkColor.makeRGB(59, 130, 246), SkColor.makeRGB(249, 115, 22), SkColor.makeRGB(168, 85, 247), SkColor.makeRGB(236, 72, 153))
            for (k in 0 until 4) {
                c.drawRRect(RRect.makeXYWH(32f, 340f + k * 190f, 526f, 160f, 22f), fill(SkColor.WHITE))
                c.drawCircle(100f, 420f + k * 190f, 38f, fill(colors[k]))
                c.drawRRect(RRect.makeXYWH(160f, 395f + k * 190f, 120f + k * 70f, 22f, 11f), fill(SkColor.makeRGB(203, 213, 225)))
            }
            c.drawRRect(RRect.makeXYWH(32f, 1160f, 526f, 90f, 45f), fill(SkColor.makeRGB(22, 163, 74)))
        },
        file,
    )

    /** Four seconds in a café: warm light, a cup, steam rising and a slow drift across the table. */
    fun clip(id: String, seconds: Float = 4f): Attached {
        fun frame(t: Float): Image = draw(1280, 720) { c ->
            for (k in 0 until 72) c.drawRect(Rect.makeXYWH(0f, k * 10f, 1280f, 11f), fill(SkColor.makeRGB(92 + k, 58 + k / 2, 36 + k / 3)))
            val dx = -t * 40f
            for (i in 0 until 5) c.drawCircle(160f + i * 260f + dx, 160f, 40f, fill(SkColor.makeARGB(70, 255, 220, 160)))
            c.drawRect(Rect.makeXYWH(0f, 520f, 1280f, 200f), fill(SkColor.makeRGB(120, 78, 48)))
            c.drawRRect(RRect.makeXYWH(540f + dx, 360f, 200f, 180f, 30f), fill(SkColor.makeRGB(245, 240, 232)))
            c.drawOval(Rect.makeXYWH(555f + dx, 365f, 170f, 36f), fill(SkColor.makeRGB(90, 52, 30)))
            for (s in 0 until 3) c.drawCircle(600f + s * 40f + dx, 330f - ((t * 60f + s * 30f) % 120f), 16f, fill(SkColor.makeARGB(60, 255, 255, 255)))
        }
        val first = frame(0f)
        val frames = listOf(0f, seconds / 2f, seconds * 0.9f).map { Media.thumbnail(frame(it)).first }
        val source = object : MediaSource {
            private var at = Float.NaN
            private var cached: ImageBitmap? = null
            override fun frame(source: String, time: Float): ImageBitmap? {
                if (source != id) return null
                val t = ((time.coerceIn(0f, seconds) * 15f).toInt() / 15f)
                if (t != at) {
                    cached = frame(t).toComposeImageBitmap()
                    at = t
                }
                return cached
            }
        }
        return Attached(UserAsset(id, UserAsset.Kind.Video, Media.thumbnail(first).first, frames = frames, duration = seconds, source = "$id.mp4"), source)
    }
}
