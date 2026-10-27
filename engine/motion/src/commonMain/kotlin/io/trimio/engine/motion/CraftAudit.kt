package io.trimio.engine.motion

/**
 * Machine-checkable craft assertions over a generated timeline. Each rule is a small pure check;
 * violations come back as [Finding]s so a generator (or a test) can iterate until the piece is
 * clean. Thresholds live in [MotionSpec], so the generator and the jury agree on what readable
 * and alive mean.
 */
object CraftAudit {

    /** One violated rule. [atSec] is where it shows in the piece; null when it spans the whole film. */
    data class Finding(val rule: String, val message: String, val atSec: Float? = null)

    /**
     * The facts a rule needs about one generated element — deliberately not a scene node.
     * Times are composition seconds; [entrySec]/[exitSec] are the durations of its entrance and
     * exit (null = a cut).
     */
    data class Element(
        val name: String,
        val appearsAtSec: Float,
        val disappearsAtSec: Float? = null,
        val entrySec: Float? = null,
        val exitSec: Float? = null,
        val textWordCount: Int? = null,
        val textHeightPx: Float? = null,
        val shortSidePx: Float = 1080f,
        val opacityIsBouncy: Boolean = false,
        val entryKind: String? = null,
        val hasLinearMotion: Boolean = false,
    )

    data class Shot(
        val name: String,
        val startSec: Float,
        val endSec: Float,
        val elements: List<Element>,
        val firstFrameHasContent: Boolean = true,
    )

    data class Timeline(val shots: List<Shot>, val hasAudio: Boolean = true, val durationSec: Float)

    /** Every violated rule, ordered by [Finding.atSec] (null first) then rule id. */
    fun audit(t: Timeline): List<Finding> {
        val findings = mutableListOf<Finding>()
        t.shots.forEachIndexed { i, shot ->
            if (i == 0) firstFrameEmpty(shot)?.let { findings += it }
            deadStretch(shot)?.let { findings += it }
            findings += uniformEntry(shot)
            for (e in shot.elements) findings += elementFindings(shot, e)
        }
        filmSilent(t)?.let { findings += it }
        return findings.sortedWith(compareBy({ it.atSec }, { it.rule }))
    }

    private fun elementFindings(shot: Shot, e: Element): List<Finding> = listOfNotNull(
        lineUnread(shot, e),
        textBelowFloor(e),
        opacityBounce(e),
        linearMotion(e),
    )

    /** Text that flashes by faster than the hold a reader needs for its words. */
    private fun lineUnread(shot: Shot, e: Element): Finding? {
        val words = e.textWordCount ?: return null
        val visible = (e.disappearsAtSec ?: shot.endSec) - e.appearsAtSec
        if (visible >= CaptionMetrics.holdForLine(words, isLast = false)) return null
        return Finding("line_unread", "${e.name} is briefer than the readable hold for $words words", e.appearsAtSec)
    }

    private fun textBelowFloor(e: Element): Finding? {
        val height = e.textHeightPx ?: return null
        if (height / e.shortSidePx >= MotionSpec.Legibility.MinTextFractionOfShortSide) return null
        return Finding("text_below_floor", "${e.name} is under the legibility floor", e.appearsAtSec)
    }

    /** Nothing comes, goes or moves for too long: the shot stalls instead of holding with intent. */
    private fun deadStretch(shot: Shot): Finding? {
        if (shot.endSec - shot.startSec <= MotionSpec.Time.DeadStretchMax) return null
        val e = shot.elements.singleOrNull() ?: return null
        if (e.entrySec != null || e.exitSec != null || e.hasLinearMotion) return null
        val gone = e.disappearsAtSec
        val spans = e.appearsAtSec <= shot.startSec && (gone == null || gone >= shot.endSec)
        return if (spans) {
            Finding("dead_stretch", "${shot.name} holds one static element past ${MotionSpec.Time.DeadStretchMax} seconds", shot.startSec)
        } else {
            null
        }
    }

    private fun firstFrameEmpty(shot: Shot): Finding? =
        if (shot.firstFrameHasContent) {
            null
        } else {
            Finding("first_frame_empty", "the piece opens on an empty frame", shot.startSec)
        }

    private fun opacityBounce(e: Element): Finding? =
        if (e.opacityIsBouncy) {
            Finding("opacity_bounce", "${e.name} overshoots opacity or colour", e.appearsAtSec)
        } else {
            null
        }

    private fun linearMotion(e: Element): Finding? =
        if (e.hasLinearMotion) {
            Finding("linear_motion", "${e.name} moves at constant speed with no easing", e.appearsAtSec)
        } else {
            null
        }

    /** Three or more elements stamped with the same entry reads as templating, not craft. */
    private fun uniformEntry(shot: Shot): List<Finding> = shot.elements
        .filter { it.entryKind != null && it.entrySec != null }
        .groupBy { it.entryKind to it.entrySec }
        .values
        .filter { it.size >= UniformEntryMin }
        .map { group ->
            Finding("uniform_entry", "${group.size} elements in ${shot.name} share one ${group.first().entryKind} entry", shot.startSec)
        }

    private fun filmSilent(t: Timeline): Finding? =
        if (t.durationSec > SilentFilmSec && !t.hasAudio) {
            Finding("film_silent", "a piece over $SilentFilmSec seconds plays silent")
        } else {
            null
        }

    private const val SilentFilmSec = 15f
    private const val UniformEntryMin = 3
}
