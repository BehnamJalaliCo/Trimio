package io.trimio.engine.perception.ocr

import io.trimio.core.model.text.TextKey
import io.trimio.engine.models.MediaAddOns
import io.trimio.engine.onnx.Network
import io.trimio.engine.onnx.Tensor
import io.trimio.engine.perception.Box
import io.trimio.engine.perception.Frame
import io.trimio.engine.perception.Perception
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A line of text found in a frame: where (frame fractions), what it says (logical order) and how sure the reader is. */
data class TextLine(val box: Box, val text: String, val confidence: Float)

/**
 * Text in one frame. Two uses: text already in the footage (a sign, a slide, burnt-in subtitles)
 * that our graphics must not cover, and the jury's check that what we rendered reads back as what
 * we meant (letters joined, nothing clipped). [measured] is false without the add-on.
 */
data class OcrText(val lines: List<TextLine>, val measured: Boolean) {
    /** Boxes of text to keep clear of. */
    val regions: List<Box> get() = lines.map { it.box }

    fun overlaps(box: Box): Boolean = lines.any { it.box.intersects(box) }

    /**
     * How well [expected] reads back from this frame, 0..1: the best similarity of its letters
     * (Persian forms folded, spaces and marks ignored) to any line or run of adjacent lines.
     */
    fun readBack(expected: String): Float {
        val want = TextKey.of(expected)
        if (want.isEmpty()) return 1f
        val keys = lines.sortedWith(compareBy({ it.box.y }, { -it.box.x })).map { TextKey.of(it.text) }
        var best = 0f
        for (i in keys.indices) {
            var joined = ""
            for (j in i until min(keys.size, i + MAX_JOIN)) {
                joined += keys[j]
                best = max(best, similarity(want, joined))
            }
        }
        return best
    }

    companion object {
        private const val MAX_JOIN = 4

        /** 1 − normalised Levenshtein distance. */
        fun similarity(a: String, b: String): Float {
            if (a.isEmpty() && b.isEmpty()) return 1f
            var prev = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val cur = IntArray(b.length + 1)
                cur[0] = i
                for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = cur
            }
            return 1f - prev[b.length].toFloat() / max(a.length, b.length)
        }
    }
}

/**
 * PP-OCRv5 mobile (PaddlePaddle, Apache-2.0): the DB text detector and the Arabic-script CTC
 * recogniser (its 747-character table covers Persian). Detection follows PaddleOCR's
 * DetResizeForTest/NormalizeImage (BGR, ImageNet mean and std, sides rounded to 32) and a
 * simplified DBPostProcess: the probability map thresholded at 0.3, connected regions scored by
 * mean probability (≥ 0.6) and grown by the unclip distance (area × 1.5 / perimeter) as axis-aligned
 * boxes — rotated text is not expected in talking-head footage or our own captions. Recognition crops
 * each box to 48 px high, normalises to −1..1 and decodes CTC greedily; the network reads Arabic script
 * left to right, so runs are reversed into logical order the way PaddleOCR does.
 */
