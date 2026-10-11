package io.trimio.engine.motion

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.trimio.engine.motion.recipe.MediaRecipes
import io.trimio.engine.motion.recipe.Recipes
import io.trimio.engine.motion.score.Compiler
import io.trimio.engine.motion.score.Score
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.skia.Color as SkColor

/**
 * The creator's own media in the motion language: a logo as the brand-mark tile, a product photo
 * as a tilted premium card, screenshots in a phone and a browser frame, a clip as picture-in-picture
 * and as a full-frame cut-in, and a site's address bar typing in. Each is compiled from a small
 * score, must draw the creator's own picture (not the placeholder), and lands as a still in
 * build/media-recipes/ for review. All pictures are synthetic.
 */
class MediaRecipesTest {
    private val out = File("build/media-recipes")
    private val text = MotionTestKit(Composition(1080, 1920, 30, 1f, androidx.compose.ui.graphics.Color.Black, Group(emptyList()))).renderer.text

    private fun picture(w: Int, h: Int, draw: (org.jetbrains.skia.Canvas) -> Unit): ImageBitmap {
        val s = Surface.makeRasterN32Premul(w, h)
        draw(s.canvas)
        return s.makeImageSnapshot().toComposeImageBitmap()
    }

    private fun paint(c: Int) = Paint().apply { color = c; isAntiAlias = true }

    /** A transparent PNG-style logo: an orange ring and a bar. */
    private val logo = picture(400, 400) { c ->
        c.drawCircle(200f, 200f, 150f, paint(SkColor.makeRGB(255, 106, 0)))
        c.drawCircle(200f, 200f, 95f, Paint().apply { blendMode = org.jetbrains.skia.BlendMode.CLEAR })
        c.drawRect(Rect.makeXYWH(170f, 40f, 60f, 320f), paint(SkColor.makeRGB(20, 20, 30)))
    }

    /** A product shot: a bottle on a warm studio sweep. */
    private val product = picture(800, 1000) { c ->
        for (k in 0 until 50) c.drawRect(Rect.makeXYWH(0f, k * 20f, 800f, 21f), paint(SkColor.makeRGB(240 - k, 214 - k, 190 - k)))
        c.drawRRect(org.jetbrains.skia.RRect.makeXYWH(300f, 330f, 200f, 520f, 40f), paint(SkColor.makeRGB(40, 90, 70)))
        c.drawRect(Rect.makeXYWH(350f, 230f, 100f, 110f), paint(SkColor.makeRGB(25, 25, 25)))
        c.drawRect(Rect.makeXYWH(320f, 520f, 160f, 140f), paint(SkColor.makeRGB(245, 240, 230)))
    }

    /** An app screen: header, cards and a button. */
    private val phone = picture(590, 1280) { c ->
        c.drawRect(Rect.makeXYWH(0f, 0f, 590f, 1280f), paint(SkColor.makeRGB(246, 247, 250)))
        c.drawRect(Rect.makeXYWH(0f, 0f, 590f, 220f), paint(SkColor.makeRGB(36, 99, 235)))
        for (k in 0 until 4) c.drawRRect(org.jetbrains.skia.RRect.makeXYWH(36f, 270f + k * 210f, 518f, 180f, 24f), paint(SkColor.WHITE))
        c.drawRRect(org.jetbrains.skia.RRect.makeXYWH(36f, 1140f, 518f, 100f, 50f), paint(SkColor.makeRGB(16, 185, 129)))
    }

    /** A wide dashboard. */
    private val wide = picture(1600, 1000) { c ->
        c.drawRect(Rect.makeXYWH(0f, 0f, 1600f, 1000f), paint(SkColor.WHITE))
        c.drawRect(Rect.makeXYWH(0f, 0f, 300f, 1000f), paint(SkColor.makeRGB(17, 24, 39)))
        for (k in 0 until 6) c.drawRect(Rect.makeXYWH(380f + k * 190f, 900f - k * 110f, 120f, k * 110f + 40f), paint(SkColor.makeRGB(99, 102, 241)))
    }

    /** A clip: a sunset sky whose sun rises with time. */
    private fun clip(t: Float) = picture(960, 540) { c ->
        for (k in 0 until 27) c.drawRect(Rect.makeXYWH(0f, k * 20f, 960f, 21f), paint(SkColor.makeRGB(250 - k * 5, 120 + k * 3, 60 + k * 4)))
        c.drawCircle(480f, 420f - t * 60f, 90f, paint(SkColor.makeRGB(255, 236, 160)))
    }

