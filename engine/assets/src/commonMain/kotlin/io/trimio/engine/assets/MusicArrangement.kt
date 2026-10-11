package io.trimio.engine.assets

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** The parts of a cue, each with its own layers (see [MusicComposer]). */
enum class SectionKind {
    /** Pad and a filtered figure: room for the hook. */
    Intro,
    /** The groove without the top: kick, hats, bass, keys. */
    Verse,
    /** One or two bars of tension into the drop: snare roll, rising filter and noise, the kick drops out. */
    Build,
    /** Everything, from a crash and a sub hit on its first downbeat. */
    Drop,
    /** Drums out, pads and a held bass: the call to action is said over air. */
    Breakdown,
    /** The final chord on a downbeat, ringing out to the last frame. */
    Outro,
}

data class Section(val kind: SectionKind, val from: Double, val to: Double) {
    val length: Double get() = to - from
}

/**
 * A cue composed to one edit: the tempo stretched (within [Arranger.MAX_STRETCH]) so the drop and
 * the final hit both land on downbeats, and the sections in the order the edit needs them.
 * Times are on the output clock, in seconds.
 */
data class MusicArrangement(
    val mood: MusicMood,
    val grid: BeatGrid,
    /** Where the bed enters (the cold open's restart, else 0). */
    val start: Double,
    /** The edit's last frame. */
    val end: Double,
    /** Contiguous, from [start] to [end]; every boundary after the first is a downbeat. */
    val sections: List<Section>,
) {
    /** The drop's first downbeat, if the cue has one. */
    val drop: Double? get() = sections.firstOrNull { it.kind == SectionKind.Drop }?.from

    /** The final hit: always a downbeat. */
    val ending: Double get() = sections.last { it.kind == SectionKind.Outro }.from

    val breakdown: Double? get() = sections.firstOrNull { it.kind == SectionKind.Breakdown }?.from

    fun sectionAt(t: Double): Section? = sections.firstOrNull { t >= it.from && t < it.to }
}

/**
 * Fits a cue to an edit. The two anchors that matter to a viewer — the drop (the payoff, or the
 * cold open's restart) and the final hit — are put on downbeats by stretching the tempo a little
 * (a few percent is inaudible; a click track would hear it, a listener does not) and phasing the
 * grid. Everything else follows bar lines.
 */
object Arranger {
    /** Largest tempo change from the mood's own, as a fraction. */
    const val MAX_STRETCH = 0.06

    /** The ring-out after the final hit, seconds (the chord and the reverb's tail). */
    const val RING = 1.6

    private const val MIN_RING = 0.9
    private const val LONG_INTRO_BARS = 6
    private const val INTRO_BARS = 2
    private const val MIN_BREAKDOWN_BARS = 2

    /**
     * [drop]: where the payoff lands (null: the bed starts with the groove at [start]).
     * [breakdown]: where the call to action starts (null: none). Both on the output clock.
     */
    fun fit(mood: MusicMood, start: Double, end: Double, drop: Double? = null, breakdown: Double? = null): MusicArrangement {
        val base = mood.bpm.toDouble()
        val anchor = (drop ?: start).coerceIn(start, max(start, end - MIN_RING))
        val target = end - RING
        val bpm = stretched(base, anchor, target)
        val grid = BeatGrid(bpm, offset = anchor)
        // The final hit: the last downbeat that still leaves a ring-out (never before the anchor).
        var ending = grid.downbeatAtOrBefore(end - MIN_RING)
        if (ending < anchor) ending = anchor.takeIf { end - it >= MIN_RING * 0.5 } ?: end
        val sections = mutableListOf<Section>()
        fun add(kind: SectionKind, from: Double, to: Double) { if (to - from > 1e-6) sections += Section(kind, from, to) }

        val hasDrop = drop != null && anchor > start + 1e-3
        val dropAt = if (hasDrop) anchor else start
        if (hasDrop) {
            val bars = (dropAt - start) / grid.bar
            val buildBars = when { bars >= LONG_INTRO_BARS -> 2; bars >= 2 -> 1; else -> 0 }
            val buildFrom = max(start, dropAt - buildBars * grid.bar)
            val introTo = if (bars >= LONG_INTRO_BARS) grid.downbeatAtOrAfter(start + INTRO_BARS * grid.bar).coerceAtMost(buildFrom) else buildFrom
            add(SectionKind.Intro, start, introTo)
            add(SectionKind.Verse, introTo, buildFrom)
            add(SectionKind.Build, buildFrom, dropAt)
        }
        // The call to action over a breakdown: from the bar it starts in, when it leaves room for the drop and the ending.
        val down = breakdown?.let { grid.downbeatAtOrBefore(it + grid.period * 0.5) }
            ?.takeIf { it >= dropAt + MIN_BREAKDOWN_BARS * grid.bar - 1e-6 && ending - it >= grid.bar - 1e-6 }
        if (ending > dropAt) {
            add(SectionKind.Drop, dropAt, down ?: ending)
            down?.let { add(SectionKind.Breakdown, it, ending) }
        }
        add(SectionKind.Outro, ending, end)
        if (sections.none { it.kind == SectionKind.Outro }) sections += Section(SectionKind.Outro, end, end)
        return MusicArrangement(mood, grid, start, end, sections)
    }

    /** The tempo that puts a whole number of bars between [anchor] and [target], when one is near enough. */
    private fun stretched(base: Double, anchor: Double, target: Double): Double {
        val span = target - anchor
        val bar = 240.0 / base
        if (span < bar * 0.75) return base
        val bars = max(1, (span / bar).roundToInt())
        val bpm = 240.0 * bars / span
        return if (abs(bpm / base - 1.0) <= MAX_STRETCH) bpm else base
    }
}
