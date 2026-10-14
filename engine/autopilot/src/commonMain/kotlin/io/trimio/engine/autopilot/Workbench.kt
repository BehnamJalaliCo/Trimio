package io.trimio.engine.autopilot

import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.transcript.Transcript
import io.trimio.engine.assets.MusicArrangement
import io.trimio.engine.assets.MusicMood
import io.trimio.engine.audio.LoudnessMeter
import io.trimio.engine.motion.TextAlign
import io.trimio.engine.motion.TextLayoutEngine
import io.trimio.engine.motion.recipe.BrandLibrary
import io.trimio.engine.motion.score.BeatScore
import io.trimio.engine.motion.score.Compiler
import io.trimio.engine.motion.score.EditPlan
import io.trimio.engine.motion.score.Score
import io.trimio.engine.motion.score.Subject
import io.trimio.engine.motion.visual.VectorIcon

/**
 * What a delivered production keeps so the creator's own changes (the editor) can be applied to it
 * without the director: the words as spelled, their lines and reading, the silence cut, the voice,
 * and the compiler with the marks and pictures the edit was given. Every function is deterministic
 * and fast (no model, no rendering): the same score, words and cut give the same edit.
 *
 * The quality gates stay in the loop: [gate] runs the on-screen spelling check and the supervising
 * [Critic] exactly as a draft does, and [judge] lets the [Jury] score the result. The creator's own
 * words ([gate]'s `own`) are respected: the spelling gate never rewrites or drops them, it only
 * warns when the lexicon does not know them.
 */
