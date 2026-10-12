package io.trimio.engine.autopilot.judges

import io.trimio.core.model.transcript.Word
import io.trimio.engine.autopilot.Finding
import io.trimio.engine.autopilot.Jury
import io.trimio.engine.autopilot.Severity
import io.trimio.engine.motion.score.BeatScore
import io.trimio.engine.motion.score.Compiler
import io.trimio.engine.motion.score.CutPoints
import io.trimio.engine.motion.score.NumberWords
import io.trimio.engine.motion.score.SceneScore
import io.trimio.engine.motion.score.Score

/**
 * What the judges share: the edit read as an editor reads a timeline (graphics, captions, scenes,
 * which beat of the score a placed graphic came from) and small score edits for their fixes.
 */
internal object Bench {
    const val CAPTIONS = "pop-captions"
    const val COMMENT = "comment"
    const val STAMP = "stamp"

    val TEXT = setOf("slam", "mask-rise", "type-on", "blur-in", "flip", "spread", "stack", "glitch")

    /** Figures that need time to be read (the planner's DATA_HOLD applies to them). */
    val DATA = setOf("counter", "voucher", "stats", "countdown", "progress", "chart", "meter", "network", "terminal", "bars")
    val PICTURES = setOf("object", "objects", "icon", "animated", "live-reaction")

    /** The craft's alternatives for a text treatment used too often (the critic's table). */
    val ALTERNATE = mapOf(
        "slam" to "stack", "mask-rise" to "flip", "type-on" to "mask-rise", "blur-in" to "mask-rise",
        "flip" to "mask-rise", "spread" to "slam", "stack" to "slam", "glitch" to "slam",
    )

    /** Scenes whose background is a full-frame stage rather than the speaker. */
    private val STAGES = setOf("aurora", "grid", "plain")

    /** The compiler starts a scene this much before its first word. */
    private const val SCENE_LEAD = 0.12f

    /** How far a placed graphic may sit from the beat that asked for it. */
    private const val MATCH_S = 0.45f

    fun graphics(c: Jury.Context) = c.compiled.beats.filter { it.recipe != CAPTIONS }
    fun captions(c: Jury.Context) = c.compiled.beats.filter { it.recipe == CAPTIONS }

    fun start(words: List<Word>, i: Int) = words.getOrNull(i.coerceIn(0, (words.size - 1).coerceAtLeast(0)))?.range?.startMs?.div(1000f) ?: 0f

    /** Nominal scene starts on the output clock (the compiler's rule, without its stage refinements). */
    fun sceneStarts(score: Score, words: List<Word>): List<Float> = score.scenes.mapIndexed { k, s ->
        if (k == 0) 0f else s.time ?: s.from?.let { start(words, it) - SCENE_LEAD } ?: 0f
    }

    fun sceneAt(score: Score, words: List<Word>, t: Float): Int =
        sceneStarts(score, words).indexOfLast { it <= t + 0.05f }.coerceAtLeast(0)

    /** The speaker is on screen behind scene [k] (not a full-frame stage). */
    fun overFootage(c: Jury.Context, k: Int): Boolean = c.footage && c.score.scenes.getOrNull(k)?.bg?.lowercase() !in STAGES

    fun overFootageAt(c: Jury.Context, t: Float): Boolean = overFootage(c, sceneAt(c.score, c.words, t))

    /** Where a beat lands, as the compiler would place it (time, word, or the scene's start). */
    fun beatStart(b: BeatScore, scene: Int, score: Score, words: List<Word>, starts: List<Float> = sceneStarts(score, words)): Float =
        b.time ?: b.at?.let { start(words, it) } ?: b.text?.let { quoted(it, words, score.scenes[scene], starts[scene]) } ?: (starts[scene] + 0.2f)

