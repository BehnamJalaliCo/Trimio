package io.trimio.core.model

import io.trimio.core.model.input.AspectRatio
import io.trimio.core.model.input.CanvasSpec
import io.trimio.core.model.input.InputSource
import io.trimio.core.model.input.MediaUri
import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.timeline.BackgroundClip
import io.trimio.core.model.timeline.CaptionClip
import io.trimio.core.model.timeline.CutClip
import io.trimio.core.model.timeline.CutReason
import io.trimio.core.model.timeline.ElementClip
import io.trimio.core.model.timeline.SfxClip
import io.trimio.core.model.timeline.Timeline
import io.trimio.core.model.timeline.TimelineJson
import io.trimio.core.model.timeline.TimelineValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TimelineTest {

    private val sample = Timeline(
        styleId = "liquid-glass",
        seed = 42,
        canvas = CanvasSpec(AspectRatio.Portrait9x16),
        durationMs = 5_000,
        clips = listOf(
            CaptionClip(TimeRange(3_210, 3_580), text = "سود", language = Language.Persian, preset = "pop-scale", emphasis = 0.9f, wordIndex = 7),
            ElementClip(TimeRange(3_200, 4_400), assetId = "coin-3d", preset = "drop-bounce"),
            SfxClip(TimeRange(3_210, 3_900), assetId = "cash-register"),
            CutClip(TimeRange(0, 400), CutReason.Silence),
        ),
    )

    @Test
    fun jsonRoundTripKeepsEveryClipType() {
        val json = TimelineJson.encode(sample)
        assertEquals(sample, TimelineJson.decode(json))
        assertTrue("\"type\":\"caption\"" in json)
        assertTrue("\"type\":\"sfx\"" in json)
    }

    @Test
    fun decodeIgnoresFieldsFromNewerMinorVersions() {
        val json = TimelineJson.encode(sample).replaceFirst("{", "{\"futureField\":true,")
        assertEquals(sample, TimelineJson.decode(json))
    }

    @Test
    fun decodeRejectsNewerSchemaVersion() {
        val json = TimelineJson.encode(sample.copy(version = Timeline.CURRENT_VERSION + 1))
        assertFailsWith<IllegalArgumentException> { TimelineJson.decode(json) }
    }

    @Test
    fun validTimelinePasses() {
        assertTrue(TimelineValidator().isRenderable(sample))
    }

    @Test
    fun overlappingCaptionsAtSameAnchorAreRejected() {
        val bad = sample.copy(
            clips = sample.clips + CaptionClip(TimeRange(3_400, 3_900), text = "بیشتر", language = Language.Persian, preset = "pop-scale", wordIndex = 8),
        )
        val issues = TimelineValidator().validate(bad)
        assertTrue(issues.any { it.code == "caption-overlap" })
    }

    @Test
    fun clipBeyondDurationIsRejected() {
        val bad = sample.copy(clips = listOf(SfxClip(TimeRange(4_900, 5_400), assetId = "whoosh")))
        assertFalse(TimelineValidator().isRenderable(bad))
    }

    @Test
    fun audioOnlyInputRequiresFullBackground() {
        val input = InputSource.AudioOnly(MediaUri("file://voice.m4a"), 5_000, CanvasSpec())
        assertFalse(TimelineValidator().isRenderable(sample, input))

        val withBackground = sample.copy(
            clips = sample.clips + BackgroundClip(TimeRange(0, 2_500), preset = "aurora") +
                BackgroundClip(TimeRange(2_500, 5_000), preset = "aurora", audioReactive = true),
        )
        assertTrue(TimelineValidator().isRenderable(withBackground, input))
    }

    @Test
    fun tooShortCaptionIsOnlyAWarning() {
        val quick = sample.copy(clips = listOf(CaptionClip(TimeRange(0, 100), text = "و", language = Language.Persian, preset = "fade", wordIndex = 0)))
        val issues = TimelineValidator().validate(quick)
        assertEquals(listOf("caption-too-short"), issues.map { it.code })
        assertTrue(TimelineValidator().isRenderable(quick))
    }
}
