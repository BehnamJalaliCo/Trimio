package io.trimio.engine.motion.explainer

import io.trimio.engine.motion.MotionTestKit
import io.trimio.engine.motion.export.Delivery
import io.trimio.engine.motion.export.MotionExporter
import java.io.File
import kotlin.test.Test

/**
 * Review renders of the sample film ("three kinds of assistants"): contact sheets and, with
 * ffmpeg, a short MP4. Off unless TRIMIO_EXPLAINER names the output folder:
 *
 * TRIMIO_EXPLAINER=/tmp/x [TRIMIO_EXPLAINER_ONLY=fa-tall,en-wide] [TRIMIO_EXPLAINER_VIDEO=1] ./gradlew :engine:motion:jvmTest --tests '*ExplainerProofTest*'
 */
class ExplainerProofTest {
    private class Case(val id: String, val film: String, val w: Int, val h: Int, val palette: String? = null, val layout: String? = null, val mark: String? = null, val captions: String? = null)

    private val cases = listOf(
        Case("fa-tall", "assistants-fa", 1080, 1920),
        Case("en-wide", "assistants-en", 1920, 1080, palette = "ink-night", layout = "split", mark = "numerals"),
        Case("fa-wide", "assistants-fa", 1920, 1080, palette = "mint", layout = "header-left", mark = "rule"),
        Case("en-tall", "assistants-en", 1080, 1920, palette = "dusk", layout = "full-bleed", captions = "plain-lower"),
        Case("news-fa", "refunds-fa", 1080, 1920),
        Case("news-en-wide", "refunds-en", 1920, 1080, palette = "news-teal"),
        Case("news-en", "refunds-en", 1080, 1920, palette = "news-red"),
    )

    /** Every primitive once its ops have landed (cropped to the stage), and caught mid-morph. */
    private fun atlas(out: File, only: List<String>?) {
        for ((id, lang, palette) in listOf(Triple("atlas-fa", "fa", "paper-warm"), Triple("atlas-en", "en", "ink-night"))) {
            if (only != null && id !in only) continue
            val film = ExplainerSamples.everyOp(lang, palette)
            val o = ExplainerLab.compose(film, 1080, 1920)
            val at = System.getenv("TRIMIO_EXPLAINER_AT")?.toFloatOrNull() ?: 4.6f
            val ts = film.chapters.map { (it.start + at).toFloat() }
            val f = o.report.frame
            val crop = org.jetbrains.skia.Rect.makeLTRB(0f, f.header.bottom - 40f, 1080f, f.captionY - 60f)
            ExplainerLab.sheet(o.composition, ts, out.resolve("$id.png"), 9, System.getenv("TRIMIO_EXPLAINER_SCALE")?.toFloatOrNull() ?: 0.2f, crop)
        }
    }

    /** One moment of the sample film in every palette of the book. */
    private fun palettes(out: File) {
        val tiles = PaletteBook.ids.mapIndexed { i, id ->
            val base = ExplainerLab.film(if (i % 2 == 0) "assistants-fa" else "assistants-en")
            base.copy(art = base.art.copy(palette = id)) to (if (i % 3 == 0) 51.2f else if (i % 3 == 1) 37.6f else 21.0f)
        }
        val images = tiles.map { (film, t) -> ExplainerLab.compose(film, 1080, 1920).composition to t }
        ExplainerLab.tiles(images, out.resolve("palettes.png"), 5, 0.2f)
    }

    /** The four layout grammars, tall and wide, with their marks and caption styles. */
    private fun grammars(out: File) {
        val marks = listOf("dashes", "numerals", "rule", "dashes")
        val caps = listOf("band-pill", "band-pill", "plain-lower", "band-pill")
        val tall = StageGrammar.entries.mapIndexed { i, g ->
            val base = ExplainerLab.film(if (i % 2 == 0) "assistants-fa" else "assistants-en")
            ExplainerLab.compose(base.copy(art = base.art.copy(layout = g.id, chapterMark = marks[i], captionStyle = caps[i])), 1080, 1920).composition to 51.0f
        }
        ExplainerLab.tiles(tall, out.resolve("grammars-tall.png"), 4, 0.2f)
        val wide = StageGrammar.entries.mapIndexed { i, g ->
            val base = ExplainerLab.film(if (i % 2 == 1) "assistants-fa" else "assistants-en")
            ExplainerLab.compose(base.copy(art = base.art.copy(layout = g.id, chapterMark = marks[i], captionStyle = caps[i], palette = "slate")), 1920, 1080).composition to 37.6f
        }
        ExplainerLab.tiles(wide, out.resolve("grammars-wide.png"), 2, 0.2f)
    }

    @Test
    fun proof() {
        val out = System.getenv("TRIMIO_EXPLAINER")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        val only = System.getenv("TRIMIO_EXPLAINER_ONLY")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        val times = System.getenv("TRIMIO_EXPLAINER_TIMES")?.split(",")?.mapNotNull { it.trim().toFloatOrNull() }?.takeIf { it.isNotEmpty() }
        if (only == null || "atlas" in only.joinToString()) atlas(out, only)
        if (only == null || "palettes" in only) palettes(out)
        if (only == null || "grammars" in only) grammars(out)
        for (c in cases) {
            if (only != null && c.id !in only) continue
            val base = ExplainerLab.film(c.film)
            val art = base.art.copy(
                palette = c.palette ?: base.art.palette, layout = c.layout ?: base.art.layout,
                chapterMark = c.mark ?: base.art.chapterMark, captionStyle = c.captions ?: base.art.captionStyle,
            )
            val film = base.copy(art = art)
            val output = ExplainerLab.compose(film, c.w, c.h)
            out.resolve("${c.id}-notes.txt").apply { parentFile.mkdirs() }.writeText(output.report.notes.joinToString("\n"))
            val ts = times ?: ExplainerLab.settled(film)
            val cols = if (c.w > c.h) 6 else 8
            ExplainerLab.sheet(output.composition, ts, out.resolve("${c.id}.png"), System.getenv("TRIMIO_EXPLAINER_COLS")?.toIntOrNull() ?: cols,
                System.getenv("TRIMIO_EXPLAINER_SCALE")?.toFloatOrNull() ?: 0.16f)
            if (System.getenv("TRIMIO_EXPLAINER_VIDEO") == "1" && MotionTestKit.ffmpeg()) {
                val to = System.getenv("TRIMIO_EXPLAINER_TO")?.toFloatOrNull() ?: output.composition.duration
                val from = System.getenv("TRIMIO_EXPLAINER_FROM")?.toFloatOrNull() ?: 0f
                MotionExporter(output.composition.withFps(30), { output.media() }, Delivery.Draft.copy(gpu = false), scale = 2f / 3f)
                    .export(out.resolve("${c.id}.mp4"), null, from, to)
            }
        }
    }
}