class Workbench internal constructor(
    /** The recognised words, spelled (brief, lexicon, director), on the source clock. */
    val transcript: Transcript,
    /** The transcript's lines (thoughts), sharing its word indices. */
    val lines: List<Lines.Line>,
    /** Silences the voice really has, in source seconds. */
    val pauses: List<ClosedFloatingPointRange<Float>>,
    /** The order the edit was made for: the voice, the prompt, the seed, the footage and the speaker's place. */
    internal val request: Autopilot.Request,
    val understanding: Understanding,
    val lexicon: PersianSpelling,
    val compiler: Compiler,
    private val jury: Jury?,
    private val brand: (String) -> Boolean,
    private val measure: LineMeasure,
    /** The levels the delivered soundtrack was mastered at. */
    val levels: Soundtrack.Levels = Soundtrack.Levels(),
    /** Marks found online for this piece (in [compiler]'s brand library), kept with the edit ([Delivery]). */
    internal val marks: Map<String, BrandLibrary.Entry> = emptyMap(),
    /** Pictures fetched from the web for this piece (in [compiler]'s vocabulary), kept with the edit ([Delivery]). */
    internal val icons: List<VectorIcon> = emptyList(),
    /**
     * The edit is its words (a plain reel, a YouTube video): the creator's changes go through the spelling check but not
     * the reel critic, which would add a hook, punch-ins and a call to action the creator did not ask for.
     */
    val plain: Boolean = false,
    /**
     * The file carries the recording's own sound (a YouTube video): nothing is mastered and the cut stays whole, so the
     * export copies the recording's track untouched.
     */
    val recorded: Boolean = false,
    /** The edit's length when it is not the speech's (a YouTube video runs to the recording's end). */
    val duration: Float? = null,
) {
    /** The voice, 48 kHz mono, on the source clock. */
    val voice: PcmAudio get() = request.voice
    val prompt: String get() = request.prompt
    val seed: Long get() = request.seed
    private val footage: String? get() = request.footage
    private val fps: Int get() = request.fps
    private val subject: Subject? get() = request.subject
    private val assets: List<String> get() = request.assets.map { it.id }

    /** Seconds of source. */
    val sourceDuration: Float get() = voice.samples.size / voice.sampleRate.toFloat()

    /** A score through the gates and compiled: [score] with what the spelling check and the critic changed. */
    data class Gated(val score: Score, val compiled: Compiler.Output, val input: Compiler.Input, val warnings: List<String>, val notes: List<String>)

    /** What the compiler is given for [score] over [words] (source clock) cut by [edit]. */
    fun input(score: Score, words: Transcript, edit: EditPlan) =
        Compiler.Input(score, words, duration = duration, footage = footage, fps = fps, edit = edit, subject = subject, voices = request.voices)

    /**
     * [score] spelled, compiled and through the critic's passes (each revision recompiled), as a
     * draft goes. [own] are the creator's words: they count as heard, so nothing of theirs is
     * changed, and each one the lexicon does not know becomes a warning instead. Beats [refused]
     * (the creator turned them off) are not put back by the critic.
     */
    fun gate(score: Score, words: Transcript, edit: EditPlan, own: Collection<String> = emptyList(), refused: (BeatScore) -> Boolean = { false }): Gated {
        val notes = mutableListOf<String>()
        val heard = transcript.words.map { it.text }
        val said = heard.map(::core).toSet()
        val warnings = own.flatMap { it.split(' ') }.map(::core).filter { w -> persian(w) && lexicon.count(w) == 0 && w !in said }.distinct()
        var current = ScreenSpelling(lexicon, heard, prompt, own).check(score) { notes += it }
        var compiled = compiler.compile(input(current, words, edit))
        val output = edit.remap(words).words
        repeat(if (plain) 0 else CRITIC_PASSES) {
            val review = Critic().review(current, compiled, output, lines, understanding.cta)
            review.issues.forEach { notes += "critic: ${it.kind} at ${it.at}s — ${it.detail}" }
            // What the creator turned off stays off: the critic does not put it back (the jury still says so).
            val revised = review.revised?.let { r -> r.copy(scenes = r.scenes.map { sc -> sc.copy(beats = sc.beats.filterNot(refused)) }) }
                ?.takeIf { it != current } ?: return@repeat
            current = revised
            compiled = compiler.compile(input(current, words, edit))
        }
        return Gated(current, compiled, input(current, words, edit), warnings, notes)
    }

    /** The cue for [mood] composed to [edit] (null without music): the drop on the payoff or the restart, the breakdown under the ask. */
    fun arrange(mood: MusicMood?, edit: EditPlan, words: Transcript): MusicArrangement? =
        MusicEdit.arrange(mood, edit, edit.remap(words).words, lines, understanding)

    /** [score] on the cue's grid, so hits snap to its beats. */
    fun gridded(score: Score, arrangement: MusicArrangement?): Score = MusicEdit.gridded(score, arrangement)

    /** The mastered soundtrack of [compiled] (its cues) over the voice cut by [edit], with [mood] at [levels]. */
    fun master(
        compiled: Compiler.Output,
        edit: EditPlan,
        words: Transcript,
        mood: MusicMood?,
        arrangement: MusicArrangement?,
        levels: Soundtrack.Levels,
    ): Soundtrack.Result {
        if (recorded) {
            // The recording's own track plays (copied at export): the voice stands in for it, measured, never mixed.
            val measured = LoudnessMeter.measure(voice)
            return Soundtrack.Result(voice, measured.integratedLufs, measured.samplePeakDb)
        }
        return Soundtrack(voice.sampleRate).master(
            voice, edit, edit.preludeWords(words) + edit.remap(words).words, compiled.composition.duration, mood,
            musicSeed = seed.toInt(), cues = compiled.sfx, levels = levels, arrangement = arrangement,
        )
    }

    /** The panel's verdict on the edit as it is (no revisions: the creator's choices stand); null when no jury sits. */
    fun judge(
        gated: Gated,
        edit: EditPlan,
        words: Transcript,
        mood: MusicMood?,
        arrangement: MusicArrangement?,
        sound: Soundtrack.Result,
        levels: Soundtrack.Levels,
    ): Jury.Panel? = jury?.deliberate(
        Jury.Context(
            compiled = gated.compiled, score = gated.score, words = edit.remap(words).words, lines = lines, understanding = understanding,
            prompt = prompt, music = mood, arrangement = arrangement, edit = edit, source = words.words, prelude = edit.preludeWords(words),
            subject = subject, footage = footage != null, sound = sound, levels = levels, lexicon = lexicon, measure = measure, voice = voice,
            brand = brand, assets = assets,
        ),
    )

    companion object {
        private const val CRITIC_PASSES = 2
        private const val ZWNJ = '‌'

        /**
         * The workbench of a plain edit (a plain reel, a YouTube video): the creator changes its words, look and elements;
         * no critic and no jury, since neither the reel's hook nor its call to action belongs there. [recorded]: the
         * recording's own sound plays and the cut stays whole (YouTube); [duration]: the edit's length when it is not the
         * speech's.
         */
        internal fun plain(
            text: TextLayoutEngine,
            brands: BrandLibrary,
            compiler: Compiler,
            request: Autopilot.Request,
            lines: List<Lines.Line>,
            pauses: List<ClosedFloatingPointRange<Float>>,
            understanding: Understanding,
            lexicon: PersianSpelling,
            recorded: Boolean = false,
            duration: Float? = null,
        ) = Workbench(
            request.transcript, lines, pauses, request, understanding, lexicon, compiler, null, { brands.find(it)?.isMonogram == false },
            LineMeasure { s, type, maxWidth, rtl -> text.layout(s, type, maxWidth, TextAlign.Center, rtl).lines.size },
            plain = true, recorded = recorded, duration = duration,
        )

        private fun core(w: String) = w.trim { !it.isLetterOrDigit() && it != ZWNJ }.trim(ZWNJ)
        private fun persian(w: String) = w.any { it in 'ؠ'..'ۿ' && it.isLetter() }
    }
}
