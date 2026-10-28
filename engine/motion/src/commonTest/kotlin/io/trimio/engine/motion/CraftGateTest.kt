package io.trimio.engine.motion

import androidx.compose.ui.graphics.Color
import io.trimio.core.brand.BrandFonts
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * CraftGate over synthetic compositions: each craft rule is triggered by a scene graph that really
 * exhibits the tell, and the mapper must carry the facts (times, words, entry, motion) faithfully.
 */
class CraftGateTest {

    @Test
    fun briefTextIsLineUnread() {
        val c = composition(3f, text("hello world", start = 0f, end = 0.4f))
        val findings = CraftGate.audit(c, hasAudio = true).findings
        assertTrue(findings.any { it.rule == "line_unread" }, "2 words held 0.4s must fail the readable hold: $findings")
    }

    @Test
    fun textUnderLegibilityFloorIsFlagged() {
        val c = composition(3f, text("hi", start = 0f, end = 3f, size = 12f))
        val findings = CraftGate.audit(c, hasAudio = true).findings
        assertTrue(findings.any { it.rule == "text_below_floor" }, "12px on 1080 short side is under the floor: $findings")
    }

    @Test
    fun sharedEntryOfThreeElementsIsUniformEntry() {
        val enter = TextAnimator(TextUnit.All, UnitState(opacity = 0f), at = 0f, duration = 0.5f, ease = Easing.ExpoOut)
        val c = composition(
            4f,
            text("a", start = 0f, end = 4f, animators = listOf(enter), name = "t0"),
            text("b", start = 0f, end = 4f, animators = listOf(enter), name = "t1"),
            text("c", start = 0f, end = 4f, animators = listOf(enter), name = "t2"),
        )
        val findings = CraftGate.audit(c, hasAudio = true).findings
        assertTrue(findings.any { it.rule == "uniform_entry" }, "three identical fade entries are templating: $findings")
    }

    @Test
    fun easedEntriesDoNotReadAsUniform() {
        val a = TextAnimator(TextUnit.All, UnitState(opacity = 0f), at = 0f, duration = 0.4f, ease = Easing.ExpoOut)
        val b = TextAnimator(TextUnit.All, UnitState(dy = 1f, opacity = 0f), at = 0.2f, duration = 0.7f, ease = Easing.ExpoOut)
        val c = TextAnimator(TextUnit.All, UnitState(scale = 0.5f, opacity = 0f), at = 0.5f, duration = 0.3f, ease = Easing.Spring())
        val comp = composition(
            4f,
            text("a", start = 0f, end = 4f, animators = listOf(a), name = "t0"),
            text("b", start = 0f, end = 4f, animators = listOf(b), name = "t1"),
            text("c", start = 0f, end = 4f, animators = listOf(c), name = "t2"),
        )
        val findings = CraftGate.audit(comp, hasAudio = true).findings
        assertFalse(findings.any { it.rule == "uniform_entry" }, "distinct entry kinds must not group: $findings")
    }

    @Test
    fun constantSpeedTravelIsLinearMotion() {
        val box = ShapeNode(
            shape = ShapeSpec.Rect(50f.anim, 50f.anim),
            fill = Color.White.fill(),
            transform = Transform(x = Anim.tween(0f, 200f, 0f, 2f, Easing.Linear)),
            start = 0f,
            end = 2f,
            name = "slide",
        )
        val findings = CraftGate.audit(composition(2f, box), hasAudio = true).findings
        assertTrue(findings.any { it.rule == "linear_motion" }, "linear x-tween is the generated tell: $findings")
    }

    @Test
    fun easedTravelIsNotLinearMotion() {
        val box = ShapeNode(
            shape = ShapeSpec.Rect(50f.anim, 50f.anim),
            fill = Color.White.fill(),
            transform = Transform(x = Anim.tween(0f, 200f, 0f, 2f, Easing.ExpoOut)),
            start = 0f,
            end = 2f,
            name = "slide",
        )
        val findings = CraftGate.audit(composition(2f, box), hasAudio = true).findings
        assertFalse(findings.any { it.rule == "linear_motion" }, "expo-out must not read as linear: $findings")
    }

    @Test
    fun overshootingOpacityIsBouncy() {
        val t = text(
            "hi",
            start = 0f,
            end = 2f,
            animators = listOf(
                TextAnimator(TextUnit.All, UnitState(opacity = 0f), at = 0f, duration = 0.5f, ease = Easing.BackOut(2f)),
            ),
        )
        val findings = CraftGate.audit(composition(2f, t), hasAudio = true).findings
        assertTrue(findings.any { it.rule == "opacity_bounce" }, "back-out on opacity rings past its target: $findings")
    }

    @Test
    fun singleStaticElementPastTheDeadStretchIsFlagged() {
        val box = ShapeNode(
            shape = ShapeSpec.Rect(100f.anim, 100f.anim),
            fill = Color.White.fill(),
            start = 0f,
            end = 5f,
            name = "box",
        )
        val findings = CraftGate.audit(composition(5f, box), hasAudio = true).findings
        assertTrue(findings.any { it.rule == "dead_stretch" }, "one static element for 5s stalls the shot: $findings")
    }

