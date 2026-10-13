package io.trimio.engine.motion.explainer

import androidx.compose.ui.graphics.Color
import io.trimio.engine.motion.ColorAnim
import io.trimio.engine.motion.CounterNode
import io.trimio.engine.motion.Fill
import io.trimio.engine.motion.Group
import io.trimio.engine.motion.Node
import io.trimio.engine.motion.TextNode
import io.trimio.engine.motion.recipe.Box
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Image
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The explainer stage, measured: every primitive through every op in three palettes, Persian and
 * English, tall and wide; the safe area; the contrast floor; Persian digits; no overlap between
 * live elements; the same frames for the same film.
 */
class ExplainerStageTest {
    private val sizes = listOf(1080 to 1920, 1920 to 1080)
    private val palettes = listOf("paper-warm", "ink-night", "mint")

    // ------------------------------------------------------------------ films

    private fun everyOp(lang: String, palette: String, layout: String = "header-top", mark: String = "dashes", captions: String = "band-pill") =
        ExplainerSamples.everyOp(lang, palette, layout, mark, captions)

    private fun sample(lang: String) = ExplainerLab.film("assistants-$lang")

    private fun times(film: ExplainerFilm): List<Float> = film.chapters.flatMap { ch -> ch.beats.map { (it.at + 0.35).toFloat() } }

    // ------------------------------------------------------------------ tests

    @Test
    fun everyPrimitiveAndOpRenders() {
        for (lang in listOf("fa", "en")) for (palette in palettes) for ((w, h) in sizes) renderEveryOp(lang, palette, w, h)
    }

    private fun renderEveryOp(lang: String, palette: String, w: Int, h: Int) {
        val film = everyOp(lang, palette)
        val out = ExplainerLab.compose(film, w, h)
        assertTrue(out.report.elements.map { it.primitive }.toSet().containsAll(Primitive.entries), "every primitive on stage ($lang $palette ${w}x$h)")
        // Every beat mid-flight (a sample of them in the slower combinations).
        val ts = times(film).let { all -> if (palette == palettes.first()) all else all.filterIndexed { i, _ -> i % 3 == 0 } }
        ExplainerLab.frames(out.composition, 0.2f).use { f -> ts.forEach { f.draw(it) } }
    }

    @Test
    fun everyGrammarMarkAndCaptionStyleRenders() {
        for (layout in StageGrammar.entries) for ((w, h) in sizes) {
            val mark = ChapterMark.entries[(layout.ordinal) % ChapterMark.entries.size]
            val captions = CaptionStyle.entries[layout.ordinal % CaptionStyle.entries.size]
            val film = sample(if (layout.ordinal % 2 == 0) "fa" else "en").let { it.copy(art = it.art.copy(layout = layout.id, chapterMark = mark.id, captionStyle = captions.id)) }
            val out = ExplainerLab.compose(film, w, h)
            ExplainerLab.frames(out.composition, 0.2f).use { f -> for (t in listOf(1f, 20f, 37f, 55f)) f.draw(t) }
            assertInside(out, "${layout.id} ${w}x$h")
        }
    }

    @Test
    fun liveElementsNeverOverlapAndStayOnStage() {
        val films = listOf(sample("fa"), sample("en"), everyOp("fa", "paper-warm"), everyOp("en", "ink-night", layout = "split"))
        for (film in films) for ((w, h) in sizes) {
            val out = ExplainerLab.compose(film, w, h)
            assertTrue(out.report.rests.isNotEmpty())
            assertInside(out, "${film.language} ${w}x$h")
        }
    }

    private fun assertInside(out: ExplainerStage.Output, what: String) {
        val r = out.report
        val stage = r.frame.stage
        assertTrue(inside(stage, r.frame.safe), "$what: the stage is inside the safe area")
        assertTrue(!stage.overlaps(r.frame.header), "$what: the stage keeps off the header")
        for (rest in r.rests) {
            val boxes = rest.boxes.entries.toList()
            for ((name, b) in boxes) assertTrue(inside(b, stage, 0.5f), "$what: '$name' at ${rest.from}s is on the stage: $b in $stage")
            for (i in boxes.indices) for (j in i + 1 until boxes.size) {
                assertTrue(!boxes[i].value.overlaps(boxes[j].value), "$what: '${boxes[i].key}' and '${boxes[j].key}' overlap at ${rest.from}s")
            }
        }
    }

    private fun inside(b: Box, o: Box, slack: Float = 0f) = b.left >= o.left - slack && b.top >= o.top - slack && b.right <= o.right + slack && b.bottom <= o.bottom + slack