    private fun quoted(text: String, words: List<Word>, scene: SceneScore, from: Float): Float? {
        val q = text.split(' ').map(NumberWords::normalize).filter { it.isNotEmpty() }.firstOrNull() ?: return null
        val first = scene.from ?: words.indexOfFirst { it.range.startMs / 1000f >= from }.coerceAtLeast(0)
        return (first until words.size).firstOrNull { NumberWords.normalize(words[it].text).trimEnd('.', '،', ',') == q.trimEnd('.', '،', ',') }?.let { start(words, it) }
    }

    /** The score beat (scene, index) that produced [p], when it can be told. */
    fun locate(score: Score, words: List<Word>, p: Compiler.Placed): Pair<Int, Int>? {
        val starts = sceneStarts(score, words)
        val candidates = score.scenes.indices.flatMap { k -> score.scenes[k].beats.indices.filter { score.scenes[k].beats[it].recipe == p.recipe }.map { k to it } }
        return candidates.minByOrNull { (k, j) -> kotlin.math.abs(beatStart(score.scenes[k].beats[j], k, score, words, starts) - p.at) }
            ?.takeIf { (k, j) -> kotlin.math.abs(beatStart(score.scenes[k].beats[j], k, score, words, starts) - p.at) <= MATCH_S || candidates.size == 1 }
    }

    fun mapBeat(score: Score, at: Pair<Int, Int>, f: (BeatScore) -> BeatScore?): Score = score.copy(
        scenes = score.scenes.mapIndexed { k, s -> if (k != at.first) s else s.copy(beats = s.beats.mapIndexedNotNull { j, b -> if (j == at.second) f(b) else b }) },
    )

    fun addBeat(score: Score, k: Int, beat: BeatScore): Score = score.copy(
        scenes = score.scenes.mapIndexed { i, s -> if (i == k) s.copy(beats = listOf(beat) + s.beats) else s },
    )

    /**
     * A new scene (a punch-in cut over the speaker) from the word spoken nearest [t]; null when
     * there is none to cut on, or when the call to action is already up (its scene runs to the end).
     */
    fun punchAt(score: Score, words: List<Word>, t: Float): Score? {
        val i = words.indices.minByOrNull { kotlin.math.abs(start(words, it) - t) }?.takeIf { it > 0 } ?: return null
        if (score.scenes.any { it.from == i } || askedBy(score, words)?.let { start(words, i) >= it - SCENE_LEAD } == true) return null
        val scenes = (score.scenes + SceneScore(from = i)).sortedBy { s -> s.time ?: s.from?.let { start(words, it) } ?: 0f }
        return score.copy(scenes = scenes)
    }

    /** When the call to action lands in [score], if it is there. */
    fun askedBy(score: Score, words: List<Word>): Float? {
        val starts = sceneStarts(score, words)
        return score.scenes.indices.flatMap { k -> score.scenes[k].beats.filter { it.recipe == COMMENT }.map { beatStart(it, k, score, words, starts) } }.minOrNull()
    }

    /**
     * A beat the jury keeps finding at fault is taken one step further each round, never back:
     * moved to the other band (over the speaker) or given another layout of its device — either
     * way its treatment is then pinned ([BeatScore.variant] set) — then a plain treatment of the
     * same words or figure, then left out. The call to action and the end card are never left out.
     */
    object Ladder {
        private val KEEP = setOf(COMMENT, "endcard")

        fun next(b: BeatScore, p: Compiler.Placed, moveFirst: Boolean, gentle: Boolean = false): BeatScore? {
            val variants = io.trimio.engine.motion.recipe.Recipes.named(b.recipe)?.variants ?: 1
            return when {
                b.variant == null && moveFirst && p.overFootage -> b.copy(place = otherBand(p.zone), variant = p.variant)
                b.variant == null && variants > 1 -> b.copy(variant = (p.variant + 1) % variants)
                b.variant == null -> b.copy(place = if (p.overFootage) otherBand(p.zone) else "center", variant = p.variant)
                // A small flaw (a graphic on the small side) never costs the graphic: another layout is all it gets.
                gentle || b.recipe in KEEP -> b
                b.recipe in MARKS -> null
                b.recipe !in TEXT -> plain(b)
                else -> null
            }
        }

