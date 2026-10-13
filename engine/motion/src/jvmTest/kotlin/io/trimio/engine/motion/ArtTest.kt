package io.trimio.engine.motion

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.trimio.engine.motion.art.ArtPaint
import io.trimio.engine.motion.art.Colouring
import io.trimio.engine.motion.art.MotionArt
import io.trimio.engine.motion.art.MotionArts
import io.trimio.engine.motion.art.styled
import io.trimio.engine.motion.visual.VectorIcon
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Animated pictures: Lottie and animated SVG read, refused when they need what the engine does not
 * draw, and drawn at any time of their clock with Compose graphics (the code Android runs too).
 */
class ArtTest {

    private fun render(art: MotionArt, time: Float, px: Int = 100, paint: ArtPaint = ArtPaint(Color.White)): ImageBitmap {
        val image = ImageBitmap(px, px)
        val k = px / art.width
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(image), Size(px.toFloat(), px.toFloat())) {
            drawContext.transform.scale(k, k, androidx.compose.ui.geometry.Offset.Zero)
            art.draw(this, time, paint)
        }
        return image
    }

    private fun ImageBitmap.at(x: Int, y: Int): Color = toPixelMap()[x, y]

    private fun ImageBitmap.coverage(): Float {
        val m = toPixelMap()
        var n = 0
        for (y in 0 until height) for (x in 0 until width) if (m[x, y].alpha > 0.1f) n++
        return n.toFloat() / (width * height)
    }

    /** A 100×100, 30 fps, one-second animation: a red square sliding right under a blue matte strip. */
    private val slide = """
        {"v":"5.8.1","fr":30,"ip":0,"op":30,"w":100,"h":100,"layers":[
          {"ty":4,"ind":1,"ip":0,"op":30,"st":0,"ks":{"p":{"a":1,"k":[{"t":0,"s":[20,50],"i":{"x":[1],"y":[1]},"o":{"x":[0],"y":[0]}},{"t":30,"s":[80,50]}]}},
           "shapes":[{"ty":"gr","it":[{"ty":"rc","p":{"k":[0,0]},"s":{"k":[20,20]},"r":{"k":0}},{"ty":"fl","c":{"k":[1,0,0,1]},"o":{"k":100}},{"ty":"tr","p":{"k":[0,0]},"a":{"k":[0,0]},"s":{"k":[100,100]},"r":{"k":0},"o":{"k":100}}]}]},
          {"ty":4,"ind":2,"ip":0,"op":30,"st":0,"ks":{},
           "shapes":[{"ty":"el","p":{"k":[50,15]},"s":{"k":[20,20]}},{"ty":"st","c":{"k":[0,0,1,1]},"o":{"k":100},"w":{"k":4},"lc":2,"lj":2},
                     {"ty":"tm","s":{"k":0},"e":{"a":1,"k":[{"t":0,"s":[0],"i":{"x":[1],"y":[1]},"o":{"x":[0],"y":[0]}},{"t":30,"s":[100]}]},"o":{"k":0}}]}
        ]}
    """.trimIndent()

    @Test
    fun lottieIsDrawnAtTheTimeAsked() {
        val art = assertNotNull(MotionArts.read(MotionArt.FORMAT_LOTTIE, slide, loops = false))
        assertEquals(1f, art.duration, 1e-3f)
        assertTrue(art.colourful)
        val start = render(art, 0f)
        val middle = render(art, 0.5f)
        // The square starts round x = 20 and is half way (x = 50) after half a second (linear keys).
        assertTrue(start.at(20, 50).red > 0.9f, "square at its first key")
        assertTrue(start.at(50, 50).alpha < 0.1f)
        assertTrue(middle.at(50, 50).red > 0.9f, "square at the middle key")
        assertTrue(middle.at(20, 50).alpha < 0.1f)
        // The trimmed ring: nothing at the start, part of it by the middle.
        val ringStart = (0 until 100).sumOf { x -> (0 until 30).count { y -> start.at(x, y).blue > 0.5f } }
        val ringMiddle = (0 until 100).sumOf { x -> (0 until 30).count { y -> middle.at(x, y).blue > 0.5f } }
        assertEquals(0, ringStart)
        assertTrue(ringMiddle > 20, "half the ring drawn on: $ringMiddle px")
    }

    @Test
    fun aLoopingAnimationWrapsAndAOnceAnimationRests() {
        val loop = assertNotNull(MotionArts.read(MotionArt.FORMAT_LOTTIE, slide, loops = true))
        val once = assertNotNull(MotionArts.read(MotionArt.FORMAT_LOTTIE, slide, loops = false))
        assertTrue(render(loop, 1.0f).at(20, 50).red > 0.9f, "a loop starts again after its duration")
        assertTrue(render(once, 3.0f).at(79, 50).red > 0.9f, "a single play rests on its last frame")
    }

    @Test
    fun mattesAndMasksCutTheLayer() {
        // Layer 1 (a full red square) shows only through its alpha matte (layer above: a left half rectangle).
        val matted = """
            {"fr":30,"ip":0,"op":30,"w":100,"h":100,"layers":[
              {"ty":4,"ind":1,"td":1,"ip":0,"op":30,"st":0,"ks":{},"shapes":[{"ty":"rc","p":{"k":[25,50]},"s":{"k":[50,100]},"r":{"k":0}},{"ty":"fl","c":{"k":[1,1,1,1]},"o":{"k":100}}]},
              {"ty":4,"ind":2,"tt":1,"ip":0,"op":30,"st":0,"ks":{},"shapes":[{"ty":"rc","p":{"k":[50,50]},"s":{"k":[100,100]},"r":{"k":0}},{"ty":"fl","c":{"k":[1,0,0,1]},"o":{"k":100}}]},
              {"ty":4,"ind":3,"ip":0,"op":30,"st":0,"ks":{},"hasMask":true,
               "masksProperties":[{"mode":"a","inv":false,"o":{"k":100},"pt":{"k":{"c":true,"v":[[0,0],[100,0],[100,10],[0,10]],"i":[[0,0],[0,0],[0,0],[0,0]],"o":[[0,0],[0,0],[0,0],[0,0]]}}}],
               "shapes":[{"ty":"rc","p":{"k":[50,50]},"s":{"k":[100,100]},"r":{"k":0}},{"ty":"fl","c":{"k":[0,1,0,1]},"o":{"k":100}}]}
            ]}
        """.trimIndent()
        val art = assertNotNull(MotionArts.readOrWhy(MotionArt.FORMAT_LOTTIE, matted).let { (a, why) -> assertNull(why); a })
        val img = render(art, 0f)
        assertTrue(img.at(10, 50).red > 0.9f, "red inside the matte")
        assertTrue(img.at(90, 50).alpha < 0.1f, "nothing outside the matte")
        assertTrue(img.at(70, 5).green > 0.9f, "the masked green strip shows inside its mask")
        assertTrue(img.at(70, 30).alpha < 0.1f, "and nowhere else")
    }

    @Test
    fun whatTheEngineCannotDrawIsRefusedWithAReason() {
        val text = """{"fr":30,"ip":0,"op":30,"w":10,"h":10,"layers":[{"ty":5,"ks":{},"ip":0,"op":30,"st":0}]}"""
        val expression = """{"fr":30,"ip":0,"op":30,"w":10,"h":10,"layers":[{"ty":4,"ks":{"o":{"k":100,"x":"var a = 1;"}},"ip":0,"op":30,"st":0,"shapes":[]}]}"""
        val image = """{"fr":30,"ip":0,"op":30,"w":10,"h":10,"assets":[{"id":"img","p":"a.png","u":"","w":10,"h":10}],"layers":[{"ty":2,"refId":"img","ks":{},"ip":0,"op":30,"st":0}]}"""
        val threeD = """{"fr":30,"ip":0,"op":30,"w":10,"h":10,"ddd":1,"layers":[]}"""
        for ((doc, reason) in listOf(text to "text", expression to "expression", image to "image", threeD to "3D")) {
            val (art, why) = MotionArts.readOrWhy(MotionArt.FORMAT_LOTTIE, doc)
            assertNull(art)
            assertTrue(why.orEmpty().contains(reason), "refused for $reason: $why")
        }
        assertNull(MotionArts.read(MotionArt.FORMAT_LOTTIE, "not json"))
        assertNull(MotionArts.read(MotionArt.FORMAT_SVG, """<path d="M0 0"><animateMotion dur="1s" path="M0 0L1 1"/></path>"""))
    }

    /** Material Line Icons' heart (MIT): a stroke drawing itself on over 0.6 s. */
    private val heart = """<path fill="none" stroke="currentColor" stroke-dasharray="30" stroke-dashoffset="30" stroke-linecap="round" stroke-linejoin="round" stroke-width="2" d="M12 8c0 0 0 0 -0.76 -1c-0.88 -1.16 -2.18 -2 -3.74 -2c-2.49 0 -4.5 2.01 -4.5 4.5c0 0.93 0.28 1.79 0.76 2.5c0.81 1.21 8.24 9 8.24 9M12 8c0 0 0 0 0.76 -1c0.88 -1.16 2.18 -2 3.74 -2c2.49 0 4.5 2.01 4.5 4.5c0 0.93 -0.28 1.79 -0.76 2.5c-0.81 1.21 -8.24 9 -8.24 9"><animate fill="freeze" attributeName="stroke-dashoffset" dur="0.6s" values="30;0"/></path>"""

    @Test
    fun anSvgIconDrawsItselfOnInTheTint() {
        val art = assertNotNull(MotionArts.read(MotionArt.FORMAT_SVG, heart))
        assertEquals(0.6f, art.duration, 1e-3f)
        assertTrue(!art.colourful && !art.loops)
        val tint = Color(0xFFD7FF3A)
        val none = render(art, 0f, paint = ArtPaint(tint)).coverage()
        val half = render(art, 0.3f, paint = ArtPaint(tint)).coverage()
        val done = render(art, 2f, paint = ArtPaint(tint))
        assertTrue(none < 0.002f, "nothing drawn before it starts: $none")
        assertTrue(half > 0.01f && half < done.coverage(), "half drawn: $half of ${done.coverage()}")
        // Drawn in the look's tint.
        val m = done.toPixelMap()
        val ink = (0 until 100).flatMap { x -> (0 until 100).map { y -> m[x, y] } }.filter { it.alpha > 0.9f }
        assertTrue(ink.isNotEmpty() && ink.all { kotlin.math.abs(it.green - tint.green) < 0.05f && kotlin.math.abs(it.blue - tint.blue) < 0.05f })
    }

    @Test
    fun svgMasksAndMorphsFollowTheirAnimations() {
        // A disc that grows (r 0 → 10) inside a mask that hides its right half.
        val doc = """<mask id="m"><rect width="12" height="24" fill="#fff"/></mask>""" +
            """<g mask="url(#m)"><circle cx="12" cy="12" r="0" fill="currentColor">""" +
            """<animate fill="freeze" attributeName="r" dur="1s" values="0;10"/></circle></g>"""
        val art = assertNotNull(MotionArts.read(MotionArt.FORMAT_SVG, doc))
        val img = render(art, 1f, px = 96)
        assertTrue(img.at(30, 48).alpha > 0.9f, "left half of the grown disc")
        assertTrue(img.at(66, 48).alpha < 0.1f, "right half masked away")
        assertTrue(render(art, 0f, px = 96).coverage() < 0.002f)
    }

    @Test
    fun colouringFollowsTheLook() {
        val art = assertNotNull(MotionArts.read(MotionArt.FORMAT_LOTTIE, slide, loops = false))
        val tinted = render(art.styled(Colouring.Tint), 0f, paint = ArtPaint(Color(0xFF00FF00)))
        assertTrue(tinted.at(20, 50).green > 0.9f && tinted.at(20, 50).red < 0.1f, "a silhouette in the tint")
        val duo = render(art.styled(Colouring.Duotone(Color.Black, Color(0xFF0000FF))), 0f)
        val c = duo.at(20, 50)
        assertTrue(c.red < 0.1f && c.blue in 0.02f..0.9f, "red mapped onto the look's two colours by its luminance: $c")
        val mirrored = render(art.styled(mirror = true), 0f)
        assertTrue(mirrored.at(80, 50).red > 0.9f && mirrored.at(20, 50).alpha < 0.1f, "mirrored for right-to-left")
    }

    @Test
    fun anAnimatedIconPlaysInAVectorNodeOnItsOwnClock() {
        val art = assertNotNull(MotionArts.read(MotionArt.FORMAT_LOTTIE, slide, loops = false))
        val icon = VectorIcon("slide", "test", art.width, art.height, emptyList(), listOf("slide"), art)
        assertTrue(icon.animated && icon.colourful)
        val node = VectorNode(icon, 100f, transform = Transform(anchorX = 0f, anchorY = 0f)).also { it.play = Anim.tween(0f, 1f, 2f, 3f, Easing.Linear) }
        val comp = Composition(100, 100, 30, 4f, Color.Transparent, Group(listOf(node)), shutterAngle = 0f)
        val kit = MotionTestKit(comp)
        val early = kit.draw(2f).toComposeImageBitmap()
        val late = kit.draw(2.5f).toComposeImageBitmap()
        assertTrue(early.at(20, 50).red > 0.9f, "the animation's start at its play time")
        assertTrue(late.at(50, 50).red > 0.9f, "half a second later, half way")
    }

    /**
     * Every animation of the bundled starter pack (engine/autopilot files/art) is read, drawn at
     * several times without error, shows something, and draws a 256 px frame quickly.
     */
    @Test
    fun theStarterPackDrawsAndDrawsFast() {
        val file = File("../autopilot/src/commonMain/composeResources/files/art/starter.json")
        if (!file.exists()) return
        val docs = Json.parseToJsonElement(file.readText()).jsonObject.mapValues { it.value.jsonPrimitive.content }
        assertTrue(docs.size >= 20)
        val slow = mutableListOf<String>()
        for ((id, doc) in docs) {
            val format = if (id.startsWith("noto:")) MotionArt.FORMAT_LOTTIE else MotionArt.FORMAT_SVG
            val (art, why) = MotionArts.readOrWhy(format, doc)
            assertNotNull(art, "$id refused: $why")
            val seen = listOf(0.1f, art.duration / 2f, art.duration * 0.9f).maxOf { render(art, it, px = 128).coverage() }
            assertTrue(seen > 0.01f, "$id shows something ($seen)")
            val t0 = System.nanoTime()
            repeat(FRAMES) { render(art, it / 30f, px = 256) }
            val ms = (System.nanoTime() - t0) / 1e6 / FRAMES
            if (ms > SLOW_MS) slow += "$id ${"%.1f".format(ms)} ms"
            println("art $id: ${"%.1f".format(ms)} ms/frame at 256 px, ${art.duration}s")
        }
        assertTrue(slow.isEmpty(), "too slow: $slow")
    }

    private companion object {
        const val FRAMES = 10
        const val SLOW_MS = 60.0
    }
}
