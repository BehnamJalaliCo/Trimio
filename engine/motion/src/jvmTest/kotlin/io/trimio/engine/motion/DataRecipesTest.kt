package io.trimio.engine.motion

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.trimio.engine.motion.recipe.BrandLibrary
import io.trimio.engine.motion.recipe.Cue
import io.trimio.engine.motion.recipe.DataRecipes
import io.trimio.engine.motion.recipe.Look
import io.trimio.engine.motion.recipe.Recipes
import io.trimio.engine.motion.score.Compiler
import io.trimio.engine.motion.score.Score
import io.trimio.engine.motion.score.Subject
import kotlinx.coroutines.runBlocking
import org.jetbrains.skia.Color as SkColor
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The data-storytelling recipes of the crypto campaign benchmark (a deadline, proof numbers, a
 * capacity filling up, the reward voucher), compiled from small scores and rendered as stills for
 * review: on a grid stage, over (synthetic) footage of a speaker, and left-to-right on paper.
 * Stills land in build/data-recipes/.
 */
class DataRecipesTest {
    private val out = File("build/data-recipes")
    private val probe = MotionTestKit(Composition(1080, 1920, 30, 1f, androidx.compose.ui.graphics.Color.Black, Group(emptyList())))
    private val brands = runBlocking { BrandLibrary.load() }

    private fun beats(fa: Boolean, place: (String) -> String) = if (fa) {
        listOf(
            """{"recipe":"countdown","time":0.4,"hold":3.4,"value":48,"suffix":"ساعت","label":"تا پایان کمپین",""" +
                """"energy":0.85,"place":"${place("center")}"}""",
            """{"recipe":"stats","time":4.4,"hold":3.6,"items":["نفر اول","ساعت","بازدید"],"points":[1000,12,50000],"label":"کمتر از ۱۲ ساعت","place":"${place("center")}"}""",
            """{"recipe":"progress","time":8.4,"hold":3.4,"value":100,"label":"۱۰۰۰ نفر اول","energy":0.85,"place":"${place("center")}"}""",
            """{"recipe":"voucher","time":12.4,"hold":3.8,"value":350,"suffix":"تتر","label":"سرمایه اولیه","items":["Tether"],"place":"${place("center")}"}""",
        )
    } else {
        listOf(
            """{"recipe":"timer","time":0.4,"hold":3.4,"value":48,"suffix":"HOURS","label":"until the campaign ends","energy":0.6}""",
            """{"recipe":"metrics","time":4.4,"hold":3.6,"items":["views","comments"],"points":[50,5],"suffix":"K"}""",
            """{"recipe":"capacity","time":8.4,"hold":3.4,"value":72,"from":10,"label":"2,000 more places"}""",
            """{"recipe":"coupon","time":12.4,"hold":3.8,"value":350,"suffix":"USDT","label":"Starter capital","items":["usdt"]}""",
        )
    }

    private fun score(fa: Boolean, look: String, bg: String, place: (String) -> String = { it }): Score {
        val scenes = beats(fa, place).mapIndexed { i, b -> """{"time":${i * 4f},"bg":"$bg","beats":[$b]}""" }
        return Score.parse("""{"look":"$look","captions":{"show":false},"auto":false,"scenes":[${scenes.joinToString(",")}]}""")
    }

    /** A synthetic talking-head frame: a warm room, a bright window and a dark figure. */
    private val footage: ImageBitmap by lazy {
        val s = Surface.makeRasterN32Premul(1080, 1920)
        val c = s.canvas
        // A warm wall, light at the top and darker towards the floor, in bands.
        for (k in 0 until 48) {
            val f = k / 47f
            c.drawRect(Rect.makeXYWH(0f, k * 40f, 1080f, 41f), Paint().apply { color = SkColor.makeRGB((196 - 104 * f).toInt(), (170 - 100 * f).toInt(), (140 - 82 * f).toInt()) })
        }
        c.drawRect(Rect.makeXYWH(620f, 120f, 420f, 640f), Paint().apply { color = SkColor.makeRGB(246, 242, 232) })
        c.drawRect(Rect.makeXYWH(40f, 1300f, 300f, 500f), Paint().apply { color = SkColor.makeRGB(210, 205, 196) })
        val figure = Paint().apply { color = SkColor.makeRGB(48, 40, 36) }
        c.drawOval(Rect.makeXYWH(370f, 600f, 340f, 420f), figure)
        c.drawRRect(org.jetbrains.skia.RRect.makeXYWH(200f, 980f, 680f, 1000f, 220f), figure)
        s.makeImageSnapshot().toComposeImageBitmap()
    }