    @Test
    fun openingOnAnEmptyFrameIsFlagged() {
        val c = composition(3f, text("hi", start = 1f, end = 3f))
        val findings = CraftGate.audit(c, hasAudio = true).findings
        assertTrue(findings.any { it.rule == "first_frame_empty" }, "nothing alive at t=0 opens empty: $findings")
    }

    @Test
    fun longSilentPieceIsFlagged() {
        val c = composition(20f, text("hi", start = 0f, end = 2f, size = 48f))
        val findings = CraftGate.audit(c, hasAudio = false).findings
        assertTrue(findings.any { it.rule == "film_silent" }, "20s without audio must be silent-film: $findings")
    }

    @Test
    fun longPieceWithAudioIsNotSilent() {
        val c = composition(20f, text("hi", start = 0f, end = 2f, size = 48f))
        val findings = CraftGate.audit(c, hasAudio = true).findings
        assertFalse(findings.any { it.rule == "film_silent" }, "audio present must clear film_silent: $findings")
    }

    @Test
    fun mapperCarriesTimesWordsAndEntry() {
        val enter = TextAnimator(TextUnit.Word, UnitState(dy = 1f, opacity = 0f), at = 1f, duration = 0.4f, stagger = 0.1f)
        val c = composition(4f, text("one two three", start = 1f, end = 3.5f, size = 40f, animators = listOf(enter)))
        val timeline = CraftGate.map(c)
        val e = timeline.shots.flatMap { it.elements }.single()
        assertEquals("text", e.name.substringAfterLast('/').substringBefore('['), "node name is kept: ${e.name}")
        assertEquals(1f, e.appearsAtSec, "start comes from the node window")
        assertEquals(3.5f, e.disappearsAtSec, "end comes from the node window")
        assertEquals(3, e.textWordCount, "word count is real words: ${e.textWordCount}")
        assertEquals(40f, e.textHeightPx, "height is the type size: ${e.textHeightPx}")
        assertEquals(0.7f, e.entrySec!!, "entry spans duration+stagger*(words-1): ${e.entrySec}")
        assertEquals("fade-rise", e.entryKind, "dy+opacity reads as fade-rise: ${e.entryKind}")
        assertEquals(1080f, e.shortSidePx, "short side of 1080x1920")
    }

    @Test
    fun explicitLinesAreExtractedForTheHold() {
        assertEquals(listOf("a b", "c d e"), CraftGate.linesOf("a b\nc d e"), "explicit breaks split visual lines")
        assertEquals(listOf("hello world"), CraftGate.linesOf("hello world"), "a single row stays one line")
    }

    @Test
    fun enforceRejectsFindingsByDefault() {
        val c = composition(3f, text("hello world", start = 0f, end = 0.4f))
        val result = CraftGate.enforce(c)
        assertTrue(result.isFailure, "findings must fail the export door")
        val failure = result.exceptionOrNull() as CraftGate.Rejected
        assertFalse(failure.retryable, "default policy is reject, not retry")
        assertTrue(failure.findings.isNotEmpty(), "the failure carries the findings")
    }

    @Test
    fun enforcePassesACleanPiece() {
        val enter = TextAnimator(TextUnit.Word, UnitState(dy = 0.8f, opacity = 0f), at = 0f, duration = 0.5f, stagger = 0.05f)
        val c = composition(
            4f,
            text("hello world", start = 0f, end = 4f, size = 48f, animators = listOf(enter)),
        )
        val result = CraftGate.enforce(c, hasAudio = true)
        assertTrue(result.isSuccess, "a readable, eased, legible piece must pass: ${result.exceptionOrNull()}")
    }

    @Test
    fun sceneGroupsBecomeShots() {
        val g0 = Group(listOf(text("a", start = 0f, end = 2f, size = 48f)), start = 0f, end = 2f, name = "scene-0")
        val g1 = Group(listOf(text("b", start = 2f, end = 4f, size = 48f)), start = 2f, end = 4f, name = "scene-1")
        val c = Composition(1080, 1920, 30, 4f, Color.Black, Group(listOf(g0, g1), name = "root"))
        val shots = CraftGate.map(c).shots
        assertEquals(2, shots.size, "two scene groups are two shots: ${shots.size}")
        assertEquals(0f, shots[0].startSec, "shot 0 opens the piece")
        assertEquals(2f, shots[1].startSec, "shot 1 starts at the cut")
    }

    // ------------------------------------------------------------------ fixtures

    private fun composition(
        duration: Float,
        vararg children: Node,
        width: Int = 1080,
        height: Int = 1920,
    ): Composition = Composition(
        width = width,
        height = height,
        fps = 30,
        duration = duration,
        background = Color.Black,
        root = Group(children.toList(), name = "root"),
    )

    private fun text(
        words: String,
        start: Float = 0f,
        end: Float = Float.POSITIVE_INFINITY,
        size: Float = 48f,
        animators: List<TextAnimator> = emptyList(),
        name: String = "text",
    ): TextNode = TextNode(
        text = words,
        type = TypeSpec(BrandFonts.Role.Display, 700, size),
        fill = Color.White.fill(),
        animators = animators,
        start = start,
        end = end,
        name = name,
    )
}
