package io.trimio.engine.autopilot

import kotlinx.serialization.Serializable

/**
 * What the piece is and what matters in it, read from the transcript and the creator's brief.
 * Written by the understanding model (on-device or cloud), or by [RulesUnderstander] when no
 * model is available. Word positions are transcript indices; the planner turns all of this into a
 * score, so nothing here is about timing or layout.
 */
@Serializable
data class Understanding(
    /** A 2–6 word title in the transcript's language. */
    val title: String = "",
    /** English, a few words: what the piece is about. */
    val topic: String = "",
    val domain: String = "general",
    val mood: String = "confident",
    val brief: BriefRead = BriefRead(),
    /** Recogniser spelling fixes, one word each (brand names back in their own spelling). */
    val fixes: List<Fix> = emptyList(),
    val entities: List<Entity> = emptyList(),
    /** One reading per transcript line ([Lines]), in order. */
    val lines: List<LineRead> = emptyList(),
    val hook: Hook? = null,
    val cta: Cta? = null,
) {
    companion object {
        val DOMAINS = listOf(
            "tech", "software", "ai", "crypto", "finance", "business", "marketing", "medical", "health", "fitness", "food",
            "automotive", "real-estate", "education", "travel", "fashion", "beauty", "lifestyle", "news", "sports", "gaming", "general",
        )
        val MOODS = listOf("energetic", "confident", "calm", "serious", "playful", "inspiring", "urgent", "luxury")
        val SHOWS = listOf(
            "headline", "counter", "logos", "terminal", "network", "meter", "chart", "object", "objects", "list", "comment", "lower-third", "stamp", "none",
        )
        val ROLES = listOf("hook", "claim", "problem", "solution", "insight", "number", "list", "steps", "comparison", "warning", "benefit", "proof", "question", "cta", "punchline")
        val KINDS = listOf("brand", "product", "app", "person", "place", "organization", "object", "concept")
    }
}

/** What the creator asked for, as the model read it ("auto" when the brief does not say). */
@Serializable
data class BriefRead(
    /** noir, paper, lumen or auto. */
    val look: String = "auto",
    /** 0 calm … 1 explosive; negative when the brief does not say. */
    val energy: Float = -1f,
    /** fast, normal, calm or auto. */
    val pace: String = "auto",
    /** word, phrase, none or auto. */
    val captions: String = "auto",
    /** A music mood, none, or auto. */
    val music: String = "auto",
    /** Lead with the payoff before the setup. */
    val payoffFirst: Boolean = true,
)

@Serializable
data class Fix(val at: Int, val text: String)

@Serializable
data class Entity(
    val at: Int,
    val until: Int = -1,
    /** Canonical name in its own spelling ("Claude Code", "Toyota", "ibuprofen"). */
    val name: String,
    val kind: String = "concept",
    /** English picture query ("electric car", "stethoscope"); empty when no picture fits. */
    val visual: String = "",
)

/** How one line of the transcript should be shown. */
@Serializable
data class LineRead(
    /** The line rewritten with correct spelling (copying it first also makes the model read it). */
    val fixed: String = "",
    /** What the line says, in a few English words: makes a small model read this line, not the last one. */
    val gist: String = "",
    val role: String = "claim",
    /** How to show it: one of [Understanding.SHOWS]. */
    val show: String = "headline",
    /** 1–4 words for the screen, in the transcript's language. */
    val title: String = "",
    /** English picture query for object/icon beats. */
    val visual: String = "",
    /** Items said in a list (tool names, ingredients, steps). */
    val items: List<String> = emptyList(),
    val energy: Float = 0.6f,
)

@Serializable
data class Hook(
    /** The line holding the payoff the piece promises (often said late): shown first. */
    val line: Int,
    /** 2–5 words for the opening frame. */
    val title: String = "",
)

@Serializable
data class Cta(
    /** comment, follow, save, share, link or dm. */
    val action: String = "comment",
    /** The word viewers should send, when there is one. */
    val keyword: String = "",
)
