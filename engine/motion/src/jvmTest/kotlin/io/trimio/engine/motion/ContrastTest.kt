// Pixel-measuring test code: flat loops read better here than split for the rule.
@file:Suppress("CyclomaticComplexMethod", "NestedBlockDepth", "LoopWithTooManyJumpStatements", "MaxLineLength")

package io.trimio.engine.motion

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.motion.score.Compiler
import io.trimio.engine.motion.score.Score
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Paint
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File
import kotlin.math.ceil
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Legibility over any footage, measured. A Persian piece (headline, counter, stamp, list and
 * spoken captions) is compiled in every look over three synthetic rooms — bright (white wall,
 * white shirt), dark (navy wall, dark clothes) and busy (saturated stripes, a checker shelf) —
 * and for every piece of text the contrast ratio of its glyphs against their immediate
 * surround (2 px to 0.08 em outside the glyph, what the eye reads an edge against) is computed
 * from the rendered pixels, before and after the contrast guard. After, every text clears 4.5:1.
 * Report and stills: build/contrast/.
 */
class ContrastTest {
    private val out = File("build/contrast")
    private val probe = MotionTestKit(Composition(1080, 1920, 30, 1f, Color.Black, Group(emptyList())))

    private fun transcript(): Transcript {
        val sentences = listOf(
            "امروز بیت‌کوین پنج درصد رشد کرد و سیگنال خرید ما به اولین تارگت رسید",
            "یکی اومده تو دایرکت می‌گه همون اولش می‌گفتی ثبت‌نام می‌کردیم",
            "کلمه تتر رو برام کامنت کن و بعد دایرکتتو چک کن",
        )
        var t = 300L
        val words = mutableListOf<Word>()
        for (sentence in sentences) {
            for (w in sentence.split(' ')) {
                val d = 170L + w.length * 38L
                words += Word(w, TimeRange(t, t + d), language = Language.Persian, emphasis = if (w.length > 5) 0.7f else 0.2f)
                t += d + 55L
            }
            t += 380L
        }
        return Transcript(Language.Persian, words)
    }

    private fun score(look: String) = Score.parse(
        """{"look":"$look","auto":false,"captions":{"show":true},"scenes":[{"time":0,"beats":[
          {"recipe":"slam","time":0.4,"hold":2.2,"text":"سیگنال خرید","place":"top"},
          {"recipe":"counter","time":3.0,"hold":2.4,"value":5,"prefix":"+","suffix":"٪","label":"رشد امروز","place":"top"},
          {"recipe":"stamp","time":5.8,"hold":1.8,"text":"دایرکت","place":"center"},
          {"recipe":"mask-rise","time":8.0,"hold":2.4,"text":"کامنت کن تتر","place":"top"}]}]}""",
    )

    /** Three rooms: what text over footage has to survive. */
    private fun room(kind: String): ImageBitmap {
        val s = Surface.makeRasterN32Premul(1080, 1920)
        val c = s.canvas
        fun rect(x: Float, y: Float, w: Float, h: Float, r: Int, g: Int, b: Int) = c.drawRect(Rect.makeXYWH(x, y, w, h), Paint().apply { color = org.jetbrains.skia.Color.makeRGB(r, g, b) })
        when (kind) {
            "bright" -> {
                rect(0f, 0f, 1080f, 1920f, 240, 238, 232)
                rect(600f, 80f, 440f, 700f, 255, 255, 252)
            }
            "dark" -> {
                rect(0f, 0f, 1080f, 1920f, 22, 30, 58)
                rect(600f, 80f, 440f, 700f, 34, 44, 80)
            }
            else -> {
                rect(0f, 0f, 1080f, 1920f, 200, 180, 150)
                val stripes = listOf(Triple(255, 196, 0), Triple(0, 120, 200), Triple(255, 255, 255), Triple(20, 20, 30), Triple(230, 60, 90))
                for (k in 0 until 36) stripes[k % stripes.size].let { (r, g, b) -> rect(k * 30f, 0f, 30f, 1920f, r, g, b) }
                for (i in 0 until 27) for (j in 0 until 48) if ((i + j) % 2 == 0) rect(i * 40f, j * 40f, 40f, 40f, 250, 250, 250)
            }
        }
        // The speaker: head and shoulders; a white shirt in the bright room.
        val skin = Paint().apply { color = org.jetbrains.skia.Color.makeRGB(196, 150, 120) }
        c.drawOval(Rect.makeXYWH(380f, 640f, 320f, 400f), skin)
        val shirt = when (kind) { "bright" -> 250; "dark" -> 30; else -> 140 }
        c.drawRRect(RRect.makeXYWH(160f, 1000f, 760f, 1000f, 240f), Paint().apply { color = org.jetbrains.skia.Color.makeRGB(shirt, shirt, shirt) })
        return s.makeImageSnapshot().toComposeImageBitmap()
    }

