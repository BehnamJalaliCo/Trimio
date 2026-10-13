package io.trimio.engine.motion.explainer

import io.trimio.engine.motion.Group
import io.trimio.engine.motion.Node
import io.trimio.engine.motion.VectorNode
import io.trimio.engine.motion.visual.VectorIcon
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Visual keywords: the stage's own glyphs, the injected art library, then a geometric shape; hand-drawn strokes are seeded. */
class ExplainerGlyphsTest {
    private val rocket = VectorIcon("rocket-ship", "test", 24f, 24f, emptyList(), listOf("spaceship"))
    private val library = IconResolver { q -> rocket.takeIf { "spaceship" in q } }

    @Test
    fun keywordsResolveInOrder() {
        assertTrue(Glyphs.resolve("writing", library) is Glyph.Line, "a curated glyph first")
        assertEquals(Glyph.Picture(rocket), Glyphs.resolve("spaceship", library), "then the art library")
        assertTrue(Glyphs.resolve("qzxv", library) is Glyph.Line, "a geometric shape when nothing matches")
        assertEquals(Glyphs.resolve("qzxv", IconResolver.None), Glyphs.resolve("qzxv", IconResolver.None))
    }

    @Test
    fun libraryPicturesReachTheStage() {
        val film = ExplainerFilm(
            ArtDirection("paper-warm", "header-top", "dashes", "band-pill", 3L),
            listOf(
                Chapter(
                    "Tiles", 0.0, 6.0, 0,
                    listOf(Beat(0.5, 2.0, "line", StageOp.Add, "t", Primitive.IconTiles, BeatContent(items = listOf(Item("Launch", visual = "spaceship"), Item("Write", visual = "writing"))))),
                ),
            ),
            language = "en",
        )
        val out = ExplainerLab.stage(library).compose(film, 1080, 1920)
        var pictures = 0
        fun walk(n: Node) {
            if (n is VectorNode && n.icon === rocket) pictures++
            if (n is Group) n.children.forEach(::walk)
        }
        walk(out.composition.root)
        assertTrue(pictures > 0, "the library's picture is on the tile")
    }

    @Test
    fun handDrawnStrokesAreSeeded() {
        assertEquals(Scribbles.tangle(42L, 400f, 200f), Scribbles.tangle(42L, 400f, 200f))
        assertTrue(Scribbles.tangle(42L, 400f, 200f) != Scribbles.tangle(43L, 400f, 200f))
        assertTrue(Scribbles.tangle(7L, 400f, 200f).all { (x, y) -> x in 0f..400f && y in 0f..200f })
    }
}
