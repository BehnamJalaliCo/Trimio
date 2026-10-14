package io.trimio.engine.autopilot

import io.trimio.engine.assets.MusicMood

/**
 * The music bed by meaning (8d.3, text↔sound): with the retrieval add-on installed, the piece's topic, mood and
 * opening words are matched against the music library's descriptions; the match is used only when the brief leaves
 * the music open and the match fits the brief's energy (a calm brief never gets a driving bed). Without the add-on
 * nothing changes: the planner's own rules choose.
 */
object MusicShelf {
    /** The retrieval's best music id for a description of the moment ("tense"), or null; set by the app. */
    var recall: (suspend (String) -> String?)? = null

    /** [u] with its brief's music named by meaning, when the add-on answers and the answer fits; [u] as it is otherwise. */
    suspend fun pick(u: Understanding, opening: String, log: (String) -> Unit = {}): Understanding {
        val search = recall ?: return u
        if (u.brief.music != "auto") return u
        val moment = listOf(u.topic, u.mood, u.domain, opening.take(OPENING)).filter { it.isNotBlank() }.joinToString(". ")
        val mood = runCatching { search(moment) }.getOrNull()?.let(MusicMood::fromId) ?: return u
        if (!fits(mood, u.brief.energy)) return u
        log("music: ${mood.id} by meaning (the retrieval add-on)")
        return u.copy(brief = u.brief.copy(music = mood.id))
    }

    /** The music judge's energy rule: a calm brief stays calm, a high-energy one drives. */
    private fun fits(mood: MusicMood, energy: Float): Boolean = when {
        energy >= HIGH -> mood.bpm >= FAST_BPM && mood in DRIVING
        energy in 0f..LOW -> mood.bpm <= CALM_BPM && mood in CALM
        else -> true
    }

    private const val OPENING = 300
    private const val HIGH = 0.7f
    private const val LOW = 0.4f
    private const val FAST_BPM = 105
    private const val CALM_BPM = 100
    private val DRIVING = setOf(MusicMood.Energetic, MusicMood.Uplifting, MusicMood.Corporate)
    private val CALM = setOf(MusicMood.Chill, MusicMood.Cinematic, MusicMood.Corporate)
}
