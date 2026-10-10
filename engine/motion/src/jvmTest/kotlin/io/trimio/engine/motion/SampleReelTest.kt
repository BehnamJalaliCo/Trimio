package io.trimio.engine.motion

import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.motion.score.Compiler
import io.trimio.engine.motion.score.Score
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The phase-3 benchmark piece: a Persian crypto-signal reel, audio only. One score is the kind a
 * small model writes (short, loose); the other is empty, so everything comes from the compiler.
 */
class SampleReelTest {

    /** Synthetic speech timing: ~0.3 s per word, longer words take longer, pauses after sentences. */
    private fun transcript(): Transcript {
        val sentences = listOf(
            "امروز بیت‌کوین پنج درصد رشد کرد" to listOf(0.2f, 0.9f, 0.3f, 0.95f, 0.5f, 0.1f),
            "و سیگنال خرید ما به اولین تارگت رسید." to listOf(0f, 0.7f, 0.65f, 0.1f, 0f, 0.2f, 0.85f, 0.2f),
            "قیمت الان شصت و هشت هزار دلاره." to listOf(0.3f, 0.1f, 0.6f, 0f, 0.6f, 0.6f, 0.4f),
            "تا آخر ببین، تارگت بعدی رو بهت میگم." to listOf(0.1f, 0.2f, 0.3f, 0.9f, 0.5f, 0f, 0.2f, 0.4f),
        )
        var t = 350L
        val words = mutableListOf<Word>()
        for ((sentence, stress) in sentences) {
            for ((i, w) in sentence.split(' ').withIndex()) {
                val d = 170L + w.length * 38L
                words += Word(w, TimeRange(t, t + d), language = Language.Persian, emphasis = stress.getOrElse(i) { 0f })
                t += d + 55L
            }
            t += 380L
        }
        return Transcript(Language.Persian, words)
    }

    private val directed = """
        Here is the score:
        ```json
        {"look":"noir","bpm":118,
         "scenes":[
          {"from":0,"camera":"push-in","beats":[
            {"recipe":"slam","text":"امروز بیت‌کوین","emphasis":["بیت‌کوین"],"energy":0.95},
            {"recipe":"counter","text":"پنج درصد","value":5,"prefix":"+","suffix":"٪","label":"رشد امروز","place":"top"}]},
          {"from":6,"transition":"whip","beats":[
            {"recipe":"mask-rise","text":"سیگنال خرید ما","emphasis":["سیگنال خرید"]},
            {"recipe":"icon","icon":"target","text":"تارگت","place":"top"}]},
          {"from":14,"transition":"flash","bg":"grid","beats":[
            {"recipe":"chart","label":"BTC / USD","value":68000,"points":[61,62.5,61.8,64,63.2,66.1,65.4,68],"place":"center"},
            {"recipe":"ticker","label":"BTC","value":5.2,"place":"top"}]},
          {"from":21,"transition":"zoom","beats":[
            {"recipe":"stack","text":"تارگت بعدی رو بهت میگم","emphasis":["تارگت"]}]}
         ]}
        ```
    """.trimIndent()

    private fun render(score: Score, name: String) {
        val kitText = MotionTestKit(Composition(1080, 1920, 30, 1f, androidx.compose.ui.graphics.Color.Black, Group(emptyList())))
        val compiled = Compiler(kitText.renderer.text).compile(Compiler.Input(score, transcript()))
        val out = File("build/motion")
        out.resolve("$name.txt").apply { parentFile.mkdirs() }.writeText(
            buildString {
                appendLine("beats:")
                compiled.beats.forEach { appendLine("  %-12s %-6s %5.2f–%5.2f  %s".format(it.recipe, it.zone, it.at, it.out, it.text)) }
                appendLine("sfx: " + compiled.sfx.joinToString { "%.2f %s".format(it.at, it.kind) })
                appendLine("notes:")
                compiled.notes.forEach { appendLine("  $it") }
            },
        )
        val kit = MotionTestKit(compiled.composition)
        val d = compiled.composition.duration
        kit.contactSheet((0 until 16).map { 0.4f + it * (d - 0.6f) / 15f }, out.resolve("$name-sheet.png"), columns = 8, scale = 0.2f)
        System.getProperty("motion.frames")?.split(',')?.forEach { kit.png(it.toFloat(), out.resolve("$name-at-$it.png")) }
        if (MotionTestKit.ffmpeg() && System.getProperty("motion.video") != "false") kit.mp4(out.resolve("$name.mp4"))
        assertTrue(compiled.beats.isNotEmpty())
    }

    @Test
    fun directedScore() = render(Score.parse(directed), "reel-directed")

    @Test
    fun automaticOnly() = render(Score(), "reel-auto")
}