    /** Rendered: nothing is drawn outside the safe area except the caption band. */
    @Test
    fun nothingDrawsOutsideTheSafeArea() {
        val cases = listOf(
            Triple(sample("fa"), 1080, 1920), Triple(sample("en"), 1920, 1080),
            Triple(sample("fa").let { it.copy(art = it.art.copy(layout = "split", palette = "dusk")) }, 1920, 1080),
            Triple(sample("en").let { it.copy(art = it.art.copy(layout = "full-bleed", palette = "chalk")) }, 1080, 1920),
            Triple(everyOp("fa", "mint"), 1080, 1920),
        )
        val scale = 0.25f
        for ((film, w, h) in cases) {
            val out = ExplainerLab.compose(film, w, h)
            val f = out.report.frame
            val bg = PaletteBook.named(film.art.palette).background
            ExplainerLab.frames(out.composition, scale).use { frames ->
                val ts = ExplainerLab.settled(film).filterIndexed { i, _ -> i % 2 == 0 } + times(film).filterIndexed { i, _ -> i % 5 == 0 }
                for (t in ts) {
                    val px = pixels(frames.draw(t))
                    val stray = strays(px, frames.width, frames.height, scale, f, bg)
                    if (stray != null) fail("${film.language} ${film.art.layout} ${w}x$h at ${t}s: drawn outside the safe area at $stray")
                }
            }
        }
    }

    private class Px(val argb: IntArray, val w: Int, val h: Int)

    private fun pixels(img: Image): Px {
        val w = img.width
        val h = img.height
        val bmp = Bitmap()
        bmp.allocPixels(ImageInfo.makeN32(w, h, ColorAlphaType.PREMUL))
        img.readPixels(bmp, 0, 0)
        val bytes = bmp.readPixels()!!
        val argb = IntArray(w * h) { i -> val b = bytes[i * 4].toInt() and 0xFF; val g = bytes[i * 4 + 1].toInt() and 0xFF; val r = bytes[i * 4 + 2].toInt() and 0xFF; (r shl 16) or (g shl 8) or b }
        bmp.close()
        return Px(argb, w, h)
    }

    /** The first pixel outside the safe area (and off the band) that is not the paper, in frame px, or null. */
    private fun strays(px: Px, w: Int, h: Int, scale: Float, f: StageFrame, bg: Color): String? {
        val r = (bg.red * 255).toInt()
        val g = (bg.green * 255).toInt()
        val b = (bg.blue * 255).toInt()
        val safe = f.safe
        for (y in 0 until minOf(h, px.h)) for (x in 0 until minOf(w, px.w)) {
            val fx = (x + 0.5f) / scale
            val fy = (y + 0.5f) / scale
            val inSafe = fx >= safe.left && fx <= safe.right && fy >= safe.top && fy <= safe.bottom
            val inBand = fy >= f.band.top - BAND_PAD && fy <= f.band.bottom + BAND_PAD + CAPTION_PAD
            if (inSafe || inBand) continue
            val c = px.argb[y * px.w + x]
            val d = maxOf(kotlin.math.abs(((c shr 16) and 0xFF) - r), kotlin.math.abs(((c shr 8) and 0xFF) - g), kotlin.math.abs((c and 0xFF) - b))
            if (d > TOLERANCE) return "(${fx.toInt()}, ${fy.toInt()}) #${Integer.toHexString(c)} vs #${Integer.toHexString((r shl 16) or (g shl 8) or b)}"
        }
        return null
    }

    @Test
    fun textHoldsTheContrastFloor() {
        for (palette in PaletteBook.ids) for (lang in listOf("fa", "en")) {
            val film = everyOp(lang, palette)
            val out = ExplainerLab.compose(film, 1080, 1920)
            var count = 0
            walk(out.composition.root) { node ->
                val (fill, ground, label) = when (node) {
                    is TextNode -> Triple(node.fill, node.ground, node.text)
                    is CounterNode -> Triple(node.fill, node.ground, node.name)
                    else -> return@walk
                }
                ground ?: fail("$palette $lang: '$label' says nothing about what it sits on")
                val color = (fill as? Fill.Solid)?.color ?: fail("$palette $lang: '$label' is not a solid ink")
                for (c in ends(color)) {
                    val ratio = ExplainerPalette.ratio(c, ground)
                    assertTrue(ratio >= ExplainerPalette.FLOOR, "$palette $lang: '$label' (${node.name}) reads at ${"%.2f".format(ratio)}:1 on its ground $ground")
                }
                count++
            }
            assertTrue(count > 100, "$palette $lang: words were checked ($count)")
        }
    }

