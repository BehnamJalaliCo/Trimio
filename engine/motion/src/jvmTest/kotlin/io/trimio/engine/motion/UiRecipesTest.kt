package io.trimio.engine.motion

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.trimio.engine.motion.recipe.Cue
import io.trimio.engine.motion.recipe.Fitted
import io.trimio.engine.motion.recipe.Fitter
import io.trimio.engine.motion.recipe.Look
import io.trimio.engine.motion.recipe.Recipes
import io.trimio.engine.motion.recipe.UiRecipes
import io.trimio.engine.motion.score.Compiler
import io.trimio.engine.motion.score.Score
import io.trimio.engine.motion.score.Subject
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.jetbrains.skia.Color as SkColor

/**
 * B-roll UI simulations and the icon scene (6.5), compiled from small scores and rendered at
 * 1080×1920: each on a dark stage (with a shader stage and frosted glass), on paper, and over
 * (synthetic) footage. Stills and sheets land in build/ui-recipes/. Text on the interfaces is
 * on its own body: every ink must clear 4.5:1 against the body it sits on.
 */
class UiRecipesTest {
    private val out = File("build/ui-recipes")
    private val probe = MotionTestKit(Composition(1080, 1920, 30, 1f, Color.Black, Group(emptyList())))

    private fun json(items: List<String>) = items.joinToString(",") { "\"" + it.replace("\"", "\\\"") + "\"" }

    private val feed = listOf("کمپین جدید فعال شد", "هزار نفر اول در کمتر از دوازده ساعت", "سیصد و پنجاه تتر برای هر کاربر")
    private val code = listOf("// first 1000 users", "fun claim(user: User) {", "    val bonus = 350", "    if (user.isNew) send(bonus, \"USDT\")", "    return ok()", "}")

    /** Each recipe's beat: (name, extra JSON fields). */
    private val beats = listOf(
        "ui-feed" to """"label":"خانه","items":[${json(feed)}]""",
        "ui-code" to """"label":"campaign.kt","items":[${json(code)}]""",
        "ui-dashboard" to """"label":"داشبورد کمپین","value":50000,"items":["بازدید","کامنت","تبدیل"],"points":[2,3,2.6,4.1,5,4.6,6.8,8.4]""",
        "ui-chat" to """"label":"صرافی","items":["ظرفیت جدید باز شد","۳۵۰ تتر ووچر برای شما","فقط ۴۸ ساعت فرصت دارید"]""",
        "ui-browser" to """"text":"ثبت‌نام در کمپین","label":"exchange-campaign.com","items":["ثبت‌نام"]""",
        "ui-scene" to """"icon":"phone>calendar+check","text":"ثبت‌نام تا ۴۸ ساعت","items":["bell","gift","user"]""",
    )

    private fun score(look: String, bg: String, recipe: String, fields: String, place: String, background: String? = null) = Score.parse(
        """{"look":"$look",${background?.let { "\"background\":\"$it\"," } ?: ""}"captions":{"show":false},"auto":false,"scenes":[{"time":0,"bg":"$bg","beats":[
           {"recipe":"$recipe","time":0.4,"hold":4.6,$fields,"place":"$place"}]}]}""",
    )

    private fun render(name: String, score: Score, footage: Boolean): Compiler.Output {
        val input = Compiler.Input(score, transcript = null, duration = 5.6f, footage = if (footage) "speaker" else null, subject = if (footage) Subject(0.3f, 0.3f, 0.7f, 0.62f) else null)
        val compiled = Compiler(probe.renderer.text).compile(input)
        val kit = MotionTestKit(compiled.composition, if (footage) MediaSource { _, _ -> room } else MediaSource.None)
        val b = compiled.beats.single()
        val times = (0 until 6).map { b.at + 0.2f + it * (b.out - b.at - 0.6f) / 5f }
        kit.contactSheet(times, out.resolve("$name-sheet.png"), columns = times.size, scale = 0.3f)
        kit.png(b.out - 0.5f, out.resolve("$name.png"))
        return compiled
    }

    @Test
    fun everyRecipeOnStagesAndFootage() {
        for ((recipe, fields) in beats) {
            val stage = render("$recipe-noir-glow", score("noir", "grid", recipe, fields, "center", background = "glow"), footage = false)
            assertEquals(recipe, stage.beats.single().recipe)
            render("$recipe-paper", score("paper", "grid", recipe, fields, "center"), footage = false)
            render("$recipe-lumen-liquid", score("lumen", "aurora", recipe, fields, "center", background = "liquid"), footage = false)
            render("$recipe-footage", score("noir", "media", recipe, fields, "top"), footage = true)
        }
    }

