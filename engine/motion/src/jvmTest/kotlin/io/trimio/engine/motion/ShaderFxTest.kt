package io.trimio.engine.motion

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import io.trimio.engine.motion.recipe.Look
import io.trimio.engine.motion.recipe.Stages
import io.trimio.engine.motion.score.Compiler
import io.trimio.engine.motion.score.Score
import io.trimio.engine.motion.score.Subject
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import org.jetbrains.skia.Color as SkColor

/**
 * The shader families (6.4): every source compiles, every stage family renders as a stage (with
 * a dashboard on frosted glass over it) on the dark and the paper look, the light families
 * screen over footage, and the distortions run over footage with captions — stills and sheets
 * at 1080×1920 in build/shader-fx/, and the frame cost of each on the JVM in perf.txt.
 */
class ShaderFxTest {
    private val out = File("build/shader-fx")
    private val probe = MotionTestKit(Composition(1080, 1920, 30, 1f, androidx.compose.ui.graphics.Color.Black, Group(emptyList())))

    private val stages = ShaderFamily.entries.filter { !it.filter }
    private val filters = ShaderFamily.entries.filter { it.filter }

    @Test
    fun everyFamilyCompilesAndResolvesByName() {
        val fx = FxPrograms()
        for (f in ShaderFamily.entries) {
            assertNotNull(fx.of(f), "${f.id} compiles")
            assertEquals(f, ShaderFamily.named(f.id.uppercase()))
        }
        assertEquals(ShaderFamily.Leaks, ShaderFamily.named("light leak"))
        assertEquals(ShaderFamily.Halation, ShaderFamily.named("film"))
        assertEquals(null, Stages.shader(Look.Noir), "no backdrop keeps the aurora")
        assertEquals(ShaderFamily.Glow, Stages.shader(Look.Noir.copy(backdrop = "light"))?.family)
        assertEquals(null, Stages.lens(Look.Noir.copy(lens = "glow")), "a stage family is not a lens")
    }

    private fun stageScore(look: String, family: String?, lens: String? = null) = Score.parse(
        """{"look":"$look",${family?.let { "\"background\":\"$it\"," } ?: ""}${lens?.let { "\"lens\":\"$it\"," } ?: ""}"captions":{"show":false},"auto":false,
           "scenes":[{"time":0,"bg":"aurora","beats":[
             {"recipe":"ui-dashboard","time":0.4,"hold":4.2,"label":"رشد کاربران","items":["کاربر","سفارش","تبدیل"],"place":"center"}]}]}""",
    )

    private fun kit(score: Score, footage: Boolean, duration: Float = 5f): MotionTestKit {
        val input = Compiler.Input(score, transcript = null, duration = duration, footage = if (footage) "speaker" else null, subject = if (footage) Subject(0.3f, 0.3f, 0.7f, 0.62f) else null)
        val compiled = Compiler(probe.renderer.text).compile(input)
        return MotionTestKit(compiled.composition, if (footage) MediaSource { _, _ -> room } else MediaSource.None)
    }

    @Test
    fun stagesWithGlassCards() {
        for (f in stages) for (look in listOf("noir", "paper")) {
            val k = kit(stageScore(look, f.id), footage = false)
            k.png(3.2f, out.resolve("stage-${f.id}-$look.png"))
            if (look == "noir") k.contactSheet(listOf(0.2f, 0.8f, 1.6f, 2.6f, 3.6f, 4.4f), out.resolve("stage-${f.id}-sheet.png"), columns = 6, scale = 0.25f)
            if (look == "noir") {
                k.renderer.runtimeShaders = false
                k.png(3.2f, out.resolve("stage-${f.id}-fallback.png"))
            }
        }
    }

    @Test
    fun lightOverFootage() {
        for (f in listOf(ShaderFamily.Leaks, ShaderFamily.Dust, ShaderFamily.Bokeh, ShaderFamily.Glow)) {
            val look = Look.Noir
            val c = Composition(
                1080, 1920, 30, 4f, look.canvas,
                Group(
                    listOf(
                        MediaNode("speaker", 1080f, 1920f, transform = Transform(x = 540f.anim, y = 960f.anim)),
                        EffectNode(Effect.Shader(f, Stages.palette(look), overlay = true)),
                    ),
                ),
            )
            MotionTestKit(c, MediaSource { _, _ -> room }).contactSheet(listOf(0.5f, 1.5f, 2.5f, 3.5f), out.resolve("overlay-${f.id}-sheet.png"), columns = 4, scale = 0.3f)
        }
    }