    private val media = MediaSource { id, t ->
        when (id) {
            "logo" -> logo
            "product" -> product
            "phone" -> phone
            "wide" -> wide
            "clip" -> clip(t)
            else -> null
        }
    }

    private fun compile(beat: String, bg: String = "aurora") = Compiler(text).compile(
        Compiler.Input(Score.parse("""{"look":"noir","captions":{"show":false},"auto":false,"scenes":[{"time":0,"bg":"$bg","beats":[$beat]}]}"""), null, duration = 4f),
    )

    private fun medias(node: Node): List<MediaNode> = when (node) {
        is Group -> node.children.flatMap(::medias)
        is MediaNode -> listOf(node)
        else -> emptyList()
    }

    /** Mean absolute difference of two frames, 0–255, sampled. */
    private fun difference(a: org.jetbrains.skia.Image, b: org.jetbrains.skia.Image): Float {
        val pa = org.jetbrains.skia.Bitmap.makeFromImage(a)
        val pb = org.jetbrains.skia.Bitmap.makeFromImage(b)
        var sum = 0L
        var n = 0
        for (y in 0 until a.height step 16) for (x in 0 until a.width step 16) {
            val ca = pa.getColor(x, y)
            val cb = pb.getColor(x, y)
            sum += kotlin.math.abs(SkColor.getR(ca) - SkColor.getR(cb)) + kotlin.math.abs(SkColor.getG(ca) - SkColor.getG(cb)) + kotlin.math.abs(SkColor.getB(ca) - SkColor.getB(cb))
            n++
        }
        return sum / (n * 3f)
    }

    @Test
    fun everyAssetFormDrawsTheCreatorsOwnPicture() {
        val cases = listOf(
            "logo-mark" to """{"recipe":"logo-mark","time":0.4,"hold":3,"media":"logo","aspect":1,"label":"Nike","place":"center"}""",
            "product" to """{"recipe":"product","time":0.4,"hold":3,"media":"product","aspect":0.8,"label":"سرم ویتامین سی","place":"center"}""",
            "screenshot-phone" to """{"recipe":"screenshot","time":0.4,"hold":3,"media":"phone","aspect":0.46,"place":"center"}""",
            "screenshot-browser" to """{"recipe":"screenshot","time":0.4,"hold":3,"media":"wide","aspect":1.6,"label":"nobitex.ir","place":"center"}""",
            "pip" to """{"recipe":"pip","time":0.4,"hold":3,"media":"clip","aspect":1.78,"place":"top"}""",
            "b-roll" to """{"recipe":"b-roll","time":0.4,"hold":3,"media":"clip","aspect":1.78,"place":"center"}""",
        )
        for ((name, beat) in cases) {
            val compiled = compile(beat, bg = if (name == "b-roll") "plain" else "aurora")
            val id = Regex("\"media\":\"(\\w+)\"").find(beat)!!.groupValues[1]
            assertTrue(medias(compiled.composition.root).any { it.source == id }, "$name draws media '$id'")
            val with = MotionTestKit(compiled.composition, media)
            val without = MotionTestKit(compiled.composition)
            val diff = difference(with.draw(1.8f), without.draw(1.8f))
            assertTrue(diff > 2f, "$name shows the creator's picture, not the placeholder (difference $diff)")
            with.png(1.8f, File(out, "$name.png"))
        }
        MotionTestKit(compile(cases[1].second).composition, media).contactSheet(listOf(0.3f, 0.6f, 0.9f, 1.4f, 2.4f, 3.3f), File(out, "product-sheet.png"), columns = 6)
    }

    @Test
    fun aSiteTypesInItsAddressBar() {
        assertEquals("url", Recipes.named("url-bar")?.name)
        assertEquals("b-roll", Recipes.named("broll")?.name)
        val compiled = compile("""{"recipe":"url","time":0.4,"hold":3,"text":"nobitex.ir","place":"top"}""", bg = "grid")
        fun texts(n: Node): List<TextNode> = when (n) {
            is Group -> n.children.flatMap(::texts)
            is TextNode -> listOf(n)
            else -> emptyList()
        }
        assertTrue(texts(compiled.composition.root).any { it.text == "nobitex.ir" && !it.rtl }, "the domain as written, left to right")
        val kit = MotionTestKit(compiled.composition)
        kit.contactSheet(listOf(0.4f, 0.7f, 1.0f, 1.6f), File(out, "url-sheet.png"), columns = 4)
        kit.png(1.8f, File(out, "url.png"))
        assertTrue(MediaRecipes.SHOWS_MEDIA.all { Recipes.named(it) != null }, "every media recipe is registered")
    }
}
