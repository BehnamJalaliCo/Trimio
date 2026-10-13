package io.trimio.engine.motion.explainer

import io.trimio.engine.motion.TextAlign
import io.trimio.engine.motion.recipe.Box

/** Where the chapter header, the stage and the caption band sit ([ArtDirection.layout]). */
enum class StageGrammar(val id: String) {
    /** Mark and heading centred at the top, the stage centred under them (the reference). */
    HeaderTop("header-top"),

    /** Mark and heading set from the reading start at the top, the stage under them. */
    HeaderLeft("header-left"),

    /** Landscape: the heading in a column on the reading-start side; portrait: a tall header with a rule. */
    Split("split"),

    /** A small tag in the reading-start corner; the stage takes the whole frame. */
    FullBleed("full-bleed"),
    ;

    companion object {
        fun named(id: String?): StageGrammar {
            val key = id?.trim()?.lowercase()?.replace(' ', '-')?.replace('_', '-').orEmpty()
            return entries.firstOrNull { it.id == key } ?: when (key) {
                "top", "centered", "centre", "center", "header" -> HeaderTop
                "left", "start", "header-start", "aligned" -> HeaderLeft
                "side", "column", "two-column", "split-screen" -> Split
                "full", "bleed", "fullbleed", "immersive", "minimal" -> FullBleed
                else -> HeaderTop
            }
        }
    }
}

/** How chapters are counted on screen ([ArtDirection.chapterMark]). */
enum class ChapterMark(val id: String) {
    /** A dash per chapter that fills, in the chapter's accent, as chapters go by. */
    Dashes("dashes"),

    /** "02 / 04" in the chapter's accent. */
    Numerals("numerals"),

    /** A hairline that grows with the film's progress, with an accent head. */
    Rule("rule"),
    None("none"),
    ;

    companion object {
        fun named(id: String?): ChapterMark {
            val key = id?.trim()?.lowercase().orEmpty()
            return entries.firstOrNull { it.id == key } ?: when (key) {
                "dash", "progress", "pips", "dots" -> Dashes
                "numbers", "number", "numeral", "index" -> Numerals
                "line", "bar", "hairline" -> Rule
                else -> if (key.isEmpty()) Dashes else None
            }
        }
    }
}

/** The caption treatment ([ArtDirection.captionStyle]). */
enum class CaptionStyle(val id: String) {
    /** A dark pill of a few words resting on a thin accent band (the reference). */
    BandPill("band-pill"),

    /** Quiet words low in the frame, no body. */
    PlainLower("plain-lower"),
    None("none"),
    ;

    companion object {
        fun named(id: String?): CaptionStyle {
            val key = id?.trim()?.lowercase()?.replace(' ', '-')?.replace('_', '-').orEmpty()
            return entries.firstOrNull { it.id == key } ?: when (key) {
                "band", "pill", "band-pills" -> BandPill
                "plain", "lower", "subtitle", "subtitles" -> PlainLower
                "off", "no", "hidden" -> None
                else -> BandPill
            }
        }
    }
}

/**
 * The frame's plan for one film: the safe area, the header (mark and heading), the stage the
 * layout solver fills, and the caption band. Everything is in frame px; [u] is the size unit
 * (1 at a 1080-px short side) every piece is drawn in.
 */
class StageFrame(val width: Float, val height: Float, val grammar: StageGrammar, val rtl: Boolean, val captions: CaptionStyle) {
    val u: Float = minOf(width, height) / 1080f
    val portrait: Boolean = height >= width

    /** Nothing but the caption band may leave this. */
    val safe: Box = if (portrait) {
        Box(width * 0.075f, height * 0.065f, width * 0.925f, height * 0.86f)
    } else {
        Box(width * 0.065f, height * 0.08f, width * 0.935f, height * 0.93f)
    }

    /** The caption band's centre line, and the band itself (a full-width strip, outside the safe check). */
    val captionY: Float = if (portrait) height * 0.80f else height * 0.885f
    val band: Box = Box(0f, captionY - BAND * u / 2f, width, captionY + BAND * u / 2f)

    /** Size of the chapter heading. */
    val titleSize: Float = u * when (grammar) {
        StageGrammar.FullBleed -> 46f
        StageGrammar.Split -> if (portrait) 112f else 96f
        else -> if (portrait) 100f else 84f
    }

    /** Where the chapter mark sits (its centre line) and the heading's centre. */
    val markY: Float
    val titleY: Float
    val titleX: Float
    val titleAlign: TextAlign
    val titleWidth: Float

    /** The box the header occupies (the stage keeps off it). */
    val header: Box

    /** The live stage. */
    val stage: Box

    init {
        val s = safe
        val bottom = if (captions == CaptionStyle.None) s.bottom else captionY - u * (if (portrait) 90f else 70f)
        val startEdge = if (rtl) s.right else s.left
        when (grammar) {
            StageGrammar.HeaderTop, StageGrammar.HeaderLeft -> {
                markY = s.top + u * 14f
                titleY = markY + u * 30f + titleSize * 0.72f
                val centred = grammar == StageGrammar.HeaderTop
                titleAlign = if (centred) TextAlign.Center else TextAlign.Start
                titleWidth = s.width * if (centred) 0.9f else 0.8f
                titleX = if (centred) s.centerX else startEdge
                header = Box(s.left, s.top, s.right, titleY + titleSize * 0.75f)
                stage = Box(s.left + u * 10f, header.bottom + u * (if (portrait) 70f else 40f), s.right - u * 10f, bottom)
            }
            StageGrammar.Split -> if (portrait) {
                markY = s.top + u * 14f
                titleY = markY + u * 36f + titleSize * 0.75f
                titleAlign = TextAlign.Start
                titleWidth = s.width * 0.86f
                titleX = startEdge
                header = Box(s.left, s.top, s.right, titleY + titleSize * 0.8f + u * 30f)
                stage = Box(s.left + u * 10f, header.bottom + u * 70f, s.right - u * 10f, bottom)
            } else {
                val column = s.width * 0.34f
                titleY = (s.top + bottom) / 2f
                markY = titleY - titleSize * 0.95f - u * 26f
                titleAlign = TextAlign.Start
                titleWidth = column - u * 40f
                titleX = startEdge
                header = if (rtl) Box(s.right - column, s.top, s.right, bottom) else Box(s.left, s.top, s.left + column, bottom)
                stage = if (rtl) Box(s.left, s.top + u * 10f, s.right - column - u * 60f, bottom) else Box(s.left + column + u * 60f, s.top + u * 10f, s.right, bottom)
            }
            StageGrammar.FullBleed -> {
                markY = s.top + u * 10f
                titleY = markY + u * 22f + titleSize * 0.7f
                titleAlign = TextAlign.Start
                titleWidth = s.width * 0.6f
                titleX = startEdge
                header = Box(s.left, s.top, s.right, titleY + titleSize * 0.75f)
                stage = Box(s.left + u * 10f, header.bottom + u * 40f, s.right - u * 10f, bottom)
            }
        }
    }

    companion object {
        /** Caption band thickness at u = 1. */
        const val BAND = 22f
    }
}
