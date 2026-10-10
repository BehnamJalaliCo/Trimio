package io.trimio.engine.autopilot

import io.trimio.engine.llm.RgbImage
import io.trimio.engine.motion.score.Subject
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.roundToInt

/**
 * Where the speaker's head is, from a few small frames across the clip, in pure Kotlin so every
 * platform (Android included) finds it the same way. Graphics over footage keep above the hair and
 * below the chin, so the box runs from the top of the hair to the chin (or the beard).
 *
 * A fixed "skin colour" range is not enough in ordinary rooms: beige walls and light wood have
 * skin's chroma. So the finder combines:
 *
 * - **background**: colours seen along the frame's left and right borders, row by row, are the
 *   room, not the face ([FaceScene]);
 * - **an adaptive skin model**: the dominant skin-like colour of the centre-upper frame (where a
 *   talking head is), in brightness-free rg, with a luma band — so a wall a shade off the face's
 *   hue, or dark wood of the same hue, falls out;
 * - **shape**: a face is an ellipse-ish blob with holes (eyes, mouth), a narrower neck below it and
 *   shoulders under that; blobs merged with furniture are split by erosion and scored again;
 * - **motion**: the head and mouth move between frames, walls and shelves do not;
 * - **hair and beard**: the dark region connected above the skin (up to ~0.45 face heights), at
 *   its sides, and below a cut-off chin, belongs to the head;
 * - **time**: each frame is read alone and the boxes are combined by their median, so one bad
 *   frame (a hand, a turn) does not move it.
 *
 * When nothing face-like is found the answer is [PRIOR], the upper-centre band where talking heads
 * sit, never the whole frame. A seeing model can refine the box ([SubjectGrounding]).
 */
object SubjectFinder {

    /** A head [subject], how sure the finder is (0–1), and whether it fell back to the framing [prior]. */
    data class Found(val subject: Subject, val confidence: Float, val prior: Boolean = false)

    /** Where a talking head sits in vertical video when nothing better is known: upper centre, generous. */
    val PRIOR = Subject(0.25f, 0.14f, 0.75f, 0.5f)

    /** [frames]: RGBA, [width]×[height] each (~90×160 is plenty). Null only without frames. */
    fun find(frames: List<ByteArray>, width: Int, height: Int): Subject? =
        read(frames.map { FacePixels.of(it, width, height, 4) })?.subject

    /** [frames]: small RGB frames across one clip (five is a good number). Null only without frames. */
    fun locate(frames: List<RgbImage>): Found? = read(frames.map { FacePixels.of(it) })

    private fun read(pixels: List<FacePixels>): Found? {
        if (pixels.isEmpty()) return null
        val same = pixels.filter { it.w == pixels[0].w && it.h == pixels[0].h }
        val scene = FaceScene(same)
        // The room excluded first; if that leaves no face (a face at the frame's edge), without it.
        val strict = SkinModel.seed(scene, excludeBackground = true)?.let { combine(scene, it) }
        val found = if (strict != null && strict.confidence >= SURE) strict
        else listOfNotNull(strict, SkinModel.seed(scene, excludeBackground = false)?.let { combine(scene, it) }).maxByOrNull { it.confidence }
        return found?.takeIf { it.confidence >= MIN_CONFIDENCE } ?: Found(PRIOR, found?.confidence ?: 0f, prior = true)
    }

    /** Each frame's best head, combined by the median box; agreement between frames raises confidence. */
    private fun combine(scene: FaceScene, model: SkinModel): Found? {
        val heads = scene.frames.indices.mapNotNull { k -> HeadReader(scene, k, model).best() }.filter { it.score >= MIN_SCORE }
        if (heads.isEmpty()) return null
        val median = FloatArray(4) { c -> heads.map { it.box[c] }.sorted()[heads.size / 2] }
        val agree = heads.count { iou(it.box, median) >= AGREE } / scene.frames.size.toFloat()
        val score = heads.map { it.score }.sorted()[heads.size / 2]
        val box = Subject(median[0], median[1], median[2], median[3])
        if (!plausible(box)) return null
        return Found(box, (score * (0.5f + 0.5f * agree)).coerceIn(0f, 1f))
    }

    /** A head, not a wall: neither tiny nor most of the frame. */
    private fun plausible(s: Subject): Boolean {
        val w = s.right - s.left
        val h = s.bottom - s.top
        return w in 0.04f..0.8f && h in 0.03f..0.65f && w * h <= MAX_AREA
    }

