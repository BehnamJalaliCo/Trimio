package io.trimio.engine.autopilot.workshop

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.trimio.engine.motion.MediaSource
import org.jetbrains.skia.Canvas
import org.jetbrains.skia.Color
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import kotlin.math.PI
import kotlin.math.sin

/**
 * A talking head without a camera: a room (wall, window, shelf, plant, lamp, floor) and a person
 * (hair, face, neck, shoulders) who sways a little and moves their mouth, drawn at any size. The
 * same picture the motion tests use for a speaker, made varied per scenario (skin tone, shirt,
 * wall colour, framing) so layout and the face finder meet more than one room. Rendering stills
 * over it needs no video and no vision model.
 */
internal class SyntheticFootage private constructor(private val look: Look) : MediaSource {
    constructor(variant: Int) : this(LOOKS[Math.floorMod(variant, LOOKS.size)])

    private var cachedAt = Float.NaN
    private var cached: ImageBitmap? = null

    override fun frame(source: String, time: Float): ImageBitmap? {
        // Frames 1/15 s apart are close enough for stills; keep the last one.
        val t = (time * 15f).toInt() / 15f
        if (t != cachedAt) {
            val s = Surface.makeRasterN32Premul(W, H)
            paint(s.canvas, 1f, t)
            cached = s.makeImageSnapshot().toComposeImageBitmap()
            cachedAt = t
        }
        return cached
    }

    /** A small RGBA frame (for [io.trimio.engine.autopilot.SubjectFinder]) at [time]. */
    fun rgba(width: Int, height: Int, time: Float): ByteArray {
        val s = Surface.makeRaster(ImageInfo(width, height, org.jetbrains.skia.ColorType.RGBA_8888, ColorAlphaType.PREMUL))
        paint(s.canvas, width / W.toFloat(), time)
        val bitmap = org.jetbrains.skia.Bitmap().apply { allocPixels(ImageInfo(width, height, org.jetbrains.skia.ColorType.RGBA_8888, ColorAlphaType.PREMUL)) }
        s.makeImageSnapshot().readPixels(bitmap)
        return bitmap.readPixels()!!
    }

    /**
     * The true head at [time], as fractions of the frame: from the top of the hair to the chin (or
     * the beard), hair included at the sides. The face finder is measured against it.
     */
    fun head(time: Float): FloatArray {
        val cx = look.x + sway(time)
        val headY = look.headY + nod(time)
        val bottom = headY + if (look.beard) BEARD_BOTTOM else 200f
        return floatArrayOf((cx - 175f) / W, (headY - 230f) / H, (cx + 175f) / W, bottom / H)
    }

    private fun paint(c: Canvas, scale: Float, t: Float) {
        c.save()
        c.scale(scale, scale)
        room(c)
        person(c, t)
        c.restore()
    }

    private fun fill(rgb: Int) = Paint().apply { color = rgb; isAntiAlias = true }