    private fun footageScore(lens: String?, transition: String?) = Score.parse(
        """{"look":"noir",${lens?.let { "\"lens\":\"$it\"," } ?: ""}"captions":{"show":false},"auto":false,"scenes":[
           {"time":0,"bg":"media","beats":[{"recipe":"mask-rise","time":0.3,"hold":1.6,"text":"این کمپین مخصوص پیج من","place":"top"}]},
           {"time":2.2,"bg":"media",${transition?.let { "\"transition\":\"$it\"," } ?: ""}"beats":[{"recipe":"slam","time":2.3,"hold":1.5,"text":"۳۵۰ تتر","place":"top"}]}]}""",
    )

    @Test
    fun distortionsOverFootage() {
        for (f in filters) {
            val cut = f == ShaderFamily.Glitch || f == ShaderFamily.Ripple
            val k = kit(footageScore(if (cut) null else f.id, if (cut) f.id else null), footage = true, duration = 4.2f)
            val times = if (cut) listOf(1.9f, 2.05f, 2.15f, 2.2f, 2.3f, 2.5f) else listOf(1.0f, 2.6f)
            k.contactSheet(times, out.resolve("filter-${f.id}-sheet.png"), columns = times.size, scale = 0.3f)
            k.png(if (cut) 2.2f else 1.2f, out.resolve("filter-${f.id}.png"))
        }
    }

    /** Milliseconds per 1080×1920 frame (mean of [n] after a warm-up). */
    private fun cost(k: MotionTestKit, from: Float, n: Int = 8): Double {
        k.draw(from).close()
        val t0 = System.nanoTime()
        for (i in 0 until n) k.draw(from + i * 0.1f).close()
        return (System.nanoTime() - t0) / 1e6 / n
    }

    @Test
    fun framePerformance() {
        val lines = mutableListOf("frame cost on the JVM render (1080x1920, Skia raster, ms/frame, mean of 8)")
        val plain = cost(kit(stageScore("noir", null), footage = false), 3f)
        lines += "baseline aurora stage + dashboard: %.1f".format(plain)
        for (f in stages) {
            val k = kit(stageScore("noir", f.id), footage = false)
            val gpu = cost(k, 3f)
            k.renderer.runtimeShaders = false
            val cpu = cost(k, 3f)
            lines += "stage ${f.id} + glass + dashboard: %.1f (fallback %.1f)".format(gpu, cpu)
        }
        val plainFootage = kit(footageScore(null, null), footage = true, duration = 4.2f)
        lines += "baseline footage + text: %.1f at 1.0 s, %.1f at 2.1 s".format(cost(plainFootage, 1f, 4), cost(plainFootage, 2.1f, 4))
        for (f in filters) {
            val cut = f == ShaderFamily.Glitch || f == ShaderFamily.Ripple
            val k = kit(footageScore(if (cut) null else f.id, if (cut) f.id else null), footage = true, duration = 4.2f)
            val at = if (cut) 2.1f else 1f
            val gpu = cost(k, at, 4)
            k.renderer.runtimeShaders = false
            lines += "filter ${f.id} over footage: %.1f (fallback %.1f)".format(gpu, cost(k, at, 4))
        }
        out.mkdirs()
        out.resolve("perf.txt").writeText(lines.joinToString("\n"))
        println(lines.joinToString("\n"))
        assertTrue(lines.size == 2 + stages.size + 1 + filters.size)
    }

    /** A bright, busy synthetic room with a dark figure (no real footage in the repository). */
    private val room: ImageBitmap by lazy {
        val s = Surface.makeRasterN32Premul(1080, 1920)
        val c = s.canvas
        for (k in 0 until 48) {
            val f = k / 47f
            c.drawRect(Rect.makeXYWH(0f, k * 40f, 1080f, 41f), Paint().apply { color = SkColor.makeRGB((236 - 150 * f).toInt(), (214 - 140 * f).toInt(), (190 - 110 * f).toInt()) })
        }
        val stripes = listOf(SkColor.makeRGB(255, 196, 0), SkColor.makeRGB(0, 170, 220), SkColor.makeRGB(255, 255, 255), SkColor.makeRGB(230, 60, 90))
        for (k in 0 until 18) c.drawRect(Rect.makeXYWH(k * 60f, 60f, 30f, 480f), Paint().apply { color = stripes[k % stripes.size] })
        for (i in 0 until 6) for (j in 0 until 4) c.drawRect(Rect.makeXYWH(560f + i * 80f, 120f + j * 120f, 72f, 110f), Paint().apply { color = SkColor.makeRGB(252, 250, 244) })
        val figure = Paint().apply { color = SkColor.makeRGB(48, 40, 36) }
        c.drawOval(Rect.makeXYWH(370f, 640f, 340f, 420f), figure)
        c.drawRRect(org.jetbrains.skia.RRect.makeXYWH(200f, 1020f, 680f, 1000f, 220f), figure)
        s.makeImageSnapshot().toComposeImageBitmap()
    }
}
