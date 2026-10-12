package io.trimio.engine.motion

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.trimio.engine.motion.recipe.Cue
import io.trimio.engine.motion.recipe.Family
import io.trimio.engine.motion.recipe.Fitted
import io.trimio.engine.motion.recipe.Fitter
import io.trimio.engine.motion.recipe.Look
import io.trimio.engine.motion.recipe.Stages
import io.trimio.engine.motion.score.Layout
import io.trimio.engine.motion.score.Subject
import org.jetbrains.skia.Color as SkColor
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File

/**
 * The bench the families are reviewed on: sample beats in Persian and English (crypto and
 * trading first: the owner's niche), a fitter like the compiler's, frames built straight from a
 * recipe on a stage or over a synthetic speaker, and contact sheets.
 */
object FamilyLab {
    val probe = MotionTestKit(Composition(1080, 1920, 30, 1f, androidx.compose.ui.graphics.Color.Black, Group(emptyList())))

    /** The compiler's fitter: shrink until the text fits the box in the allowed lines. */
    val fitter = Fitter { s, voice, preferred, maxW, maxH, maxLines ->
        var size = preferred
        var last: Fitted? = null
        val rtl = s.any { it in '؀'..'ۿ' }
        repeat(28) {
            val type = voice.at(size)
            val block = probe.renderer.text.layout(s, type, maxW, TextAlign.Center, rtl)
            val f = Fitted(type, block.width, block.height, block.lines.size)
            last = f
            if (block.width <= maxW + 0.5f && block.height <= maxH + 0.5f && block.lines.size <= maxLines) return@Fitter f
            size *= 0.92f
        }
        last!!
    }

    const val W = 1080
    const val H = 1920
    val subject = Subject(0.3f, 0.3f, 0.7f, 0.62f)

    /** A sample beat for [family] in Persian ([rtl]) or English, its slot in the given band. */
    fun cue(family: String, rtl: Boolean, over: Boolean, variant: Int, variety: Int = 7): Cue {
        val layout = Layout(W, H, if (over) subject else null)
        val zone = if (over) Layout.Zone.Top else Layout.Zone.Center
        val r = io.trimio.engine.motion.recipe.Recipes.named(family)!!
        val slot = layout.slot(zone, r.preferredHeight * (if (over) 1f else 1.6f), aroundSubject = over)
        val base = Cue(
            at = 0.5f, out = 6f, x = slot.x, y = slot.y, width = slot.w, height = slot.h, energy = 0.7f, overMedia = over, rtl = rtl, seed = 5,
            variant = variant, variety = variety, frameWidth = W.toFloat(), frameHeight = H.toFloat(),
        )
        return Samples.fill(family, base, rtl)
    }

    /** One frame of [family] at [t] in [look]: on its stage, or over the synthetic speaker. */
    fun frame(family: String, look: Look, cue: Cue, t: Float): Image {
        val recipe = io.trimio.engine.motion.recipe.Recipes.named(family)!!
        val built = recipe.build(cue, look, fitter)
        if (cue.overMedia) built.nodes.forEach { guard(it, look.guard) }
        val base = if (cue.overMedia) {
            listOf(
                MediaNode("speaker", W.toFloat(), H.toFloat(), transform = Transform(x = (W / 2f).anim, y = (H / 2f).anim)),
                EffectNode(Effect.Scrim(fromBottom = false, coverage = 0.36f, opacity = 0.42f.anim)),
            )
        } else {
            listOf(EffectNode(Stages.background(look, look.aurora, 1)))
        }
        val root = Group(base + built.nodes + built.overlays, name = "root")
        val comp = Composition(W, H, 30, cue.out + 0.5f, look.canvas, root)
        return MotionTestKit(comp, if (cue.overMedia) MediaSource { _, _ -> footage } else MediaSource.None).draw(t)
    }

    /** Only the graphic, on a black frame (for the safe-area check: anything not black is drawn). */
    fun alone(family: String, look: Look, cue: Cue, t: Float): Image {
        val built = io.trimio.engine.motion.recipe.Recipes.named(family)!!.build(cue, look, fitter)
        val comp = Composition(W, H, 30, cue.out + 0.5f, androidx.compose.ui.graphics.Color.Black, Group(built.nodes, name = "root"))
        return MotionTestKit(comp).draw(t)
    }

    fun guard(node: Node, g: Guard) {
        when (node) {
            is Group -> node.children.forEach { guard(it, g) }
            is TextNode -> if (!node.onBody) node.guard = g
            is CounterNode -> if (!node.onBody) node.guard = g
            else -> Unit
        }
    }

    /** A synthetic talking-head frame: a warm room, a bright window and a dark figure. */
    val footage: ImageBitmap by lazy {
        val s = Surface.makeRasterN32Premul(W, H)
        val c = s.canvas
        for (k in 0 until 48) {
            val f = k / 47f
            c.drawRect(Rect.makeXYWH(0f, k * 40f, W.toFloat(), 41f), Paint().apply { color = SkColor.makeRGB((196 - 104 * f).toInt(), (170 - 100 * f).toInt(), (140 - 82 * f).toInt()) })
        }
        c.drawRect(Rect.makeXYWH(620f, 120f, 420f, 640f), Paint().apply { color = SkColor.makeRGB(246, 242, 232) })
        val figure = Paint().apply { color = SkColor.makeRGB(48, 40, 36) }
        c.drawOval(Rect.makeXYWH(370f, 600f, 340f, 420f), figure)
        c.drawRRect(org.jetbrains.skia.RRect.makeXYWH(200f, 980f, 680f, 1000f, 220f), figure)
        s.makeImageSnapshot().toComposeImageBitmap()
    }

    /** A tile: the part of [img] around ([cx], [cy]) in a [side]-px square, scaled to [px]. */
    class Tile(val img: Image, val cx: Float, val cy: Float, val side: Float, val caption: String)

    /** Tiles in rows into one PNG ([px] per tile), with a thin caption strip under each. */
    fun sheet(rows: List<List<Tile>>, file: File, px: Int = 340) {
        val cols = rows.maxOf { it.size }
        val strip = 26
        val surface = Surface.makeRasterN32Premul(cols * px, rows.size * (px + strip))
        val c = surface.canvas
        c.clear(SkColor.makeRGB(24, 24, 24))
        val font = org.jetbrains.skia.Font(null, 15f)
        val white = Paint().apply { color = SkColor.makeRGB(220, 220, 220) }
        for ((r, row) in rows.withIndex()) for ((k, tile) in row.withIndex()) {
            val half = tile.side / 2f
            val x0 = (tile.cx - half).coerceIn(0f, W - tile.side)
            val y0 = (tile.cy - half).coerceIn(0f, H - tile.side)
            val dst = Rect.makeXYWH((k * px).toFloat(), (r * (px + strip)).toFloat(), px.toFloat(), px.toFloat())
            c.drawImageRect(tile.img, Rect.makeXYWH(x0, y0, tile.side, tile.side), dst)
            c.drawString(tile.caption, dst.left + 6f, dst.bottom + 18f, font, white)
        }
        file.parentFile.mkdirs()
        file.writeBytes(surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes)
    }

    fun families(): List<Family> = io.trimio.engine.motion.recipe.Families.all.filterIsInstance<Family>()
}
