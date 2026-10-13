package io.trimio.engine.autopilot.explainer

import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.transcript.Transcript
import io.trimio.engine.autopilot.Autopilot
import io.trimio.engine.autopilot.Choices
import io.trimio.engine.autopilot.Lines
import io.trimio.engine.autopilot.PersianSpelling
import io.trimio.engine.autopilot.Planner
import io.trimio.engine.autopilot.Proofreader
import io.trimio.engine.autopilot.RulesUnderstander
import io.trimio.engine.autopilot.Soundtrack
import io.trimio.engine.autopilot.SpeechAnalysis
import io.trimio.engine.autopilot.Understander
import io.trimio.engine.autopilot.Understanding
import io.trimio.engine.autopilot.explainer.judges.FilmJury
import io.trimio.engine.autopilot.withSpelling
import io.trimio.engine.llm.LanguageModel
import io.trimio.engine.motion.Composition
import io.trimio.engine.motion.explainer.ArtDirection
import io.trimio.engine.motion.explainer.ExplainerFilm
import io.trimio.engine.motion.score.Compiler
import io.trimio.engine.motion.score.EditPlan
import io.trimio.engine.motion.score.Score
import kotlin.time.Duration.Companion.minutes

/**
 * Draws an [ExplainerFilm] as a composition the exporter renders: the stage renderer's entry
 * point (engine/motion `explainer`), behind this small interface so the pipeline does not depend
 * on how it is drawn.
 */
fun interface FilmStage {
    /** [film] laid out for [format] ("9:16", "16:9") at [fps], [duration] seconds long. */
    fun compose(film: ExplainerFilm, format: String, fps: Int, duration: Float): Composition

    /** Gets what [film] draws ready before [compose] (its hero illustrations); answers report lines. */
    suspend fun prepare(film: ExplainerFilm): List<String> = emptyList()
}

/**
 * The audio-only explainer, end to end: listen (the voice's words, spelled as the brief and the
 * lexicon write them), understand (a cloud model when connected, else the rules: the film's
 * director reads everything anyway), write the film ([Screenwriter]: director, checks, jury),
 * draw it ([FilmStage]) and master the narration with music under it ([Soundtrack]). The result is
 * an [Autopilot.Production] like any edit's, so the app renders, previews and exports it the same way.
 */
class ExplainerPilot(
    private val stage: FilmStage,
    private val model: LanguageModel? = null,
    private val jury: FilmJury? = FilmJury(),
    /** Model time for one film on the device; the rules write the rest. */
    private val budget: kotlin.time.Duration = MODEL_BUDGET,
) {
    data class Request(
        val transcript: Transcript,
        val voice: PcmAudio,
        val prompt: String,
        val seed: Long,
        val understanding: Understanding? = null,
        val fps: Int = 30,
        val format: String = "9:16",
        /** Looks of films already made of this narration (earlier seeds): the new one differs. */
        val others: List<ArtDirection> = emptyList(),
    )

    suspend fun produce(request: Request, onStage: (String, Float) -> Unit = { _, _ -> }): Autopilot.Production {
        val report = mutableListOf<String>()
        onStage("listen", 0f)
        // No cuts: the narration plays as recorded; the film is timed to it.
        val heard = SpeechAnalysis.of(request.voice, request.transcript, tightenCuts = false)
        val lexicon = PersianSpelling.load()
        val texts = heard.transcript.words.map { it.text }
        val spelled = Proofreader.fromBrief(texts, request.prompt, lexicon)
        val lexical = Proofreader.fromLexicon(texts, request.prompt, spelled)
        (spelled + lexical).forEach { (i, w) -> report += "spelling: ${texts[i]} → ${w.ifEmpty { "(joined)" }}" }
        val transcript = heard.transcript.withSpelling(spelled + lexical)
        val duration = heard.duration
        report += "listen: ${transcript.words.size} words, ${round1(duration)}s"

        onStage("understand", 0.1f)
        val lines = Lines.split(transcript.words, pauses = heard.pauses)
        val understanding = request.understanding ?: understand(transcript, request, lines, report)
        val n = Narration.of(transcript.words, request.prompt, understanding, heard.pauses, duration.toDouble(), lexicon)
        report += "understand: ${understanding.title} | ${understanding.domain} | ${n.clauses.size} clauses"

        onStage("write", 0.3f)
        val director: FilmDirector = model?.let { LlmDirector(it, budget = if (it.isLocal) budget else null) } ?: RulesDirector()
        val writer = Screenwriter(director, jury, askAgain = model?.isLocal == false || model == null)
        val written = writer.write(n, request.seed, request.others) { report += it }
        val film = leadIn(written.film)
        Beats.table(film).forEach { report += it }

        onStage("draw", 0.8f)
        report += runCatching { stage.prepare(film) }.getOrElse { listOf("illustrations: failed (${it.message}); the stage draws its own glyphs") }
        val composition = stage.compose(film, request.format, request.fps, duration)
        onStage("sound", 0.9f)
        val music = ArtDirector.music(n, request.seed)
        val edit = EditPlan.identity(composition.duration)
        val sound = Soundtrack(request.voice.sampleRate).master(request.voice, edit, transcript.words, composition.duration, music, request.seed.toInt(), cues = cues(film))
        report += "sound: ${music?.id ?: "no music"} under the narration, ${round1(sound.lufs.toFloat())} LUFS"
        onStage("done", 1f)
        val score = Score(format = request.format, look = film.art.palette)
        val choices = Choices(request.seed, film.art.palette, music?.id, film.chapters.flatMap { c -> c.beats.mapNotNull { it.primitive?.let(Drafts::serial) } }.distinct(), emptyList())
        val plan = Planner.Plan(score, music, choices, emptyList())
        val compiled = Compiler.Output(composition, emptyList(), emptyList(), listOf("explainer film"))
        return Autopilot.Production(compiled, score, plan, understanding, sound, edit, report, written.panel, film = film)
    }

    /** A cloud model reads the lines (English gists help the metaphors); on the device the rules read them (the director reads everything). */
    private suspend fun understand(transcript: Transcript, request: Request, lines: List<Lines.Line>, report: MutableList<String>): Understanding {
        val m = model?.takeIf { !it.isLocal } ?: return RulesUnderstander.understand(transcript, request.prompt, lines)
        return runCatching { Understander(m).understand(transcript, request.prompt, lines, seed = request.seed.toInt(), budget = UNDERSTAND_BUDGET) }
            .onFailure { report += "understand: ${m.id} failed (${it.message}); the rules read it" }
            .getOrElse { RulesUnderstander.understand(transcript, request.prompt, lines) }
    }

    /**
     * No chapter stands empty under its heading while the voice already speaks: a chapter whose first
     * beat comes later than [LEAD] after it opens has that beat brought in at the opening (diagram films;
     * a news scene opens on its headline anyway).
     */
    private fun leadIn(film: ExplainerFilm): ExplainerFilm {
        if (film.art.grammar != io.trimio.engine.motion.explainer.FilmGrammar.DiagramChapters.id) return film
        return film.copy(
            chapters = film.chapters.map { c ->
                val first = c.beats.firstOrNull()?.takeIf { it.at - c.start > LEAD } ?: return@map c
                c.copy(beats = listOf(first.copy(at = c.start + OPEN)) + c.beats.drop(1))
            },
        )
    }

    /** A soft whoosh where a new chapter (or news scene) arrives; nothing under the first one. */
    private fun cues(film: ExplainerFilm): List<io.trimio.engine.motion.recipe.Sfx> =
        film.chapters.drop(1).map { io.trimio.engine.motion.recipe.Sfx(it.start.toFloat(), io.trimio.engine.motion.recipe.SfxKind.Whoosh, CUE_GAIN) }

    private companion object {
        const val CUE_GAIN = 0.55f
        const val LEAD = 1.2
        const val OPEN = 0.5
        val MODEL_BUDGET = 12.minutes
        val UNDERSTAND_BUDGET = 3.minutes
        fun round1(v: Float) = kotlin.math.round(v * 10f) / 10f
    }
}