    @Test
    fun everyPaletteClearsTheFloor() {
        assertTrue(PaletteBook.all.size >= 8)
        for (p in PaletteBook.all) {
            assertTrue(p.accents.size in 4..6, "${p.id}: four to six accents")
            val pairs = mutableListOf(p.ink to p.background, p.muted to p.background, p.ink to p.card, p.muted to p.card, p.onPill to p.pill)
            for (i in p.accents.indices) {
                pairs += p.accentText(i) to p.background
                pairs += p.accentText(i, p.card) to p.card
                pairs += p.onAccent(i) to p.accentBody(i)
            }
            for ((a, b) in pairs) assertTrue(ExplainerPalette.ratio(a, b) >= ExplainerPalette.FLOOR, "${p.id}: $a on $b")
        }
    }

    @Test
    fun persianFilmsUsePersianDigits() {
        for (film in listOf(everyOp("fa", "sand", mark = "numerals"), sample("fa").let { it.copy(art = it.art.copy(chapterMark = "numerals")) })) {
            val out = ExplainerLab.compose(film, 1080, 1920)
            var counters = 0
            walk(out.composition.root) { node ->
                if (node is TextNode) assertTrue(node.text.none { it in '0'..'9' }, "Latin digits in '${node.text}'")
                if (node is CounterNode) {
                    counters++
                    assertTrue(node.persianDigits, "a counter in Latin digits")
                }
            }
            assertTrue(counters > 0)
        }
        val en = ExplainerLab.compose(everyOp("en", "sand", mark = "numerals"), 1080, 1920)
        walk(en.composition.root) { node -> if (node is CounterNode) assertTrue(!node.persianDigits) }
    }

    @Test
    fun sameFilmSameFrames() {
        val film = sample("fa")
        val a = ExplainerLab.stage().compose(film, 1080, 1920)
        val b = ExplainerLab.stage().compose(film, 1080, 1920)
        val ts = listOf(6.5f, 21.4f, 35.2f, 41.9f, 56.0f)
        val fa = ExplainerLab.frames(a.composition, 0.3f).use { f -> ts.map { pixels(f.draw(it)).argb } }
        val fb = ExplainerLab.frames(b.composition, 0.3f).use { f -> ts.map { pixels(f.draw(it)).argb } }
        for (i in ts.indices) assertTrue(fa[i].contentEquals(fb[i]), "frame at ${ts[i]}s differs between two runs")
        assertEquals(a.report.rests, b.report.rests)
    }

    @Test
    fun theStagePersistsWithinAChapterAndClearsBetween() {
        val out = ExplainerLab.compose(sample("en"), 1080, 1920)
        val lives = out.report.elements
        val film = sample("en")
        for ((ci, ch) in film.chapters.withIndex()) {
            val mine = lives.filter { it.chapter == ci }
            assertTrue(mine.isNotEmpty())
            for (l in mine) assertTrue(l.leave <= ch.end + 1e-3 && l.enter >= ch.start - 1e-3, "${l.element} lives inside chapter $ci")
        }
        // Within a chapter the stage builds: some moments hold more than one element.
        assertTrue(out.report.rests.any { it.boxes.size >= 2 })
        assertTrue(out.report.notes.isEmpty(), out.report.notes.joinToString())
    }

    @Test
    fun aCrowdedStageStepsTheOldestOff() {
        val rules = FilmRules.Diagram
        val beats = (0 until 7).map { i -> Beat(0.5 + i * 1.2, 1.6 + i * 1.2, "line", StageOp.Add, "e$i", Primitive.Kicker, BeatContent(text = ExplainerSamples.phrase("en", i))) }
        val film = ExplainerFilm(ArtDirection("mono", "header-top", "dashes", "none", 2L), listOf(Chapter("Crowd", 0.0, 10.0, 0, beats)), language = "en")
        val out = ExplainerLab.compose(film, 1080, 1920)
        assertTrue(out.report.rests.all { it.boxes.size <= rules.maxLive }, "never more than ${rules.maxLive} on the stage")
        assertTrue(out.report.notes.any { "stepped off" in it })
    }

    // ------------------------------------------------------------------ helpers

    private fun walk(node: Node, visit: (Node) -> Unit) {
        visit(node)
        if (node is Group) node.children.forEach { walk(it, visit) }
    }

    /** A colour's first and last state. */
    private fun ends(c: ColorAnim): List<Color> = listOf(c.at(-1e6f), c.at(1e6f))

    private companion object {
        const val TOLERANCE = 6
        const val BAND_PAD = 4f
        /** The caption pill rests on the band and rises a little above it. */
        const val CAPTION_PAD = 0f
    }
}