    private fun room(c: Canvas) {
        val (r, g, b) = look.wall
        // The wall, lit from the window side, in bands; a two-tone room paints its right part apart.
        for (k in 0 until 48) {
            val f = k / 47f
            c.drawRect(Rect.makeXYWH(0f, k * 40f, W.toFloat(), 41f), fill(Color.makeRGB((r * (1 - 0.45f * f)).toInt(), (g * (1 - 0.45f * f)).toInt(), (b * (1 - 0.45f * f)).toInt())))
            look.split?.let { (r2, g2, b2) ->
                c.drawRect(Rect.makeXYWH(SPLIT_X, k * 40f, W - SPLIT_X, 41f), fill(Color.makeRGB((r2 * (1 - 0.45f * f)).toInt(), (g2 * (1 - 0.45f * f)).toInt(), (b2 * (1 - 0.45f * f)).toInt())))
            }
        }
        // Floor.
        c.drawRect(Rect.makeXYWH(0f, 1500f, W.toFloat(), 420f), fill(Color.makeRGB(r / 3, g / 3, b / 3)))
        // Window with a frame.
        val wx = if (look.windowLeft) 60f else 640f
        c.drawRect(Rect.makeXYWH(wx, 160f, 380f, 600f), fill(Color.makeRGB(232, 236, 240)))
        c.drawRect(Rect.makeXYWH(wx + 185f, 160f, 10f, 600f), fill(Color.makeRGB(150, 150, 155)))
        c.drawRect(Rect.makeXYWH(wx, 455f, 380f, 10f), fill(Color.makeRGB(150, 150, 155)))
        // Shelf with books on the other side.
        val sx = if (look.windowLeft) 700f else 60f
        c.drawRect(Rect.makeXYWH(sx, 980f, 320f, 18f), fill(Color.makeRGB(90, 66, 48)))
        val books = listOf(Color.makeRGB(150, 40, 40), Color.makeRGB(40, 70, 130), Color.makeRGB(210, 170, 60), Color.makeRGB(60, 110, 70), Color.makeRGB(80, 80, 90))
        var bx = sx + 12f
        for ((i, col) in books.withIndex()) {
            val bw = 34f + (i % 3) * 10f
            val bh = 110f + (i * 23 % 50)
            c.drawRect(Rect.makeXYWH(bx, 980f - bh, bw, bh), fill(col))
            bx += bw + 6f
        }
        // A plant and a lamp's glow.
        c.drawRect(Rect.makeXYWH(sx + 40f, 1380f, 120f, 130f), fill(Color.makeRGB(120, 80, 60)))
        for ((dx, dy) in listOf(0f to 0f, -50f to 40f, 50f to 30f, -20f to -60f, 30f to -50f)) {
            c.drawOval(Rect.makeXYWH(sx + 70f + dx, 1270f + dy, 70f, 120f), fill(Color.makeRGB(50, 110, 60)))
        }
        c.drawCircle(if (look.windowLeft) 980f else 100f, 300f, 70f, fill(Color.makeARGB(120, 255, 220, 150)))
        // A light oak wardrobe just behind the speaker's shoulder: skin-coloured, touching the jaw.
        if (look.wardrobe) {
            val x0 = look.x + 140f
            c.drawRect(Rect.makeXYWH(x0, 380f, 280f, 1070f), fill(Color.makeRGB(196, 150, 108)))
            for (k in 1..5) c.drawRect(Rect.makeXYWH(x0, 380f + k * 178f, 280f, 3f), fill(Color.makeRGB(176, 132, 92)))
        }
    }

    private fun sway(t: Float) = 12f * sin(2 * PI * t / 3.7).toFloat()

    private fun nod(t: Float) = 6f * sin(2 * PI * t / 2.3).toFloat()

    private fun person(c: Canvas, t: Float) {
        val cx = look.x + sway(t)
        val headY = look.headY + nod(t)
        val (sr, sg, sb) = look.skin
        val skin = Color.makeRGB(sr, sg, sb)
        // Shoulders and torso.
        c.drawRRect(RRect.makeXYWH(cx - 360f, headY + 330f, 720f, 1100f, 230f), fill(look.shirt))
        // Neck.
        c.drawRect(Rect.makeXYWH(cx - 70f, headY + 160f, 140f, 210f), fill(Color.makeRGB((sr * 0.85f).toInt(), (sg * 0.85f).toInt(), (sb * 0.85f).toInt())))
        // Hair behind, then the face.
        c.drawOval(Rect.makeXYWH(cx - 175f, headY - 230f, 350f, 330f), fill(look.hair))
        c.drawOval(Rect.makeXYWH(cx - 150f, headY - 190f, 300f, 390f), fill(skin))
        c.drawOval(Rect.makeXYWH(cx - 160f, headY - 225f, 320f, 120f), fill(look.hair))
        // Eyes, brows, a talking mouth.
        for (side in listOf(-1f, 1f)) {
            c.drawOval(Rect.makeXYWH(cx + side * 62f - 18f, headY - 40f, 36f, 20f), fill(Color.makeRGB(40, 30, 28)))
            c.drawRect(Rect.makeXYWH(cx + side * 62f - 30f, headY - 75f, 60f, 10f), fill(look.hair))
        }
        // A full beard over the jaw, down past the chin.
        if (look.beard) {
            c.save()
            c.clipRect(Rect.makeLTRB(cx - 200f, headY + 50f, cx + 200f, headY + 400f))
            c.drawOval(Rect.makeXYWH(cx - 152f, headY - 190f, 304f, 190f + BEARD_BOTTOM), fill(look.hair))
            c.restore()
        }
        val open = 6f + 14f * (0.5f + 0.5f * sin(2 * PI * t * 4.6).toFloat())
        c.drawRRect(RRect.makeXYWH(cx - 42f, headY + 85f, 84f, open, open / 2), fill(Color.makeRGB(110, 45, 45)))
    }