    internal fun iou(a: FloatArray, b: FloatArray): Float {
        val iw = (minOf(a[2], b[2]) - maxOf(a[0], b[0])).coerceAtLeast(0f)
        val ih = (minOf(a[3], b[3]) - maxOf(a[1], b[1])).coerceAtLeast(0f)
        val inter = iw * ih
        val union = (a[2] - a[0]) * (a[3] - a[1]) + (b[2] - b[0]) * (b[3] - b[1]) - inter
        return if (union <= 0f) 0f else inter / union
    }

    private const val MIN_SCORE = 0.3f
    private const val MIN_CONFIDENCE = 0.32f
    private const val SURE = 0.5f
    private const val AGREE = 0.5f
    private const val MAX_AREA = 0.3f
}

/**
 * The clip's skin colour: the dominant skin-like colour of the centre-upper frame, weighted toward
 * where a talking head sits, with a tolerance from its own spread. [excludeBackground] drops
 * colours the frame's borders show (the wall).
 */
internal class SkinModel private constructor(
    private val rn: Float,
    private val gn: Float,
    private val rTolerance: Float,
    private val gTolerance: Float,
    val luma: Float,
    private val excludeBackground: Boolean,
) {
    override fun toString() = "SkinModel(rn=$rn, gn=$gn, ±$rTolerance/$gTolerance, luma=$luma, room excluded=$excludeBackground)"

    fun skin(p: FacePixels, background: Float, i: Int): Boolean =
        p.skinGate(i) && abs(p.rn[i] - rn) <= rTolerance && abs(p.gn[i] - gn) <= gTolerance &&
            p.luma[i] in luma * LOW..luma * HIGH && (!excludeBackground || background > BACKGROUND)

    companion object {
        /** Squared colour distance under which a pixel is the room. */
        const val BACKGROUND = 90f
        private const val LOW = 0.5f
        private const val HIGH = 1.7f
        private const val BIN = 0.01f
        private const val BINS = 64

        fun seed(scene: FaceScene, excludeBackground: Boolean): SkinModel? {
            val w = scene.w
            val h = scene.h
            val hist = FloatArray(BINS * BINS)
            val picked = ArrayList<Int>()
            val weights = ArrayList<Float>()
            for ((k, f) in scene.frames.withIndex()) for (y in (h * 0.06f).toInt() until (h * 0.66f).toInt()) for (x in (w * 0.12f).toInt() until (w * 0.88f).toInt()) {
                val i = y * w + x
                if (!f.skinGate(i) || (excludeBackground && scene.background[k][i] <= BACKGROUND)) continue
                val dx = (x - w * 0.5f) / (w * 0.28f)
                val dy = (y - h * 0.36f) / (h * 0.2f)
                // Dark hair and beards share skin's hue; the face is the lit part.
                val weight = exp(-(dx * dx + dy * dy)) * ((f.luma[i] - 25f) / 75f).coerceIn(0.05f, 1f)
                hist[bin(f.rn[i]) * BINS + bin(f.gn[i])] += weight
                picked += k * w * h + i
                weights += weight
            }
            if (picked.size < scene.frames.size * w * h / MIN_SHARE) return null
            val peak = peak(hist)
            val pr = (peak / BINS + 0.5f) * BIN
            val pg = (peak % BINS + 0.5f) * BIN
            fun frame(j: Int) = scene.frames[j / (w * h)]
            val near = picked.indices.filter { n -> val f = frame(picked[n]); val i = picked[n] % (w * h); abs(f.rn[i] - pr) < 0.03f && abs(f.gn[i] - pg) < 0.02f }
            if (near.size < MIN_NEAR) return null
            // The face's brightness: the weighted median, so a large dark beard of the same hue does not set it.
            val byLuma = near.sortedBy { n -> frame(picked[n]).luma[picked[n] % (w * h)] }
            val half = byLuma.sumOf { weights[it].toDouble() } / 2
            var acc = 0.0
            val mid = byLuma.first { acc += weights[it]; acc >= half }
            val luma = frame(picked[mid]).luma[picked[mid] % (w * h)]
            val face = near.map { picked[it] }.filter { j -> frame(j).luma[j % (w * h)] in luma * LOW..luma * HIGH }
            val rs = face.map { j -> frame(j).rn[j % (w * h)] }.sorted()
            val gs = face.map { j -> frame(j).gn[j % (w * h)] }.sorted()
            val r = rs[rs.size / 2]
            val g = gs[gs.size / 2]
            return SkinModel(r, g, (3f * mad(rs, r)).coerceIn(0.022f, 0.06f), (3f * mad(gs, g)).coerceIn(0.016f, 0.04f), luma, excludeBackground)
        }

        private fun bin(v: Float) = (v / BIN).toInt().coerceIn(0, BINS - 1)

        /** The histogram's peak after a 3×3 smoothing (one bin of noise must not win). */
        private fun peak(hist: FloatArray): Int {
            var best = 0
            var bestValue = -1f
            for (a in 1 until BINS - 1) for (b in 1 until BINS - 1) {
                var s = 0f
                for (da in -1..1) for (db in -1..1) s += hist[(a + da) * BINS + b + db]
                if (s > bestValue) { bestValue = s; best = a * BINS + b }
            }
            return best
        }

        private fun mad(sorted: List<Float>, median: Float): Float = sorted.map { abs(it - median) }.sorted()[sorted.size / 2] * 1.4826f

        /** At least one pixel in this many skin-like, or there is no face to model. */
        private const val MIN_SHARE = 1500
        private const val MIN_NEAR = 12
    }
}

