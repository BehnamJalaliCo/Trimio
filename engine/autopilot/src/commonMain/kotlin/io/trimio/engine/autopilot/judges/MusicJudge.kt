package io.trimio.engine.autopilot.judges

import io.trimio.engine.assets.MusicMood
import io.trimio.engine.autopilot.Finding
import io.trimio.engine.autopilot.Judge
import io.trimio.engine.autopilot.Jury
import io.trimio.engine.autopilot.RulesUnderstander
import io.trimio.engine.autopilot.Severity
import io.trimio.engine.autopilot.Verdict
import io.trimio.engine.motion.score.Score
import kotlin.math.abs

/**
 * The music supervisor. Music plays unless the brief says none (and never when it does); a mood
 * the brief names is the mood used; otherwise tempo and mood follow the brief's energy — a
 * high-energy brief gets [fastBpm]+ and a driving mood, a calm one stays under [calmBpm]; the
 * edit's beat grid is the track's; and the piece ends cleanly, the bed faded out by the last
 * frame (the last [tailMs] ms nearly silent).
 */
class MusicJudge(
    private val fastBpm: Int = 105,
    private val calmBpm: Int = 100,
    private val tailMs: Int = 15,
) : Judge {
    override val name = "music"
    override val dimension = "music"

    override fun score(c: Jury.Context): Verdict {
        val f = mutableListOf<Finding>()
        val brief = RulesUnderstander.brief(c.prompt)
        val none = brief.music == "none" || c.understanding.brief.music == "none"
        val music = c.music
        when {
            none && music != null -> f += Bench.finding(0f, Severity.Critical, "music plays though the brief says none", "drop the music")
            !none && music == null -> f += Bench.finding(0f, Severity.Major, "no music under the voice", "a bed that fits the brief")
        }
        if (music != null) {
            f += fit(c, music, brief.energy)
            val bpm = c.score.bpm
            if (bpm == null || abs(bpm - music.bpm) > 0.5f) {
                f += Bench.finding(0f, Severity.Minor, "the beat grid (${bpm ?: "none"}) is not the track's ${music.bpm} bpm", "snap hits to the track's grid")
            }
        }
        val sound = c.sound
        if (sound != null && music != null) {
            val x = sound.audio.samples
            val n = (sound.audio.sampleRate * tailMs / 1000).coerceAtMost(x.size)
            val last = (x.size - n until x.size).maxOfOrNull { abs(x[it]) } ?: 0f
            if (last > END_PEAK) {
                f += Bench.finding(c.duration, Severity.Minor, "the mix is still at ${(last * 100).toInt()}% on the last frame", "fade the bed out over the last 0.8 s")
            }
        }
        return Verdict.of(this, f)
    }

    /** Tempo and mood against the brief: a named mood is binding, else energy decides. */
    private fun fit(c: Jury.Context, music: MusicMood, briefEnergy: Float): List<Finding> {
        val named = MusicMood.fromId(c.understanding.brief.music)
        if (named != null && named != music) return listOf(Bench.finding(0f, Severity.Major, "the brief asks for ${named.id} music, the edit plays ${music.id}", "use ${named.id}"))
        val energy = if (briefEnergy >= 0f) briefEnergy else c.understanding.brief.energy
        return when {
            energy >= HIGH && (music.bpm < fastBpm || music !in DRIVING) ->
                listOf(Bench.finding(0f, Severity.Major, "a high-energy brief under ${music.id} at ${music.bpm} bpm", "an energetic bed at $fastBpm+ bpm"))
            energy in 0f..LOW && (music.bpm > calmBpm || music !in CALM) ->
                listOf(Bench.finding(0f, Severity.Major, "a calm brief under ${music.id} at ${music.bpm} bpm", "a calm bed under $calmBpm bpm"))
            else -> emptyList()
        }
    }

    override fun revise(c: Jury.Context, verdict: Verdict): Score? {
        val music = c.music ?: return null
        return c.score.copy(bpm = music.bpm.toFloat()).takeIf { it != c.score }
    }

    private companion object {
        const val HIGH = 0.7f
        const val LOW = 0.4f
        const val END_PEAK = 0.05f
        val DRIVING = setOf(MusicMood.Energetic, MusicMood.Uplifting, MusicMood.Corporate)
        val CALM = setOf(MusicMood.Chill, MusicMood.Cinematic, MusicMood.Corporate)
    }
}
