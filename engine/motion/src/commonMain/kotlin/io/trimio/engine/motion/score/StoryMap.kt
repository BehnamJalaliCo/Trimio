package io.trimio.engine.motion.score

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The narrative map behind a score, as the model writes it: the beats the story runs through
 * (setup, turn, payoff, callback), each with the claim it makes and the reason it earns its
 * place — the creative decision, made before any picture. The object chain is what travels
 * through the piece; the banned list is what no beat may show. Timing and layout are never
 * decided here: the planner executes this map.
 */
@Serializable
data class StoryMap(
    val beats: List<StoryBeat> = emptyList(),
    /** Things carried from beat to beat ("the voucher", "the channel card"): one thread, not a pile of stickers. */
    val objects: List<String> = emptyList(),
    /** What the edit must never show ("no countdown", "no stock smiles"): the model's own restraint. */
    val banned: List<String> = emptyList(),
) {
    /** The first beat of [kind], or null when the story does without it. */
    fun beatOf(kind: String): StoryBeat? = beats.firstOrNull { it.kind == kind }

    companion object {
        val KINDS = listOf("setup", "turn", "payoff", "callback")

        private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true; explicitNulls = false }

        /** Parses a story map, tolerating code fences and prose around the JSON object ([ScoreGrammar.repair]). */
        fun parse(text: String): StoryMap = json.decodeFromString(serializer(), ScoreGrammar.repair(text))

        fun encode(map: StoryMap): String = json.encodeToString(serializer(), map)

        /**
         * Keeps what points inside a transcript of [n] words: kinds pinned to the four the story
         * knows, beats ordered by where they start, each running until the next one begins.
         */
        fun sanitize(map: StoryMap, n: Int): StoryMap {
            val ordered = map.beats.filter { it.from in 0 until n }.sortedBy { it.from }
            val beats = ordered.mapIndexed { i, b ->
                val end = ordered.getOrNull(i + 1)?.from?.minus(1) ?: (n - 1)
                b.copy(
                    kind = b.kind.takeIf { it in KINDS } ?: "setup",
                    until = b.until.coerceIn(b.from, end),
                    claim = b.claim.trim().take(CLAIM_CHARS),
                    rationale = b.rationale.trim().take(RATIONALE_CHARS),
                    energy = b.energy.coerceIn(0f, 1f),
                )
            }
            return StoryMap(
                beats = beats,
                objects = map.objects.map { it.trim() }.filter { it.isNotBlank() },
                banned = map.banned.map { it.trim() }.filter { it.isNotBlank() },
            )
        }

        private const val CLAIM_CHARS = 80
        private const val RATIONALE_CHARS = 160
    }
}

/** One beat of the story: what happens in the transcript's words, and why it is there. */
@Serializable
data class StoryBeat(
    /** setup, turn, payoff or callback ([StoryMap.KINDS]). */
    val kind: String = "setup",
    /** First word of the beat (transcript index). */
    val from: Int = 0,
    /** Last word of the beat (inclusive); until the next beat when it trails. */
    val until: Int = -1,
    /** What the beat claims, in the transcript's language. */
    val claim: String = "",
    /** Why the piece needs this beat here — the story's own reason. */
    val rationale: String = "",
    /** 0 calm … 1 explosive. */
    val energy: Float = 0.6f,
)
