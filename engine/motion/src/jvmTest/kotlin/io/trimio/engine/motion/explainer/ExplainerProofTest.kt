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

    @Test
    fun proof() {
        val out = System.getenv("TRIMIO_EXPLAINER")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        val only = System.getenv("TRIMIO_EXPLAINER_ONLY")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        val times = System.getenv("TRIMIO_EXPLAINER_TIMES")?.split(",")?.mapNotNull { it.trim().toFloatOrNull() }?.takeIf { it.isNotEmpty() }
        if (only == null || "atlas" in only.joinToString()) atlas(out, only)
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
            ExplainerLab.sheet(output.composition, ts, out.resolve("${c.id}.png"), System.getenv("TRIMIO_EXPLAINER_COLS")?.toIntOrNull() ?: cols, System.getenv("TRIMIO_EXPLAINER_SCALE")?.toFloatOrNull() ?: 0.16f)
            if (System.getenv("TRIMIO_EXPLAINER_VIDEO") == "1" && MotionTestKit.ffmpeg()) {
                val to = System.getenv("TRIMIO_EXPLAINER_TO")?.toFloatOrNull() ?: output.composition.duration
                val from = System.getenv("TRIMIO_EXPLAINER_FROM")?.toFloatOrNull() ?: 0f
                MotionExporter(output.composition.withFps(30), { io.trimio.engine.motion.MediaSource.None }, Delivery.Draft.copy(gpu = false), scale = 2f / 3f)
                    .export(out.resolve("${c.id}.mp4"), null, from, to)
            }
        }
    }
}
