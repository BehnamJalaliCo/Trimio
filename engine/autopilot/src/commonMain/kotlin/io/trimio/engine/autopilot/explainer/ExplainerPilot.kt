package io.trimio.engine.autopilot.explainer

import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.transcript.Transcript
import io.trimio.engine.autopilot.Autopilot
import io.trimio.engine.autopilot.Lines
import io.trimio.engine.autopilot.PersianSpelling
import io.trimio.engine.autopilot.Proofreader
import io.trimio.engine.autopilot.RulesUnderstander
import io.trimio.engine.autopilot.SpeechAnalysis
import io.trimio.engine.autopilot.Understander
import io.trimio.engine.autopilot.Understanding
import io.trimio.engine.autopilot.explainer.judges.FilmJury
import io.trimio.engine.autopilot.withSpelling
import io.trimio.engine.llm.LanguageModel
import io.trimio.engine.motion.Composition
import io.trimio.engine.motion.explainer.ArtDirection
import io.trimio.engine.motion.explainer.ExplainerFilm
import kotlin.time.Duration.Companion.minutes

/**
 * Draws an [ExplainerFilm] as a composition the exporter renders: the stage renderer's entry
 * point (engine/motion `explainer`), behind this small interface so the pipeline does not depend
 * on how it is drawn.
 */
fun interface FilmStage {
    /** [film] laid out for [format] ("9:16", "16:9") at [fps], [duration] seconds long. */
    fun compose(film: ExplainerFilm, format: String, fps: Int, duration: Float): Composition

    /**
     * Gets what [film] draws ready before [compose] (its hero illustrations); answers report lines. [onProgress] (0..1)
     * moves with every picture, and with every step of a generated one.
     */
    suspend fun prepare(film: ExplainerFilm, onProgress: (Float) -> Unit = {}): List<String> = emptyList()
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
        val film = endCard(leadIn(written.film))
        Beats.table(film).forEach { report += it }

        onStage("draw", DRAW_AT)
        // The director's memory goes before the pictures are drawn: a phone holds one big model at a time (the generator add-on).
        runCatching { model?.release() }
        report += runCatching { stage.prepare(film) { p -> onStage("draw", DRAW_AT + DRAW_SPAN * p) } }
            .getOrElse { listOf("illustrations: failed (${it.message}); the stage draws its own glyphs") }
        val music = ArtDirector.music(n, request.seed)
        val cut = FilmCut(film, music?.id, request.seed, request.format, request.fps, duration, understanding, transcript)
        val bench = FilmBench(stage, cut, request.voice)
        val compiled = bench.compiled(film)
        onStage("sound", 0.9f)
        val sound = bench.master(film, music, duration = compiled.composition.duration)
        report += "sound: ${music?.id ?: "no music"} under the narration, ${round1(sound.lufs.toFloat())} LUFS"
        onStage("done", 1f)
        // What is kept to draw and mix it again (a reopen after a restart, the editor).
        val kept = FilmBench(stage, cut.copy(report = report.toList()), request.voice)
        return kept.production(report = report.toList(), panel = written.panel, sound = sound, laid = compiled)
    }

    /**
     * The film [cut] describes, drawn and mixed again over [voice] without the director (its pictures fetched again):
     * the delivered film itself, for the editor and for exports after the app restarts.
     */
    suspend fun restore(cut: FilmCut, voice: PcmAudio): Autopilot.Production {
        runCatching { stage.prepare(cut.film) }
        return FilmBench(stage, cut, voice).production()
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

    /**
     * The film ends on a card, not on a passing beat: the last chapter's call to action (its badge), else its
     * last beat, comes up alone at least [END_CARD] seconds before the end and stays to the last frame.
     */
    private fun endCard(film: ExplainerFilm): ExplainerFilm {
        if (film.art.grammar != io.trimio.engine.motion.explainer.FilmGrammar.DiagramChapters.id) return film
        val c = film.chapters.lastOrNull() ?: return film
        val beats = c.beats.sortedBy { it.at }
        val card = beats.lastOrNull { it.primitive == io.trimio.engine.motion.explainer.Primitive.Badge } ?: beats.lastOrNull { it.primitive != null } ?: return film
        val at = minOf(card.at, maxOf(c.start + OPEN, c.end - END_CARD))
        val before = beats.filter { it.at < at && it !== card }
        val sweep = io.trimio.engine.motion.explainer.Beat(at, at, card.line, io.trimio.engine.motion.explainer.StageOp.Clear, "*", metaphor = "the stage clears for the end card")
        val shown = card.copy(at = at + CARD_GAP, until = c.end, op = io.trimio.engine.motion.explainer.StageOp.Add, element = "end-card")
        return film.copy(chapters = film.chapters.dropLast(1) + c.copy(beats = before + sweep + shown))
    }

    private companion object {
        const val DRAW_AT = 0.8f
        const val DRAW_SPAN = 0.1f
        const val LEAD = 1.2
        const val OPEN = 0.5
        const val END_CARD = 2.5
        const val CARD_GAP = 0.3
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
