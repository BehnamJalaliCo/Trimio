package io.trimio.engine.illustration

import io.trimio.engine.illustration.vector.Affine
import io.trimio.engine.illustration.vector.PathData
import io.trimio.engine.illustration.vector.Rasterizer
import io.trimio.engine.illustration.vector.Render
import io.trimio.engine.illustration.vector.Svg
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VectorTest {
    @Test
    fun parsesCompactPathNumbers() {
        assertEquals(listOf(1.5f, 0.5f, -2f, 0.001f, 3f), PathData.numbers("1.5.5-2 1e-3,3"))
        val arcs = PathData.tokenize("a1 1 0 01 2 2")
        assertEquals(listOf<Any>('a', 1f, 1f, 0f, 0f, 1f, 2f, 2f), arcs)
    }

    @Test
    fun rectangleCoverageIsItsArea() {
        val r = Rasterizer(20, 20)
        val lines = PathData(0.1f).parse("M2.5 3.25H12.5V11.75H2.5Z")
        val cov = r.fill(lines, false, Affine.Identity)
        val area = cov.sum()
        // Five sub-scanlines per row: partial rows are covered in fifths.
        assertTrue(abs(area - 10f * 8.5f) < 1.5f, "area $area")
        assertTrue(cov[5 * 20 + 5] > 0.99f)
        assertEquals(0f, cov[15 * 20 + 15])
    }

    @Test
    fun circleAndEvenOddRing() {
        val d = Svg.parse("""<g fill-rule="evenodd"><path d="M0 0h10v10H0zM3 3h4v4H3z" fill="#000"/></g>""")
        val flat = Render.paint(d, 10, 10, Affine.Identity)
        assertEquals(0, Colors.a(flat.image[5, 5]), "hole stays clear")
        assertEquals(255, Colors.a(flat.image[1, 1]))
        val c = Svg.parse("""<circle cx="10" cy="10" r="8" fill="#f00"/>""", tolerance = 0.05f)
        val cov = Rasterizer(20, 20).fill(c.shapes.single().lines, false, Affine.Identity)
        val area = cov.sum()
        assertTrue(abs(area - Math.PI.toFloat() * 64f) < 2f, "circle area $area")
    }

    @Test
    fun transformsAndGradientsResolve() {
        val d = Svg.parse(
            """<defs><linearGradient id="g"><stop offset="0" stop-color="#000"/><stop offset="1" stop-color="#fff"/></linearGradient></defs>
            <g transform="translate(10 0) scale(2)"><rect x="1" y="1" width="2" height="3" fill="url(#g)"/></g>""",
        )
        val s = d.shapes.single()
        val b = s.bounds!!
        assertEquals(12f, b.left)
        assertEquals(16f, b.right)
        assertEquals(8f, b.bottom)
        assertTrue(Colors.r(s.fill!!) in 120..135, "gradient averaged")
    }

    @Test
    fun strokesCoverTheirWidth() {
        val d = Svg.parse("""<path d="M2 10H18" stroke="#000" stroke-width="4" fill="none"/>""")
        val flat = Render.paint(d, 20, 20, Affine.Identity)
        assertTrue(Colors.a(flat.image[10, 9]) > 250)
        assertTrue(Colors.a(flat.image[10, 11]) > 250)
        assertEquals(0, Colors.a(flat.image[10, 14]))
    }
}