/** A film as a readable beat table, for the report and the proof. */
object Beats {
    fun table(f: ExplainerFilm): List<String> = buildList {
        add("film: ${f.language}, ${f.art.palette} / ${f.art.layout} / ${f.art.chapterMark} / ${f.art.captionStyle}, ${f.captions.size} caption chunks")
        for ((i, c) in f.chapters.withIndex()) {
            add("chapter ${i + 1} «${c.title}» ${fmt(c.start)}–${fmt(c.end)} accent ${c.accent}")
            for (b in c.beats) {
                val what = listOfNotNull(
                    b.content.text.takeIf { it.isNotBlank() }?.let { "«$it»" },
                    b.content.caption.takeIf { it.isNotBlank() }?.let { "caption «$it»" },
                    b.content.items.takeIf { it.isNotEmpty() }?.let { items -> "items [" + items.joinToString(", ") { it.label.ifBlank { "·" } } + "]" },
                    b.content.value?.let { "value $it${b.content.unit}" },
                    b.content.picks.takeIf { it.isNotEmpty() }?.let { "picks $it" },
                ).joinToString(" ")
                val pic = b.primitive?.let { " " + Drafts.serial(it) }.orEmpty()
                add("  ${fmt(b.at)}–${fmt(b.until)} ${Drafts.serial(b.op)} ${b.element}$pic $what  ← ${b.line.take(LINE)} | ${b.metaphor}")
            }
        }
    }

    private fun fmt(v: Double) = (kotlin.math.round(v * 10) / 10).toString()
    private const val LINE = 48
}

/** The stage renderer of engine/motion ([io.trimio.engine.motion.explainer.ExplainerStage]) as the pipeline's [FilmStage]. */
class StageRenderer(
    private val text: io.trimio.engine.motion.TextLayoutEngine,
    private val icons: io.trimio.engine.motion.explainer.IconResolver = io.trimio.engine.motion.explainer.IconResolver.None,
) : FilmStage {
    override fun compose(film: ExplainerFilm, format: String, fps: Int, duration: Float): Composition {
        val (w, h) = size(format)
        return io.trimio.engine.motion.explainer.ExplainerStage(text, icons).compose(film, w, h, fps).composition
    }

    companion object {
        /** Frame size for a format: 1080 on the short side. */
        fun size(format: String): Pair<Int, Int> = when (format.trim()) {
            "16:9" -> 1920 to 1080
            "1:1" -> 1080 to 1080
            "4:5" -> 1080 to 1350
            else -> 1080 to 1920
        }
    }
}
