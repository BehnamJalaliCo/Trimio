package io.trimio.engine.autopilot.judges

import io.trimio.engine.assets.Arranger
import io.trimio.engine.assets.MusicArrangement
import io.trimio.engine.assets.MusicMood
import io.trimio.engine.autopilot.MusicEdit
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
 * edit's beat grid (tempo and phase) is the cue's; the cue is cut to the edit — its tempo
 * stretched no further than [Arranger.MAX_STRETCH], the drop on the cold open's restart (else on
 * the payoff), a breakdown under the call to action, the final hit on a downbeat with room to ring;
 * and the piece ends cleanly, the bed faded out by the last frame (the last [tailMs] ms nearly silent).
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
            val arr = c.arrangement
            val bpm = c.score.bpm
            val want = arr?.grid?.bpm?.toFloat() ?: music.bpm.toFloat()
            if (bpm == null || abs(bpm - want) > GRID_BPM) {
                f += Bench.finding(0f, Severity.Minor, "the beat grid (${bpm ?: "none"}) is not the cue's $want bpm", "snap hits to the cue's grid")
            } else if (arr != null && phaseOff(c.score.beatOffset, arr)) {
                f += Bench.finding(0f, Severity.Minor, "the beat grid is out of phase with the cue's beats", "take the cue's beat offset")
            }
            if (arr == null) f += Bench.finding(0f, Severity.Note, "a looped bed: no cue composed to the edit", "-") else f += cut(c, arr)
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

    /** The cue against the edit: tempo stretch, drop, breakdown and ending. */
    private fun cut(c: Jury.Context, arr: MusicArrangement): List<Finding> {
        val out = mutableListOf<Finding>()
        val beat = arr.grid.period
        if (abs(arr.grid.bpm / arr.mood.bpm - 1.0) > Arranger.MAX_STRETCH + 1e-6) {
            out += Bench.finding(0f, Severity.Minor, "the ${arr.mood.id} cue is stretched to ${arr.grid.bpm.toInt()} bpm", "a mood nearer this tempo")
        }
        val restart = c.edit?.preludeLength?.toDouble() ?: 0.0
        val drop = arr.drop
        val payoff = if (restart > 0.0) restart else MusicEdit.payoff(c.words, c.lines, c.understanding, arr.end)
        when {
            restart > 0.0 && (drop == null || abs(drop - restart) > ALIGN_S) ->
                out += Bench.finding(restart.toFloat(), Severity.Major, "the music does not drop on the cold open's restart", "drop on the restart")
            payoff != null && (drop == null || abs(drop - payoff) > beat) ->
                out += Bench.finding(payoff.toFloat(), Severity.Minor, "the drop (${drop?.let { r1(it) } ?: "none"}) misses the payoff at ${r1(payoff)}s", "drop on the payoff")
            drop != null && !arr.grid.isDownbeat(drop, ALIGN_S) -> out += Bench.finding(drop.toFloat(), Severity.Minor, "the drop is off the bar", "drop on a downbeat")
        }
        val cta = MusicEdit.cta(c.words, c.lines, c.understanding)
        val roomy = cta != null && drop != null && cta - drop >= 3 * arr.grid.bar && arr.ending - cta >= arr.grid.bar + beat
        if (roomy && arr.breakdown == null) out += Bench.finding(cta!!.toFloat(), Severity.Minor, "the call to action is said over the full drop", "a breakdown under the call to action")
        if (!arr.grid.isDownbeat(arr.ending, ALIGN_S)) {
            out += Bench.finding(arr.ending.toFloat(), Severity.Minor, "the music's ending is off the downbeat", "end on a downbeat")
        } else if (arr.end - arr.ending < MIN_RING_S) {
            out += Bench.finding(arr.ending.toFloat(), Severity.Minor, "the final hit has ${r1(arr.end - arr.ending)}s to ring", "end a bar earlier")
        }
        return out
    }

    private fun phaseOff(offset: Float, arr: MusicArrangement): Boolean {
        val p = arr.grid.period
        val d = ((offset - arr.grid.scoreOffset) % p + p) % p
        return minOf(d, p - d) > PHASE_S
    }

    private fun r1(v: Double) = kotlin.math.round(v * 10.0) / 10.0

    /**
     * A bed whose mood the brief wants (the 9B director picked an uplifting 118 bpm bed for a calm brief and no fix
     * reached it): the named mood, else for a calm brief the calm mood nearest in tempo under [calmBpm], for a
     * high-energy one the driving mood at [fastBpm]+ nearest in tempo. Null when the mood is not what is wrong.
     */
    override fun remood(c: Jury.Context, verdict: Verdict): MusicMood? {
        val music = c.music ?: return null
        val wrong = verdict.findings.map { it.what }
        val named = MusicMood.fromId(c.understanding.brief.music)
        return when {
            named != null && wrong.any { "the brief asks for" in it } -> named
            wrong.any { it.startsWith("a calm brief under") } -> CALM.filter { it.bpm <= calmBpm }.minByOrNull { kotlin.math.abs(it.bpm - music.bpm) }
            wrong.any { it.startsWith("a high-energy brief under") } -> DRIVING.filter { it.bpm >= fastBpm }.minByOrNull { kotlin.math.abs(it.bpm - music.bpm) }
            else -> null
        }?.takeIf { it != music }
    }

    override fun revise(c: Jury.Context, verdict: Verdict): Score? {
        val music = c.music ?: return null
        val arr = c.arrangement ?: return c.score.copy(bpm = music.bpm.toFloat()).takeIf { it != c.score }
        return MusicEdit.gridded(c.score, arr).takeIf { it != c.score }
    }

    private companion object {
        const val HIGH = 0.7f
        const val LOW = 0.4f
        const val END_PEAK = 0.05f
        const val GRID_BPM = 0.05f
        const val PHASE_S = 0.005
        const val ALIGN_S = 0.02
        const val MIN_RING_S = 0.45
        val DRIVING = setOf(MusicMood.Energetic, MusicMood.Uplifting, MusicMood.Corporate)
        val CALM = setOf(MusicMood.Chill, MusicMood.Cinematic, MusicMood.Corporate)
    }
}
