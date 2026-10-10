package io.trimio.engine.motion.score

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Level 3 of the motion language: the score the director writes. It is deliberately small and
 * forgiving — words are referenced by index or by quoting them, every field is optional, unknown
 * names fall back to the nearest recipe — so even a small on-device model writes a valid score.
 * Timing, layout, readability and polish are the compiler's job, not the model's.
 *
 * ```json
 * {"look":"noir","scenes":[{"from":0,"camera":"push-in","beats":[
 *   {"recipe":"slam","text":"بیت‌کوین","energy":0.9},
 *   {"recipe":"counter","at":3,"value":5,"suffix":"٪","label":"رشد امروز"}]}]}
 * ```
 */
@Serializable
data class Score(
    /** "9:16", "1:1", "4:5" or "16:9". */
    val format: String = "9:16",
    val look: String = "noir",
    /** Music tempo; hits snap to the beat grid when known. */
    val bpm: Float? = null,
    val beatOffset: Float = 0f,
    val captions: CaptionScore = CaptionScore(),
    val scenes: List<SceneScore> = emptyList(),
    /** Fill empty scenes with automatic direction (numbers, keywords, emphasis). */
    val auto: Boolean = true,
) {
    companion object {
        private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }

        /** Parses a score, tolerating code fences and prose around the JSON object. */
        fun parse(text: String): Score {
            val start = text.indexOf('{')
            val end = text.lastIndexOf('}')
            require(start >= 0 && end > start) { "no JSON object in score" }
            return json.decodeFromString(serializer(), text.substring(start, end + 1))
        }

        fun encode(score: Score): String = json.encodeToString(serializer(), score)
    }
}

@Serializable
data class CaptionScore(
    val show: Boolean = true,
    val recipe: String = "pop-captions",
    /** "lower", "center" or "top". */
    val place: String = "lower",
    val maxWords: Int = 4,
)

@Serializable
data class SceneScore(
    /** First word of the scene (index into the transcript). Scenes run until the next one starts. */
    val from: Int? = null,
    /** Or a start time in seconds. */
    val time: Float? = null,
    /** "media", "aurora", "grid" or "plain". Defaults to footage when there is any. */
    val bg: String? = null,
    /** "push-in", "pull-out", "drift" or "still". */
    val camera: String? = null,
    /** How this scene enters: "cut", "whip", "flash", "leak" or "zoom". */
    val transition: String? = null,
    val beats: List<BeatScore> = emptyList(),
)

@Serializable
data class BeatScore(
    val recipe: String = "mask-rise",
    /** Word index the beat lands on. */
    val at: Int? = null,
    /** Word index it stays until (inclusive). */
    val until: Int? = null,
    /** Or a landing time in seconds. */
    val time: Float? = null,
    /** Seconds on screen, when not tied to words. */
    val hold: Float? = null,
    /** What to show. When it quotes the transcript, the beat lands as those words are spoken. */
    val text: String? = null,
    /** Words (quoted from [text]) to mark. */
    val emphasis: List<String> = emptyList(),
    /** "top", "center", "lower" or "full". */
    val place: String? = null,
    val energy: Float? = null,
    val value: Float? = null,
    val from: Float? = null,
    val prefix: String? = null,
    val suffix: String? = null,
    val decimals: Int? = null,
    val points: List<Float>? = null,
    val icon: String? = null,
    val label: String? = null,
    /** "block", "ink", "underline" or "circle". */
    val mark: String? = null,
)
