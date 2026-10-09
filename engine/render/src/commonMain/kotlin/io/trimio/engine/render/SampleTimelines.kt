package io.trimio.engine.render

import io.trimio.core.model.input.AspectRatio
import io.trimio.core.model.input.CanvasSpec
import io.trimio.core.model.style.BoxStyle
import io.trimio.core.model.style.CaptionMode
import io.trimio.core.model.style.CaptionSpec
import io.trimio.core.model.style.EmphasisSpec
import io.trimio.core.model.style.Palette
import io.trimio.core.model.style.StyleFamily
import io.trimio.core.model.style.StyleSpec
import io.trimio.core.model.text.Language
import io.trimio.core.model.text.ScriptDetector
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.timeline.Anchor
import io.trimio.core.model.timeline.BackgroundClip
import io.trimio.core.model.timeline.CaptionClip
import io.trimio.core.model.timeline.ElementClip
import io.trimio.core.model.timeline.Timeline

/**
 * A short Persian crypto-signal edit used by golden tests, design reviews and the style gallery
 * before the user has footage of their own.
 */
object SampleTimelines {

    private data class W(val text: String, val start: Long, val end: Long, val emphasis: Float = 0f, val group: Int)

    private val words = listOf(
        W("سلام", 300, 650, group = 0), W("دوستان،", 680, 1150, group = 0),
        W("امروز", 1350, 1700, group = 1), W("بیت‌کوین", 1720, 2250, 0.9f, group = 1),
        W("پنج", 2400, 2700, 0.8f, group = 2), W("درصد", 2720, 3050, group = 2), W("رشد", 3080, 3500, 0.85f, group = 2), W("کرد", 3520, 3800, group = 2),
        W("سیگنال", 4100, 4600, 0.75f, group = 3), W("خرید", 4620, 4950, group = 3), W("ما", 4980, 5150, group = 3),
        W("به", 5200, 5350, group = 4), W("تارگت", 5380, 5900, 0.9f, group = 4), W("رسید", 5920, 6400, group = 4),
        W("BTC", 6700, 7200, 0.95f, group = 5), W("بالای", 7250, 7600, group = 5), W("حمایت", 7650, 8200, group = 5),
    )

    fun cryptoSignal(canvas: CanvasSpec = CanvasSpec(AspectRatio.Portrait9x16), styleId: String = "liquid-glass") = Timeline(
        styleId = styleId,
        seed = 7,
        canvas = canvas,
        durationMs = 9_000,
        clips = buildList {
            add(BackgroundClip(TimeRange(0, 9_000), preset = BackgroundClip.STYLE_PRESET, audioReactive = true))
            words.forEachIndexed { i, w ->
                add(
                    CaptionClip(
                        range = TimeRange(w.start, w.end),
                        text = w.text,
                        language = ScriptDetector.detect(w.text, Language.Persian),
                        preset = "pop",
                        emphasis = w.emphasis,
                        wordIndex = i,
                        group = w.group,
                    ),
                )
            }
            add(ElementClip(TimeRange(1_700, 2_380), assetId = "icon/coin", preset = "pop", anchor = Anchor.Center, scale = 1.1f))
            add(ElementClip(TimeRange(2_400, 4_000), assetId = "counter/percent", preset = "pop", anchor = Anchor.Center, params = mapOf("from" to "0", "to" to "5", "suffix" to "٪", "digits" to "fa", "label" to "رشد امروز")))
            add(ElementClip(TimeRange(4_000, 6_500), assetId = "chart/candles", preset = "rise", anchor = Anchor.Center, params = mapOf("trend" to "up")))
            add(ElementClip(TimeRange(6_600, 8_800), assetId = "arrow/up", preset = "pop", anchor = Anchor.Center, scale = 1.2f))
        },
    )

    /** Liquid-glass look used until style packs load (phase 4 replaces this with the real pack). */
    val previewStyle = StyleSpec(
        id = "liquid-glass",
        family = StyleFamily.ShaderFx,
        palette = Palette(
            background = listOf("#05050A", "#2B1A7A", "#0A5C78", "#B0249C"),
            text = "#FFFFFF",
            accent = "#3DE8FF",
            accent2 = "#FF4FD8",
        ),
        captions = CaptionSpec(
            mode = CaptionMode.BuildUp,
            entry = "pop",
            box = BoxStyle.Glass,
            emphasis = EmphasisSpec(scale = 1.22f, colorRole = "accent"),
        ),
    )
}
