package io.trimio.engine.motion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Each craft rule fires on a synthetic violation and stays silent on its clean twin; the last
 * test checks that a fully clean piece comes back with no findings at all.
 */
class CraftAuditTest {

    @Test
    fun briefTextIsUnreadable() {
        val needed = MotionSpec.Time.holdForLine(3, isLast = false)
        val brief = timeline(shot(el(words = 3, heightPx = 60f, disappearsAt = needed * 0.5f), el("decoy")))
        val held = timeline(shot(el(words = 3, heightPx = 60f), el("decoy"), end = needed + 1f))
        assertEquals(listOf("line_unread"), rules(brief))
        assertEquals(emptyList<String>(), rules(held))
    }

    @Test
    fun tinyTextIsBelowTheLegibilityFloor() {
        val floor = MotionSpec.Legibility.MinTextFractionOfShortSide
        val tiny = timeline(shot(el(words = 2, heightPx = 1080f * floor * 0.5f), el("decoy")))
        val big = timeline(shot(el(words = 2, heightPx = 1080f * floor * 1.5f), el("decoy")))
        assertEquals(listOf("text_below_floor"), rules(tiny))
        assertEquals(emptyList<String>(), rules(big))
    }

    @Test
    fun longStaticShotIsADeadStretch() {
        val stalled = timeline(shot(el(), end = MotionSpec.Time.DeadStretchMax + 1f))
        val alive = timeline(shot(el(), end = MotionSpec.Time.DeadStretchMax))
        assertEquals(listOf("dead_stretch"), rules(stalled))
        assertEquals(emptyList<String>(), rules(alive))
    }

    @Test
    fun thePieceMustOpenOnContent() {
        val blank = timeline(shot(el(appearsAt = 0.5f), firstFrameHasContent = false))
        val full = timeline(shot(el(), firstFrameHasContent = true))
        assertEquals(listOf("first_frame_empty"), rules(blank))
        assertEquals(emptyList<String>(), rules(full))
    }

    @Test
    fun opacityAndColourMustNotOvershoot() {
        val bouncy = timeline(shot(el(bouncy = true)))
        val calm = timeline(shot(el()))
        assertEquals(listOf("opacity_bounce"), rules(bouncy))
        assertEquals(emptyList<String>(), rules(calm))
    }

    @Test
    fun motionMustNotRunLinear() {
        val drifting = timeline(shot(el(linear = true)))
        val eased = timeline(shot(el()))
        assertEquals(listOf("linear_motion"), rules(drifting))
        assertEquals(emptyList<String>(), rules(eased))
    }

    @Test
    fun entriesMustNotBeStampedOut() {
        val stamped = timeline(
            shot(
                el("a", entryKind = "slide-up", entrySec = 0.4f),
                el("b", entryKind = "slide-up", entrySec = 0.4f),
                el("c", entryKind = "slide-up", entrySec = 0.4f),
            ),
        )
        val varied = timeline(
            shot(
                el("a", entryKind = "slide-up", entrySec = 0.4f),
                el("b", entryKind = "slide-up", entrySec = 0.4f),
                el("c", entryKind = "pop", entrySec = 0.4f),
            ),
        )
        assertEquals(listOf("uniform_entry"), rules(stamped))
        assertEquals(emptyList<String>(), rules(varied))
    }

    @Test
    fun longFilmsNeedAudio() {
        val silent = timeline(shot(el()), hasAudio = false, duration = 16f)
        val voiced = timeline(shot(el()), hasAudio = true, duration = 16f)
        assertEquals(listOf("film_silent"), rules(silent))
        assertEquals(emptyList<String>(), rules(voiced))
    }

    @Test
    fun cleanTimelineHasNoFindings() {
        val piece = timeline(
            shot(
                el("title", words = 2, heightPx = 1080f * MotionSpec.Legibility.MinTextFractionOfShortSide * 2f, entryKind = "rise", entrySec = 0.35f),
                el("rule", entryKind = "wipe", entrySec = 0.35f),
                end = 2.5f,
            ),
            shot(
                el("caption", words = 3, heightPx = 60f, entryKind = "rise", entrySec = 0.35f, appearsAt = 3f),
                el("badge", entryKind = "pop", entrySec = 0.35f, appearsAt = 3f),
                start = 3f,
                end = 6f,
            ),
            duration = 6f,
        )
        val findings = CraftAudit.audit(piece)
        assertTrue(findings.isEmpty(), "clean piece must have no findings, got $findings")
    }

    private fun rules(t: CraftAudit.Timeline): List<String> = CraftAudit.audit(t).map { it.rule }

    private fun el(
        name: String = "el",
        appearsAt: Float = 0f,
        disappearsAt: Float? = null,
        entrySec: Float? = null,
        exitSec: Float? = null,
        words: Int? = null,
        heightPx: Float? = null,
        shortSide: Float = 1080f,
        bouncy: Boolean = false,
        entryKind: String? = null,
        linear: Boolean = false,
    ) = CraftAudit.Element(
        name = name,
        appearsAtSec = appearsAt,
        disappearsAtSec = disappearsAt,
        entrySec = entrySec,
        exitSec = exitSec,
        textWordCount = words,
        textHeightPx = heightPx,
        shortSidePx = shortSide,
        opacityIsBouncy = bouncy,
        entryKind = entryKind,
        hasLinearMotion = linear,
    )

    private fun shot(
        vararg elements: CraftAudit.Element,
        name: String = "shot",
        start: Float = 0f,
        end: Float = 2f,
        firstFrameHasContent: Boolean = true,
    ) = CraftAudit.Shot(name, start, end, elements.toList(), firstFrameHasContent)

    private fun timeline(
        vararg shots: CraftAudit.Shot,
        hasAudio: Boolean = true,
        duration: Float = 2f,
    ) = CraftAudit.Timeline(shots.toList(), hasAudio, duration)
}
