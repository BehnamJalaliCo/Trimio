package io.trimio.engine.motion.explainer

import io.trimio.engine.motion.CounterNode
import io.trimio.engine.motion.Fill
import io.trimio.engine.motion.Group
import io.trimio.engine.motion.MediaNode
import io.trimio.engine.motion.Node
import io.trimio.engine.motion.ShapeNode
import io.trimio.engine.motion.TextNode
import io.trimio.engine.motion.recipe.Box
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ImageInfo
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The illustrated-news grammar against its own rules ([FilmRules.News]): fast scenes joined by circle
 * wipes, pushes and peels; at most one device; words and devices inside the safe area while the hero
 * may rise from beyond the bottom edge; nothing standing still for long; readable words, Persian
 * digits, the same frames for the same film; and every primitive through every op in this grammar too.
 */
class ExplainerNewsTest {
    private val rules = FilmRules.News
    private fun sample(lang: String) = ExplainerLab.film("refunds-$lang")

    @Test
    fun theSampleKeepsTheNewsRules() {
        for (lang in listOf("fa", "en")) for ((w, h) in listOf(1080 to 1920, 1920 to 1080)) {
            val film = sample(lang)
            val out = ExplainerLab.compose(film, w, h)
            val r = out.report
            assertEquals(FilmGrammar.IllustratedNews, r.rules.grammar)
            assertTrue(r.notes.isEmpty(), "$lang ${w}x$h: ${r.notes}")
            // Pacing: every scene within the grammar's span.
            for (ch in film.chapters) assertTrue((ch.end - ch.start) in rules.sceneMin.toDouble()..rules.sceneMax.toDouble())
            // Transitions: from the grammar's set, and not one kind all along.
            val tr = r.transitions.drop(1)
            assertTrue(tr.all { it in rules.transitions }, "$tr")
            assertTrue(tr.toSet().size >= 2, "the scenes are joined in more than one way: $tr")
            assertZones(out, "$lang ${w}x$h")
        }
    }

    /** At most one device at rest; words and devices inside the safe area; heroes inside it but for the bottom edge. */
    private fun assertZones(out: ExplainerStage.Output, what: String) {
        val r = out.report
        val safe = r.frame.safe
        val kinds = r.elements.associate { (it.chapter to it.element) to FilmRules.zone(it.primitive) }
        for (rest in r.rests) {
            val devices = rest.boxes.keys.count { kinds[rest.chapter to it] == FilmRules.Zone.Device }
            assertTrue(devices <= rules.maxDevices, "$what: $devices devices at once in scene ${rest.chapter}")
            for ((name, b) in rest.boxes) {
                val bleeds = name in r.bleed
                val inside = b.left >= safe.left - 0.5f && b.right <= safe.right + 0.5f && b.top >= safe.top - 0.5f && (bleeds || b.bottom <= safe.bottom + 0.5f)
                assertTrue(inside, "$what: '$name' in scene ${rest.chapter} leaves the safe area: $b in $safe")
            }
            val boxes = rest.boxes.values.toList()
            for (i in boxes.indices) for (j in i + 1 until boxes.size) assertTrue(!boxes[i].overlaps(boxes[j]), "$what: overlap in scene ${rest.chapter}")
        }
        // Zones never overlap within a scene either: words above devices above the hero.
        val byScene = r.rests.groupBy { it.chapter }
        for ((ci, rests) in byScene) {
            val all: List<Pair<String, Box>> = rests.flatMap { it.boxes.entries.map { e -> e.key to e.value } }.distinctBy { it.first }
            for (i in all.indices) for (j in i + 1 until all.size) {
                val a = all[i]
                val b = all[j]
                val sameZone = kinds[ci to a.first] == kinds[ci to b.first]
                if (!sameZone) assertTrue(!a.second.overlaps(b.second), "$what: '${a.first}' and '${b.first}' collide in scene $ci")
            }
        }
    }

    @Test
    fun nothingStandsStillForLong() {
        val film = sample("fa")
        val out = ExplainerLab.compose(film, 1080, 1920)
        val step = 0.25f
        val ts = generateSequence(0.1f) { it + step }.takeWhile { it < out.composition.duration - 0.3f }.toList()
        val frames = ExplainerLab.frames(out.composition, 0.15f).use { f -> ts.map { pixels(f.draw(it)) } }
        var still = 0f
        for (i in 1 until frames.size) {
            val moved = frames[i].indices.count { k -> frames[i][k] != frames[i - 1][k] } > frames[i].size / 2000
            still = if (moved) 0f else still + step
            assertTrue(still <= rules.maxStill, "the picture stands still for ${still}s before ${ts[i]}s")
        }
    }

    private fun pixels(img: org.jetbrains.skia.Image): IntArray {
        val bmp = Bitmap()
        bmp.allocPixels(ImageInfo.makeN32(img.width, img.height, ColorAlphaType.PREMUL))
        img.readPixels(bmp, 0, 0)
        val bytes = bmp.readPixels()!!
        bmp.close()
        return IntArray(bytes.size / 4) { i -> (bytes[i * 4].toInt() and 0xFF) or ((bytes[i * 4 + 1].toInt() and 0xFF) shl 8) or ((bytes[i * 4 + 2].toInt() and 0xFF) shl 16) }
    }

