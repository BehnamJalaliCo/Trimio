package io.trimio.engine.motion

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.trimio.engine.motion.recipe.Craft
import io.trimio.engine.motion.recipe.Cue
import io.trimio.engine.motion.recipe.Fitted
import io.trimio.engine.motion.recipe.Fitter
import io.trimio.engine.motion.recipe.Look
import io.trimio.engine.motion.recipe.MessageRecipes
import io.trimio.engine.motion.recipe.Recipes
import io.trimio.engine.motion.score.Compiler
import io.trimio.engine.motion.score.Score
import io.trimio.engine.motion.score.Subject
import org.jetbrains.skia.Color as SkColor
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The chat thread for reported speech ("someone DM'd me and said …"), compiled from small scores
 * and rendered as stills for review: a Persian thread on a stage and over (synthetic) footage, an
 * English one on paper, and a single long message. Stills land in build/message-recipes/.
 */
class MessageRecipesTest {
    private val out = File("build/message-recipes")
    private val probe = MotionTestKit(Composition(1080, 1920, 30, 1f, androidx.compose.ui.graphics.Color.Black, Group(emptyList())))

    private val thread = listOf("بابا فلان صرافی بود، همون اولش می‌گفتی", "اسمش رو تو همون کلیپت می‌گفتی، ما می‌رفتیم ثبت‌نام می‌کردیم", ">نه عزیزم، اینجوری نیست")
    private val english = listOf("Which exchange was it?", "You could have just said the name in the clip, we would have signed up", "> No dear, it's not like that")
    private val long = listOf(
        "یکی اومده تو دایرکت می‌گه بابا فلان صرافی بود، همون اولش می‌گفتی می‌رفتیم ثبت‌نام می‌کردیم، دیگه نیازی نبود کامنت بذاریم و بریم دایرکت",
    )

    private fun json(items: List<String>) = items.joinToString(",") { "\"" + it.replace("\"", "\\\"") + "\"" }

    private fun score(look: String, bg: String, items: List<String>, label: String?, place: String) = Score.parse(
        """{"look":"$look","captions":{"show":false},"auto":false,"scenes":[{"time":0,"bg":"$bg","beats":[
           {"recipe":"chat","time":0.4,"hold":6.2,"items":[${json(items)}],${label?.let { "\"label\":\"$it\"," } ?: ""}"place":"$place"}]}]}""",
    )

    /** A synthetic talking-head frame: a warm room, a bright window and a dark figure. */
    private val footage: ImageBitmap by lazy {
        val s = Surface.makeRasterN32Premul(1080, 1920)
        val c = s.canvas
        for (k in 0 until 48) {
            val f = k / 47f
            c.drawRect(Rect.makeXYWH(0f, k * 40f, 1080f, 41f), Paint().apply { color = SkColor.makeRGB((196 - 104 * f).toInt(), (170 - 100 * f).toInt(), (140 - 82 * f).toInt()) })
        }
        c.drawRect(Rect.makeXYWH(620f, 120f, 420f, 640f), Paint().apply { color = SkColor.makeRGB(246, 242, 232) })
        val figure = Paint().apply { color = SkColor.makeRGB(48, 40, 36) }
        c.drawOval(Rect.makeXYWH(370f, 600f, 340f, 420f), figure)
        c.drawRRect(org.jetbrains.skia.RRect.makeXYWH(200f, 980f, 680f, 1000f, 220f), figure)
        s.makeImageSnapshot().toComposeImageBitmap()
    }

    private fun render(name: String, score: Score, overFootage: Boolean) {
        val input = Compiler.Input(
            score, transcript = null, duration = 7.2f, footage = if (overFootage) "speaker" else null,
            subject = if (overFootage) Subject(0.3f, 0.3f, 0.7f, 0.62f) else null,
        )
        val compiled = Compiler(probe.renderer.text).compile(input)
        val kit = MotionTestKit(compiled.composition, if (overFootage) MediaSource { _, _ -> footage } else MediaSource.None)
        val b = compiled.beats.single()
        val times = (0 until 6).map { b.at + 0.25f + it * (b.out - b.at - 0.85f) / 5f }
        kit.contactSheet(times, out.resolve("$name-sheet.png"), columns = times.size, scale = 0.3f)
        kit.png(b.out - 0.6f, out.resolve("$name.png"))
        out.resolve("$name.txt").writeText("${b.recipe} ${b.zone} ${b.at}–${b.out} [${b.left},${b.top},${b.right},${b.bottom}]")
        assertEquals("message", b.recipe)
    }

    @Test
    fun threadOnStage() = render("stage", score("noir", "grid", thread, "یه فالوور", "center"), overFootage = false)

    @Test
    fun threadOverFootage() = render("footage", score("noir", "media", thread, "یه فالوور", "top"), overFootage = true)

    @Test
    fun englishOnPaper() = render("paper", score("paper", "grid", english, "A follower", "center"), overFootage = false)

    @Test
    fun singleLongMessage() = render("long", score("lumen", "grid", long, null, "center"), overFootage = false)

    @Test
    fun bubblesLandOnTheirQuotesAndLeaveByTheOut() {
        val fitter = Fitter { s, voice, preferred, maxW, _, _ ->
            val w = minOf(maxW, s.length * preferred * 0.5f)
            Fitted(voice.at(preferred), w, preferred * 1.3f * (1 + (s.length * preferred * 0.5f / maxW).toInt()), 1)
        }
        assertEquals(MessageRecipes.Message, Recipes.named("dm"))
        assertEquals("message", Recipes.named("testimonial-chat")?.name)
        assertEquals("comment", Recipes.named("cta")?.name)
        for (rtl in listOf(true, false)) for (over in listOf(true, false)) {
            val spoken = listOf(2.0f, 3.6f, 5.2f)
            val cue = Cue(
                at = 1f, out = 7f, x = 540f, y = 900f, width = 880f, height = if (over) 410f else 700f,
                items = if (rtl) thread else english, itemTimes = spoken, label = "x", overMedia = over, rtl = rtl,
            )
            val built = MessageRecipes.Message.build(cue, Look.Noir, fitter)
            val root = built.nodes.single() as Group
            assertTrue(root.end <= cue.out + 0.05f && root.transform.opacity.at(cue.out) < 0.01f, "gone by its out")
            val items = (root.children.single { it.name == "message-thread" } as Group).children
            assertEquals(3, items.size)
            // Each bubble opens (the typing indicator morphs) exactly as its quote is said.
            items.forEachIndexed { i, n -> assertTrue(abs(n.start + 0.02f + 0.45f - (spoken[i] - Craft.LEAD)) < 0.01f, "bubble $i lands on its quote") }
            assertTrue(built.sfx.all { it.at in (cue.at - 0.2f)..cue.out })
        }
    }
}