/** One frame's reading: skin blobs scored as faces, the best one grown to the whole head. */
internal class HeadReader(private val scene: FaceScene, k: Int, private val model: SkinModel) {
    class Head(val box: FloatArray, val score: Float)

    private val w = scene.w
    private val h = scene.h
    private val f = scene.frames[k]
    private val bg = scene.background[k]
    private val mask = BooleanArray(w * h) { model.skin(f, bg[it], it) }

    /**
     * The best-scoring face over the blobs of the mask, of the mask cut along luma edges (a face
     * against wood of its own hue still differs in brightness; the neck is in shade) and of their
     * erosions (which split a face from things it touches on a thin bridge).
     */
    fun best(): Head? {
        var best: Head? = null
        for (base in listOf(mask, cut(mask))) {
            var m = base
            for (level in 0..2) {
                if (level > 0) m = erode(m)
                val grow = level + if (base === mask) 0 else 1
                for (blob in blobs(m)) {
                    val head = read(if (grow == 0) blob else regrow(blob, grow)) ?: continue
                    if (head.score > (best?.score ?: 0f)) best = head
                }
            }
        }
        return best
    }

    /** [m] without the pixels on a brightness edge (a step over two pixels of a tenth of the skin's luma). */
    private fun cut(m: BooleanArray): BooleanArray {
        val step = model.luma * EDGE
        return BooleanArray(m.size) { c ->
            val x = c % w
            val y = c / w
            m[c] && x in 1 until w - 1 && y in 1 until h - 1 &&
                abs(f.luma[c + 1] - f.luma[c - 1]) <= step && abs(f.luma[c + w] - f.luma[c - w]) <= step
        }
    }

    /** Rows of a blob: first and last column and pixel count per row. */
    private class Rows(h: Int) {
        val min = IntArray(h) { Int.MAX_VALUE }
        val max = IntArray(h) { -1 }
        val count = IntArray(h)
        fun width(y: Int) = if (count[y] == 0) 0 else max[y] - min[y] + 1
    }

    private fun read(blob: IntArray): Head? {
        if (blob.size < MIN_PIXELS) return null
        val rows = Rows(h)
        for (i in blob) {
            val y = i / w
            val x = i % w
            rows.min[y] = minOf(rows.min[y], x); rows.max[y] = maxOf(rows.max[y], x); rows.count[y]++
        }
        val top = rows.count.indexOfFirst { it > 0 }
        val bottom = rows.count.indexOfLast { it > 0 }
        val chin = chin(rows, top, bottom)
        val face = Face(rows, top, chin)
        if (face.width < 3 || chin - top < 3) return null
        val score = score(face, rows)
        val box = head(face)
        return Head(box, score)
    }

    /** The face part of a blob that may run on into the neck: where the width drops to a neck and stops narrowing. */
    private fun chin(rows: Rows, top: Int, bottom: Int): Int {
        val upper = top + maxOf(3, ((bottom - top + 1) * 0.6f).toInt())
        var widest = top
        for (y in top..minOf(bottom, upper)) if (rows.width(y) > rows.width(widest)) widest = y
        val maxW = rows.width(widest)
        var y = widest + 1
        while (y <= bottom && rows.width(y) > maxW * NECK) y++
        while (y < bottom && rows.width(y + 1) < rows.width(y) - maxOf(1f, maxW * 0.04f)) y++
        return minOf(y, bottom, top + (maxW * 1.7f).toInt())
    }

