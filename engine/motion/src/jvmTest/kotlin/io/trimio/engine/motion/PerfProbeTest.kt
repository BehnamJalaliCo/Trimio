package io.trimio.engine.motion

import io.trimio.engine.motion.score.Compiler
import io.trimio.engine.motion.score.Score
import kotlin.test.Test

/** Render cost of the sample reel, printed for tracking (no assertion: machines differ). */
class PerfProbeTest {
    @Test
    fun perf() {
        val kit0 = MotionTestKit(Composition(1080, 1920, 30, 1f, androidx.compose.ui.graphics.Color.Black, Group(emptyList())))
        val sample = SampleReelTest()
        val m = SampleReelTest::class.java.getDeclaredMethod("transcript").apply { isAccessible = true }
        val f = SampleReelTest::class.java.getDeclaredField("directed").apply { isAccessible = true }
        val c = Compiler(kit0.renderer.text).compile(Compiler.Input(Score.parse(f.get(sample) as String), m.invoke(sample) as io.trimio.core.model.transcript.Transcript)).composition
        val kit = MotionTestKit(c)
        kit.draw(1f)
        val times = mutableListOf<Pair<Float, Long>>()
        var t = 0f
        while (t < c.duration) { val a = System.nanoTime(); kit.draw(t); times += t to (System.nanoTime() - a) / 1_000_000; t += 0.1f }
        println("PERF ${times.sumOf { it.second } / times.size} ms/frame; worst ${times.sortedByDescending { it.second }.take(6)}")
    }
}
