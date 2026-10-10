package io.trimio.engine.motion

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.trimio.core.brand.BrandFonts
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.ui.text.style.TextDirection
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp

/** A unit's ink box and the slice of the line it owns for clipping, in line-layout coordinates. */
class UnitBox(val index: Int, val left: Float, val right: Float, var clipLeft: Float = left, var clipRight: Float = right) {
    val center: Float get() = (left + right) / 2f
}

/**
 * One laid-out line. [x]/[top] place the line box in the block; the shaped [layout] is drawn at
 * ([x], [glyphTop]) so the letters' ink sits optically centred in a line box `lineHeight` tall.
 * [baseline], [inkTop] and [inkBottom] are measured from rendered ink, not trusted from metrics.
 */
class TextLine(
    val index: Int,
    val text: String,
    val layout: TextLayoutResult,
    val x: Float,
    val top: Float,
    val height: Float,
    val glyphTop: Float,
    val words: List<UnitBox>,
    val chars: List<UnitBox>,
    val baseline: Float,
    val inkTop: Float,
    val inkBottom: Float,
) {
    val width: Float get() = layout.size.width.toFloat()
}

class TextBlock(val lines: List<TextLine>, val width: Float, val height: Float, val fontSize: Float) {
    val wordCount: Int get() = lines.sumOf { it.words.size }
    val charCount: Int get() = lines.sumOf { it.chars.size }

    fun lineOfWord(word: Int): TextLine? = lines.firstOrNull { l -> l.words.any { it.index == word } }
}

/**
 * Lays text out the way a typographer would for motion: lines are balanced (no lonely last word),
 * every line is shaped on its own so units can animate without breaking Persian joining, and each
 * word and letter gets a clip slice that partitions its line.
 */
class TextLayoutEngine(private val measurer: TextMeasurer, private val fonts: MotionFonts) {

    /**
     * The accent face is Latin-only: Persian text asked of it is set in the display face instead,
     * so a line never falls back to a system font.
     */
    fun style(type: TypeSpec, rtl: Boolean, text: String = ""): TextStyle = TextStyle(
        fontFamily = fonts.family(roleFor(type.role, text)),
        fontWeight = FontWeight(type.weight.coerceIn(1, 1000)),
        fontSize = type.size.sp,
        letterSpacing = type.tracking.em,
        textDirection = if (rtl) TextDirection.Rtl else TextDirection.Ltr,
        localeList = LocaleList(if (rtl) "fa" else "en"),
    )

    private fun roleFor(role: BrandFonts.Role, text: String) =
        if (role == BrandFonts.Role.Accent && text.any { it in '\u0600'..'\u06FF' }) BrandFonts.Role.Display else role

    /** Ink metrics of a face, as fractions of the font size from the layout top. */
    private class Ink(val baseline: Float, val top: Float, val bottom: Float)

    private val inks = HashMap<Pair<BrandFonts.Role, Int>, Ink>()

