package io.trimio.engine.autopilot

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.llm.local.LlamaEmbedder
import io.trimio.engine.llm.local.LlamaLanguageModel
import io.trimio.engine.llm.local.LlamaReranker
import io.trimio.engine.llm.local.LocalEncoders
import io.trimio.engine.models.ChatFormat
import io.trimio.engine.models.TextAddOns
import io.trimio.engine.motion.recipe.BrandLibrary
import io.trimio.engine.motion.score.Score
import io.trimio.engine.motion.score.Subject
import io.trimio.engine.motion.visual.VisualLibrary
import io.trimio.engine.onnx.OrtEngine
import io.trimio.engine.perception.AddOnPass
import io.trimio.engine.perception.Frame
import io.trimio.engine.perception.ModelFiles
import io.trimio.engine.perception.Perception
import io.trimio.engine.retrieval.Corpora
import io.trimio.engine.retrieval.PictureRecall
import io.trimio.engine.retrieval.Retrieval
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

        // -Pauto.addons=<dir>[,<dir>]: the add-ons installed there run as in the app (AddOnPass); absent: none.
        val addOns = AddOns(System.getProperty("auto.addons")?.split(',')?.map(::File)?.filter { it.isDirectory }.orEmpty())
        // -Pauto.embedder=<dir>: the retrieval add-on (bge-m3, plus the reranker when it is there) widens the picture search.
        useEmbedder(System.getProperty("auto.embedder"))

        // 1. Speech, recognised by the app's own whisper.cpp (cached: it does not change between seeds).
        val transcriptFile = File(out, "transcript.json")
        val transcript = if (transcriptFile.isFile) {
            val saved = json.decodeFromString(ListSerializer(W.serializer()), transcriptFile.readText())
            Transcript(Language.Persian, saved.map { Word(it.w, TimeRange((it.s * 1000).toLong(), (it.e * 1000).toLong()), language = Language.Persian) })
        } else {
            val whisper = System.getProperty("auto.whisper") ?: error("set -Pauto.whisper")
            val t0 = System.nanoTime()
            runBlocking { addOns.transcribe(video, whisper, prompt) }.also { t ->
                transcriptFile.writeText(json.encodeToString(ListSerializer(W.serializer()), t.words.map { W(it.text, it.range.startMs / 1000f, it.range.endMs / 1000f) }))
                println("whisper: ${(System.nanoTime() - t0) / 1e9}s: ${t.words.joinToString(" ") { it.text }}")
            }
        }

        val brands = runBlocking { BrandLibrary.load() }
        val visuals = System.getProperty("visuals.dir")?.let(::File)?.takeIf { it.isDirectory }?.let { dir ->
            VisualLibrary.parse(dir.listFiles { f -> f.extension == "json" }!!.associate { it.nameWithoutExtension to it.readText() })
        } ?: VisualLibrary.Empty
        val knowledge = WebKnowledge(HttpClient(OkHttp), FileCache(cache))
        // -Pauto.vision=<mmproj>: the same director also looks at rendered frames; -Pauto.llm.id picks its calibrated profile.
        val llm = System.getProperty("auto.llm")?.let {
            LlamaLanguageModel(
                System.getProperty("auto.llm.id") ?: "qwen3.5-4b-q4km", it, ChatFormat.ChatMl,
                contextSize = 8192, threads = System.getProperty("auto.threads")?.toIntOrNull() ?: 4, visionPath = System.getProperty("auto.vision"), maxImageTokens = 96,
            )
        }

        // -Pauto.assets=<dir>: the pictures and clips the creator attached beside the prompt (the app works out what each is).
        val assets = System.getProperty("auto.assets")?.let(::File)?.takeIf { it.isDirectory }?.listFiles()?.filter { it.isFile }?.sortedBy { it.name }
            ?.mapIndexed { i, f -> Media.asset(f, "asset-$i") }.orEmpty()
        val voice = Media.audio(video, Studio.RATE)
        for (seed in seeds) {
            val t0 = System.nanoTime()
            val studio = Studio(brands, visuals, llm, knowledge, head = addOns::head)
            // -Pauto.reuse=<file>: plan from a saved reading (no model run), for fast iteration and variations.
            val reuse = System.getProperty("auto.reuse")?.let(::File)?.takeIf { it.isFile }?.let { json.decodeFromString(Understanding.serializer(), it.readText()) }
            val planned0 = runBlocking { studio.produce(video, prompt, seed, transcript, reuse, assets) { stage, p -> println("seed $seed: $stage ${(p * 100).toInt()}%") } }
            // -Pauto.score=<file>: a score written by hand (an editor's own cut of the piece), compiled and mastered as the
            // planner's would be, so the director's edit and an editor's can be put side by side on the same footage.
            val production = System.getProperty("auto.score")?.let(::File)?.takeIf { it.isFile }?.let { f -> handScored(planned0, Score.parse(f.readText())) } ?: planned0
            System.getProperty("auto.frames")?.split(',')?.map { it.trim().toFloat() }?.let { times ->
                val c = production.compiled.composition
                io.trimio.engine.motion.export.FfmpegFootage(video, c.fps, c.width, c.height).use { footage ->
                    val frames = io.trimio.engine.motion.export.MotionExporter.Frames(c, footage)
                    for (t in times) File(out, "seed-$seed-at-$t.png").writeBytes(frames.draw(t).encodeToData(org.jetbrains.skia.EncodedImageFormat.PNG)!!.bytes)
                }
            }
            // -Pauto.sheet=true: a contact sheet of the edit, one frame at every graphic once it has landed (the art director's look).
            if (System.getProperty("auto.sheet") == "true") Sheet.draw(production, video, File(out, "seed-$seed-sheet.png"))
            // -Pauto.every=<s>: every frame of the piece at that step, as a JPEG sheet (the whole viewing, not only the graphics).
            System.getProperty("auto.every")?.toFloatOrNull()?.let { step -> Sheet.draw(production, video, File(out, "seed-$seed-every.jpg"), Sheet.every(production, step)) }
            val planned = (System.nanoTime() - t0) / 1e9
            // The real benchmark's acceptance counts (unknown words, fragments, cuts inside words, every graphic text).
            val counted = BenchmarkCount.lines(production, voice, runBlocking { PersianSpelling.load() })
            val lines = production.report + "" + counted + "" + addOns.lines().let { if (it.isEmpty()) it else it + "" }
            File(out, "seed-$seed-report.txt").writeText(lines.joinToString("\n") + "\nplanned in ${planned}s\n")
            println(counted.joinToString("\n"))
            File(out, "seed-$seed-score.json").writeText(Score.encode(production.score))
            File(out, "seed-$seed-understanding.json").writeText(json.encodeToString(Understanding.serializer(), production.understanding))
            println(production.report.joinToString("\n"))
            if (System.getProperty("auto.video.out") != "false") {
                kotlinx.coroutines.runBlocking { llm?.release() }
                val t1 = System.nanoTime()
                studio.render(production, video, File(out, "seed-$seed.mp4"), assets = assets)
                println("seed $seed rendered in ${(System.nanoTime() - t1) / 1e9}s")
            }
        }
        llm?.close()
    }

    /** [planned] with [score] in place of the planner's: compiled over the same words and cut, its sound mastered again. */
    private fun handScored(planned: Autopilot.Production, score: Score): Autopilot.Production {
        val bench = planned.bench ?: return planned
        val compiled = bench.compiler.compile(bench.input(score, bench.transcript, planned.edit))
        val mood = planned.plan.music
        val sound = bench.master(compiled, planned.edit, bench.transcript, mood, bench.arrange(mood, planned.edit, bench.transcript), bench.levels)
        val beats = compiled.beats.filter { it.recipe != "pop-captions" }.map { "  ${it.recipe.padEnd(12)} ${it.zone.padEnd(6)} ${"%.1f".format(it.at)}–${"%.1f".format(it.out)}  ${it.text}" }
        return planned.copy(compiled = compiled, score = score, soundtrack = sound, report = listOf("hand score: ${score.scenes.size} scenes") + beats)
    }

    private fun useEmbedder(dir: String?) {
        File(dir ?: return).takeIf { it.isDirectory }?.let { ArtShelf.recall = recall(it) }
    }

    private fun recall(dir: File): ArtShelf.Recall {
        val embedder = TextAddOns.embedder.let { LlamaEmbedder(it.id, File(dir, it.fileName).path, LocalEncoders.familyOf(it)) }
        val reranker = TextAddOns.reranker06b.let { s -> File(dir, s.fileName).takeIf { it.isFile }?.let { LlamaReranker(s.id, it.path) } }
        val r = PictureRecall {
            embedder.load()
            Retrieval.open(Corpora.pictures(ArtShelf.indexJson()), embedder, reranker)
        }
        return ArtShelf.Recall(r::search)
    }

    /** The add-ons found in [dirs], run as the app runs them; with no dirs, none (the plain path). */
    private class AddOns(private val dirs: List<File>) {
        private fun file(name: String) = dirs.map { File(it, name) }.firstOrNull { it.isFile }?.path
        private val pass = dirs.takeIf { it.isNotEmpty() }?.let { AddOnPass(Perception(OrtEngine(), ModelFiles { spec -> file(spec.fileName) })) }

        suspend fun transcribe(video: File, whisper: String, prompt: String): Transcript {
            val t = Studio.transcribe(video, whisper, prompt)
            return pass?.let { p -> t.copy(words = p.words(t.words, Media.audio(video, WHISPER_RATE), t.language)) } ?: t
        }

        suspend fun head(frames: List<io.trimio.engine.llm.RgbImage>, found: SubjectFinder.Found?): SubjectFinder.Found? {
            val h = pass?.head(frames.mapIndexed { i, f -> Frame(f.width, f.height, f.rgb, i.toFloat()) }) ?: return found
            return SubjectFinder.Found(Subject(h[0], h[1], h[2], h[3]), FACE_CONFIDENCE)
        }

        fun lines(): List<String> = pass?.report.orEmpty().map { "add-on $it" }
    }

    private class FileCache(private val dir: File) : KnowledgeCache {
        private fun file(key: String) = File(dir, key.hashCode().toUInt().toString(16) + "-" + key.length + ".txt")
        override fun get(key: String) = file(key).takeIf { it.isFile }?.readText()
        override fun put(key: String, value: String) = file(key).writeText(value)
    }

    private companion object {
        const val WHISPER_RATE = 16_000
        const val FACE_CONFIDENCE = 0.9f
    }
}