class PpOcr(private val charset: List<String>) {
    /** The detector's input for [frame] with its long side at most [longSide] (sides multiples of 32). */
    fun detectorInput(frame: Frame, longSide: Int = LONG_SIDE): Triple<Tensor, Int, Int> {
        val r = longSide.toFloat() / max(frame.width, frame.height)
        val w = max(ALIGN, (frame.width * r / ALIGN).roundToInt() * ALIGN)
        val h = max(ALIGN, (frame.height * r / ALIGN).roundToInt() * ALIGN)
        val small = frame.resized(w, h)
        val plane = w * h
        val data = FloatArray(3 * plane)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            data[i] = (small.b(x, y) / BYTE - MEAN[0]) / STD[0]
            data[plane + i] = (small.g(x, y) / BYTE - MEAN[1]) / STD[1]
            data[2 * plane + i] = (small.r(x, y) / BYTE - MEAN[2]) / STD[2]
        }
        return Triple(Tensor.of(longArrayOf(1, 3, h.toLong(), w.toLong()), data), w, h)
    }

    /** Text boxes (frame fractions) and their scores from a probability map of [w]×[h]. */
    fun boxes(map: FloatArray, w: Int, h: Int, thresh: Float = 0.3f, boxThresh: Float = 0.6f): List<Pair<Box, Float>> {
        val seen = BooleanArray(w * h)
        val out = ArrayList<Pair<Box, Float>>()
        for (start in 0 until w * h) {
            if (map[start] > thresh && !seen[start]) region(map, w, h, start, thresh, seen)?.takeIf { it.second >= boxThresh }?.let { out += it }
        }
        return out
    }

    /** The 4-connected region above [thresh] from [start]: its unclipped box and mean probability; null when too thin. */
    private fun region(map: FloatArray, w: Int, h: Int, start: Int, thresh: Float, seen: BooleanArray): Pair<Box, Float>? {
        val stack = ArrayDeque<Int>().apply { add(start) }
        seen[start] = true
        var x0 = w
        var y0 = h
        var x1 = 0
        var y1 = 0
        var sum = 0f
        var n = 0
        while (stack.isNotEmpty()) {
            val i = stack.removeLast()
            val x = i % w
            val y = i / w
            x0 = min(x0, x)
            x1 = max(x1, x)
            y0 = min(y0, y)
            y1 = max(y1, y)
            sum += map[i]
            n++
            for (j in intArrayOf(i - 1, i + 1, i - w, i + w)) {
                val inside = j in 0 until w * h && (j / w == y || j % w == x)
                if (inside && map[j] > thresh && !seen[j]) {
                    seen[j] = true
                    stack.add(j)
                }
            }
        }
        val bw = x1 - x0 + 1f
        val bh = y1 - y0 + 1f
        if (bh < MIN_SIDE || bw < MIN_SIDE) return null
        val d = bw * bh * UNCLIP / (2 * (bw + bh))
        return Box.of((x0 - d) / w, (y0 - d) / h, (x1 + 1 + d) / w, (y1 + 1 + d) / h) to sum / n
    }

    /** The recogniser's input for [box] of [frame]: 48 px high, width by aspect, values −1..1, BGR. */
    fun recognizerInput(frame: Frame, box: Box): Tensor {
        val x0 = (box.x * frame.width).toInt().coerceIn(0, frame.width - 1)
        val y0 = (box.y * frame.height).toInt().coerceIn(0, frame.height - 1)
        val cw = max(1, (box.right * frame.width).toInt().coerceAtMost(frame.width) - x0)
        val ch = max(1, (box.bottom * frame.height).toInt().coerceAtMost(frame.height) - y0)
        val tw = min(MAX_WIDTH, max(MIN_WIDTH, ceil(HEIGHT.toFloat() * cw / ch).toInt()))
        val plane = HEIGHT * tw
        val data = FloatArray(3 * plane)
        for (y in 0 until HEIGHT) {
            val sy = (y0 + ((y + 0.5f) * ch / HEIGHT - 0.5f).roundToInt().coerceIn(0, ch - 1))
            for (x in 0 until tw) {
                val sx = (x0 + ((x + 0.5f) * cw / tw - 0.5f).roundToInt().coerceIn(0, cw - 1))
                val i = y * tw + x
                data[i] = frame.b(sx, sy) / HALF - 1f
                data[plane + i] = frame.g(sx, sy) / HALF - 1f
                data[2 * plane + i] = frame.r(sx, sy) / HALF - 1f
            }
        }
        return Tensor.of(longArrayOf(1, 3, HEIGHT.toLong(), tw.toLong()), data)
    }

    /** Greedy CTC over [probs] (steps × classes): text in logical order and the mean confidence of kept characters. */
    fun decode(probs: FloatArray, classes: Int): Pair<String, Float> {
        val steps = probs.size / classes
        val sb = StringBuilder()
        var prev = -1
        var conf = 0f
        var kept = 0
        for (t in 0 until steps) {
            var best = 0
            for (c in 1 until classes) if (probs[t * classes + c] > probs[t * classes + best]) best = c
            if (best != prev && best != 0) {
                sb.append(charOf(best))
                conf += probs[t * classes + best]
                kept++
            }
            prev = best
        }
        return logical(sb.toString()) to if (kept == 0) 0f else conf / kept
    }

    private fun charOf(id: Int): String = when {
        id in 1..charset.size -> charset[id - 1]
        else -> " "
    }

    companion object {
        const val LONG_SIDE = 960
        private const val ALIGN = 32
        private const val BYTE = 255f
        private const val HALF = 127.5f
        private val MEAN = floatArrayOf(0.485f, 0.456f, 0.406f)
        private val STD = floatArrayOf(0.229f, 0.224f, 0.225f)
        private const val UNCLIP = 1.5f
        private const val MIN_SIDE = 3
        private const val HEIGHT = 48
        private const val MIN_WIDTH = 8
        private const val MAX_WIDTH = 3200
        private val LATIN = Regex("[a-zA-Z0-9 :*./%+-]")

        /** PaddleOCR's pred_reverse: reverse the order of runs, keeping Latin/digit runs as they are. */
        fun logical(visual: String): String {
            val parts = ArrayList<String>()
            val run = StringBuilder()
            for (c in visual) {
                if (LATIN.matches(c.toString())) {
                    run.append(c)
                } else {
                    if (run.isNotEmpty()) parts += run.toString()
                    run.clear()
                    parts += c.toString()
                }
            }
            if (run.isNotEmpty()) parts += run.toString()
            return parts.asReversed().joinToString("")
        }

        /** The character table from the recogniser's inference.yml (`character_dict:` list). */
        fun charsetFromYaml(yaml: String): List<String> {
            val body = yaml.substringAfter("character_dict:", "").lineSequence().drop(1)
            return body.takeWhile { it.startsWith("  - ") }.map { line ->
                val v = line.removePrefix("  - ")
                if (v.length >= 2 && v.startsWith("'") && v.endsWith("'")) v.substring(1, v.length - 1).replace("''", "'") else v
            }.toList()
        }
    }
}