    /**
     * Some faces report line metrics far from where they draw (one brand face claims an ascent of
     * almost two ems), so the baseline and the ink box are measured by rendering reference letters.
     */
    private fun ink(role: BrandFonts.Role, weight: Int): Ink = inks.getOrPut(role to weight) {
        fun extent(s: String): Pair<Int, Int>? {
            val l = measureLine(s, style(TypeSpec(role, weight, PROBE_SIZE), rtl = true))
            val w = l.size.width + 4
            val h = l.size.height + 4
            val image = ImageBitmap(w, h)
            CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(w.toFloat(), h.toFloat())) { drawText(l, Color.White) }
            val px = IntArray(w * h)
            image.readPixels(px)
            val rows = (0 until h).filter { y -> (0 until w).any { x -> (px[y * w + x] ushr 24) > INK_ALPHA } }
            return if (rows.isEmpty()) null else rows.first() to rows.last()
        }
        val alef = extent("ا")
        val body = extent("تارگت Tg")
        if (alef == null || body == null) {
            val l = measureLine("ا", style(TypeSpec(role, weight, PROBE_SIZE), rtl = true))
            Ink(l.firstBaseline / PROBE_SIZE, 0.1f, l.size.height / PROBE_SIZE)
        } else {
            Ink((alef.second + 1) / PROBE_SIZE, minOf(alef.first, body.first) / PROBE_SIZE, (body.second + 1) / PROBE_SIZE)
        }
    }

    fun measureLine(text: String, style: TextStyle): TextLayoutResult = measurer.measure(text, style, softWrap = false, maxLines = 1)

    fun layout(text: String, type: TypeSpec, maxWidth: Float, align: TextAlign, rtl: Boolean): TextBlock {
        val style = style(type, rtl, text)
        val ink = ink(roleFor(type.role, text), type.weight)
        val widthCache = HashMap<String, Float>()
        fun width(s: String) = widthCache.getOrPut(s) { measureLine(s, style).size.width.toFloat() }

        // Some display faces ship a hairline space (Ravagh's is ~0.07 em): words would read as one.
        // Gaps are widened with extra spaces to at least a quarter em.
        val space = (width("ا ا") - width("اا")).coerceAtLeast(1f)
        val gap = " ".repeat(kotlin.math.ceil(MIN_WORD_GAP * type.size / space).toInt().coerceIn(1, MAX_SPACES))
        val lineTexts = text.split('\n').flatMap { paragraph ->
            val words = paragraph.split(' ', '\t').filter { it.isNotEmpty() }
            if (words.isEmpty()) listOf("") else balance(words, maxWidth, gap, ::width)
        }
        val lineBox = type.size * type.lineHeight
        var wordIndex = 0
        var charIndex = 0
        val shaped = lineTexts.mapIndexed { i, lineText ->
            val layout = measureLine(lineText, style)
            // Centre the ink (ascender to descender) in the line box.
            val glyphTop = i * lineBox + lineBox / 2f - (ink.top + ink.bottom) / 2f * type.size
            val words = wordBoxes(lineText, layout, wordIndex)
            val chars = charBoxes(lineText, layout, charIndex)
            wordIndex += words.size
            charIndex += chars.size
            partition(words, type.size)
            partition(chars, type.size)
            TextLine(
                i, lineText, layout, 0f, i * lineBox, lineBox, glyphTop, words, chars,
                baseline = glyphTop + ink.baseline * type.size, inkTop = glyphTop + ink.top * type.size, inkBottom = glyphTop + ink.bottom * type.size,
            )
        }
        val blockWidth = shaped.maxOfOrNull { it.width } ?: 0f
        val lines = shaped.map { l ->
            val free = blockWidth - l.width
            val x = when (align) {
                TextAlign.Center -> free / 2f
                TextAlign.Start -> if (rtl) free else 0f
                TextAlign.End -> if (rtl) 0f else free
            }
            TextLine(l.index, l.text, l.layout, x, l.top, l.height, l.glyphTop, l.words, l.chars, l.baseline, l.inkTop, l.inkBottom)
        }
        return TextBlock(lines, blockWidth, lineBox * lines.size, type.size)
    }

    /**
     * Splits [words] into the fewest lines that fit [maxWidth], then rebalances them so the widest
     * line is as narrow as possible (CSS `text-wrap: balance`).
     */
    private fun balance(words: List<String>, maxWidth: Float, gap: String, width: (String) -> Float): List<String> {
        fun span(i: Int, j: Int) = words.subList(i, j).joinToString(gap)
        if (maxWidth == Float.POSITIVE_INFINITY || width(span(0, words.size)) <= maxWidth) return listOf(span(0, words.size))
        // Fewest lines, greedily.
        var count = 0
        var i = 0
        while (i < words.size) {
            var j = i + 1
            while (j < words.size && width(span(i, j + 1)) <= maxWidth) j++
            count++
            i = j
        }
        if (words.size > MAX_BALANCED_WORDS || count <= 1) return greedy(words, maxWidth, gap, width)
        // best[k][j]: narrowest possible widest line when words[0, j) are set in k lines.
        val inf = Float.POSITIVE_INFINITY
        val n = words.size
        val best = Array(count + 1) { FloatArray(n + 1) { inf } }
        val cut = Array(count + 1) { IntArray(n + 1) }
        best[0][0] = 0f
        for (k in 1..count) for (j in 1..n) for (s in (k - 1) until j) {
            val w = width(span(s, j))
            val fits = best[k - 1][s] != inf && (w <= maxWidth || j - s == 1)
            val score = maxOf(best[k - 1][s], w)
            if (fits && score < best[k][j]) { best[k][j] = score; cut[k][j] = s }
        }
        if (best[count][n] == inf) return greedy(words, maxWidth, gap, width)
        val lines = ArrayDeque<String>()
        var j = n
        for (k in count downTo 1) { val s = cut[k][j]; lines.addFirst(span(s, j)); j = s }
        return lines.toList()
    }

    private fun greedy(words: List<String>, maxWidth: Float, gap: String, width: (String) -> Float): List<String> {
        val lines = mutableListOf<String>()
        var current = ""
        for (w in words) {
            val next = if (current.isEmpty()) w else "$current$gap$w"
            if (current.isNotEmpty() && width(next) > maxWidth) { lines += current; current = w } else current = next
        }
        if (current.isNotEmpty()) lines += current
        return lines
    }

    private fun wordBoxes(text: String, layout: TextLayoutResult, firstIndex: Int): List<UnitBox> {
        val boxes = mutableListOf<UnitBox>()
        var i = 0
        while (i < text.length) {
            if (text[i] == ' ') { i++; continue }
            val start = i
            while (i < text.length && text[i] != ' ') i++
            var left = Float.POSITIVE_INFINITY
            var right = Float.NEGATIVE_INFINITY
            for (c in start until i) {
                val b = layout.getBoundingBox(c)
                if (b.width <= 0f) continue
                left = minOf(left, b.left); right = maxOf(right, b.right)
            }
            if (left.isFinite()) boxes += UnitBox(firstIndex + boxes.size, left, right)
        }
        return boxes
    }

    private fun charBoxes(text: String, layout: TextLayoutResult, firstIndex: Int): List<UnitBox> = text.indices
        .filterNot { text[it].isWhitespace() || text[it] == ZWNJ }
        .map { layout.getBoundingBox(it) }
        .filter { it.width > 0f }
        .mapIndexed { i, b -> UnitBox(firstIndex + i, b.left, b.right) }

    /** Slices the line between neighbouring units so every pixel belongs to exactly one unit. */
    private fun partition(units: List<UnitBox>, size: Float) {
        val sorted = units.sortedBy { it.left }
        for ((k, u) in sorted.withIndex()) {
            val prev = sorted.getOrNull(k - 1)
            val next = sorted.getOrNull(k + 1)
            u.clipLeft = if (prev == null) u.left - size * 4 else (prev.right + u.left) / 2f
            u.clipRight = if (next == null) u.right + size * 4 else (u.right + next.left) / 2f
        }
    }

    private companion object {
        const val MAX_BALANCED_WORDS = 40
        const val MIN_WORD_GAP = 0.26f
        const val MAX_SPACES = 6
        const val PROBE_SIZE = 100f
        const val INK_ALPHA = 100
        const val ZWNJ = '\u200C'
    }
}
