package io.trimio.engine.autopilot

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.llm.local.LlamaLanguageModel
import io.trimio.engine.models.ChatFormat
import io.trimio.engine.motion.recipe.BrandLibrary
import io.trimio.engine.motion.score.Score
import io.trimio.engine.motion.visual.VisualLibrary
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test

/**
 * The phase-5 proof: one real video and one prompt, edited with no human decision at all — speech
 * by the app's whisper.cpp, understanding and picture choice by the on-device director model
 * through llama.cpp, marks and pictures from the web, the planner's craft, the critic, mastering,
 * export. Each seed is a different edit.
 *
 * ./gradlew :engine:autopilot:jvmTest --tests '*AutopilotProofTest*' -Pauto.video=… -Pauto.prompt=prompt.txt \
 *   -Pauto.llm=Qwen3.5-4B-Q4_K_M.gguf -Pauto.whisper=ggml-large-v3-turbo-q5_0.bin -Pauto.native=… -Pvisuals.dir=… -Pauto.seeds=1,2,3
 */
class AutopilotProofTest {
    @kotlinx.serialization.Serializable
    data class W(val w: String, val s: Float, val e: Float)

    private val json = Json { prettyPrint = true; encodeDefaults = false }

    @Test
    fun proof() {
        val video = System.getProperty("auto.video")?.let(::File)?.takeIf { it.isFile } ?: return
        val prompt = System.getProperty("auto.prompt")?.let { p -> File(p).takeIf { it.isFile }?.readText() ?: p }.orEmpty()
        val seeds = System.getProperty("auto.seeds")?.split(',')?.map { it.trim().toLong() } ?: listOf(1L)
        val out = File(System.getProperty("auto.out") ?: "build/proof").apply { mkdirs() }
        val cache = File(out, "cache").apply { mkdirs() }

        // 1. Speech, recognised by the app's own whisper.cpp (cached: it does not change between seeds).
        val transcriptFile = File(out, "transcript.json")
        val transcript = if (transcriptFile.isFile) {
            Transcript(Language.Persian, json.decodeFromString(ListSerializer(W.serializer()), transcriptFile.readText()).map { Word(it.w, TimeRange((it.s * 1000).toLong(), (it.e * 1000).toLong()), language = Language.Persian) })
        } else {
            val whisper = System.getProperty("auto.whisper") ?: error("set -Pauto.whisper")
            val t0 = System.nanoTime()
            runBlocking { Studio.transcribe(video, whisper, prompt) }.also { t ->
                transcriptFile.writeText(json.encodeToString(ListSerializer(W.serializer()), t.words.map { W(it.text, it.range.startMs / 1000f, it.range.endMs / 1000f) }))
                println("whisper: ${(System.nanoTime() - t0) / 1e9}s: ${t.words.joinToString(" ") { it.text }}")
            }
        }

        val brands = runBlocking { BrandLibrary.load() }
        val visuals = System.getProperty("visuals.dir")?.let(::File)?.takeIf { it.isDirectory }?.let { dir ->
            VisualLibrary.parse(dir.listFiles { f -> f.extension == "json" }!!.associate { it.nameWithoutExtension to it.readText() })
        } ?: VisualLibrary.Empty
        val knowledge = WebKnowledge(HttpClient(OkHttp), FileCache(cache))
        val llm = System.getProperty("auto.llm")?.let { LlamaLanguageModel("qwen3.5-4b-q4km", it, ChatFormat.ChatMl, contextSize = 8192, threads = 4) }

        for (seed in seeds) {
            val t0 = System.nanoTime()
            val studio = Studio(brands, visuals, llm, knowledge)
            val production = runBlocking { studio.produce(video, prompt, seed, transcript) { stage, p -> println("seed $seed: $stage ${(p * 100).toInt()}%") } }
            val planned = (System.nanoTime() - t0) / 1e9
            File(out, "seed-$seed-report.txt").writeText(production.report.joinToString("\n") + "\n\nplanned in ${planned}s\n")
            File(out, "seed-$seed-score.json").writeText(Score.encode(production.score))
            File(out, "seed-$seed-understanding.json").writeText(json.encodeToString(Understanding.serializer(), production.understanding))
            println(production.report.joinToString("\n"))
            if (System.getProperty("auto.video.out") != "false") {
                kotlinx.coroutines.runBlocking { llm?.release() }
                val t1 = System.nanoTime()
                studio.render(production, video, File(out, "seed-$seed.mp4"))
                println("seed $seed rendered in ${(System.nanoTime() - t1) / 1e9}s")
            }
        }
        llm?.close()
    }

    private class FileCache(private val dir: File) : KnowledgeCache {
        private fun file(key: String) = File(dir, key.hashCode().toUInt().toString(16) + "-" + key.length + ".txt")
        override fun get(key: String) = file(key).takeIf { it.isFile }?.readText()
        override fun put(key: String, value: String) = file(key).writeText(value)
    }
}