    private fun render(name: String, score: Score, overFootage: Boolean) {
        val input = Compiler.Input(
            score, transcript = null, duration = 16.6f, footage = if (overFootage) "speaker" else null,
            subject = if (overFootage) Subject(0.3f, 0.3f, 0.7f, 0.62f) else null,
        )
        val compiled = Compiler(probe.renderer.text, brands).compile(input)
        val kit = MotionTestKit(compiled.composition, if (overFootage) MediaSource { _, _ -> footage } else MediaSource.None)
        for (b in compiled.beats) {
            val land = b.at + 1.6f
            val times = listOf(b.at + 0.12f, b.at + 0.5f, land, b.out - 0.6f, b.out - 0.12f)
            kit.contactSheet(times, out.resolve("$name-${b.recipe}-sheet.png"), columns = times.size, scale = 0.3f)
            kit.png(b.out - 0.6f, out.resolve("$name-${b.recipe}.png"))
        }
        out.resolve("$name.txt").writeText(compiled.beats.joinToString("\n") { "${it.recipe} ${it.zone} ${it.at}–${it.out} [${it.left},${it.top},${it.right},${it.bottom}]" })
        assertEquals(4, compiled.beats.size)
    }

    @Test
    fun rendersOnStage() = render("stage", score(fa = true, look = "noir", bg = "grid"), overFootage = false)

    @Test
    fun rendersOverFootage() = render("footage", score(fa = true, look = "noir", bg = "media") { p -> if (p == "center") "top" else p }, overFootage = true)

    @Test
    fun rendersLeftToRight() = render("paper", score(fa = false, look = "paper", bg = "grid"), overFootage = false)

    @Test
    fun everyRecipeBuildsAndIsGoneByItsOut() {
        val fitter = io.trimio.engine.motion.recipe.Fitter { s, voice, preferred, maxW, _, _ ->
            val w = minOf(maxW, s.length * preferred * 0.55f)
            io.trimio.engine.motion.recipe.Fitted(voice.at(preferred * w / (s.length * preferred * 0.55f).coerceAtLeast(1f)), w, preferred, 1)
        }
        for (recipe in DataRecipes.all) {
            assertEquals(recipe, Recipes.named(recipe.name), "registered: ${recipe.name}")
            for (rtl in listOf(true, false)) for (over in listOf(true, false)) {
                val cue = Cue(
                    text = "", at = 1f, out = 5f, x = 540f, y = 900f, width = 880f, height = 420f, value = 100f, suffix = if (rtl) "ساعت" else "H",
                    label = if (rtl) "تا پایان کمپین" else "left", items = listOf("a", "b", "c"), points = listOf(1000f, 12f, 50000f),
                    marks = listOfNotNull(brands.find("tether")), overMedia = over, rtl = rtl,
                )
                val built = recipe.build(cue, Look.Noir, fitter)
                assertTrue(built.nodes.isNotEmpty(), "${recipe.name} draws")
                assertTrue(built.sfx.isNotEmpty(), "${recipe.name} sounds")
                for (node in built.nodes) {
                    assertTrue(node.end <= cue.out + 0.05f, "${recipe.name} ends by its out")
                    assertTrue(node.transform.opacity.at(cue.out) < 0.01f, "${recipe.name} has faded by its out")
                    assertTrue(node.start <= cue.at, "${recipe.name} is on screen as its beat lands")
                }
                assertTrue(built.sfx.all { it.at in (cue.at - 0.2f)..cue.out }, "${recipe.name} sounds within its cue")
            }
        }
        assertEquals("countdown", Recipes.named("timer")?.name)
        assertEquals("voucher", Recipes.named("coupon")?.name)
        assertEquals("progress", Recipes.named("capacity")?.name)
        assertEquals("stats", Recipes.named("kpis")?.name)
        assertEquals("counter", Recipes.named("stat")?.name)
    }
}