    @Test
    fun everyTextClearsTheFloorOverAnyFootage() {
        out.mkdirs()
        val report = StringBuilder("look   room    time  text                       before  after\n")
        val failures = mutableListOf<String>()
        var worstBefore = Float.MAX_VALUE
        var worstAfter = Float.MAX_VALUE
        for (look in listOf("noir", "paper", "lumen")) for (kind in listOf("bright", "dark", "busy")) {
            val compiled = Compiler(probe.renderer.text).compile(Compiler.Input(score(look), transcript(), footage = "room"))
            val c = compiled.composition
            val image = room(kind)
            val media = MediaSource { _, _ -> image }
            val times = compiled.beats.map { minOf(it.at + 1.1f, it.out - 0.45f) }.distinct().sorted()
            val after = measure(c, media, times)
            val saved = mutableMapOf<Node, Guard?>()
            strip(c.root, saved)
            val before = measure(c, media, times)
            saved.forEach { (n, g) -> if (n is TextNode) n.guard = g else if (n is CounterNode) n.guard = g }
            MotionTestKit(c, media).contactSheet(times.take(8), out.resolve("$look-$kind.png"), columns = minOf(8, times.size), scale = 0.3f)
            for ((key, ratio) in after) {
                val b = before[key]
                report.append("%-6s %-7s %5.2f  %-26s %6.2f  %5.2f\n".format(look, kind, key.first, key.second.take(26), b ?: Float.NaN, ratio))
                worstAfter = minOf(worstAfter, ratio)
                b?.let { worstBefore = minOf(worstBefore, it) }
                if (ratio < FLOOR) failures += "$look/$kind ${key.first}s ${key.second}: ${"%.2f".format(ratio)}"
            }
        }
        report.append("\nworst before %.2f, worst after %.2f (floor %.1f)\n".format(worstBefore, worstAfter, FLOOR))
        out.resolve("report.txt").writeText(report.toString())
        assertTrue(failures.isEmpty(), "below ${FLOOR}:1 — ${failures.joinToString("; ")}")
    }

    /** Contrast of every fully visible text at each time: (time, text) → ratio of glyph vs surround medians. */
    private fun measure(c: Composition, media: MediaSource, times: List<Float>): Map<Pair<Float, String>, Float> {
        val kit = MotionTestKit(c, media)
        val texts = mutableListOf<Node>()
        collect(c.root, texts)
        check(texts.size <= MAX_IDS) { "too many texts to tell apart: ${texts.size}" }
        val ids = texts.withIndex().associate { (i, n) -> n to i }
        val maskKit = MotionTestKit(Composition(c.width, c.height, c.fps, c.duration, Color.Black, mask(c.root, ids) as Group, c.camera, shutterAngle = 0f))
        val result = mutableMapOf<Pair<Float, String>, Float>()
        for (t in times) {
            val frame = rgba(kit.draw(t), c.width, c.height)
            val maskPx = rgba(maskKit.draw(t), c.width, c.height)
            // Only text at rest is read: a word mid-slam (moving, motion-blurred) is not yet text to read.
            val still = boxes(maskPx, c.width, c.height, texts.size)
            val earlier = boxes(rgba(maskKit.draw(t - SETTLE), c.width, c.height), c.width, c.height, texts.size)
            for ((k, ratio) in ratios(frame, maskPx, c.width, c.height, texts)) {
                val a = still[k]
                val b = earlier[k]
                if (a == null || b == null || (0 until 4).any { kotlin.math.abs(a[it] - b[it]) > 2 }) continue
                val node = texts[k]
                result[t to "${node.name}:" + ((node as? TextNode)?.text ?: "#")] = ratio
            }
        }
        return result
    }

    private fun collect(n: Node, into: MutableList<Node>) {
        when (n) {
            is Group -> n.children.forEach { collect(it, into) }
            is TextNode -> if (n.name != "glitch-ghost") into += n
            is CounterNode -> into += n
            else -> Unit
        }
    }

    /** The same tree with only its text, each piece filled with its own id colour (green 255, red = id). */
    private fun mask(n: Node, ids: Map<Node, Int>): Node? = when (n) {
        is Group -> Group(n.children.mapNotNull { mask(it, ids) }, n.transform, n.width, n.height, n.start, n.end, n.mask, name = n.name)
        is TextNode -> ids[n]?.let { k ->
            TextNode(n.text, n.type, idColor(k).fill(), n.maxWidth, n.align, n.rtl, n.animators, transform = n.transform, start = n.start, end = n.end, mask = n.mask, name = n.name)
        }
        is CounterNode -> ids[n]?.let { k ->
            CounterNode(n.value, n.decimals, n.prefix, n.suffix, n.persianDigits, n.grouping, n.type, idColor(k).fill(), transform = n.transform, start = n.start, end = n.end, mask = n.mask, name = n.name)
        }
        else -> null
    }

