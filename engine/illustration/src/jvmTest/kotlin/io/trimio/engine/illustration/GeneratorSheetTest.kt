package io.trimio.engine.illustration

import io.trimio.engine.illustration.library.Library
import io.trimio.engine.illustration.library.LibrarySource
import io.trimio.engine.illustration.photo.PhotoStyler
import io.trimio.engine.illustration.quality.StyleStats
import io.trimio.engine.illustration.quality.TextArtefacts
import io.trimio.engine.illustration.style.FilmLook
import kotlinx.coroutines.runBlocking
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Review harness for the generator add-on, run by hand (no model in CI):
 *  - -Pillustration.templates=<dir> writes the library's sketches of a few scenes on white (the img2img templates);
 *  - -Pillustration.generated=<dir> styles every PNG in <dir> (sd-cli output) into the film's look, checks it for
 *    text-like marks and foreign colour, and writes a sheet (raw | styled) to -Pillustration.sheets.
 */
class GeneratorSheetTest {
    private val look = FilmLook(seed = 1402)

    @Test
    fun templates() = runBlocking {
        val dir = System.getProperty("illustration.templates") ?: return@runBlocking
        val source = LibrarySource(Library.bundled())
        val scenes = mapOf(
            "man" to IllustrationRequest("a worried young man", action = "looking at his phone"),
            "safe" to IllustrationRequest("safe full of gold", objects = listOf("gold bars")),
            "shop" to IllustrationRequest("three people", place = "shop", mood = "worried"),
        )
        for ((name, r) in scenes) {
            val p = source.illustrate(r, look.copy(accentObjects = false), 512)!!
            Png.write(p.image, File(dir, "$name.png"), background = Colors.WHITE)
        }
    }

    @Test
    fun styleGenerated() {
        val dir = System.getProperty("illustration.generated") ?: return
        val out = System.getProperty("illustration.sheets") ?: return
        val cells = ArrayList<Pair<Raster, String>>()
        for (f in File(dir).listFiles { x -> x.extension == "png" }.orEmpty().sortedBy { it.name }) {
            val img = ImageIO.read(f)
            val raw = Raster(img.width, img.height, img.getRGB(0, 0, img.width, img.height, null, 0, img.width))
            val started = System.nanoTime()
            val styled = PhotoStyler.style(raw, look, 512)
            val ms = (System.nanoTime() - started) / 1_000_000
            val text = TextArtefacts.find(raw)
            val textStyled = TextArtefacts.find(styled)
            val stats = StyleStats.of(styled, look)
            println(
                "${f.name}: styled in $ms ms; text-like raw=${text.textLike} (chain ${text.longestChain}) " +
                    "styled=${textStyled.textLike} (chain ${textStyled.longestChain}); foreign=${stats.foreign} accent=${stats.accent}",
            )
            assertTrue(stats.foreign < 0.005f, "${f.name} foreign colour")
            cells += raw to "SDXS-512 raw: ${f.nameWithoutExtension}"
            cells += styled to "in the film's look"
        }
        if (cells.isNotEmpty()) Png.sheet(cells, 4, 280, File(out, "illustration-generator.png"), "On-device SDXS-512 (1 step, CPU) and the same pictures through the film's styler")
    }
}
