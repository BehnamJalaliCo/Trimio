package io.trimio.engine.illustration.quality

import io.trimio.engine.illustration.Colors
import io.trimio.engine.illustration.Raster
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Letter-like marks in a picture: generators love to scribble pseudo-text on signs and papers, and a
 * Persian film must never show garbled Latin. Dark blobs of glyph size and shape are found (connected
 * components), then chains of four or more of similar height side by side on one baseline — the
 * signature of a written line. Hatching (long thin strokes) and faces do not chain that way.
 * An OCR add-on, when installed, can confirm through [TextDetector].
 */
object TextArtefacts {
    /** What was found: the longest chain of glyph-like marks and how many chains of three or more. */
    data class Report(val longestChain: Int, val chains: Int, val glyphs: Int) {
        val textLike: Boolean get() = longestChain >= MIN_CHAIN || chains >= 2
    }

    private class Blob(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val pixels: Int) {
        val w: Int get() = x1 - x0 + 1
        val h: Int get() = y1 - y0 + 1
        val cy: Float get() = (y0 + y1) / 2f
    }

    fun find(image: Raster, darkLuma: Float = DARK): Report {
        val w = image.width
        val h = image.height
        val dark = BooleanArray(w * h) { i -> val c = image.argb[i]; Colors.a(c) > HALF_ALPHA && Colors.luma(c) < darkLuma }
        val seen = BooleanArray(w * h)
        val stack = IntArray(w * h)
        val glyphs = ArrayList<Blob>()
        val minH = max(MIN_PX, (h * MIN_HEIGHT).toInt())
        val maxH = max(minH + 1, (h * MAX_HEIGHT).toInt())
        for (start in 0 until w * h) {
            if (!dark[start] || seen[start]) continue
            var top = 0
            stack[top++] = start
            seen[start] = true
            var x0 = w; var y0 = h; var x1 = 0; var y1 = 0; var n = 0
            while (top > 0) {
                val i = stack[--top]
                val x = i % w
                val y = i / w
                n++
                x0 = min(x0, x); x1 = max(x1, x); y0 = min(y0, y); y1 = max(y1, y)
                if (x > 0 && dark[i - 1] && !seen[i - 1]) { seen[i - 1] = true; stack[top++] = i - 1 }
                if (x < w - 1 && dark[i + 1] && !seen[i + 1]) { seen[i + 1] = true; stack[top++] = i + 1 }
                if (y > 0 && dark[i - w] && !seen[i - w]) { seen[i - w] = true; stack[top++] = i - w }
                if (y < h - 1 && dark[i + w] && !seen[i + w]) { seen[i + w] = true; stack[top++] = i + w }
            }
            val b = Blob(x0, y0, x1, y1, n)
            if (b.h in minH..maxH && glyphShaped(b)) glyphs += b
        }
        return chains(glyphs)
    }

    private fun chains(glyphs: List<Blob>): Report {
        val sorted = glyphs.sortedBy { it.x0 }
        val used = BooleanArray(sorted.size)
        var longest = 0
        var count = 0
        for (i in sorted.indices) {
            if (used[i]) continue
            var len = 1
            var last = sorted[i]
            used[i] = true
            var j = i + 1
            while (j < sorted.size) {
                val c = sorted[j]
                val gap = c.x0 - last.x1
                if (gap > last.h * MAX_GAP) break
                if (!used[j] && gap >= -1 && follows(last, c)) {
                    used[j] = true; len++; last = c
                }
                j++
            }
            longest = max(longest, len)
            if (len >= 3) count++
        }
        return Report(longest, count, glyphs.size)
    }

    /** A glyph is about as wide as tall (never a long hatch stroke) and partly filled. */
    private fun glyphShaped(b: Blob): Boolean {
        val fill = b.pixels.toFloat() / (b.w * b.h)
        return b.w <= b.h * MAX_ASPECT && b.w >= max(1, b.h / MIN_ASPECT_DIV) && fill in MIN_FILL..MAX_FILL
    }

    /** [next] sits on [last]'s baseline with a similar height. */
    private fun follows(last: Blob, next: Blob): Boolean = abs(next.cy - last.cy) < last.h * BASELINE && next.h.toFloat() / last.h in SIMILAR

    private const val DARK = 0.45f
    private const val HALF_ALPHA = 128
    private const val MIN_PX = 4
    private const val MIN_HEIGHT = 0.012f
    private const val MAX_HEIGHT = 0.06f
    private const val MAX_ASPECT = 1.6f
    private const val MIN_ASPECT_DIV = 6
    private const val MIN_FILL = 0.22f
    private const val MAX_FILL = 0.85f
    private const val MAX_GAP = 1.1f
    private const val BASELINE = 0.35f
    private val SIMILAR = 0.6f..1.6f
    const val MIN_CHAIN = 4
}

/** An OCR add-on's verdict (the media add-ons' PP-OCR): true when it reads any text in the picture. */
fun interface TextDetector {
    suspend fun hasText(image: Raster): Boolean
}
