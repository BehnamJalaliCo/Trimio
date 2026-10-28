package io.trimio.engine.autopilot

import io.trimio.core.model.transcript.Word
import io.trimio.engine.motion.score.NumberWords
import io.trimio.engine.motion.score.SceneScore
import io.trimio.engine.motion.score.Score
import io.trimio.engine.motion.score.StoryMap

/**
 * Executes a designed story map into a score: timing and layout only. Each [DesignPass.Treatment]
 * becomes one scene anchored on the words its beat covers, held for as long as those words take to
 * say (plus a breath), with the beat's own energy. Nothing here chooses what to show — that was
 * decided upstream, once, with a reason — so the same map and the same words always give the same
 * score, and the creative variance lives where the story is told.
 */
internal object StoryPlanner {

    data class Planned(val score: Score, val notes: List<String>)

    /** The map designed ([DesignPass.design]) and laid out over [words]. */
    fun plan(map: StoryMap, words: List<Word>, design: DesignPass.Design = DesignPass.design(map, words)): Planned {
        val treatments = design.treatments.sortedBy { it.beat.from }
        val scenes = treatments.map { scene(it, words) }
        val notes = design.notes + "story planner: ${scenes.size} scenes from ${map.beats.size} beats"
        return Planned(Score(scenes = scenes, auto = false), notes)
    }

    private fun scene(t: DesignPass.Treatment, words: List<Word>): SceneScore {
        val beat = t.beat
        val until = maxOf(beat.from, beat.until)
        val start = words.getOrNull(beat.from)?.range?.startMs?.div(1000f) ?: 0f
        val end = words.getOrNull(until)?.range?.endMs?.div(1000f) ?: start
        val hold = (end - start + LINGER).coerceAtLeast(t.beatScore.hold ?: MIN_HOLD)
        return SceneScore(
            from = beat.from,
            beats = listOf(t.beatScore.copy(hold = hold)),
            camera = if (beat.kind == "payoff") "push-in" else null,
            transition = if (beat.kind == "turn") "flash" else null,
        )
    }

    /**
     * The beats a model wrote directly (the spec-first top-N path, [ScoreGrammar] shapes): their
     * timing is snapped to the words they quote so the score and the transcript cannot drift.
     */
    fun snap(score: Score, words: List<Word>): Score {
        val texts = words.map { NumberWords.normalize(it.text) }
        fun locate(text: String?): Int? {
            val first = text?.split(' ')?.map(NumberWords::normalize)?.firstOrNull { it.isNotBlank() } ?: return null
            return texts.indexOfFirst { it == first }
        }
        return score.copy(scenes = score.scenes.map { sc ->
            sc.copy(beats = sc.beats.map { b ->
                val at = b.at ?: locate(b.text)
                if (at == null || at !in words.indices) b else b.copy(at = at, until = b.until ?: at)
            })
        })
    }

    private const val LINGER = 0.55f
    private const val MIN_HOLD = 1.5f
}
