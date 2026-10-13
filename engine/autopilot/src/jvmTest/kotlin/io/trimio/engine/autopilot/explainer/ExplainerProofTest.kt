package io.trimio.engine.autopilot.explainer

import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.autopilot.Lines
import io.trimio.engine.autopilot.PersianSpelling
import io.trimio.engine.autopilot.Proofreader
import io.trimio.engine.autopilot.RulesUnderstander
import io.trimio.engine.autopilot.withSpelling
import io.trimio.engine.llm.local.LlamaLanguageModel
import io.trimio.engine.models.ChatFormat
import io.trimio.engine.motion.explainer.ExplainerFilm
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes

/**
 * The screenwriter on a real narration's words (whisper's, saved as [{"w","s","e"}]): the film by
 * the rules and, with -Dexplainer.llm=<gguf>, by the on-device model, each printed as a beat table
 * with the jury's panel. Skipped without -Dexplainer.transcript.
 *
 *   -Dexplainer.transcript=words.json [-Dexplainer.prompt=…] [-Dexplainer.out=dir] [-Dexplainer.seeds=1,2]
 *   [-Dexplainer.llm=model.gguf -Dexplainer.llm.id=qwen3.5-9b-q4km -Dexplainer.threads=4 -Dexplainer.chapters=2]
 */
class ExplainerProofTest {
    @Serializable
    data class W(val w: String, val s: Float, val e: Float)

    private val json = Json { prettyPrint = true; encodeDefaults = true }

    @Test
    fun proof() {
        val file = System.getProperty("explainer.transcript")?.let(::File)?.takeIf { it.isFile } ?: return
        val prompt = System.getProperty("explainer.prompt").orEmpty()
        val out = File(System.getProperty("explainer.out") ?: "build/explainer-proof").apply { mkdirs() }
        val seeds = System.getProperty("explainer.seeds")?.split(',')?.map { it.trim().toLong() } ?: listOf(1L)
        val saved = Json.decodeFromString(ListSerializer(W.serializer()), file.readText())
        val heard = saved.map { Word(it.w, TimeRange((it.s * 1000).toLong(), (it.e * 1000).toLong()), language = Language.Persian) }
        val lexicon = runBlocking { PersianSpelling.load() }
        // The listen step's spelling, as the explainer pipeline does it (no voice needed).
        val texts = heard.map { it.text }
        val fixes = Proofreader.fromBrief(texts, prompt, lexicon).let { b -> b + runBlocking { Proofreader.fromLexicon(texts, prompt, b) } }
        fixes.forEach { (i, w) -> println("spelling: ${texts[i]} → ${w.ifEmpty { "(joined)" }}") }
        val transcript = Transcript(Language.Persian, heard).withSpelling(fixes)
        val lines = Lines.split(transcript.words)
        val u = RulesUnderstander.understand(transcript, prompt, lines)
        val n = Narration.of(transcript.words, prompt, u, lexicon = lexicon)
        println("narration: ${n.clauses.size} clauses, ${n.duration}s, category ${n.category}")
        n.clauses.forEach { c -> println("  c${c.index} [${c.start}–${c.end}] ${c.text}") }
        println("chapters (rules): " + Chapters.find(n).joinToString(" | ") { "c${it.clauses.first}-c${it.clauses.last} «${it.title}»" })

        for (seed in seeds) {
            val t0 = System.nanoTime()
            val r = runBlocking { Screenwriter().write(n, seed) }
            report(out, "rules-seed-$seed", r.film, r.report, (System.nanoTime() - t0) / 1e9)
        }
        val llm = System.getProperty("explainer.llm") ?: return
        val model = LlamaLanguageModel(
            System.getProperty("explainer.llm.id") ?: "qwen3.5-9b-q4km", llm, ChatFormat.ChatMl,
            contextSize = 8192, threads = System.getProperty("explainer.threads")?.toIntOrNull() ?: 4,
        )
        // -Dexplainer.chapters=k: the model writes only the first k chapters (short runs on a CPU); the rules the rest.
        val keep = System.getProperty("explainer.chapters")?.toIntOrNull()
        val narration = keep?.let { k -> Chapters.find(n).getOrNull(k)?.let { cut -> Narration(n.words, n.clauses.take(cut.clauses.first), u, prompt, n.clauses[cut.clauses.first - 1].end, lexicon) } } ?: n
        val t0 = System.nanoTime()
        val log = mutableListOf<String>()
        val r = runBlocking { Screenwriter(LlmDirector(model, budget = 30.minutes, outline = keep == null), askAgain = false).write(narration, seeds.first()) { println(it); log += it } }
        println("llm usage: ${model.runtime?.summary()}")
        report(out, "llm-seed-${seeds.first()}", r.film, r.report + listOfNotNull(model.runtime?.summary()), (System.nanoTime() - t0) / 1e9)
        model.close()
    }

    private fun report(out: File, name: String, film: ExplainerFilm, report: List<String>, seconds: Double) {
        val table = Beats.table(film)
        println("==== $name (${"%.1f".format(seconds)}s)")
        table.forEach(::println)
        report.filter { it.startsWith("jury") || it.startsWith("  ") }.take(30).forEach(::println)
        File(out, "$name.txt").writeText((table + "" + report + "" + "written in ${"%.1f".format(seconds)}s").joinToString("\n"))
        File(out, "$name.json").writeText(json.encodeToString(ExplainerFilm.serializer(), film))
    }
}