    @Test
    fun namesResolveAndChatThreadsReuseTheMessageRecipe() {
        assertEquals("ui-feed", Recipes.named("app screen")?.name)
        assertEquals("ui-code", Recipes.named("code-editor")?.name)
        assertEquals("ui-dashboard", Recipes.named("analytics")?.name)
        assertEquals("ui-browser", Recipes.named("website")?.name)
        assertEquals("ui-chat", Recipes.named("notifications")?.name)
        assertEquals("ui-scene", Recipes.named("icon-scene")?.name)
        val fitter = Fitter { s, voice, preferred, maxW, _, _ -> Fitted(voice.at(preferred), minOf(maxW, s.length * preferred * 0.5f), preferred * 1.3f, 1) }
        val cue = Cue(at = 1f, out = 6f, x = 540f, y = 900f, width = 900f, height = 700f, items = listOf("سلام", "> نه عزیزم"))
        val built = UiRecipes.Chat.build(cue, Look.Noir, fitter)
        assertEquals("message", (built.nodes.single() as Group).name)
    }

    /** Ink on its own body: every text an interface draws clears 4.5:1 against the surface under it. */
    @Test
    fun interfaceTextIsReadableOnItsBody() {
        val fitter = Fitter { s, voice, preferred, maxW, _, _ -> Fitted(voice.at(preferred), minOf(maxW, s.length * preferred * 0.5f), preferred * 1.3f, 1) }
        for (look in Look.all) for (over in listOf(false, true)) for (r in UiRecipes.all.filter { it.name != "ui-scene" }) {
            val cue = Cue(
                text = "ثبت‌نام", at = 1f, out = 6f, x = 540f, y = 900f, width = 900f, height = 800f, label = "label", value = 1200f,
                items = listOf("یک", "دو", "سه"), overMedia = over,
            )
            val texts = mutableListOf<Pair<Color, String>>()
            fun walk(n: Node) {
                when (n) {
                    is Group -> n.children.forEach(::walk)
                    is TextNode -> (n.fill as? Fill.Solid)?.let { texts += it.color.at(5f) to n.name }
                    is CounterNode -> (n.fill as? Fill.Solid)?.let { texts += it.color.at(5f) to n.name }
                    else -> Unit
                }
            }
            r.build(cue, look, fitter).nodes.forEach(::walk)
            val dark = Contrast.luminance(look.canvas) < 0.4f
            val body = if (dark) Color(0xFF15161A) else Color.White
            for ((ink, name) in texts.filter { !it.second.startsWith("page-cta") }) {
                val ratio = Contrast.ratio(Contrast.luminance(ink.compositeOver(body)), Contrast.luminance(body))
                assertTrue(ratio >= 4.5f, "${r.name} ${look.name} $name: ${"%.2f".format(ratio)}")
            }
        }
    }

    private fun Color.compositeOver(bg: Color) = Color(
        red * alpha + bg.red * (1 - alpha), green * alpha + bg.green * (1 - alpha), blue * alpha + bg.blue * (1 - alpha), 1f,
    )

    private val room: ImageBitmap by lazy {
        val s = Surface.makeRasterN32Premul(1080, 1920)
        val c = s.canvas
        for (k in 0 until 48) {
            val f = k / 47f
            c.drawRect(Rect.makeXYWH(0f, k * 40f, 1080f, 41f), Paint().apply { color = SkColor.makeRGB((236 - 150 * f).toInt(), (214 - 140 * f).toInt(), (190 - 110 * f).toInt()) })
        }
        val stripes = listOf(SkColor.makeRGB(255, 196, 0), SkColor.makeRGB(0, 170, 220), SkColor.makeRGB(255, 255, 255), SkColor.makeRGB(230, 60, 90))
        for (k in 0 until 18) c.drawRect(Rect.makeXYWH(k * 60f, 60f, 30f, 480f), Paint().apply { color = stripes[k % stripes.size] })
        val figure = Paint().apply { color = SkColor.makeRGB(48, 40, 36) }
        c.drawOval(Rect.makeXYWH(370f, 640f, 340f, 420f), figure)
        c.drawRRect(org.jetbrains.skia.RRect.makeXYWH(200f, 1020f, 680f, 1000f, 220f), figure)
        s.makeImageSnapshot().toComposeImageBitmap()
    }
}
