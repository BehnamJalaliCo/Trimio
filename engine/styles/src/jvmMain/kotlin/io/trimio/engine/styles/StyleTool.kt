package io.trimio.engine.styles

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import io.trimio.engine.render.DesktopRenderer
import io.trimio.engine.render.FrameContext
import io.trimio.engine.render.FrameRenderer
import io.trimio.engine.render.RenderFonts
import io.trimio.engine.render.RenderOptions
import io.trimio.engine.render.SampleTimelines
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Image
import org.jetbrains.skia.Surface
import java.io.File

/**
 * Desktop tool for style designers and the pack server.
 *  --preview=pack.json   render a contact sheet (3 moments × footage/audio-only)
 *  --sign=pack.json --key=private.der --key-id=ID   print a signed envelope for distribution
 */
fun main(args: Array<String>) = runBlocking {
    val opts = args.mapNotNull { a -> a.removePrefix("--").split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }.toMap()
    val out = File(opts["out"] ?: "build/style-tool").apply { mkdirs() }

    opts["sign"]?.let { path ->
        val pack = StylePackCodec.decodeBundled(File(path).readText())
        val key = File(requireNotNull(opts["key"]) { "--key=private.der is required" }).readBytes()
        println(StylePackCodec.sign(pack, requireNotNull(opts["key-id"]) { "--key-id is required" }, key))
        return@runBlocking
    }

    val pack = StylePackCodec.decodeBundled(File(requireNotNull(opts["preview"]) { "--preview=pack.json is required" }).readText())
    val sheet = File(out, "${pack.id}-sheet.png")
    sheet.writeBytes(contactSheet(pack))
    println("Rendered ${pack.nameEn} → ${sheet.absolutePath}")
}

private suspend fun contactSheet(pack: StylePack): ByteArray {
    val fonts = RenderFonts.vazirmatn()
    val desktop = DesktopRenderer(540, 960)
    val renderer = FrameRenderer(SampleTimelines.cryptoSignal().copy(styleId = pack.id), pack.spec, desktop.textMeasurer, fonts, RenderOptions(shaderScale = 0.5f))
    val footage: DrawScope.(Rect) -> Unit = { r ->
        drawRect(Brush.verticalGradient(listOf(Color(0xFF6B4F3A), Color(0xFF2E3B4E)), r.top, r.bottom))
        drawCircle(Brush.radialGradient(listOf(Color(0xFFE8C4A0), Color.Transparent), Offset(r.center.x, r.height * 0.38f), r.width * 0.35f), r.width * 0.35f, Offset(r.center.x, r.height * 0.38f))
    }
    val frames = listOf(1_900L, 2_950L, 5_700L).flatMap { t ->
        listOf(
            Image.makeFromEncoded(desktop.png(desktop.draw { renderer.render(this, FrameContext(t)) })),
            Image.makeFromEncoded(desktop.png(desktop.draw { renderer.render(this, FrameContext(t, footage = footage)) })),
        )
    }
    val gap = 12
    val surface = Surface.makeRasterN32Premul(540 * 6 + gap * 5, 960)
    surface.canvas.clear(0xFF121218.toInt())
    frames.forEachIndexed { i, img -> surface.canvas.drawImage(img, (i * (540 + gap)).toFloat(), 0f) }
    return surface.makeImageSnapshot().encodeToData()!!.bytes
}
