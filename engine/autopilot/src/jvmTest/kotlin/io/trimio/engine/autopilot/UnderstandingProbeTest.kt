package io.trimio.engine.autopilot

import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.llm.local.LlamaLanguageModel
import io.trimio.engine.models.ChatFormat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test

/** Runs the understanding step on the benchmark transcript with a real GGUF (-Pauto.llm=path). */
class UnderstandingProbeTest {
    @Test
    fun probe() {
        val path = System.getProperty("auto.llm") ?: return
        val words = Json.parseToJsonElement(File("../../docs/benchmark/01-repo-map/words.json").readText()).jsonArray.map { e ->
            val o = e.jsonObject
            val range = TimeRange((o.getValue("s").jsonPrimitive.float * 1000).toLong(), (o.getValue("e").jsonPrimitive.float * 1000).toLong())
            Word(o.getValue("w").jsonPrimitive.content, range, language = Language.Persian)
        }
        val prompt = System.getProperty("auto.prompt")?.let { p -> File(p).takeIf { it.isFile }?.readText() ?: p } ?: ""
        LlamaLanguageModel("probe", path, ChatFormat.ChatMl, contextSize = 8192, threads = 4).use { model ->
            val t0 = System.nanoTime()
            var tokens = 0
            val lines = Lines.split(words)
            val u = runBlocking { Understander(model).understand(Transcript(Language.Persian, words), prompt, lines, seed = 1) { tokens++; print(it) } }
            println("\n\n${(System.nanoTime() - t0) / 1e9}s, $tokens pieces")
            println("${u.title} | ${u.topic} | ${u.domain} | ${u.mood} | ${u.brief} | hook=${u.hook} | cta=${u.cta}")
            u.fixes.forEach { println("fix $it") }
            u.entities.forEach { println("entity $it") }
            lines.forEachIndexed { k, l -> println("L$k ${words.textOf(l.range)}\n    → ${u.lines[k]}") }
        }
    }
}