    /** Each text's pixel bounds (left, top, right, bottom) in a mask frame. */
    private fun boxes(mask: ByteArray, w: Int, h: Int, count: Int): Array<IntArray?> {
        val out = arrayOfNulls<IntArray>(count)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if ((mask[i * 4 + 1].toInt() and 0xFF) < 250) continue
            val k = ((mask[i * 4].toInt() and 0xFF) - ID0 + ID_STEP / 2) / ID_STEP
            if (k !in 0 until count) continue
            val b = out[k] ?: intArrayOf(x, y, x, y).also { out[k] = it }
            b[0] = minOf(b[0], x); b[1] = minOf(b[1], y); b[2] = maxOf(b[2], x); b[3] = maxOf(b[3], y)
        }
        return out
    }

    private fun idColor(k: Int) = Color(red = (ID0 + k * ID_STEP) / 255f, green = 1f, blue = 0f)

    private fun strip(n: Node, saved: MutableMap<Node, Guard?>) {
        when (n) {
            is Group -> n.children.forEach { strip(it, saved) }
            is TextNode -> { saved[n] = n.guard; n.guard = null }
            is CounterNode -> { saved[n] = n.guard; n.guard = null }
            else -> Unit
        }
    }

    private fun rgba(img: org.jetbrains.skia.Image, w: Int, h: Int): ByteArray {
        val bmp = Bitmap().apply { allocPixels(ImageInfo(w, h, ColorType.RGBA_8888, ColorAlphaType.PREMUL)) }
        img.readPixels(bmp, 0, 0)
        return bmp.readPixels()!!
    }

    /**
     * Glyph vs surround for every text in one pass, measured where legibility lives — at the
     * glyph edges: at each edge pixel, the solid glyph next to it against the mean of the pixels
     * 2 px to 0.08 em outside that hold no text. A text's ratio is the median over its edges
     * (so a highlighted word on its accent block counts against its block, not the wall). Texts
     * not fully on screen and opaque at this moment have too few solid edges and are skipped.
     */
    private fun ratios(frame: ByteArray, mask: ByteArray, w: Int, h: Int, texts: List<Node>): Map<Int, Float> {
        fun idAt(i: Int): Int {
            if ((mask[i * 4 + 1].toInt() and 0xFF) < 250) return -1
            val k = ((mask[i * 4].toInt() and 0xFF) - ID0 + ID_STEP / 2) / ID_STEP
            return if (k in texts.indices) k else -1
        }
        fun isEmpty(i: Int) = (mask[i * 4 + 1].toInt() and 0xFF) < 6
        fun solid(i: Int, k: Int) = idAt(i) == k && idAt(i - 1) == k && idAt(i + 1) == k && idAt(i - w) == k && idAt(i + w) == k
        val local = HashMap<Int, ArrayList<Float>>()
        for (y in 2 until h - 2) for (x in 2 until w - 2) {
            val i = y * w + x
            val k = idAt(i)
            if (k < 0 || solid(i, k)) continue
            // The glyph colour here: a solid pixel of the same text right next to this edge.
            val inner = listOf(i - 1, i + 1, i - w, i + w, i - w - 1, i - w + 1, i + w - 1, i + w + 1).firstOrNull { solid(it, k) } ?: continue
            val node = texts[k]
            val size = when (node) { is TextNode -> node.type.size; is CounterNode -> node.type.size; else -> 40f }
            val reach = maxOf(3, ceil(size * 0.08f).toInt())
            var sum = 0f
            var n = 0
            for (dy in -reach..reach) for (dx in -reach..reach) {
                if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) < 2) continue
                val xx = x + dx
                val yy = y + dy
                if (xx !in 0 until w || yy !in 0 until h) continue
                val j = yy * w + xx
                if (isEmpty(j)) { sum += lum(frame, j); n++ }
            }
            if (n < 4) continue
            val g = lum(frame, inner)
            val r = sum / n
            local.getOrPut(k) { ArrayList() } += (maxOf(g, r) + 0.05f) / (minOf(g, r) + 0.05f)
        }
        return local.filterValues { it.size >= MIN_PIXELS }.mapValues { (_, v) -> v.sorted()[v.size / 2] }
    }

    private fun lum(px: ByteArray, i: Int): Float {
        fun lin(b: Byte): Float { val v = (b.toInt() and 0xFF) / 255f; return if (v <= 0.04045f) v / 12.92f else ((v + 0.055f) / 1.055f).pow(2.4f) }
        return 0.2126f * lin(px[i * 4]) + 0.7152f * lin(px[i * 4 + 1]) + 0.0722f * lin(px[i * 4 + 2])
    }

    private companion object {
        const val FLOOR = 4.5f
        const val ID0 = 20
        const val ID_STEP = 6
        const val MIN_PIXELS = 40
        const val MAX_IDS = 38
        const val SETTLE = 0.05f
    }
}
