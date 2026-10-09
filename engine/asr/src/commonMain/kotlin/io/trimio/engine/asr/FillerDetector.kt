package io.trimio.engine.asr

import io.trimio.core.model.transcript.Word

/**
 * Finds hesitation fillers worth cutting. They are proposed as cuts, never removed silently:
 * the Director and the user decide.
 */
object FillerDetector {
    private val persian = setOf("ا\u0650", "اه", "ام", "امم", "اممم", "ااا", "ا\u0650م", "ععع", "هوم", "اوم")
    private val english = setOf("um", "umm", "uh", "uhh", "erm", "er", "hmm", "mm", "ah")

    fun fillerIndices(words: List<Word>): List<Int> = words.indices.filter { i ->
        val bare = words[i].text.lowercase().trim('.', ',', '!', '?', '،', '؟', '…', '-', ' ')
        bare in persian || bare in english
    }
}
