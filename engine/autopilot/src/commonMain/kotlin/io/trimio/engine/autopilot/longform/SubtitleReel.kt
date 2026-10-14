package io.trimio.engine.autopilot.longform

import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.transcript.Transcript
import io.trimio.engine.autopilot.Autopilot
import io.trimio.engine.autopilot.Choices
import io.trimio.engine.autopilot.Glossary
import io.trimio.engine.autopilot.Lines
import io.trimio.engine.autopilot.OnScreenSpelling
import io.trimio.engine.autopilot.PersianSpelling
import io.trimio.engine.autopilot.Planner
import io.trimio.engine.autopilot.Proofreader
import io.trimio.engine.autopilot.Soundtrack
import io.trimio.engine.autopilot.SpeechAnalysis
import io.trimio.engine.autopilot.Understanding
import io.trimio.engine.autopilot.Workbench
import io.trimio.engine.autopilot.withSpelling
import io.trimio.engine.llm.LanguageModel
import io.trimio.engine.motion.TextLayoutEngine
import io.trimio.engine.motion.recipe.BrandLibrary
import io.trimio.engine.motion.recipe.SubtitleStyle
import io.trimio.engine.motion.score.CaptionScore
import io.trimio.engine.motion.score.Compiler
import io.trimio.engine.motion.score.SceneScore
import io.trimio.engine.motion.score.Score
import io.trimio.engine.motion.score.Subject
import io.trimio.engine.motion.visual.VisualLibrary

/**
 * A reel with subtitles and nothing else (phase 9.9): the silences cut, every word on screen in the creator's subtitle
 * style ([SubtitleStyle]), the frame going tight and wide at each cut so the jumps read as edits, the voice mastered.
 * No graphics, no music, no cold open: some videos need only their words. The director (when there is one) only
 * proofreads what goes on screen.
 */
class SubtitleReel(
    private val text: TextLayoutEngine,
    private val brands: BrandLibrary,
    private val visuals: VisualLibrary,
    private val model: LanguageModel? = null,
) {
    data class Request(
        val transcript: Transcript,
        val voice: PcmAudio,
        val prompt: String,
        val seed: Long,
        val style: SubtitleStyle = SubtitleStyle.Pop,
        val format: String = "9:16",
        val footage: String = "main",
        val fps: Int = 30,
        val subject: Subject? = null,
        val voices: List<io.trimio.engine.motion.score.Voice> = emptyList(),
    )

    suspend fun produce(request: Request, onStage: (String, Float) -> Unit = { _, _ -> }): Autopilot.Production {
        val report = mutableListOf<String>()
        onStage("listen", 0f)
        val heard = SpeechAnalysis.of(request.voice, request.transcript, strict = SpeechAnalysis.strict(request.prompt))
        val lexicon = PersianSpelling.load()
        val texts = heard.transcript.words.map { it.text }
        val spelled = Proofreader.fromBrief(texts, request.prompt, lexicon)
        val lexical = Proofreader.fromLexicon(texts, request.prompt, spelled)
        (spelled + lexical).forEach { (i, w) -> report += "spelling: ${texts[i]} → ${w.ifEmpty { "(joined)" }}" }
        onStage("words", WORDS_AT)
        val names = Glossary.read(request.prompt).terms.keys + request.prompt.split(' ', '\n', '،', ',')
        val checked = OnScreenSpelling.check(heard.transcript.withSpelling(spelled + lexical), lexicon, names, model) { report += it }
        val words = checked.words.words
        val edit = heard.edit
        report += "listen: ${words.size} words, ${edit.cutCount} cuts, ${round1(heard.duration)}s → ${round1(edit.duration)}s (subtitles only)"

        onStage("compile", COMPILE_AT)
        // A scene at each cut: the footage alternates tight and wide there (the compiler's punch-in), so a jump reads as an edit.
        val scenes = edit.segments.mapNotNull { seg -> words.indexOfFirst { it.range.startMs / MS >= seg.sourceStart - CUT_SLACK }.takeIf { it >= 0 } }
            .distinct().map { SceneScore(from = it, bg = "media", transition = "cut") }
            .ifEmpty { listOf(SceneScore(from = 0, bg = "media")) }
        val style = request.style
        val captions = CaptionScore(show = true, recipe = style.recipe, place = "lower", maxWords = style.maxWords)
        val score = Score(format = request.format, look = LOOK, captions = captions, scenes = scenes, auto = false)
        val compiler = Compiler(text, brands, visuals)
        val input = Compiler.Input(score, checked.shown, footage = request.footage, fps = request.fps, edit = edit, subject = request.subject, voices = request.voices)
        val compiled = compiler.compile(input)
        onStage("sound", SOUND_AT)
        val sound = Soundtrack(request.voice.sampleRate).master(
            request.voice, edit, edit.remap(checked.shown).words, compiled.composition.duration, null, request.seed.toInt(), cues = compiled.sfx,
        )
        report += "subtitles: ${style.id}, ${compiled.beats.count { it.recipe == style.recipe }} lines; sound ${round1(sound.lufs.toFloat())} LUFS"
        onStage("done", 1f)
        val understanding = Understanding(title = words.take(TITLE_WORDS).joinToString(" ") { it.text }, topic = "subtitles")
        val plan = Planner.Plan(score, null, Choices(request.seed, score.look, null, listOf(style.recipe), emptyList()), emptyList())
        // The editor changes its words, cut, look and sound like a reel's, without the reel's critic.
        val ask = Autopilot.Request(checked.shown, request.voice, request.prompt, request.seed, request.footage, request.subject, understanding, request.fps, voices = request.voices)
        val lines = Lines.split(words, pauses = heard.pauses)
        val bench = Workbench.plain(text, brands, compiler, ask, lines, heard.pauses, understanding, lexicon)
        return Autopilot.Production(compiled, score, plan, understanding, sound, edit, report, source = Autopilot.Compiled(compiler, input), bench = bench)
    }

    private companion object {
        const val MS = 1000f
        const val LOOK = "noir"
        const val WORDS_AT = 0.2f
        const val COMPILE_AT = 0.6f
        const val SOUND_AT = 0.85f
        const val CUT_SLACK = 0.05f
        const val TITLE_WORDS = 6

        fun round1(v: Float): Float = (v * 10).toInt() / 10f
    }
}
