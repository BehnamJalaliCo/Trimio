// Pixel-measuring test code: flat loops read better here than split for the rule.
@file:Suppress("ComplexCondition")

package io.trimio.engine.autopilot.workshop

import io.trimio.engine.autopilot.SubjectFinder
import io.trimio.engine.llm.RgbImage
import io.trimio.engine.motion.score.Subject
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The face finder on the workshop's synthetic rooms, measured: the five scenario rooms (grey,
 * sage, beige, navy, white; light to deep skin) and four harder ones (off-centre, two-tone wall,
 * beard, an oak wardrobe touching the jaw), five frames across a 30 s clip, at the Workshop's
 * 90×160 and at Studio's 288×512 (clean, and with grain and flicker). The box must overlap the true head (top of the hair to the
 * chin) by IoU ≥ 0.7, and never be the whole frame. The table also shows the old finder.
 */
class SubjectBenchTest {
    private val times = (1..5).map { 30f * it / 6f }

    private val rooms: List<Pair<String, SyntheticFootage>> =
        listOf("grey", "sage", "beige", "navy", "white").mapIndexed { k, name -> name to SyntheticFootage(k) } +
            SyntheticFootage.HARD.map { it to SyntheticFootage.hard(it) }

    private fun truth(f: SyntheticFootage): FloatArray {
        val boxes = times.map { f.head(it) }
        return FloatArray(4) { c -> boxes.map { it[c] }.average().toFloat() }
    }

    private fun Subject.array() = floatArrayOf(left, top, right, bottom)

    /** A frame as RGB; [noise] adds sensor-like grain (±noise per channel) and a small exposure flicker, as phone video has. */
    private fun rgb(f: SyntheticFootage, w: Int, h: Int, t: Float, noise: Int = 0): RgbImage {
        val rgba = f.rgba(w, h, t)
        val random = java.util.Random(t.toBits().toLong())
        val gain = if (noise == 0) 1f else 1f + (random.nextFloat() - 0.5f) * 0.08f
        return RgbImage(w, h, ByteArray(w * h * 3) {
            val v = (rgba[it / 3 * 4 + it % 3].toInt() and 0xFF) * gain + if (noise == 0) 0 else random.nextInt(2 * noise + 1) - noise
            v.toInt().coerceIn(0, 255).toByte()
        })
    }

    @Test
    fun findsTheHeadInEveryRoom() {
        val rows = mutableListOf<String>()
        val failures = mutableListOf<String>()
        for ((name, f) in rooms) {
            val truth = truth(f)
            val small = times.map { f.rgba(SMALL_W, SMALL_H, it) }
            val before = LegacyFinder.find(small, SMALL_W, SMALL_H)?.array()
            val after = SubjectFinder.locate(times.map { rgb(f, SMALL_W, SMALL_H, it) })!!
            val large = SubjectFinder.locate(times.map { rgb(f, LARGE_W, LARGE_H, it) })!!
            val noisy = SubjectFinder.locate(times.map { rgb(f, LARGE_W, LARGE_H, it, noise = 10) })!!
            val iouBefore = before?.let { SubjectFinder.iou(it, truth) } ?: 0f
            val iouAfter = SubjectFinder.iou(after.subject.array(), truth)
            val iouLarge = SubjectFinder.iou(large.subject.array(), truth)
            val iouNoisy = SubjectFinder.iou(noisy.subject.array(), truth)
            rows += "%-10s before %.2f%s  after %.2f (conf %.2f)  288×512 %.2f (conf %.2f)  noisy %.2f (conf %.2f)  box %s  truth %s".format(
                name, iouBefore, if (before != null && area(before) > 0.3f) " (whole)" else "", iouAfter, after.confidence, iouLarge, large.confidence,
                iouNoisy, noisy.confidence, fmt(after.subject.array()), fmt(truth),
            )
            for ((label, found, iou) in listOf(Triple("90×160", after, iouAfter), Triple("288×512", large, iouLarge), Triple("noisy", noisy, iouNoisy))) {
                if (iou < 0.7f || found.prior || area(found.subject.array()) > 0.3f) failures += "$name $label: IoU %.2f prior=${found.prior}".format(iou)
            }
        }
        println("subject IoU (true head: top of hair to chin)\n" + rows.joinToString("\n"))
        assertTrue(failures.isEmpty(), failures.joinToString("; "))
    }