    @Test
    fun everyPrimitiveAndOpRendersAsNews() {
        for (lang in listOf("fa", "en")) for ((w, h) in listOf(1080 to 1920, 1920 to 1080)) {
            val base = ExplainerSamples.everyOp(lang, "news-gold")
            val film = base.copy(art = base.art.copy(grammar = FilmGrammar.IllustratedNews.id))
            val out = ExplainerLab.compose(film, w, h)
            assertTrue(out.report.elements.map { it.primitive }.toSet().containsAll(Primitive.entries))
            assertZones(out, "every-op $lang ${w}x$h")
            val ts = film.chapters.flatMap { ch -> ch.beats.map { (it.at + 0.35).toFloat() } }.filterIndexed { i, _ -> i % 2 == 0 }
            ExplainerLab.frames(out.composition, 0.2f, out.media()).use { f -> ts.forEach { f.draw(it) } }
        }
    }

    @Test
    fun scenesArriveByTheirTransitions() {
        val out = ExplainerLab.compose(sample("en"), 1080, 1920)
        val names = HashSet<String>()
        walk(out.composition.root) { names += it.name }
        val tr = out.report.transitions
        if (SceneTransition.Circle in tr) assertTrue("circle-wipe" in names)
        if (SceneTransition.Peel in tr) assertTrue("peel-flap" in names && "scene-peel" in names)
        // A circle leaves its colour as the next scene's ground.
        val film = sample("en")
        val ci = tr.indexOf(SceneTransition.Circle)
        assertTrue(ci > 0)
        val accent = PaletteBook.named(film.art.palette).accent(film.chapters[ci].accent)
        var grounds = 0
        walk(out.composition.root) { n -> if (n is ShapeNode && n.name == "paper" && ((n.fill as? Fill.Solid)?.color?.at(0f) == accent)) grounds++ }
        assertTrue(grounds > 0, "the circle's accent became a scene's ground")
    }

    @Test
    fun heroesAreStyledIllustrationsOrSketchedGlyphs() {
        val film = sample("fa")
        val withArt = ExplainerLab.compose(film, 1080, 1920)
        var media = 0
        walk(withArt.composition.root) { n -> if (n is MediaNode && n.name == "hero-art") { media++; assertTrue(n.grade.ink != null, "the sketch treatment") } }
        assertTrue(media >= 3)
        val bare = ExplainerLab.compose(film, 1080, 1920, IllustrationSource.None)
        var sketches = 0
        walk(bare.composition.root) { n -> if (n.name == "sketch" || n.name == "hero-art") sketches++ }
        assertTrue(sketches >= 3, "without art the hero is drawn by hand")
    }

    @Test
    fun newsWordsReadAndUsePersianDigits() {
        for (palette in listOf("news-gold", "news-red", "news-teal", "paper-warm", "ink-night")) for (lang in listOf("fa", "en")) {
            val base = sample(lang)
            val out = ExplainerLab.compose(base.copy(art = base.art.copy(palette = palette)), 1080, 1920)
            walk(out.composition.root) { node ->
                val (fill, ground, label) = when (node) {
                    is TextNode -> Triple(node.fill, node.ground, node.text)
                    is CounterNode -> Triple(node.fill, node.ground, node.name)
                    else -> return@walk
                }
                ground ?: fail("$palette: '$label' has no ground")
                val c = (fill as Fill.Solid).color
                for (color in listOf(c.at(-1e6f), c.at(1e6f))) {
                    assertTrue(ExplainerPalette.ratio(color, ground) >= ExplainerPalette.FLOOR, "$palette $lang: '$label' at ${ExplainerPalette.ratio(color, ground)}:1")
                }
                if (lang == "fa" && node is TextNode) assertTrue(node.text.none { it in '0'..'9' }, "Latin digits in '${node.text}'")
                if (lang == "fa" && node is CounterNode) assertTrue(node.persianDigits)
            }
        }
    }

    @Test
    fun brandColoursLeadWhenGiven() {
        val base = sample("en")
        val film = base.copy(art = base.art.copy(accent = "#7A3CFF", dark = "#101010"))
        val pal = FilmLook.of(film).pal
        assertEquals(ExplainerPalette.hex("#7A3CFF"), pal.accent(0))
        assertEquals(ExplainerPalette.hex("#101010"), pal.ink)
        ExplainerLab.compose(film, 1080, 1920)
    }

    @Test
    fun aSlowSceneIsNotedForTheScreenwriter() {
        val base = sample("en")
        val slow = base.copy(chapters = base.chapters.mapIndexed { i, ch -> if (i == 0) ch.copy(end = ch.end + 4.0) else ch.copy(start = ch.start + 4.0, end = ch.end + 4.0,
            beats = ch.beats.map { it.copy(at = it.at + 4.0, until = it.until + 4.0) }) })
        val out = ExplainerLab.compose(slow, 1080, 1920)
        assertTrue(out.report.notes.any { "scene 0" in it }, out.report.notes.joinToString())
    }

    @Test
    fun sameNewsFilmSameFrames() {
        val film = sample("fa")
        val ts = listOf(1.2f, 3.1f, 5.6f, 6.9f, 8.4f, 12.5f)
        val a = ExplainerLab.compose(film, 1080, 1920)
        val b = ExplainerLab.compose(film, 1080, 1920)
        val fa = ExplainerLab.frames(a.composition, 0.25f, a.media()).use { f -> ts.map { pixels(f.draw(it)) } }
        val fb = ExplainerLab.frames(b.composition, 0.25f, b.media()).use { f -> ts.map { pixels(f.draw(it)) } }
        for (i in ts.indices) assertTrue(fa[i].contentEquals(fb[i]), "frame at ${ts[i]}s differs")
    }

    private fun walk(node: Node, visit: (Node) -> Unit) {
        visit(node)
        if (node is Group) node.children.forEach { walk(it, visit) }
    }
}
