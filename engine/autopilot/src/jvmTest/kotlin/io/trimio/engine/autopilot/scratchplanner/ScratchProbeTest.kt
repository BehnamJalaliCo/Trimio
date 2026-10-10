package io.trimio.engine.autopilot.scratchplanner

import io.trimio.engine.autopilot.Enumerations
import io.trimio.engine.autopilot.Lines
import io.trimio.engine.autopilot.Phrases
import io.trimio.engine.autopilot.workshop.Workshop
import kotlin.test.Test

class ScratchProbeTest {
    @Test
    fun probe() {
        val id = System.getProperty("workshop.only") ?: "fa-cooking-kuku"
        val s = Workshop.scenario(id)
        val words = s.words
        val texts = words.map { it.text }
        val lines = Lines.split(words)
        for ((k, l) in lines.withIndex()) println("$k: " + texts.subList(l.first, l.last + 1).joinToString(" ") + "   || title=" + Phrases.title(words, l))
        println(Enumerations.lists(texts, lines).mapValues { (_, v) -> v.items.map { r -> r.joinToString(" ") { texts[it] } } })
        println(texts.mapIndexed { i, t -> "$t:" + (if (Phrases.verb(Phrases.key(t))) "V" else "") + (if (Phrases.nounish(t)) "N" else "") }.joinToString(" "))
    }
}
