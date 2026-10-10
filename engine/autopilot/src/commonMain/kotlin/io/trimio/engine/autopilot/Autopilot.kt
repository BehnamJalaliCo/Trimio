package io.trimio.engine.autopilot

import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.transcript.Transcript
import io.trimio.engine.llm.LanguageModel
import io.trimio.engine.motion.TextLayoutEngine
import io.trimio.engine.motion.recipe.BrandLibrary
import io.trimio.engine.motion.score.BeatScore
import io.trimio.engine.motion.score.Compiler
import io.trimio.engine.motion.score.EditPlan
import io.trimio.engine.motion.score.Score
import io.trimio.engine.motion.score.Subject
import io.trimio.engine.motion.visual.VisualLibrary

/**
 * One tap: a transcript, the voice and a prompt in; a finished, mastered motion-graphics edit
 * out. Every stage is automatic and every seed gives a different edit:
 *
 * 1. listen — pauses, stress, the cut list ([SpeechAnalysis]);
 * 2. understand — the model reads the brief and the transcript ([Understander]), or the rules do;
 * 3. look things up — brand marks and pictures the packs lack, from the web ([Knowledge]);
 * 4. plan — the seed's edit, by the craft rules ([Planner]), with the creator's [Taste];
 * 5. pick pictures — candidates proposed, the model chooses ([VisualPicker]);
 * 6. compile and critique — until the supervising [Critic] has nothing left to fix;
 * 7. master the sound ([Soundtrack]).
 */