/** Reads text in frames with the add-on when installed; without it nothing is read ([OcrText.measured] false). */
class TextReader(private val perception: Perception) {
    private var ocr: PpOcr? = null

    suspend fun read(frames: List<Frame>, longSide: Int = PpOcr.LONG_SIDE): List<OcrText> {
        val reader = if (perception.has(MediaAddOns.ocrDetector, MediaAddOns.ocrRecognizer)) load() else null
        val found = reader?.let { r ->
            val boxes = perception.network("ocr", MediaAddOns.ocrDetector) { net -> frames.map { detect(net, r, it, longSide) } } ?: return@let null
            perception.network("ocr", MediaAddOns.ocrRecognizer) { net ->
                frames.mapIndexed { i, f -> OcrText(boxes[i].map { (b, _) -> recognize(net, r, f, b) }.filter { it.text.isNotBlank() }, measured = true) }
            }
        }
        return found ?: frames.map { OcrText(emptyList(), measured = false) }
    }

    private fun detect(net: Network, r: PpOcr, frame: Frame, longSide: Int): List<Pair<Box, Float>> {
        val (input, w, h) = r.detectorInput(frame, longSide)
        val map = net.run(mapOf(net.inputNames.first() to input)).values.first().requireFloats()
        return r.boxes(map, w, h)
    }

    private fun recognize(net: Network, r: PpOcr, frame: Frame, box: Box): TextLine {
        val out = net.run(mapOf(net.inputNames.first() to r.recognizerInput(frame, box))).values.first()
        val (text, conf) = r.decode(out.requireFloats(), out.shape.last().toInt())
        return TextLine(box, text, conf)
    }

    private fun load(): PpOcr? = ocr ?: perception.readText(MediaAddOns.ocrDictionary)?.let(PpOcr::charsetFromYaml)?.takeIf { it.isNotEmpty() }?.let(::PpOcr)?.also { ocr = it }
}