    private inner class Face(val rows: Rows, val top: Int, val chin: Int) {
        val left = (top..chin).filter { rows.count[it] > 0 }.minOf { rows.min[it] }
        val right = (top..chin).filter { rows.count[it] > 0 }.maxOf { rows.max[it] }
        val width get() = right - left + 1
        val height get() = chin - top + 1
        val centreX get() = (left + right) / 2
    }

    /** How face-like: size, shape, holes, motion, framing and shoulders below. */
    private fun score(face: Face, rows: Rows): Float {
        val frac = face.width * face.height / (w * h).toFloat()
        val size = ramp(frac, 0.002f, 0.006f) * (1f - ramp(frac, 0.18f, 0.3f))
        val aspect = face.height / face.width.toFloat()
        val shapeA = ramp(aspect, 0.4f, 0.75f) * (1f - ramp(aspect, 1.7f, 2.4f))
        var count = 0
        var holes = 0
        for (y in face.top..face.chin) {
            if (rows.count[y] == 0) continue
            count += rows.count[y]
            holes += rows.width(y) - rows.count[y]
        }
        val fill = count / (face.width * face.height).toFloat()
        val shapeF = ramp(fill, 0.3f, 0.5f) * (1f - 0.5f * ramp(fill, 0.92f, 1f))
        val holeScore = (holes / (count + holes).toFloat() / 0.03f).coerceIn(0f, 1f)
        val px = (face.left + face.right + 1) / 2f / w
        val py = (face.top + face.chin + 1) / 2f / h
        val position = exp(-((px - 0.5f) / 0.32f).let { it * it }) * (if (py < 0.65f) 1f else exp(-((py - 0.65f) / 0.15f).let { it * it }))
        return size * (0.3f * shapeA * shapeF + 0.15f * holeScore + 0.15f * motion(face) + 0.2f * position + 0.2f * shoulders(face))
    }

    /** Movement around the face against the frame's average; neutral when the frames are identical. */
    private fun motion(face: Face): Float {
        if (scene.motionMean < STILL) return 0.5f
        var sum = 0f
        var n = 0
        for (y in (face.top - 2).coerceAtLeast(0)..(face.chin + 2).coerceAtMost(h - 1)) for (x in (face.left - 2).coerceAtLeast(0)..(face.right + 2).coerceAtMost(w - 1)) {
            sum += scene.motion[y * w + x]; n++
        }
        return ((sum / n / scene.motionMean - 1f) / 3f).coerceIn(0f, 1f)
    }

    /** A body under the head: something not-wall clearly wider than the face, half a face below the chin. */
    private fun shoulders(face: Face): Float {
        val y = (face.chin + face.height * 0.6f).roundToInt()
        if (y >= h) return 0.5f
        var l = face.centreX
        var r = face.centreX
        while (l > 0 && room(l - 1, y).not()) l--
        while (r < w - 1 && room(r + 1, y).not()) r++
        if (room(face.centreX, y)) return 0f
        return ramp((r - l + 1) / face.width.toFloat(), 0.8f, 1.5f)
    }

    private fun room(x: Int, y: Int) = bg[y * w + x] <= SkinModel.BACKGROUND

    /** Dark, not the room, not skin: hair, brows, a beard. */
    private fun hair(x: Int, y: Int): Boolean {
        val i = y * w + x
        return !mask[i] && bg[i] > SkinModel.BACKGROUND && f.luma[i] < model.luma * DARK
    }

    /** The face grown to the head: hair above and at the sides, a beard below, padded; as fractions of the frame. */
    private fun head(face: Face): FloatArray {
        val faceH = maxOf(face.height, (face.width * 1.15f).roundToInt())
        val reach = (faceH * HAIR_REACH).roundToInt()
        val spread = (face.width * 0.15f).roundToInt()
        var top = face.top
        var gap = 0
        for (y in face.top - 1 downTo maxOf(0, face.top - reach)) {
            if (share(face.left - spread, face.right + spread, y) >= 0.3f) { top = y; gap = 0 } else if (++gap > 1) break
        }
        val left = side(face, top, -1)
        val right = side(face, top, 1)
        val bottom = beard(face, faceH)
        val padX = (right - left + 1) * PAD
        val padY = (bottom - top + 1) * PAD
        return floatArrayOf(
            ((left - padX) / w).coerceIn(0f, 1f),
            ((top - padY) / h).coerceIn(0f, 1f),
            ((right + 1 + padX) / w).coerceIn(0f, 1f),
            ((bottom + 1 + padY) / h).coerceIn(0f, 1f),
        )
    }

