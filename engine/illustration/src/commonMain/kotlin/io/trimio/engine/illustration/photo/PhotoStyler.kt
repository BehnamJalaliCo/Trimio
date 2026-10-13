package io.trimio.engine.illustration.photo

import io.trimio.engine.illustration.Colors
import io.trimio.engine.illustration.Raster
import io.trimio.engine.illustration.style.ArtStyle
import io.trimio.engine.illustration.style.Fields
import io.trimio.engine.illustration.style.FilmLook
import io.trimio.engine.illustration.style.SketchStyler
import io.trimio.engine.illustration.style.StyleInput
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A photo (the creator's own, or a generated picture) turned into the film's look with no model:
 *  1. the subject is separated from a plain background by a flood fill from the border (or, when the
 *     background is busy, the picture fades to paper in a soft oval, as a sketch would);
 *  2. lines come from an extended difference of Gaussians (XDoG) on the luminance;
 *  3. tone is the luminance with its local contrast lifted (detail survives the hatching);
 *  4. one colour may stay: pixels of the accent's hue keep the accent (a yellow taxi stays yellow);
 *  5. the shared [SketchStyler] draws it, exactly as it draws the library's pictures.
 */
object PhotoStyler {
    /** What the separation found: [alpha] per pixel and whether the background was plain. */
    class Cutout(val alpha: FloatArray, val separated: Boolean)

    fun style(photo: Raster, look: FilmLook, size: Int): Raster {
        val s = min(1f, size.toFloat() / max(photo.width, photo.height))
        val img = if (s < 1f) photo.resized(max(1, (photo.width * s).roundToInt()), max(1, (photo.height * s).roundToInt())) else photo
        val w = img.width
        val h = img.height
        val unit = min(w, h) / UNIT_SIZE
        val luma = FloatArray(w * h) { Colors.luma(img.argb[it]) }
        val cut = cutout(img, unit)
        val tone = tone(luma, w, h, unit)
        val edges = edges(luma, w, h, unit, look.style)
        // The cut-out's outline is drawn too, so a pale subject on a pale background keeps its silhouette.
        if (cut.separated) outline(cut.alpha, w, h).forEachIndexed { i, v -> edges[i] = max(edges[i], v) }
        val accent = if (look.accentObjects) accent(img, look.accent) else null
        val grey = IntArray(w * h)
        for (i in grey.indices) {
            val a = cut.alpha[i] * (Colors.a(img.argb[i]) / BYTE)
            val v = (tone[i] * BYTE).roundToInt()
            grey[i] = Colors.argb((a * BYTE).roundToInt(), v, v, v)
            edges[i] *= cut.alpha[i]
        }
        return SketchStyler(look).style(StyleInput(Raster(w, h, grey), edges = edges, accent = accent))
    }

    /**
     * Border flood fill on colour distance to the border's median (the distance is smoothed first, so paper
     * grain and generator noise do not stop the fill), small leftover islands dropped; falls back to a soft
     * oval when the border is busy or the fill takes too little or too much of the frame.
     */
    fun cutout(img: Raster, unit: Float): Cutout {
        val w = img.width
        val h = img.height
        val border = ArrayList<Int>()
        for (x in 0 until w) {
            border += img[x, 0]
            border += img[x, h - 1]
        }
        for (y in 0 until h) {
            border += img[0, y]
            border += img[w - 1, y]
        }
        val ref = median(border)
        val spread = border.map { dist(it, ref) }.sorted()[border.size * 3 / 4]
        val far = FloatArray(w * h) { i -> if (Colors.a(img.argb[i]) < CLEAR_ALPHA) 0f else dist(img.argb[i], ref) }
        val bg = if (spread < PLAIN_BORDER) flood(Fields.blur(far, w, h, max(1, (unit * NOISE_RADIUS).roundToInt()), passes = 2), w, h) else null
        val share = bg?.let { b -> b.count { it }.toFloat() / b.size } ?: 0f
        if (bg != null && share in MIN_BACKGROUND..MAX_BACKGROUND) {
            val fg = withoutIslands(BooleanArray(w * h) { !bg[it] }, w, h)
            val hard = FloatArray(w * h) { if (fg[it]) 1f else 0f }
            return Cutout(Fields.blur(hard, w, h, max(1, unit.roundToInt()), passes = 1), true)
        }
        val alpha = FloatArray(w * h)
        for (y in 0 until h) {
            for (x in 0 until w) {
                val dx = (x - w / 2f) / (w / 2f)
                val dy = (y - h / 2f) / (h / 2f)
                alpha[y * w + x] = 1f - Fields.ramp(sqrt(dx * dx + dy * dy), OVAL_INNER, OVAL_OUTER)
            }
        }
        return Cutout(alpha, false)
    }

    /** Pixels reachable from the border through [far] below the tolerance. */
    private fun flood(far: FloatArray, w: Int, h: Int): BooleanArray {
        val bg = BooleanArray(w * h)
        val stack = IntArray(w * h)
        var top = 0
        fun push(i: Int) {
            if (!bg[i] && far[i] < FLOOD_TOLERANCE) {
                bg[i] = true
                stack[top++] = i
            }
        }
        for (x in 0 until w) {
            push(x)
            push((h - 1) * w + x)
        }
        for (y in 0 until h) {
            push(y * w)
            push(y * w + w - 1)
        }
        while (top > 0) {
            val i = stack[--top]
            val x = i % w
            if (x > 0) push(i - 1)
            if (x < w - 1) push(i + 1)
            if (i >= w) push(i - w)
            if (i + w < w * h) push(i + w)
        }
        return bg
    }

    /** The foreground without its specks: components smaller than a share of the largest are dropped. */
    private fun withoutIslands(fg: BooleanArray, w: Int, h: Int): BooleanArray {
        val label = IntArray(w * h) { -1 }
        val sizes = ArrayList<Int>()
        val stack = IntArray(w * h)
        for (start in fg.indices) {
            if (fg[start] && label[start] < 0) sizes += component(start, sizes.size, fg, label, stack, w, h)
        }
        val keep = (sizes.maxOrNull() ?: 0) * ISLAND_SHARE
        return BooleanArray(w * h) { i -> label[i] >= 0 && sizes[label[i]] >= keep }
    }

    /** Labels the foreground component of [start] with [id]; returns its size. */
    @Suppress("LongParameterList")
    private fun component(start: Int, id: Int, fg: BooleanArray, label: IntArray, stack: IntArray, w: Int, h: Int): Int {
        var top = 0
        var n = 0
        stack[top++] = start
        label[start] = id
        while (top > 0) {
            val i = stack[--top]
            n++
            for (j in neighbours(i, w, h)) {
                if (j < 0 || !fg[j] || label[j] >= 0) continue
                label[j] = id
                stack[top++] = j
            }
        }
        return n
    }

    private fun neighbours(i: Int, w: Int, h: Int): IntArray {
        val x = i % w
        return intArrayOf(if (x > 0) i - 1 else -1, if (x < w - 1) i + 1 else -1, if (i >= w) i - w else -1, if (i + w < w * h) i + w else -1)
    }

    /** Where the cut-out's alpha changes: the subject's silhouette as a line. */
    private fun outline(alpha: FloatArray, w: Int, h: Int): FloatArray {
        val out = FloatArray(alpha.size)
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val i = y * w + x
                val gx = alpha[i + 1] - alpha[i - 1]
                val gy = alpha[i + w] - alpha[i - w]
                out[i] = (hypot(gx, gy) * OUTLINE_GAIN).coerceIn(0f, OUTLINE_MAX)
            }
        }
        return out
    }

    /** Luminance with local contrast lifted: detail stays readable once it is hatched. */
    private fun tone(luma: FloatArray, w: Int, h: Int, unit: Float): FloatArray {
        val base = Fields.blur(luma, w, h, max(2, (unit * LOCAL_RADIUS).roundToInt()))
        return FloatArray(luma.size) { i ->
            (MID + (luma[i] - base[i]) * DETAIL_GAIN + (base[i] - MID) * BASE_GAIN + LIFT).coerceIn(0f, 1f)
        }
    }

    /** XDoG lines: strength where the narrow blur is darker than the wide one by more than a threshold. */
    fun edges(luma: FloatArray, w: Int, h: Int, unit: Float, style: ArtStyle): FloatArray {
        val r = max(1, (unit * DOG_RADIUS).roundToInt())
        val g1 = Fields.blur(luma, w, h, r)
        val g2 = Fields.blur(luma, w, h, (r * DOG_RATIO).roundToInt().coerceAtLeast(r + 1))
        val gain = if (style == ArtStyle.Ink) INK_GAIN else PENCIL_GAIN
        return FloatArray(luma.size) { i -> ((g2[i] - g1[i] - DOG_THRESHOLD) * gain).coerceIn(0f, 1f) }
    }

    /** Pixels whose hue is the accent's (and saturated enough) keep the accent, softly. */
    private fun accent(img: Raster, accent: Int): FloatArray? {
        val hue = Colors.hue(accent)
        var any = false
        val mask = FloatArray(img.argb.size) { i ->
            val c = img.argb[i]
            val s = Colors.saturation(c)
            if (s < MIN_SATURATION || Colors.luma(c) < MIN_LUMA) 0f else {
                val d = Colors.hueDistance(Colors.hue(c), hue)
                (1f - Fields.ramp(d, HUE_NEAR, HUE_FAR)) * Fields.ramp(s, MIN_SATURATION, FULL_SATURATION)
            }.also { if (it > 0f) any = true }
        }
        val share = mask.count { it > HALF }.toFloat() / mask.size
        return mask.takeIf { any && share >= MIN_ACCENT_SHARE }
    }

    private fun dist(a: Int, b: Int): Float =
        hypot(hypot((Colors.r(a) - Colors.r(b)).toFloat(), (Colors.g(a) - Colors.g(b)).toFloat()), (Colors.b(a) - Colors.b(b)).toFloat()) / BYTE

    private fun median(cs: List<Int>): Int {
        fun mid(f: (Int) -> Int) = cs.map(f).sorted()[cs.size / 2]
        return Colors.argb(OPAQUE, mid(Colors::r), mid(Colors::g), mid(Colors::b))
    }

    /** Mean absolute difference between two rasters' luminance (tests). */
    fun lumaDifference(a: Raster, b: Raster): Float =
        a.argb.indices.sumOf { abs(Colors.luma(a.argb[it]) - Colors.luma(b.argb[it])).toDouble() }.toFloat() / a.argb.size

    private const val BYTE = 255f
    private const val OPAQUE = 255
    private const val HALF = 0.5f
    private const val UNIT_SIZE = 384f
    private const val PLAIN_BORDER = 0.12f
    private const val FLOOD_TOLERANCE = 0.14f
    private const val NOISE_RADIUS = 1.5f
    private const val ISLAND_SHARE = 0.08f
    private const val CLEAR_ALPHA = 16
    private const val MIN_BACKGROUND = 0.12f
    private const val MAX_BACKGROUND = 0.9f
    private const val OVAL_INNER = 0.72f
    private const val OVAL_OUTER = 1.0f
    private const val LOCAL_RADIUS = 10f
    private const val MID = 0.5f
    private const val DETAIL_GAIN = 1.8f
    private const val BASE_GAIN = 0.8f
    private const val LIFT = 0.02f
    private const val OUTLINE_GAIN = 1.6f
    private const val OUTLINE_MAX = 0.85f
    private const val DOG_RADIUS = 0.9f
    private const val DOG_RATIO = 1.6f
    private const val DOG_THRESHOLD = 0.008f
    private const val PENCIL_GAIN = 18f
    private const val INK_GAIN = 22f
    private const val MIN_SATURATION = 0.45f
    private const val FULL_SATURATION = 0.7f
    private const val MIN_LUMA = 0.25f
    private const val HUE_NEAR = 12f
    private const val HUE_FAR = 24f
    private const val MIN_ACCENT_SHARE = 0.01f
}
