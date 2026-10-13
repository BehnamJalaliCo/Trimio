package io.trimio.engine.illustration

import io.trimio.engine.illustration.photo.PhotoStyler
import io.trimio.engine.illustration.quality.StyleStats
import io.trimio.engine.illustration.style.FilmLook
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PhotoTest {
    /** A dark-red disc and a yellow square on a plain light background. */
    private fun photo(): Raster {
        val r = Raster.filled(300, 200, Colors.argb(255, 230, 228, 222))
        for (y in 0 until 200) {
            for (x in 0 until 300) {
                val dx = x - 100
                val dy = y - 100
                if (dx * dx + dy * dy < 60 * 60) r[x, y] = Colors.argb(255, 150, 30, 40)
                if (x in 190..260 && y in 60..130) r[x, y] = Colors.argb(255, 250, 200, 0)
            }
        }
        return r
    }

    @Test
    fun plainBackgroundIsCutOut() {
        val cut = PhotoStyler.cutout(photo(), 1f)
        assertTrue(cut.separated)
        assertTrue(cut.alpha[100 * 300 + 100] > 0.9f, "subject kept")
        assertTrue(cut.alpha[5 * 300 + 5] < 0.1f, "background removed")
    }

    @Test
    fun busyBackgroundFadesInAnOval() {
        val rnd = java.util.Random(1)
        val busy = Raster(200, 200, IntArray(200 * 200) { Colors.argb(255, rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)) })
        val cut = PhotoStyler.cutout(busy, 1f)
        assertFalse(cut.separated)
        assertTrue(cut.alpha[100 * 200 + 100] > 0.99f && cut.alpha[0] < 0.01f)
    }

    @Test
    fun photoComesOutInTheFilmsColours() {
        val look = FilmLook(seed = 3)
        val styled = PhotoStyler.style(photo(), look, 300)
        val s = StyleStats.of(styled, look)
        // The red disc turns to graphite; the yellow square keeps the accent.
        assertTrue(s.foreign < 0.005f, "foreign ${s.foreign}")
        assertTrue(s.accent > 0.02f, "accent ${s.accent}")
    }
}
