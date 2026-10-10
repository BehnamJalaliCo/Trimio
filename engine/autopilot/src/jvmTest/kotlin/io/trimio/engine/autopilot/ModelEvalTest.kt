package io.trimio.engine.autopilot

import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.llm.Generation
import io.trimio.engine.llm.GenerationRequest
import io.trimio.engine.llm.LanguageModel
import io.trimio.engine.llm.StopReason
import io.trimio.engine.llm.StructuredJson
import io.trimio.engine.llm.local.LlamaLanguageModel
import io.trimio.engine.models.ChatFormat
import io.trimio.engine.models.DirectorProfile
import io.trimio.engine.models.ModelCatalog
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import kotlin.test.Test

/**
 * Calibration harness: how close a director model's understanding is to a gold reading, so every
 * family can be tuned (temperature, profile) until it reaches the same edit quality.
 *
 * ```
 * ./gradlew :engine:autopilot:jvmTest --tests '*ModelEvalTest*' \
 *   -Pauto.gold=gold.json [-Pauto.llm=model.gguf] [-Pauto.vision=mmproj.gguf] \
 *   [-Pauto.transcript=words.json] [-Pauto.prompt=brief.txt|"text"] [-Pauto.seeds=1,2,3]
 * ```
 * Without `auto.llm` it scores the rules reader (no model): the floor every model must beat.
 * The transcript defaults to benchmark 01; its prompt is read from the BRIEF.md next to it.
 * Prints per-line disagreements and one `RESULT` line (averaged over seeds).
 */
class ModelEvalTest {
    @Test
    fun evaluate() {
        val gold = System.getProperty("auto.gold")?.let(::File)?.takeIf { it.isFile } ?: return
        val reference = json.decodeFromString(Understanding.serializer(), gold.readText())
        val transcriptFile = File(System.getProperty("auto.transcript") ?: "../../docs/benchmark/01-repo-map/words.json")
        val words = readWords(transcriptFile)
        val prompt = System.getProperty("auto.prompt")?.let { p -> File(p).takeIf { it.isFile }?.readText() ?: p } ?: briefOf(transcriptFile)
        val seeds = System.getProperty("auto.seeds")?.split(',')?.map { it.trim().toInt() } ?: listOf(1)
        val lines = Lines.split(words)
        val transcript = Transcript(Language.Persian, words)
        check(lines.size == reference.lines.size) { "gold has ${reference.lines.size} lines, the transcript splits into ${lines.size}" }

        val llm = System.getProperty("auto.llm")
        val runs = seeds.map { seed ->
            val t0 = System.nanoTime()
            val (reading, counter) = if (llm == null) {
                RulesUnderstander.understand(transcript, prompt, lines) to null
            } else {
                read(llm, transcript, prompt, lines, seed)
            }
            val seconds = (System.nanoTime() - t0) / 1e9
            val score = UnderstandingScore.of(reading, reference)
            println("seed $seed: ${score.summary()} json=${counter?.let { "${it.valid}/${it.calls}" } ?: "-"} ${"%.1f".format(seconds)}s")
            lines.indices.forEach { k ->
                val want = reference.lines[k].show
                val got = reading.lines.getOrNull(k)?.show.orEmpty()
                val mark = if (UnderstandingScore.alike(want, got, UnderstandingScore.EQUIVALENT_SHOWS)) " " else "✗"
                println("  $mark L$k gold=$want got=$got \"${reading.lines.getOrNull(k)?.title.orEmpty()}\"  ${words.textOf(lines[k].range)}")
            }
            println("  hook=${reading.hook} cta=${reading.cta} entities=${reading.entities.map { it.name }}")
            Run(score, counter, seconds)
        }
        println(result(llm?.let { File(it).name } ?: "rules", runs))
    }

    private class Run(val score: UnderstandingScore, val counter: JsonCounter?, val seconds: Double)

