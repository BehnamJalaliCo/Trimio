package io.trimio.engine.motion

import io.trimio.engine.motion.recipe.Look
import io.trimio.engine.motion.recipe.Transitions
import java.io.File
import kotlin.test.Test

/**
 * Contact sheets of every family for review: a row per style language (noir in Persian, paper in
 * English, lumen in Persian over the speaker, and one more language per family), a column per
 * layout, and a last row of each choreography caught mid-entrance. Off unless TRIMIO_SHEETS names
 * the output folder (it renders a few hundred frames):
 *
 * TRIMIO_SHEETS=/tmp/sheets [TRIMIO_SHEETS_ONLY=ranking,ring] ./gradlew :engine:motion:jvmTest --tests '*FamilySheetsTest*'
 */
class FamilySheetsTest {
    private val extra = listOf("finance-terminal", "playful-pop", "retro-vhs", "luxury-gold", "news-broadcast", "swiss-grid", "neon-cyber", "bold-brutalist", "calm-wellness", "tech-product")

    @Test
    fun sheets() {
        val out = System.getenv("TRIMIO_SHEETS")?.takeIf { it.isNotBlank() }?.let(::File) ?: return
        val only = System.getenv("TRIMIO_SHEETS_ONLY")?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
        for ((k, f) in FamilyLab.families().withIndex()) {
            if (only != null && f.name !in only) continue
            val styles = listOf(Triple("noir", true, false), Triple("paper", false, false), Triple("lumen", true, true), Triple(extra[k % extra.size], true, false))
            val rows = styles.mapIndexed { r, (style, rtl, over) ->
                val look = Look.named(style)
                (0 until f.layouts).map { layout ->
                    val variant = layout + (r % f.moves.size) * f.layouts
                    val cue = FamilyLab.cue(f.name, rtl, over, variant)
                    val side = maxOf(cue.width, cue.height) + 120f
                    FamilyLab.Tile(FamilyLab.frame(f.name, look, cue, cue.at + 2.2f), cue.x, cue.y, side, "$style L$layout ${f.moves[r % f.moves.size]}")
                }
            }
            val motion = f.moves.indices.map { m ->
                val cue = FamilyLab.cue(f.name, true, false, m * f.layouts)
                val side = maxOf(cue.width, cue.height) + 120f
                FamilyLab.Tile(FamilyLab.frame(f.name, Look.Noir, cue, cue.at + 0.28f), cue.x, cue.y, side, "noir ${f.moves[m]} @0.3s")
            }
            FamilyLab.sheet(rows + listOf(motion), out.resolve("motion-${f.name}.png"))
        }
        if (only == null || "caption-marks" in only) marks(out)
        if (only == null || "transitions" in only) cuts(out)
    }

    /** The caption annotations: every mark on a Persian caption, in three styles, mid-line and landed. */
    private fun marks(out: File) {
        val marks = listOf("block", "ink", "underline", "circle", "scribble", "half", "double", "strike")
        val rows = listOf("noir", "paper", "lumen").map { style ->
            val look = Look.named(style)
            marks.map { m ->
                val cue = io.trimio.engine.motion.recipe.Cue(
                    text = "این استراتژی سود واقعی داره", at = 0.5f, out = 4f, x = 540f, y = 1500f, width = 885f, height = 270f,
                    emphasis = setOf(2), mark = m, rtl = true, variety = 3,
                )
                val built = io.trimio.engine.motion.recipe.TextRecipes.PopCaptions.build(cue, look, FamilyLab.fitter)
                val root = Group(listOf(EffectNode(io.trimio.engine.motion.recipe.Stages.background(look, look.aurora, 1))) + built.nodes)
                val img = MotionTestKit(Composition(1080, 1920, 30, 5f, look.canvas, root)).draw(2.4f)
                FamilyLab.Tile(img, 540f, 1500f, 700f, "$style $m")
            }
        }
        FamilyLab.sheet(rows, out.resolve("motion-caption-marks.png"), px = 220)
    }

    /** The new cuts, caught before, on and after the cut, between a speaker scene and a graphic stage. */
    private fun cuts(out: File) {
        val rows = Transitions.KINDS.map { kind ->
            listOf(-0.12f, 0f, 0.1f, 0.3f).map { dt ->
                val overlays = mutableListOf<Node>()
                Transitions.overlay(kind, 1f, Look.Noir, 1080f, 1920f, overlays, mutableListOf())
                val before = MediaNode("speaker", 1080f, 1920f, transform = Transform(x = 540f.anim, y = 960f.anim), end = 1f)
                val after = EffectNode(io.trimio.engine.motion.recipe.Stages.background(Look.Noir, Look.Noir.aurora, 1), start = 1f)
                val comp = Composition(1080, 1920, 30, 2f, Look.Noir.canvas, Group(listOf(before, after) + overlays))
                FamilyLab.Tile(MotionTestKit(comp, MediaSource { _, _ -> FamilyLab.footage }).draw(1f + dt), 540f, 960f, 1080f, "$kind ${if (dt >= 0f) "+" else ""}${dt}s")
            }
        }
        FamilyLab.sheet(rows, out.resolve("motion-transitions.png"), px = 240)
    }
}
