package io.trimio.engine.autopilot

import io.trimio.engine.llm.RgbImage
import kotlin.math.abs

/**
 * A small video frame in the colour spaces the [SubjectFinder] reads: luma, YCbCr chroma and
 * brightness-free normalised rg (r/(r+g+b), g/(r+g+b)), which keeps a face's colour the same in
 * its lit and shaded parts. Larger frames are box-averaged down to about [WORK] pixels across:
 * the finder needs the head's shape, not its pores, and stays cheap on a phone.
 */
internal class FacePixels private constructor(val w: Int, val h: Int) {
    val luma = FloatArray(w * h)
    val cb = FloatArray(w * h)
    val cr = FloatArray(w * h)
    val rn = FloatArray(w * h)
    val gn = FloatArray(w * h)

    private fun set(i: Int, r: Float, g: Float, b: Float) {
        luma[i] = 0.299f * r + 0.587f * g + 0.114f * b
        cb[i] = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b
        cr[i] = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b
        val sum = maxOf(1f, r + g + b)
        rn[i] = r / sum
        gn[i] = g / sum
    }

    /**
     * Whether pixel [i] can be skin at all, from light to deep tones: a red-leaning chroma, not
     * grey, not saturated red, not black. Walls of beige and wood pass too; the adaptive model
     * and the background model sort them out.
     */
    fun skinGate(i: Int): Boolean {
        val r = rn[i]
        val g = gn[i]
        return luma[i] > MIN_LUMA && r > g && r in 0.36f..0.6f && g in 0.26f..0.37f && cr[i] in 133f..182f && cb[i] in 72f..130f
    }

    /** Colour distance to a background sample, luma counting less: walls darken across a room, their hue does not. */
    fun distanceSq(i: Int, y: Float, b: Float, r: Float): Float {
        val dy = (luma[i] - y) * LUMA_WEIGHT
        val db = cb[i] - b
        val dr = cr[i] - r
        return dy * dy + db * db + dr * dr
    }

    companion object {
        /** Working width: about a hundred pixels across a vertical frame. */
        const val WORK = 96
        private const val MIN_LUMA = 30f
        private const val LUMA_WEIGHT = 0.35f

        /** Packed RGB ([stride] 3) or RGBA ([stride] 4) bytes, [width]×[height]. */
        fun of(bytes: ByteArray, width: Int, height: Int, stride: Int): FacePixels {
            require(bytes.size >= width * height * stride) { "frame ${bytes.size} bytes < $width×$height×$stride" }
            val f = maxOf(1, width / WORK)
            val p = FacePixels(width / f, height / f)
            for (y in 0 until p.h) for (x in 0 until p.w) {
                val (r, g, b) = average(bytes, width, stride, x * f, y * f, f)
                p.set(y * p.w + x, r, g, b)
            }
            return p
        }

        fun of(image: RgbImage): FacePixels = of(image.rgb, image.width, image.height, 3)

        private fun average(bytes: ByteArray, width: Int, stride: Int, x0: Int, y0: Int, f: Int): Triple<Float, Float, Float> {
            var r = 0
            var g = 0
            var b = 0
            for (y in y0 until y0 + f) for (x in x0 until x0 + f) {
                val i = (y * width + x) * stride
                r += bytes[i].toInt() and 0xFF
                g += bytes[i + 1].toInt() and 0xFF
                b += bytes[i + 2].toInt() and 0xFF
            }
            val n = (f * f).toFloat()
            return Triple(r / n, g / n, b / n)
        }
    }
}

/**
 * What a few frames of one clip share: the background (the frame's left and right borders, row by
 * row, so a wall lit from one side still matches itself) and where things move (the speaker's head
 * and mouth do, walls and furniture do not).
 */
internal class FaceScene(val frames: List<FacePixels>) {
    val w = frames[0].w
    val h = frames[0].h

    /** Mean absolute deviation of each pixel's luma from its median over the frames. */
    val motion = FloatArray(w * h)
    val motionMean: Float

    /** Per frame, each pixel's squared colour distance to the nearest border sample around its row. */
    val background: List<FloatArray>

    init {
        val lumas = FloatArray(frames.size)
        var total = 0f
        for (i in 0 until w * h) {
            for (k in frames.indices) lumas[k] = frames[k].luma[i]
            lumas.sort()
            val median = lumas[frames.size / 2]
            var dev = 0f
            for (k in frames.indices) dev += abs(frames[k].luma[i] - median)
            motion[i] = dev / frames.size
            total += motion[i]
        }
        motionMean = total / (w * h)
        val prototypes = borderPrototypes()
        background = frames.map { f -> distances(f, prototypes) }
    }

    /** The wall's colours along each row: border pixels of every frame, near-duplicates merged. */
    private fun borderPrototypes(): Array<FloatArray> {
        val strip = maxOf(2, w / BORDER_DIVISOR)
        return Array(h) { y ->
            val kept = ArrayList<Float>()
            for (f in frames) for (x in 0 until w) {
                if (x in strip until w - strip) continue
                val i = y * w + x
                if (near(kept, f.luma[i], f.cb[i], f.cr[i])) continue
                kept += f.luma[i]; kept += f.cb[i]; kept += f.cr[i]
            }
            kept.toFloatArray()
        }
    }

    private fun near(kept: List<Float>, y: Float, b: Float, r: Float): Boolean {
        var k = 0
        while (k < kept.size) {
            if (abs(kept[k] - y) < MERGE * 2 && abs(kept[k + 1] - b) < MERGE && abs(kept[k + 2] - r) < MERGE) return true
            k += 3
        }
        return false
    }

    private fun distances(f: FacePixels, prototypes: Array<FloatArray>): FloatArray {
        val band = maxOf(2, h / BAND_DIVISOR)
        val out = FloatArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            out[y * w + x] = nearest(f, y * w + x, prototypes, (y - band).coerceAtLeast(0), (y + band).coerceAtMost(h - 1))
        }
        return out
    }

    private fun nearest(f: FacePixels, i: Int, prototypes: Array<FloatArray>, from: Int, to: Int): Float {
        var best = Float.MAX_VALUE
        for (row in from..to step 2) {
            val p = prototypes[row]
            var k = 0
            while (k < p.size) {
                best = minOf(best, f.distanceSq(i, p[k], p[k + 1], p[k + 2]))
                k += 3
            }
        }
        return best
    }

    private companion object {
        const val BORDER_DIVISOR = 24
        const val BAND_DIVISOR = 12
        const val MERGE = 3f
    }
}