    @Test
    fun aSingleFrameStillFindsTheHead() {
        for ((name, f) in rooms) {
            val found = SubjectFinder.locate(listOf(rgb(f, SMALL_W, SMALL_H, 7f)))!!
            val iou = SubjectFinder.iou(found.subject.array(), f.head(7f))
            assertTrue(iou >= 0.6f, "$name: one frame IoU $iou")
        }
    }

    @Test
    fun aRoomWithoutAPersonGetsThePriorNotTheWholeFrame() {
        val w = SMALL_W
        val h = SMALL_H
        // A beige wall with a lighting gradient and a wooden shelf, nobody in front of it.
        val frame = ByteArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) {
            val shade = 1f - 0.4f * y / h
            val wood = y in 90..96 && x in 10..60
            val (r, g, b) = if (wood) Triple(120, 84, 56) else Triple((205 * shade).toInt(), (182 * shade).toInt(), (150 * shade).toInt())
            val i = (y * w + x) * 3
            frame[i] = r.toByte(); frame[i + 1] = g.toByte(); frame[i + 2] = b.toByte()
        }
        val found = SubjectFinder.locate(List(5) { RgbImage(w, h, frame) })!!
        assertTrue(found.prior, "${found.subject}")
        assertFalse(area(found.subject.array()) > 0.3f)
    }

    private fun area(b: FloatArray) = (b[2] - b[0]) * (b[3] - b[1])

    private fun fmt(b: FloatArray) = b.joinToString(",", "[", "]") { "%.2f".format(it) }

    private companion object {
        const val SMALL_W = 90
        const val SMALL_H = 160
        const val LARGE_W = 288
        const val LARGE_H = 512
    }
}

/** The finder before this bench (fixed YCbCr skin range on a 30-cell grid), kept for the before/after table. */
private object LegacyFinder {
    fun find(frames: List<ByteArray>, width: Int, height: Int): Subject? {
        val cell = maxOf(2, width / 30)
        val gw = width / cell
        val gh = height / cell
        val votes = IntArray(gw * gh)
        for (f in frames) for (gy in 0 until gh) for (gx in 0 until gw) if (skinCell(f, width, gx * cell, gy * cell, cell)) votes[gy * gw + gx]++
        val need = (frames.size * 0.6f).toInt().coerceAtLeast(1)
        val blob = largest(BooleanArray(votes.size) { votes[it] >= need }, gw, gh)?.takeIf { it.size >= 12 } ?: return null
        val xs = blob.map { it % gw }
        val ys = blob.map { it / gw }
        val bottom = minOf(ys.max(), ys.min() + ((xs.max() - xs.min() + 1) * 1.35f).toInt())
        return Subject(
            (xs.min().toFloat() / gw - 0.02f).coerceIn(0f, 1f), (ys.min().toFloat() / gh - 0.02f).coerceIn(0f, 1f),
            ((xs.max() + 1).toFloat() / gw + 0.02f).coerceIn(0f, 1f), ((bottom + 1).toFloat() / gh + 0.02f).coerceIn(0f, 1f),
        )
    }

    private fun skinCell(f: ByteArray, width: Int, x0: Int, y0: Int, cell: Int): Boolean {
        var skin = 0
        for (y in y0 until y0 + cell) for (x in x0 until x0 + cell) {
            val i = (y * width + x) * 4
            val r = f[i].toInt() and 0xFF
            val g = f[i + 1].toInt() and 0xFF
            val b = f[i + 2].toInt() and 0xFF
            val luma = 0.299f * r + 0.587f * g + 0.114f * b
            val cb = 128f - 0.168736f * r - 0.331264f * g + 0.5f * b
            val cr = 128f + 0.5f * r - 0.418688f * g - 0.081312f * b
            if (luma > 45f && cb in 77f..127f && cr in 135f..173f && r > b) skin++
        }
        return skin * 2 >= cell * cell
    }

    private fun largest(on: BooleanArray, w: Int, h: Int): List<Int>? {
        val seen = BooleanArray(on.size)
        var best: List<Int>? = null
        for (start in on.indices) {
            if (!on[start] || seen[start]) continue
            val blob = mutableListOf<Int>()
            val stack = ArrayDeque<Int>().apply { addLast(start) }
            seen[start] = true
            while (stack.isNotEmpty()) {
                val c = stack.removeLast()
                blob += c
                for ((dx, dy) in listOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    val nx = c % w + dx
                    val ny = c / w + dy
                    if (nx in 0 until w && ny in 0 until h && on[ny * w + nx] && !seen[ny * w + nx]) { seen[ny * w + nx] = true; stack.addLast(ny * w + nx) }
                }
            }
            if (blob.size > (best?.size ?: 0)) best = blob
        }
        return best
    }
}