        /** Devices that show brand marks: their names as a plain title would be English text, not a better graphic. */
        private val MARKS = setOf("logos", "logo-mark", "chips")

        /** The same words (or figure) as a plain text treatment, or null when it says nothing in words. */
        fun plain(b: BeatScore): BeatScore? {
            if (b.value != null && b.recipe != "counter") return BeatScore(
                recipe = "counter", at = b.at, until = b.until, time = b.time, value = b.value, decimals = b.decimals, prefix = b.prefix,
                suffix = b.suffix, label = b.label, place = b.place, energy = b.energy,
            )
            val words = (b.text ?: b.label ?: b.items.take(PLAIN_ITEMS).joinToString(" ")).replace(" | ", " ").trim()
            if (words.isBlank() || words.split(' ').size > PLAIN_WORDS) return null
            return BeatScore(recipe = "mask-rise", at = b.at, time = b.time, text = words, place = b.place, energy = b.energy, mark = b.mark, hold = b.hold)
        }

        private const val PLAIN_ITEMS = 3
        private const val PLAIN_WORDS = 5
    }

    /** The other band over footage: graphics trade the top for the lower third and back. */
    fun otherBand(zone: String) = if (zone == "lower") "top" else "lower"

    fun overlap(a0: Float, a1: Float, b0: Float, b1: Float) = (minOf(a1, b1) - maxOf(a0, b0)).coerceAtLeast(0f)

    /** Area shared by two placed boxes, in px². */
    fun shared(a: Compiler.Placed, b: Compiler.Placed): Float =
        overlap(a.left, a.right, b.left, b.right) * overlap(a.top, a.bottom, b.top, b.bottom)

    fun area(p: Compiler.Placed) = (p.right - p.left).coerceAtLeast(0f) * (p.bottom - p.top).coerceAtLeast(0f)

    fun persian(s: String) = s.any { it in 'ؠ'..'ۿ' && it.isLetter() }

    /** The words a viewer reads on a beat of the score: its text, label, items and marks. */
    fun shownTexts(b: BeatScore): List<String> = listOfNotNull(b.text, b.label) + b.items.map { it.removePrefix(">") }

    fun finding(at: Float, severity: Severity, what: String, fix: String) = Finding(at, severity, what, fix)

    fun r1(v: Float) = kotlin.math.round(v * 10f) / 10f

    /**
     * What a cut at source time [t] cuts into, or null when it is clean. With the voice: the fade
     * window on the playing side ([entering]: after the cut, else before it) sounding within
     * [LOUD_DB] of the speaker's level is a cut through a word. Without it: a word whose recognised
     * timing straddles the cut by more than [WORD_TOL].
     */
    fun cutInto(c: Jury.Context, t: Float, entering: Boolean): String? {
        val near = c.source.minByOrNull { w -> kotlin.math.abs((w.range.startMs + w.range.endMs) / 2000f - t) }?.text
        val cuts = c.cuts
        if (cuts != null) {
            val db = if (entering) cuts.db(t, t + CutPoints.FADE) else cuts.db(t - CutPoints.FADE, t)
            return (near ?: "speech").takeIf { db > cuts.speechDb - LOUD_DB }
        }
        return c.source.firstOrNull { w -> w.range.startMs / 1000f < t - WORD_TOL && w.range.endMs / 1000f > t + WORD_TOL }?.text
    }

    /** Within this of the speaker's level, the voice is still a word (CutPoints' burst margin). */
    private const val LOUD_DB = 16f
    private const val WORD_TOL = 0.06f
}

/** At most [max] findings of a kind are kept: one cause repeated should not sink a judge alone. */
internal fun List<Finding>.capped(max: Int = 3): List<Finding> = take(max)