class Autopilot(
    private val text: TextLayoutEngine,
    private val brands: BrandLibrary,
    private val visuals: VisualLibrary,
    private val model: LanguageModel? = null,
    private val knowledge: Knowledge = Knowledge.Offline,
    private val taste: Taste = Taste(),
) {
    data class Request(
        /** Recognised words on the source clock. */
        val transcript: Transcript,
        /** The voice track, 48 kHz mono, on the source clock. */
        val voice: PcmAudio,
        val prompt: String,
        val seed: Long,
        /** Footage id for the renderer's media source; null for audio-only pieces. */
        val footage: String? = "main",
        val subject: Subject? = null,
        /** Reuse an earlier reading (regenerating a variation needs no new understanding). */
        val understanding: Understanding? = null,
        val fps: Int = 30,
    )

    data class Production(
        val compiled: Compiler.Output,
        val score: Score,
        val plan: Planner.Plan,
        val understanding: Understanding,
        val soundtrack: Soundtrack.Result,
        val edit: EditPlan,
        val report: List<String>,
    )

    suspend fun produce(request: Request, onStage: (stage: String, progress: Float) -> Unit = { _, _ -> }): Production {
        val report = mutableListOf<String>()
        onStage("listen", 0f)
        val heard = SpeechAnalysis.of(request.voice, request.transcript)
        // Words the creator spelled in the brief win over sound-alike recognitions.
        val spelled = Proofreader.fromBrief(heard.transcript.words.map { it.text }, request.prompt)
        spelled.forEach { (i, w) -> report += "spelling (brief): ${heard.transcript.words[i].text} → $w" }
        val analysis = SpeechAnalysis(heard.transcript.withFixes(spelled), heard.edit, heard.pauses, heard.loudnessLufs, heard.duration)
        val lines = Lines.split(analysis.transcript.words, pauses = analysis.pauses)
        report += "listen: ${analysis.transcript.words.size} words, ${lines.size} lines, ${analysis.edit.cutCount} cuts, " +
            "${round1(analysis.duration)}s → ${round1(analysis.edit.duration)}s"

        onStage("understand", 0.1f)
        val understanding = request.understanding ?: understand(analysis.transcript, request.prompt, lines, request.seed, report)
        // The director's line-by-line spelling, aligned word for word to what was heard.
        val texts = analysis.transcript.words.map { it.text }
        val lineFixes = lines.indices.flatMap { k ->
            val l = lines[k]
            Proofreader.align(texts.subList(l.first, l.last + 1), l.first, understanding.lines.getOrNull(k)?.fixed.orEmpty()).entries
        }.associate { it.key to it.value }
        lineFixes.forEach { (i, w) -> report += "spelling (director): ${texts[i]} → $w" }
        val transcript = analysis.transcript.withFixes(lineFixes)
        report += "understand: ${understanding.title} | ${understanding.domain}/${understanding.mood} | hook line ${understanding.hook?.line} | cta ${understanding.cta?.keyword}"

        onStage("research", 0.45f)
        val brandLibrary = research(understanding, report)

        onStage("plan", 0.55f)
        val output = analysis.edit.remap(transcript).words
        val plan = Planner(taste, request.prompt).plan(output, lines, understanding, request.seed, footage = request.footage != null)
        report += plan.notes

        onStage("pictures", 0.6f)
        val picked = pictures(plan.score, output, lines, report)

        onStage("compile", 0.75f)
        val compiler = Compiler(text, brandLibrary, picked.second)
        var score = picked.first
        var compiled = compile(compiler, score, transcript, analysis.edit, request)
        val critic = Critic()
        repeat(CRITIC_PASSES) { pass ->
            val review = critic.review(score, compiled, output, lines, understanding.cta)
            review.issues.forEach { report += "critic ${pass + 1}: ${it.kind} at ${round1(it.at)}s — ${it.detail}" }
            val revised = review.revised ?: return@repeat
            score = revised
            compiled = compile(compiler, score, transcript, analysis.edit, request)
        }
        compiled.beats.filter { it.recipe != "pop-captions" }.forEach { report += "  ${it.recipe.padEnd(12)} ${it.zone.padEnd(6)} ${round1(it.at)}–${round1(it.out)}  ${it.text}" }

        onStage("sound", 0.9f)
        val sound = Soundtrack(request.voice.sampleRate).master(
            request.voice, analysis.edit, output, compiled.composition.duration, plan.music, musicSeed = request.seed.toInt(), cues = compiled.sfx,
        )
        report += "sound: ${plan.music?.id ?: "no music"}, ${compiled.sfx.size} cues, ${round1(sound.lufs.toFloat())} LUFS, peak ${round1(sound.peakDb.toFloat())} dBFS"
        onStage("done", 1f)
        return Production(compiled, score, plan, understanding, sound, analysis.edit, report)
    }

    private suspend fun understand(transcript: Transcript, prompt: String, lines: List<Lines.Line>, seed: Long, report: MutableList<String>): Understanding {
        val m = model ?: return RulesUnderstander.understand(transcript, prompt, lines).also { report += "understand: rules (no model)" }
        return runCatching { Understander(m).understand(transcript, prompt, lines, seed = seed.toInt(), temperature = TEMPERATURE) }
            .onFailure { report += "understand: model failed (${it.message}); rules instead" }
            .getOrElse { RulesUnderstander.understand(transcript, prompt, lines) }
            .also { report += "understand: ${m.id}" }
    }

    /** Marks for named brands the bundled pack lacks, looked up online. */
    private suspend fun research(u: Understanding, report: MutableList<String>): BrandLibrary {
        val wanted = (u.entities.filter { it.kind in BRAND_KINDS }.map { it.name } + u.lines.flatMap { it.items })
            .filter { n -> n.any { it in 'A'..'Z' || it in 'a'..'z' } }.distinctBy { BrandLibrary.key(it) }
        val found = wanted.filter { brands.find(it)?.isMonogram != false }.mapNotNull { name -> knowledge.brand(name)?.let { BrandLibrary.key(name) to it } }
        found.forEach { (k, e) -> report += "research: mark for ${e.t} ($k) from Simple Icons" }
        return if (found.isEmpty()) brands else brands.withMarks(found.associate { (k, e) -> k to e.copy(a = e.a + k) })
    }

    /** Every picture query in the score, resolved to one chosen icon id. */
    private suspend fun pictures(score: Score, words: List<io.trimio.core.model.transcript.Word>, lines: List<Lines.Line>, report: MutableList<String>): Pair<Score, VisualLibrary> {
        val context = mutableMapOf<String, String>()
        for (scene in score.scenes) for (b in scene.beats) {
            val line = lines.firstOrNull { (b.at ?: scene.from ?: -1) in it.range }?.let { words.textOf(it.range) }.orEmpty()
            (listOfNotNull(b.visual) + b.visuals).filter { it.isNotBlank() }.forEach { context.getOrPut(it) { line } }
        }
        if (context.isEmpty()) return score to visuals
        val result = VisualPicker(visuals, knowledge, model).pick(context)
        result.picks.forEach { (q, id) -> report += "picture: \"$q\" → ${id ?: "none"}" }
        fun BeatScore.resolved() = copy(
            visual = visual?.let { result.picks[it] ?: if (it in result.picks) null else it },
            visuals = visuals.map { result.picks[it] ?: it },
        )
        val resolved = score.copy(scenes = score.scenes.map { s -> s.copy(beats = s.beats.mapNotNull { b -> b.resolved().takeIf { r -> !(b.recipe == "object" && r.visual == null) } }) })
        return resolved to result.library
    }

    private fun compile(compiler: Compiler, score: Score, transcript: Transcript, edit: EditPlan, request: Request) =
        compiler.compile(Compiler.Input(score, transcript, footage = request.footage, fps = request.fps, edit = edit, subject = request.subject))

    private companion object {
        const val CRITIC_PASSES = 2
        const val TEMPERATURE = 0.3f
        val BRAND_KINDS = setOf("brand", "app", "product", "organization")

        fun round1(v: Float) = kotlin.math.round(v * 10f) / 10f
    }
}
