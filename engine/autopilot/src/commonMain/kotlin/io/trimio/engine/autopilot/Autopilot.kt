package io.trimio.engine.autopilot

import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.autopilot.judges.SoundJudge
import io.trimio.engine.llm.LanguageModel
import io.trimio.engine.motion.TextAlign
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
 * 7. the [Jury]: ten judges score the edit; until every one gives 10 it is revised with their
 *    fixes, then other variations are tried, and the best panel's edit goes on;
 * 8. master the sound ([Soundtrack]), and the jury hears the mix.
 */
class Autopilot(
    private val text: TextLayoutEngine,
    private val brands: BrandLibrary,
    private val visuals: VisualLibrary,
    private val model: LanguageModel? = null,
    private val knowledge: Knowledge = Knowledge.Offline,
    private val taste: Taste = Taste(),
    /** Who looks at rendered frames: the director itself when it can see, else a light looker. */
    private val eyes: LanguageModel? = model?.takeIf { it.canSee },
    /** Renders frames of a compiled edit for the eyes; without it the critic judges structure only. */
    private val grabber: FrameGrabber? = null,
    /** The panel of judges every edit must satisfy before delivery; null skips it (one critic-checked cut). */
    private val jury: Jury? = Jury.Default,
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
        /** The jury's verdict on the delivered edit (null when no jury sat). */
        val panel: Jury.Panel? = null,
    )

    suspend fun produce(request: Request, onStage: (stage: String, progress: Float) -> Unit = { _, _ -> }): Production {
        val report = mutableListOf<String>()
        onStage("listen", 0f)
        val heard = SpeechAnalysis.of(request.voice, request.transcript)
        // Words the creator spelled in the brief win over sound-alike recognitions.
        val spelled = Proofreader.fromBrief(heard.transcript.words.map { it.text }, request.prompt)
        spelled.forEach { (i, w) -> report += "spelling (brief): ${heard.transcript.words[i].text} → $w" }
        val lexical = Proofreader.fromLexicon(heard.transcript.words.map { it.text }, request.prompt, spelled)
            .onEach { (i, w) -> report += "spelling (lexicon): ${heard.transcript.words[i].text} → ${w.ifEmpty { "(joined)" }}" }
        val analysis = SpeechAnalysis(heard.transcript.withSpelling(spelled + lexical), heard.edit, heard.pauses, heard.loudnessLufs, heard.duration)
        val lines = Lines.split(analysis.transcript.words, pauses = analysis.pauses)
        report += "listen: ${analysis.transcript.words.size} words, ${lines.size} lines, ${analysis.edit.cutCount} cuts, " +
            "${round1(analysis.duration)}s → ${round1(analysis.edit.duration)}s"

        onStage("understand", 0.1f)
        val understanding = request.understanding ?: understand(analysis.transcript, request.prompt, lines, request.seed, report)
        // The director's line-by-line spelling, aligned word for word to what was heard.
        val texts = analysis.transcript.words.map { it.text }
        val lexicon = PersianSpelling.load()
        val lineFixes = lines.indices.flatMap { k ->
            val l = lines[k]
            Proofreader.align(texts.subList(l.first, l.last + 1), l.first, understanding.lines.getOrNull(k)?.fixed.orEmpty()).entries
        }.associate { it.key to it.value }.filter { (i, w) -> real(w, lexicon, request.prompt) && !lexicon.worse(texts[i], w) }
        lineFixes.forEach { (i, w) -> report += "spelling (director): ${texts[i]} → $w" }
        val transcript = analysis.transcript.withFixes(lineFixes)
        report += "understand: ${understanding.title} | ${understanding.domain}/${understanding.mood} | hook line ${understanding.hook?.line} | cta ${understanding.cta?.keyword}"

        onStage("research", 0.45f)
        val brandLibrary = research(understanding, report)

        onStage("plan", 0.55f)
        val reading = Reading(request, analysis.edit, transcript, lines, understanding, brandLibrary, lexicon)
        val first = draft(reading, request.seed, look = true, onStage) { report += it }
        // Every judge gives 10, or the jury revises the edit and tries other variations (within its budget).
        val d = deliberate(reading, first, onStage, report)
        d.compiled.beats.filter { it.recipe != "pop-captions" }.forEach { report += "  ${it.recipe.padEnd(12)} ${it.zone.padEnd(6)} ${round1(it.at)}–${round1(it.out)}  ${it.text}" }

        onStage("sound", 0.9f)
        var levels = Soundtrack.Levels()
        var sound = master(reading, d, levels)
        var panel = jury?.deliberate(context(reading, d, sound, levels))
        // A true peak over the ceiling: once more with the limiter lowered by the overshoot, kept when the mixer prefers it.
        val safer = SoundJudge.remaster(sound, levels)?.takeIf { panel != null }
        if (safer != null) {
            val again = master(reading, d, safer)
            val second = jury?.deliberate(context(reading, d, again, safer))
            if (second != null && (second.of("sound")?.score ?: 0f) > (panel?.of("sound")?.score ?: 0f)) {
                levels = safer
                sound = again
                panel = second
                report += "jury: remastered with a ${round1(safer.ceilingDb)} dB ceiling"
            }
        }
        report += "sound: ${d.plan.music?.id ?: "no music"}, ${d.compiled.sfx.size} cues, ${round1(sound.lufs.toFloat())} LUFS, peak ${round1(sound.peakDb.toFloat())} dBFS"
        panel?.let { p ->
            report += "jury: ${if (p.unanimous) "every judge gives 10" else "not unanimous — best panel within the budget"} | ${p.summary()}"
            report += p.findings()
        }
        onStage("done", 1f)
        return Production(d.compiled, d.score, d.plan, understanding, sound, d.edit, report, panel)
    }

    /** What every variation of one request shares: the words, their lines and reading, the silence cut, the marks, the lexicon. */
    private class Reading(
        val request: Request,
        val cut: EditPlan,
        val transcript: Transcript,
        val lines: List<Lines.Line>,
        val understanding: Understanding,
        val brands: BrandLibrary,
        val lexicon: PersianSpelling,
    )

    /** One seed's edit, compiled and through the critics. */
    private data class Draft(
        val seed: Long,
        val plan: Planner.Plan,
        val score: Score,
        val compiled: Compiler.Output,
        val edit: EditPlan,
        val output: List<Word>,
        val compiler: Compiler,
        val looks: List<VisionCritic.Look>,
    )

    /** Plans, pictures, compiles and critiques the edit of [seed]; the eyes look only when [look] (they are slow). */
    private suspend fun draft(r: Reading, seed: Long, look: Boolean, onStage: (String, Float) -> Unit, log: (String) -> Unit): Draft {
        val request = r.request
        val transcript = r.transcript
        val texts = transcript.words.map { it.text }
        // The payoff said first, when this piece and seed call for it; the body starts after it.
        val open = ColdOpen.choose(transcript.words, r.lines, r.understanding, request.prompt, seed, request.voice)
        val edit = open?.applyTo(r.cut, COLD_OPEN_GAP) ?: r.cut
        open?.let { log("cold open: «${texts.slice(it.words).joinToString(" ")}» ${round1(it.length)}s") }
        val output = edit.remap(transcript).words
        val plan = Planner(taste, request.prompt).plan(
            output, r.lines, r.understanding, seed, footage = request.footage != null,
            opening = open?.let { Planner.Opening(edit.preludeLength, it.line, it.words) },
        )
        plan.notes.forEach(log)

        onStage("pictures", 0.6f)
        val picked = pictures(plan.score, output, r.lines, log)

        onStage("compile", 0.75f)
        val compiler = Compiler(text, r.brands, picked.second)
        // Nothing reaches the screen misspelled: every Persian word was heard, briefed or is known.
        var score = ScreenSpelling(r.lexicon, texts, request.prompt).check(picked.first, log)
        var compiled = compile(compiler, score, transcript, edit, request)
        val critic = Critic()
        repeat(CRITIC_PASSES) { pass ->
            val review = critic.review(score, compiled, output, r.lines, r.understanding.cta)
            review.issues.forEach { log("critic ${pass + 1}: ${it.kind} at ${round1(it.at)}s — ${it.detail}") }
            val revised = review.revised ?: return@repeat
            score = revised
            compiled = compile(compiler, score, transcript, edit, request)
        }
        var looks = emptyList<VisionCritic.Look>()
        val looker = eyes
        if (look && looker != null && grabber != null) {
            onStage("look", 0.85f)
            val (seen, revised) = VisionCritic(looker).review(score, compiled, output, grabber, log)
            looks = seen
            if (revised != null) {
                score = revised
                compiled = compile(compiler, score, transcript, edit, request)
                log("eyes: edit revised from what was seen")
            }
        }
        return Draft(seed, plan, score, compiled, edit, output, compiler, looks)
    }

    /**
     * The jury's loop over [first]: the judges' own fixes, recompiled, then other variation seeds,
     * until every judge gives 10 or the budget is spent; the best panel's edit goes on.
     */
    private suspend fun deliberate(r: Reading, first: Draft, onStage: (String, Float) -> Unit, report: MutableList<String>): Draft {
        val j = jury ?: return first
        onStage("jury", 0.87f)
        val outcome = j.loop(
            first,
            context = { d -> context(r, d) },
            revise = { d, s -> d.copy(score = s, compiled = compile(d.compiler, s, r.transcript, d.edit, r.request)) },
            vary = { k -> draft(r, r.request.seed * VARIATION_STRIDE + k, look = false, onStage = { _, _ -> }) { report += "variation $k: $it" } },
            log = { report += it },
        )
        val chosen = if (outcome.best.seed == r.request.seed) "the first cut" else "variation seed ${outcome.best.seed}"
        report += "jury: $chosen after ${outcome.rounds} revision rounds and ${outcome.variations} variations"
        return outcome.best
    }

    /** What the judges read about [d]; [sound] once it is mastered. */
    private fun context(r: Reading, d: Draft, sound: Soundtrack.Result? = null, levels: Soundtrack.Levels = Soundtrack.Levels()) = Jury.Context(
        compiled = d.compiled, score = d.score, words = d.output, lines = r.lines, understanding = r.understanding, prompt = r.request.prompt,
        music = d.plan.music, edit = d.edit, source = r.transcript.words, prelude = d.edit.preludeWords(r.transcript), subject = r.request.subject,
        footage = r.request.footage != null, sound = sound, levels = levels, looks = d.looks, lexicon = r.lexicon, measure = measure, voice = r.request.voice,
    )

    private val measure = LineMeasure { s, type, maxWidth, rtl -> text.layout(s, type, maxWidth, TextAlign.Center, rtl).lines.size }

    private fun master(r: Reading, d: Draft, levels: Soundtrack.Levels): Soundtrack.Result = Soundtrack(r.request.voice.sampleRate).master(
        r.request.voice, d.edit, d.edit.preludeWords(r.transcript) + d.output, d.compiled.composition.duration, d.plan.music,
        musicSeed = r.request.seed.toInt(), cues = d.compiled.sfx, levels = levels,
    )

    private suspend fun understand(transcript: Transcript, prompt: String, lines: List<Lines.Line>, seed: Long, report: MutableList<String>): Understanding {
        val m = model ?: return RulesUnderstander.understand(transcript, prompt, lines).also { report += "understand: rules (no model)" }
        // Each model family reads at its own calibrated temperature (engine/models DirectorProfile).
        val temperature = io.trimio.engine.models.DirectorProfile.forModelId(m.id)?.understandingTemperature ?: TEMPERATURE
        return runCatching { Understander(m).understand(transcript, prompt, lines, seed = seed.toInt(), temperature = temperature) }
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
    private suspend fun pictures(score: Score, words: List<Word>, lines: List<Lines.Line>, log: (String) -> Unit): Pair<Score, VisualLibrary> {
        val context = mutableMapOf<String, String>()
        for (scene in score.scenes) for (b in scene.beats) {
            val line = lines.firstOrNull { (b.at ?: scene.from ?: -1) in it.range }?.let { words.textOf(it.range) }.orEmpty()
            (listOfNotNull(b.visual) + b.visuals).filter { it.isNotBlank() }.forEach { context.getOrPut(it) { line } }
        }
        if (context.isEmpty()) return score to visuals
        val result = VisualPicker(visuals, knowledge, model).pick(context)
        result.picks.forEach { (q, id) -> log("picture: \"$q\" → ${id ?: "none"}") }
        fun BeatScore.resolved() = copy(
            visual = visual?.let { result.picks[it] ?: if (it in result.picks) null else it },
            visuals = visuals.map { result.picks[it] ?: it },
        )
        val resolved = score.copy(scenes = score.scenes.map { s -> s.copy(beats = s.beats.mapNotNull { b -> b.resolved().takeIf { r -> !(b.recipe == "object" && r.visual == null) } }) })
        return resolved to result.library
    }

    /**
     * A director respelling in Persian must land on a word the lexicon or the brief knows: a small
     * model sometimes "fixes" a rare word into a non-word («ووچر» → «وچر»).
     */
    private fun real(word: String, lexicon: PersianSpelling, prompt: String): Boolean {
        val core = word.trim { !it.isLetterOrDigit() && it != '\u200C' }
        if (core.none { it in '\u0600'..'\u06FF' }) return true
        val said = prompt.replace("\u200C", "").split(Regex("[^\\p{L}\\p{N}]+")).toSet()
        return lexicon.count(core) > 0 || core.replace("\u200C", "") in said
    }

    private fun compile(compiler: Compiler, score: Score, transcript: Transcript, edit: EditPlan, request: Request) =
        compiler.compile(Compiler.Input(score, transcript, footage = request.footage, fps = request.fps, edit = edit, subject = request.subject))

    private companion object {
        const val CRITIC_PASSES = 2
        const val COLD_OPEN_GAP = 0.25f
        const val TEMPERATURE = 0.3f
        /** Variation k of seed s is seed s × 1000 + k: far from the next seeds a creator asks for. */
        const val VARIATION_STRIDE = 1000L
        val BRAND_KINDS = setOf("brand", "app", "product", "organization")

        fun round1(v: Float) = kotlin.math.round(v * 10f) / 10f
    }
}