    private class Look(
        val wall: Triple<Int, Int, Int>,
        val skin: Triple<Int, Int, Int>,
        val shirt: Int,
        val hair: Int,
        val x: Float,
        val headY: Float,
        val windowLeft: Boolean,
        val beard: Boolean = false,
        /** A second wall colour right of [SPLIT_X] (a two-tone room). */
        val split: Triple<Int, Int, Int>? = null,
        val wardrobe: Boolean = false,
    )

    companion object {
        const val W = 1080
        const val H = 1920
        private const val SPLIT_X = 600f
        private const val BEARD_BOTTOM = 235f

        private val LOOKS = listOf(
            // A cool grey studio wall, centred, light skin.
            Look(Triple(150, 160, 175), Triple(224, 172, 138), Color.makeRGB(30, 34, 44), Color.makeRGB(36, 26, 22), 540f, 760f, windowLeft = false),
            // A sage wall, framed a little left, olive skin.
            Look(Triple(130, 160, 130), Triple(198, 140, 105), Color.makeRGB(170, 40, 50), Color.makeRGB(20, 16, 14), 470f, 820f, windowLeft = true),
            // A beige living room (warm, close to skin), centred: the hard case for the face finder.
            Look(Triple(205, 182, 150), Triple(214, 160, 124), Color.makeRGB(240, 240, 236), Color.makeRGB(60, 40, 30), 560f, 700f, windowLeft = false),
            // A navy wall, framed right, deeper skin, a light shirt.
            Look(Triple(50, 64, 100), Triple(160, 108, 78), Color.makeRGB(214, 200, 170), Color.makeRGB(18, 14, 12), 610f, 780f, windowLeft = true),
            // A white wall, a tight frame (big head high in the frame).
            Look(Triple(228, 228, 226), Triple(230, 182, 150), Color.makeRGB(60, 90, 140), Color.makeRGB(90, 60, 30), 540f, 640f, windowLeft = false),
        )

        /**
         * Rooms harder than the scenarios' five, for the face finder's bench only (outside the
         * scenarios' rotation, so no scenario changes): an off-centre speaker, a two-tone wall
         * (beige and dark teal, split behind the head), a bearded speaker, and a light oak wardrobe
         * touching the jaw.
         */
        val HARD = listOf("off-centre", "two-tone", "beard", "wardrobe")

        /** The [HARD] room [name]. */
        fun hard(name: String): SyntheticFootage = SyntheticFootage(
            when (name) {
                "off-centre" -> Look(Triple(130, 160, 130), Triple(198, 140, 105), Color.makeRGB(40, 40, 46), Color.makeRGB(20, 16, 14), 270f, 800f, windowLeft = false)
                "two-tone" -> Look(Triple(205, 182, 150), Triple(224, 172, 138), Color.makeRGB(30, 34, 44), Color.makeRGB(36, 26, 22), 560f, 760f, windowLeft = true, split = Triple(40, 72, 80))
                "beard" -> Look(Triple(150, 160, 175), Triple(214, 160, 124), Color.makeRGB(70, 90, 70), Color.makeRGB(42, 30, 24), 540f, 740f, windowLeft = false, beard = true)
                "wardrobe" -> Look(Triple(228, 228, 226), Triple(224, 172, 138), Color.makeRGB(30, 34, 44), Color.makeRGB(36, 26, 22), 500f, 760f, windowLeft = true, wardrobe = true)
                else -> error("no hard room $name")
            },
        )
    }
}
