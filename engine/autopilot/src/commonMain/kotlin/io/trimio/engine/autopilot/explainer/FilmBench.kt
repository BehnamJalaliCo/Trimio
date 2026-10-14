package io.trimio.engine.autopilot.explainer

import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.transcript.Transcript
import io.trimio.engine.assets.MusicMood
import io.trimio.engine.autopilot.Autopilot
import io.trimio.engine.autopilot.Choices
import io.trimio.engine.autopilot.Planner
import io.trimio.engine.autopilot.Soundtrack
import io.trimio.engine.autopilot.Understanding
import io.trimio.engine.motion.explainer.ExplainerFilm
import io.trimio.engine.motion.explainer.PaletteBook
import io.trimio.engine.motion.recipe.Sfx
import io.trimio.engine.motion.recipe.SfxKind
import io.trimio.engine.motion.score.Compiler
import io.trimio.engine.motion.score.EditPlan
import io.trimio.engine.motion.score.Score
import kotlinx.serialization.Serializable

/**
 * An explainer film as it is kept on disk: the film the director wrote and what its sound was made of. Nothing that is
 * made again from the creator's recording (the voice) or derived (the layout, the mix): [ExplainerPilot.restore] draws
 * and mixes the same film from it, without the director, after the app restarts.
 */
@Serializable
data class FilmCut(
    val film: ExplainerFilm,
    /** The music mood id, or null for none. */
    val music: String? = null,
    val seed: Long,
    val format: String = "9:16",
    val fps: Int = 30,
    /** Seconds: the narration's length. */
    val duration: Float,
    val understanding: Understanding,
    /** The narration's words as spelled (where the music ducks). */
    val transcript: Transcript,
    val report: List<String> = emptyList(),
)

/**
 * What an explainer film is drawn and mixed again with: for exports in another frame, after a restart, and for the
 * creator's own changes in the editor (the palette, the music and its level, the whooshes). Deterministic: the same
 * cut and choices always give the same film and mix.
 */
class FilmBench(private val stage: FilmStage, val cut: FilmCut, private val voice: PcmAudio) {
    /** The film in [palette] (a [PaletteBook] id; anything else keeps the director's). */
    fun film(palette: String?): ExplainerFilm =
        palette?.takeIf { it != cut.film.art.palette && it in PaletteBook.ids }?.let { cut.film.copy(art = cut.film.art.copy(palette = it)) } ?: cut.film

    /** [film] laid out for [format] at [fps]. */
    fun compiled(film: ExplainerFilm, format: String = cut.format, fps: Int = cut.fps): Compiler.Output =
        Compiler.Output(stage.compose(film, format, fps, cut.duration), emptyList(), emptyList(), listOf("explainer film $format"))

    /** The narration mastered with [music] under it at [musicDb] (null: the director's level), the whooshes when [effects]. */
    fun master(film: ExplainerFilm, music: MusicMood?, musicDb: Float? = null, effects: Boolean = true, duration: Float = cut.duration): Soundtrack.Result {
        val levels = musicDb?.let { Soundtrack.Levels(musicUnderDb = it, musicGapDb = it + GAP_LIFT_DB) } ?: Soundtrack.Levels()
        return Soundtrack(voice.sampleRate).master(
            voice, EditPlan.identity(duration), cut.transcript.words, duration, music, cut.seed.toInt(), cues = if (effects) cues(film) else emptyList(), levels = levels,
        )
    }

    /**
     * The film as a production: [palette] and [music] the creator's (null palette: the director's), mixed when [sound]
     * is given (else [sound] stands, the picture alone being made).
     */
    fun production(
        palette: String? = null,
        music: MusicMood? = director,
        report: List<String> = cut.report,
        panel: io.trimio.engine.autopilot.Jury.Panel? = null,
        sound: Soundtrack.Result? = null,
        musicDb: Float? = null,
        effects: Boolean = true,
        /** The film already laid out (the director's run draws it once). */
        laid: Compiler.Output? = null,
    ): Autopilot.Production {
        val film = film(palette)
        val compiled = laid ?: compiled(film)
        val duration = compiled.composition.duration
        val mix = sound ?: master(film, music, musicDb, effects, duration)
        val score = Score(format = cut.format, look = film.art.palette)
        val primitives = film.chapters.flatMap { c -> c.beats.mapNotNull { it.primitive?.let(Drafts::serial) } }.distinct()
        val plan = Planner.Plan(score, music, Choices(cut.seed, film.art.palette, music?.id, primitives, emptyList()), emptyList())
        // The same film in another frame (the export screen's 16:9 for YouTube): the stage lays it out again.
        val restage = { format: String, fps: Int -> compiled(film, format, fps) }
        return Autopilot.Production(
            compiled, score, plan, cut.understanding, mix, EditPlan.identity(duration), report, panel, film = film, restage = restage, filmBench = this,
        )
    }

    /** The director's music. */
    val director: MusicMood? get() = cut.music?.let(MusicMood::fromId)

    companion object {
        /** The bed rises this much between words, above its level under them (the director's mix: -20 / -12). */
        private const val GAP_LIFT_DB = 8f
        private const val CUE_GAIN = 0.55f

        /** A soft whoosh where a new chapter (or news scene) arrives; nothing under the first one. */
        fun cues(film: ExplainerFilm): List<Sfx> = film.chapters.drop(1).map { Sfx(it.start.toFloat(), SfxKind.Whoosh, CUE_GAIN) }
    }
}