    /** The share of hair pixels on row [y] between [from] and [to]. */
    private fun share(from: Int, to: Int, y: Int): Float {
        val a = from.coerceAtLeast(0)
        val b = to.coerceAtMost(w - 1)
        if (b < a) return 0f
        return (a..b).count { hair(it, y) } / (b - a + 1).toFloat()
    }

    /** Hair at the face's side ([dir] −1 left, +1 right): columns with hair over a quarter of the upper head. */
    private fun side(face: Face, top: Int, dir: Int): Int {
        val rows = top..minOf(h - 1, face.top + (face.height * 0.6f).roundToInt())
        var edge = if (dir < 0) face.left else face.right
        repeat((face.width * 0.25f).roundToInt()) {
            val x = edge + dir
            if (x !in 0 until w || rows.count { y -> hair(x, y) } < (rows.last - rows.first + 1) * 0.25f) return edge
            edge = x
        }
        return edge
    }

    /** A beard below a chin the skin stops at: dark rows as wide as the face, not wider (a shirt is). */
    private fun beard(face: Face, faceH: Int): Int {
        var bottom = face.chin
        val flank = (face.width * 0.3f).roundToInt()
        for (y in face.chin + 1..minOf(h - 1, face.chin + (faceH * HAIR_REACH).roundToInt())) {
            val inside = share(face.left, face.right, y)
            val outside = (share(face.left - flank, face.left - 1, y) + share(face.right + 1, face.right + flank, y)) / 2f
            if (inside < 0.3f || outside > 0.5f) break
            bottom = y
        }
        return bottom
    }

    /** Connected blobs (4-neighbour) of [m], each as pixel indices. */
    private fun blobs(m: BooleanArray): List<IntArray> {
        val seen = BooleanArray(m.size)
        val out = ArrayList<IntArray>()
        val stack = IntArray(m.size)
        for (start in m.indices) {
            if (!m[start] || seen[start]) continue
            val blob = ArrayList<Int>()
            var top = 0
            stack[top++] = start
            seen[start] = true
            while (top > 0) {
                val c = stack[--top]
                blob += c
                for (n in neighbours(c)) if (m[n] && !seen[n]) { seen[n] = true; stack[top++] = n }
            }
            if (blob.size >= MIN_PIXELS) out += blob.toIntArray()
        }
        return out
    }

    private fun neighbours(c: Int): List<Int> {
        val x = c % w
        val y = c / w
        return listOfNotNull(
            (c - 1).takeIf { x > 0 }, (c + 1).takeIf { x < w - 1 },
            (c - w).takeIf { y > 0 }, (c + w).takeIf { y < h - 1 },
        )
    }

    private fun erode(m: BooleanArray) = BooleanArray(m.size) { c -> m[c] && neighbours(c).size == 4 && neighbours(c).all { m[it] } }

    /** An eroded blob grown back by [steps] inside the original mask (a geodesic dilation). */
    private fun regrow(blob: IntArray, steps: Int): IntArray {
        val inside = BooleanArray(w * h)
        blob.forEach { inside[it] = true }
        var frontier = blob.toList()
        repeat(steps) {
            frontier = frontier.flatMap { c -> neighbours(c).filter { n -> mask[n] && !inside[n] }.onEach { inside[it] = true } }
        }
        return inside.indices.filter { inside[it] }.toIntArray()
    }

    private companion object {
        const val MIN_PIXELS = 10
        const val NECK = 0.55f
        const val STILL = 0.3f
        const val DARK = 0.72f
        const val HAIR_REACH = 0.45f
        const val PAD = 0.04f
        const val EDGE = 0.1f

        /** 0 below [a], 1 above [b], linear between. */
        fun ramp(v: Float, a: Float, b: Float) = ((v - a) / (b - a)).coerceIn(0f, 1f)
    }
}
