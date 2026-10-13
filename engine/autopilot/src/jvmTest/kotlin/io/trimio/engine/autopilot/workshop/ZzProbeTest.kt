package io.trimio.engine.autopilot.workshop

import io.trimio.engine.autopilot.Shows
import java.io.File
import kotlin.test.Test

class ZzProbeTest {
    @Test
    fun probe() {
        val out = StringBuilder()
        for (id in listOf("fa-cafe-no-captions", "fa-cooking-kuku")) {
            val s = Workshop.scenarios().first { it.id == id }
            for (on in listOf(false, true)) {
                Shows.enabled = on
                val p = Workshop.run(s, 1L)
                out.appendLine("== $id on=$on look=${p.score.look}")
                for (sc in p.score.scenes) out.appendLine("scene ${sc.from} ${sc.bg}: " + sc.beats.joinToString { "${it.recipe}@${it.at}-${it.until}/${it.hold} '${it.text}' items=${it.items}" })
                out.appendLine("placed: " + p.compiled.beats.filter { it.recipe != "pop-captions" }.joinToString { "${it.recipe}#${it.variant}@${"%.1f".format(it.at)}" })
            }
        }
        Shows.enabled = true
        File("/tmp/claude-0/-home-user-Trimio/7e03ed16-d9d4-5525-aa3f-2586cff127ac/scratchpad/variety/probe2.txt").writeText(out.toString())
    }
}
