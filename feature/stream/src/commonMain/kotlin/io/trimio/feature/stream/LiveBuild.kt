package io.trimio.feature.stream

import androidx.compose.ui.graphics.ImageBitmap

/**
 * The six stages the creator sees, in order. The engine reports coarser stages and its own step
 * names ([of]); the screen names what a person would: listening, understanding, planning, the
 * jury, the sound and the render.
 */
enum class BuildStage(val fa: String, val en: String) {
    Listen("شنیدن", "Listening"),
    Understand("فهم", "Understanding"),
    Plan("برنامهٔ ادیت", "Planning the edit"),
    Jury("داوری", "Jury"),
    Sound("صدا و موسیقی", "Sound and music"),
    Render("رندر", "Render"),
    ;

    companion object {
        /**
         * Maps the engine's stage ("Prepare", "Transcribe", "Direct", "Render", "Done") and step
         * ("understand", "jury", "render 120/450"…) to the stage on screen.
         */
        fun of(stage: String, step: String): BuildStage {
            val s = step.substringBefore(' ').lowercase()
            return when (stage) {
                "Prepare", "Transcribe" -> Listen
                "Render", "Done" -> Render
                else -> when (s) {
                    "listen", "direct" -> Listen
                    "understand", "research", "knowledge" -> Understand
                    "jury", "look", "critic" -> Jury
                    "sound", "music", "master" -> Sound
                    else -> Plan
                }
            }
        }
    }
}

/** One of the ten judges; [score] is null until the panel has spoken. */
data class JudgeScore(val id: String, val score: Float?) {
    val perfect: Boolean get() = (score ?: 0f) >= PERFECT

    companion object {
        const val PERFECT = 9.95f

        /** The panel in its order, before any verdict. */
        val Panel: List<JudgeScore> = listOf("hook", "typography", "layout", "motion", "rhythm", "music", "sound", "spelling", "cta", "brief").map { JudgeScore(it, null) }

        fun nameFa(id: String): String = NAMES[id]?.first ?: id
        fun nameEn(id: String): String = NAMES[id]?.second ?: id

        private val NAMES = mapOf(
            "hook" to ("هوک" to "Hook"), "typography" to ("تایپوگرافی" to "Typography"), "layout" to ("چیدمان" to "Layout"),
            "motion" to ("حرکت" to "Motion"), "rhythm" to ("ریتم" to "Rhythm"), "music" to ("موسیقی" to "Music"),
            "sound" to ("صدا" to "Sound"), "spelling" to ("املا" to "Spelling"), "cta" to ("دعوت به اقدام" to "Call to action"),
            "brief" to ("وفاداری به بریف" to "Brief"),
        )
    }
}

/**
 * Everything the live build view shows. Built by the app from the production's state, so the
 * screen stays a pure function of it (and renders the same in tests and on the desktop app).
 */
data class LiveBuild(
    val styleName: String? = null,
    /** 0..1, never moving backwards. */
    val progress: Float = 0f,
    val stage: BuildStage = BuildStage.Listen,
    /** Seconds each finished stage took, for the stage list. */
    val stageSeconds: Map<BuildStage, Float> = emptyMap(),
    /** Small frames of the edit as it forms, newest last. */
    val previews: List<ImageBitmap> = emptyList(),
    val framesDone: Int = 0,
    val framesTotal: Int = 0,
    val etaSeconds: Int? = null,
    /** What the director is doing now, in its voice ("«پنج درصد» را با شمارندهٔ عدد…"). */
    val directorNote: String? = null,
    /** Words of the director's note set on the lime block. */
    val noteEmphasis: List<String> = emptyList(),
    val judges: List<JudgeScore> = JudgeScore.Panel,
    /** The transcript's tail as the words arrive, with the emphasised ones. */
    val transcript: String = "",
    val transcriptEmphasis: List<String> = emptyList(),
    val onDevice: Boolean = true,
)

/** A finished edit as the result screen shows it. */
data class BuildResult(
    val title: String,
    val styleName: String?,
    val durationMs: Long,
    /** How long the build took, and where it ran. */
    val buildSeconds: Int,
    val onDevice: Boolean,
    val scenes: Int,
    val words: Int,
    val effects: Int,
    val lufs: Float,
    val judges: List<JudgeScore>,
    /** The jury's notes (what it fixed or would still polish), one per line. */
    val findings: List<String> = emptyList(),
    val poster: ImageBitmap? = null,
    /** 1 for the first edit, 2+ for variations and revisions. */
    val version: Int = 1,
    val saved: Boolean = false,
    /** A revision or variation is being made from this edit. */
    val working: Boolean = false,
)

/** An honest failure: what went wrong, at which stage, and what the creator can do. */
data class BuildFailure(
    val stage: BuildStage,
    val title: String,
    val body: String,
    val options: List<Option>,
    val primary: String,
    val detail: String? = null,
) {
    data class Option(val title: String, val hint: String, val recommended: Boolean = false)
}

/** What the creator can do with a finished edit. */
data class ResultActions(
    val onClose: () -> Unit = {},
    val onPlay: () -> Unit = {},
    /** Their reaction in their own words; the next edit follows it. */
    val onFeedback: (String) -> Unit = {},
    /** Another variation: same words and reading, a new seed. */
    val onVariation: () -> Unit = {},
    val onSave: () -> Unit = {},
    val onShare: () -> Unit = {},
    /** The export screen (other frames, sizes and rates, captions, the pro package); null hides it. */
    val onExport: (() -> Unit)? = null,
)
