package io.trimio.engine.autopilot

import io.trimio.core.model.transcript.Word
import io.trimio.engine.motion.score.BeatScore
import io.trimio.engine.motion.score.Compiler
import io.trimio.engine.motion.score.Score
import io.trimio.engine.motion.visual.VectorIcon

/**
 * The editor's last pass before the cut is compiled: it dresses the moments that carry the piece
 * with a designer's accent ([TemplateShelf]) — a brush underline under the line that lands, a tick
 * by a gain, a cross by a loss, a ring round a number, a hand tapping the ask — the way a motion
 * designer finishes a talking head.
 *
 * It decides by what each beat is (its recipe and the meaning it already carries), never by taste
 * of the moment, and with restraint: never two accents within [GAP] seconds, at most [MAX] in a piece,
 * and one accent per role all the way through (one hand, one underline: a look, not a collage).
 */
internal object Dresser {

    /** The accent role a beat would wear, or null. */
    fun role(b: BeatScore): String? = when {
        b.recipe in ASKS -> "tap"
        b.recipe in AMOUNTS && b.mark == "loss" -> "cross"
        b.recipe in AMOUNTS && b.mark == "gain" -> "check"
        b.recipe in AMOUNTS -> "circle"
        b.recipe in TITLES && !b.text.isNullOrBlank() -> when (io.trimio.engine.motion.recipe.Meaning.tone(b.text.orEmpty().split(' '))) {
            io.trimio.engine.motion.recipe.Meaning.Tone.Loss -> "warning"
            io.trimio.engine.motion.recipe.Meaning.Tone.Gain -> "burst"
            else -> "underline"
        }
        b.recipe == "board-pill" && b.icon == "browser" -> "arrow"
        else -> null
    }

    /** The roles [score] could use. */
    fun roles(score: Score): Set<String> = score.scenes.flatMap { s -> s.beats.mapNotNull(::role) }.toSet()

    /** [score] with accents from [icons] (each keyed by its role) on the beats that carry it; and the ids worn. */
    fun dress(score: Score, words: List<Word>, icons: List<VectorIcon>): Pair<Score, List<String>> {
        val byRole = icons.groupBy { it.keywords.firstOrNull().orEmpty() }.mapValues { it.value.first() }
        if (byRole.isEmpty()) return score to emptyList()
        fun seconds(b: BeatScore): Float? = b.time ?: b.at?.let { words.getOrNull(it) }?.range?.startMs?.let { it / MS }
        var last = -GAP
        var count = 0
        val worn = mutableListOf<String>()
        val scenes = score.scenes.map { s ->
            s.copy(
                beats = s.beats.map { b ->
                    val at = seconds(b)
                    val icon = role(b)?.let(byRole::get)
                    if (icon == null || at == null || at - last < GAP || count >= MAX || b.accent != null) return@map b
                    last = at
                    count++
                    worn += icon.name
                    b.copy(accent = Compiler.TEMPLATE + icon.name)
                },
            )
        }
        return score.copy(scenes = scenes) to worn.distinct()
    }

    /**
     * The caption style of a reel told in cards ([Storyboard]), unlike its page's last edits ([recent]): phrases with the
     * word that matters inked by its meaning, a bold word-by-word pop, or a short line with the word being said on a
     * moving highlight. Another piece only: a brief that names a caption style keeps it (the score already says so).
     */
    fun captions(score: Score, recent: Set<String>, seed: Long): Pair<Score, String> {
        if (score.captions.mark != "meaning" || !score.captions.show) return score to ""
        val fresh = CAPTION_STYLES.filter { (id, _) -> CAPTIONS + id !in recent }.ifEmpty { CAPTION_STYLES }
        val (id, style) = fresh[(seed.mod(fresh.size.toLong())).toInt()]
        return score.copy(captions = style(score.captions)) to CAPTIONS + id
    }

    private const val CAPTIONS = "captions:"
    private val CAPTION_STYLES: List<Pair<String, (io.trimio.engine.motion.score.CaptionScore) -> io.trimio.engine.motion.score.CaptionScore>> = listOf(
        "phrase" to { c -> c },
        "pop" to { c -> c.copy(maxWords = 3, mark = "pop") },
        "highlight" to { c -> c.copy(maxWords = 4, mark = "highlight") },
    )

    private val TITLES = setOf("slam", "stack", "mask-rise", "board-quote", "pop-title", "kinetic", "flip", "blur-in", "spread", "type-on")
    private val AMOUNTS = setOf("board-price", "counter", "ticker", "price-tag", "stat")
    private val ASKS = setOf("board-steps", "comment", "cta", "follow")
    private const val GAP = 4f
    private const val MAX = 6
    private const val MS = 1000f
}
