package io.trimio.engine.autopilot

import io.trimio.engine.assets.art.ArtCatalogue

/**
 * Which picture beat a moment gets, for the planner: a reaction (a small animated emoji that pops
 * beside the speaker and drifts up) when the picture is a feeling — a laugh, a wow, fire, money,
 * applause — otherwise the sticker (`object`) or, on some seeds and lines, the picture beside its
 * label (`animated`). Seeded by hashing, so it never draws from the planner's own random streams.
 */
object ArtBeats {
    /** The recipe for a picture of [query] on line [line] of the edit made with [seed]. */
    fun recipe(query: String, seed: Long, line: Int): String = when {
        reaction(query) -> "live-reaction"
        mix(seed, line) % BESIDE_EVERY == 0L -> "animated"
        else -> "object"
    }

    /** The picture is a feeling or a reaction, not a thing. */
    fun reaction(query: String): Boolean {
        val words = ArtCatalogue.tokens(query)
        return words.size in 1..2 && words.any { it in REACTIONS }
    }

    private fun mix(seed: Long, line: Int): Long {
        var z = seed * GOLDEN + line * SALT
        z = (z xor (z ushr 30)) * MIX
        return (z xor (z ushr 31)).mod(PRIME)
    }

    private val REACTIONS = setOf(
        "laugh", "laughing", "lol", "funny", "joke", "joy", "wow", "shock", "shocked", "surprise", "surprised", "mind", "blown",
        "fire", "hot", "lit", "love", "heart", "celebrate", "celebration", "party", "clap", "applause", "cry", "sad", "angry",
        "rage", "cool", "eye", "100", "perfect", "sparkle", "magic", "thumb", "scared", "fear", "facepalm", "think", "hmm",
    ).map { ArtCatalogue.tokens(it).firstOrNull() ?: it }.toSet()

    private const val BESIDE_EVERY = 3L
    private const val GOLDEN = -0x61c8864680b583ebL
    private const val SALT = 0x2545F4914F6CDD1DL
    private const val MIX = -0x40a7b892e31b1a47L
    private const val PRIME = 1_000_003L
}
