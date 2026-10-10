package io.trimio.engine.motion

import androidx.compose.ui.text.TextLayoutResult
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
 * ([x], [glyphTop]) so the glyphs sit centred in a line box exactly `lineHeight` tall, as in CSS.
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
) {
    val width: Float get() = layout.size.width.toFloat()
    val baseline: Float get() = glyphTop + layout.firstBaseline
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

    fun style(type: TypeSpec, rtl: Boolean): TextStyle = TextStyle(
        fontFamily = fonts.family(type.role),
        fontWeight = FontWeight(type.weight.coerceIn(1, 1000)),
        fontSize = type.size.sp,
        letterSpacing = type.tracking.em,
        textDirection = if (rtl) TextDirection.Rtl else TextDirection.Ltr,
        localeList = LocaleList(if (rtl) "fa" else "en"),
    )

    fun measureLine(text: String, style: TextStyle): TextLayoutResult = measurer.measure(text, style, softWrap = false, maxLines = 1)

    fun layout(text: String, type: TypeSpec, maxWidth: Float, align: TextAlign, rtl: Boolean): TextBlock {
        val style = style(type, rtl)
        val widthCache = HashMap<String, Float>()
        fun width(s: String) = widthCache.getOrPut(s) { measureLine(s, style).size.width.toFloat() }

        val lineTexts = text.split('\n').flatMap { paragraph ->
            val words = paragraph.split(' ', '\t').filter { it.isNotEmpty() }
            if (words.isEmpty()) listOf("") else balance(words, maxWidth, ::width)
        }
        val lineBox = type.size * type.lineHeight
        var wordIndex = 0
        var charIndex = 0
        val shaped = lineTexts.mapIndexed { i, lineText ->
            val layout = measureLine(lineText, style)
            val ascent = layout.firstBaseline
            val descent = layout.size.height - ascent
            val cssBaseline = lineBox / 2f + (ascent - descent) / 2f
            val words = wordBoxes(lineText, layout, wordIndex)
            val chars = charBoxes(lineText, layout, charIndex)
            wordIndex += words.size
            charIndex += chars.size
            partition(words, type.size)
            partition(chars, type.size)
            TextLine(i, lineText, layout, 0f, i * lineBox, lineBox, i * lineBox + cssBaseline - ascent, words, chars)
        }
        val blockWidth = shaped.maxOfOrNull { it.width } ?: 0f
        val lines = shaped.map { l ->
            val free = blockWidth - l.width
            val x = when (align) {
                TextAlign.Center -> free / 2f
                TextAlign.Start -> if (rtl) free else 0f
                TextAlign.End -> if (rtl) 0f else free
            }
            TextLine(l.index, l.text, l.layout, x, l.top, l.height, l.glyphTop, l.words, l.chars)
        }
        return TextBlock(lines, blockWidth, lineBox * lines.size, type.size)
    }

    /**
     * Splits [words] into the fewest lines that fit [maxWidth], then rebalances them so the widest
     * line is as narrow as possible (CSS `text-wrap: balance`).
     */
    private fun balance(words: List<String>, maxWidth: Float, width: (String) -> Float): List<String> {
        fun span(i: Int, j: Int) = words.subList(i, j).joinToString(" ")
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
        if (words.size > MAX_BALANCED_WORDS || count <= 1) return greedy(words, maxWidth, width)
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
        if (best[count][n] == inf) return greedy(words, maxWidth, width)
        val lines = ArrayDeque<String>()
        var j = n
        for (k in count downTo 1) { val s = cut[k][j]; lines.addFirst(span(s, j)); j = s }
        return lines.toList()
    }

    private fun greedy(words: List<String>, maxWidth: Float, width: (String) -> Float): List<String> {
        val lines = mutableListOf<String>()
        var current = ""
        for (w in words) {
            val next = if (current.isEmpty()) w else "$current $w"
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
        const val ZWNJ = '\u200C'
    }
}
