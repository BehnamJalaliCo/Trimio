package io.trimio.engine.autopilot.workshop

import io.trimio.engine.motion.MediaSource
import io.trimio.engine.motion.export.MotionExporter
import io.trimio.engine.motion.style.StyleBook
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Font
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File
import kotlin.test.Test

/**
 * One contact sheet per style, for a designer's eye: the style on the first scenario of its axis
 * ([StyleAxis]), seed 1, at six moments (the hook, its graphics, captions alone, the last screen),
 * with its palette as swatches; and an index of all 28 side by side (one graphic moment each).
 * Renders stills only, so it runs on request:
 *
 * ./gradlew :engine:autopilot:jvmTest --tests '*StyleSheetTest*' -Pstyles.sheets=true [-Pstyles.only=retro-vhs,gaming]
 *
 * Sheets land in build/workshop/styles/<style>.png and build/workshop/styles/index.png.
 */
class StyleSheetTest {
    @Test
    fun contactSheetPerStyle() {
        if (System.getProperty("styles.sheets") != "true") return
        val only = System.getProperty("styles.only")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        val dir = File(Workshop.out, "styles").apply { mkdirs() }
        val picks = mutableListOf<Pair<String, Image>>()
        for ((id, scenarios) in StyleAxis.pairs) {
            if (only != null && id !in only) continue
            val style = StyleBook.of(id)!!
            val s = Workshop.scenario(scenarios.first())
            val p = Workshop.run(s, 1L, style = id)
            val c = p.compiled.composition
            val frames = MotionExporter.Frames(c, if (s.footage) Workshop.footage(s) else MediaSource.None)
            val times = moments(Workshop.keyTimes(p))
            val shots = times.map { (t, what) -> "${"%.1f".format(t)}s $what" to frames.draw(t) }
            val w = (c.width * SCALE).toInt()
            val h = (c.height * SCALE).toInt()
            val sheet = Surface.makeRasterN32Premul(w * shots.size, h + HEADER)
            sheet.canvas.clear(0xFF1A1A1A.toInt())
            sheet.canvas.drawString("${style.nameEn} · ${style.id} · ${s.id} · ${p.score.look} ${p.score.accent ?: ""}", 12f, 34f, font(26f), white)
            val swatches = listOf(style.look.canvas, style.look.ink, style.look.accent, style.look.hot, style.look.positive, style.look.muted)
            swatches.forEachIndexed { i, col ->
                sheet.canvas.drawRect(Rect.makeXYWH(12f + i * 46f, 48f, 40f, 24f), Paint().apply { color = col.toArgb() })
            }
            for ((i, shot) in shots.withIndex()) {
                val x = (i * w).toFloat()
                sheet.canvas.drawImageRect(shot.second, Rect.makeXYWH(x, HEADER.toFloat(), w.toFloat(), h.toFloat()))
                sheet.canvas.drawString(shot.first, x + 8f, HEADER + 26f, font(20f), white)
            }
            File(dir, "$id.png").writeBytes(sheet.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes)
            picks += id to (shots.getOrNull(1) ?: shots.first()).second
            println("style sheet: $id on ${s.id}: ${File(dir, "$id.png").absolutePath}")
        }
        if (picks.size > 1) index(picks, dir)
    }

    /** Six moments: the hook, up to four graphics (different recipes first), the last screen. */
    private fun moments(all: List<Pair<Float, String>>): List<Pair<Float, String>> {
        val hook = all.first()
        val end = all.last()
        val middle = all.drop(1).dropLast(1)
        val varied = middle.distinctBy { it.second }.take(MIDDLE)
        val more = middle.filter { it !in varied }.take(MIDDLE - varied.size)
        return (listOf(hook) + (varied + more).sortedBy { it.first } + end).distinct()
    }

    private fun index(picks: List<Pair<String, Image>>, dir: File) {
        val first = picks.first().second
        val w = (first.width * INDEX_SCALE).toInt()
        val h = (first.height * INDEX_SCALE).toInt()
        val rows = (picks.size + COLUMNS - 1) / COLUMNS
        val sheet = Surface.makeRasterN32Premul(w * COLUMNS, (h + LABEL) * rows)
        sheet.canvas.clear(0xFF1A1A1A.toInt())
        for ((i, pick) in picks.withIndex()) {
            val x = (i % COLUMNS * w).toFloat()
            val y = (i / COLUMNS * (h + LABEL)).toFloat()
            sheet.canvas.drawString(pick.first, x + 6f, y + 22f, font(18f), white)
            sheet.canvas.drawImageRect(pick.second, Rect.makeXYWH(x, y + LABEL, w.toFloat(), h.toFloat()))
        }
        File(dir, "index.png").writeBytes(sheet.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }

    private val white = Paint().apply { color = 0xFFFFFFFF.toInt() }

    private fun font(size: Float): Font {
        val fonts = FontMgr.default
        return Font(fonts.matchFamilyStyle("DejaVu Sans", FontStyle.BOLD) ?: fonts.legacyMakeTypeface("", FontStyle.BOLD), size)
    }

    private fun androidx.compose.ui.graphics.Color.toArgb(): Int =
        ((alpha * 255).toInt() shl 24) or ((red * 255).toInt() shl 16) or ((green * 255).toInt() shl 8) or (blue * 255).toInt()

    private companion object {
        const val SCALE = 0.3f
        const val INDEX_SCALE = 0.2f
        const val HEADER = 84
        const val LABEL = 30
        const val COLUMNS = 7
        const val MIDDLE = 4
    }
}
