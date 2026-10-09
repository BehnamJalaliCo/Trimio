package io.trimio.engine.director

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.Json

/**
 * The Director's decisions, expressed over transcript word indices. Both the rules engine and the
 * LLMs produce this compact plan; [TimelineComposer] turns it into a frame-exact [io.trimio.core.model.timeline.Timeline].
 * Keeping models away from raw milliseconds is what makes a 4B on-device model reliable.
 */
@Serializable
data class EditPlan(
    val styleId: String,
    /** 0 calm … 1 hyper. */
    val energy: Float,
    /** auto, BuildUp, Phrase, Karaoke or SingleWord. */
    val captionMode: String = "auto",
    val cutSilences: Boolean = true,
    val cutFillers: Boolean = true,
    /** none or a music mood: uplifting, energetic, chill, cinematic, corporate, tense. */
    val music: String = "none",
    /** Last word index of the hook (opening seconds that must grab attention); -1 for none. */
    val hookEnd: Int = -1,
    /** First word index of the call to action; -1 for none. */
    val ctaStart: Int = -1,
    /** Word indices to emphasise (bigger, accent colour, camera punch, sound). */
    val emphasis: List<Int> = emptyList(),
    val elements: List<ElementCue> = emptyList(),
    /** Short on-screen title for the hook; empty for none. */
    val headline: String = "",
    /** One sentence explaining the edit, shown on the build screen. */
    val summaryFa: String = "",
    val summaryEn: String = "",
)

/**
 * A motion-graphics element timed to a spoken word. [kind] is one of [KINDS]; [value] is the
 * number for counters/progress (as text), the icon name for icons, the text for badges.
 */
@Serializable
data class ElementCue(
    val word: Int,
    val kind: String,
    val value: String = "",
    val label: String = "",
) {
    companion object {
        val KINDS = listOf("counter", "icon", "arrow-up", "arrow-down", "chart-up", "chart-down", "badge", "progress")
        val ICONS = listOf("coin", "check", "star", "bolt", "heart")
    }
}

object EditPlanSchema {
    val MUSIC_MOODS = listOf("none", "uplifting", "energetic", "chill", "cinematic", "corporate", "tense")
    val CAPTION_MODES = listOf("auto", "BuildUp", "Phrase", "Karaoke", "SingleWord")

    /** JSON schema of [EditPlan] for structured output; [styleIds] restricts the style to installed packs. */
    fun schema(styleIds: List<String>, maxEmphasis: Int = 40, maxElements: Int = 16): JsonObject {
        fun enum(values: List<String>) = values.joinToString(",") { "\"$it\"" }
        return Json.parseToJsonElement(
            """
            {"type":"object","properties":{
              "styleId":{"type":"string","enum":[${enum(styleIds)}]},
              "energy":{"type":"number"},
              "captionMode":{"type":"string","enum":[${enum(CAPTION_MODES)}]},
              "cutSilences":{"type":"boolean"},
              "cutFillers":{"type":"boolean"},
              "music":{"type":"string","enum":[${enum(MUSIC_MOODS)}]},
              "hookEnd":{"type":"integer"},
              "ctaStart":{"type":"integer"},
              "emphasis":{"type":"array","maxItems":$maxEmphasis,"items":{"type":"integer"}},
              "elements":{"type":"array","maxItems":$maxElements,"items":{"type":"object","properties":{
                "word":{"type":"integer"},
                "kind":{"type":"string","enum":[${enum(ElementCue.KINDS)}]},
                "value":{"type":"string","maxLength":24},
                "label":{"type":"string","maxLength":32}}}},
              "headline":{"type":"string","maxLength":48},
              "summaryFa":{"type":"string","maxLength":160},
              "summaryEn":{"type":"string","maxLength":160}
            }}
            """,
        ).jsonObject
    }
}