    private fun result(name: String, runs: List<Run>): String {
        fun avg(f: (Run) -> Double) = "%.2f".format(runs.map(f).average())
        val calls = runs.sumOf { it.counter?.calls ?: 0 }
        val valid = runs.sumOf { it.counter?.valid ?: 0 }
        val jsonRate = if (calls == 0) "-" else "%.2f".format(valid.toDouble() / calls)
        return "RESULT model=$name seeds=${runs.size} json=$jsonRate ($valid/$calls) show=${avg { it.score.show.toDouble() }} " +
            "showExact=${avg { it.score.showExact.toDouble() }} hook=${avg { if (it.score.hook) 1.0 else 0.0 }} " +
            "cta=${avg { if (it.score.ctaKeyword) 1.0 else 0.0 }} entities=${avg { it.score.entityRecall.toDouble() }} " +
            "domain=${avg { if (it.score.domain) 1.0 else 0.0 }} total=${avg { it.score.total.toDouble() }} seconds=${avg { it.seconds }}"
    }

    /** One understanding with a local GGUF, set up the way the app sets up that family. */
    private fun read(path: String, transcript: Transcript, prompt: String, lines: List<Lines.Line>, seed: Int): Pair<Understanding, JsonCounter> {
        val spec = ModelCatalog.all.firstOrNull { it.fileName == File(path).name }
        val name = File(path).name.lowercase()
        val format = spec?.chatFormat ?: when {
            "gemma-4" in name || "gemma4" in name -> ChatFormat.Gemma4
            "lfm" in name -> ChatFormat.Lfm
            else -> ChatFormat.ChatMl
        }
        val profile = spec?.let(DirectorProfile::of) ?: when (format) {
            ChatFormat.Gemma4 -> DirectorProfile.Gemma4
            ChatFormat.Lfm -> DirectorProfile.Lfm25
            else -> if ("qwen3.6" in name) DirectorProfile.Qwen36 else DirectorProfile.Qwen35
        }
        println("model ${File(path).name}: ${profile.family}, $format, t=${profile.understandingTemperature}, ctx=${profile.contextSize}, no-think=${profile.suppressesThinking}")
        return LlamaLanguageModel(
            spec?.id ?: name, path, format, contextSize = profile.contextSize, threads = 4,
            visionPath = System.getProperty("auto.vision"), maxImageTokens = profile.maxImageTokens, assistantPrefix = profile.assistantPrefix,
        ).use { model ->
            val counter = JsonCounter(model)
            val u = runBlocking { Understander(counter).understand(transcript, prompt, lines, seed = seed, temperature = profile.understandingTemperature) }
            u to counter
        }
    }

    /** Counts structured replies and how many were valid JSON objects on their own (before any retry). */
    private class JsonCounter(private val inner: LanguageModel) : LanguageModel by inner {
        var calls = 0
        var valid = 0

        override suspend fun generate(request: GenerationRequest, onText: (String) -> Unit): Generation {
            if (request.schema != null) calls++
            val g = inner.generate(request, onText)
            val ok = request.schema != null && g.stopReason == StopReason.EndTurn &&
                runCatching { StructuredJson.decode(JsonObject.serializer(), g.text) }.isSuccess
            if (ok) valid++
            return g
        }
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        fun readWords(file: File): List<Word> = Json.parseToJsonElement(file.readText()).jsonArray.map { e ->
            val o = e.jsonObject
            val range = TimeRange((o.getValue("s").jsonPrimitive.float * 1000).toLong(), (o.getValue("e").jsonPrimitive.float * 1000).toLong())
            Word(o.getValue("w").jsonPrimitive.content, range, language = Language.Persian)
        }

        /** The creator's prompt quoted in a benchmark's BRIEF.md ("> " lines), or none. */
        fun briefOf(transcript: File): String =
            File(transcript.parentFile, "BRIEF.md").takeIf { it.isFile }?.readLines()
                ?.filter { it.startsWith("> ") }?.joinToString("\n") { it.removePrefix("> ") }.orEmpty()
    }
}
