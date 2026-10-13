package io.trimio.engine.autopilot.explainer

import io.trimio.core.model.text.Language
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.autopilot.LineRead
import io.trimio.engine.autopilot.Lines
import io.trimio.engine.autopilot.RulesUnderstander
import io.trimio.engine.autopilot.Understanding
import io.trimio.engine.motion.score.NumberWords

/**
 * One thought of the narration: a sentence or a clause, with its words, its time and what the
 * understanding read in it. Clauses are the screenwriter's unit: every clause gets a picture.
 */
data class Clause(
    val index: Int,
    /** First and last word, indices into [Narration.words]. */
    val first: Int,
    val last: Int,
    /** Seconds on the narration's clock: the first word's start and the last word's end. */
    val start: Double,
    val end: Double,
    val text: String,
    /** The understanding's reading of this clause (its line), when there is one. */
    val read: LineRead? = null,
) {
    val words: IntRange get() = first..last
    val length: Double get() = end - start
}

/**
 * What every director reads: the narration's words, cut into [clauses] with their timings, the
 * understanding of the piece, the brief, and the language the screen speaks.
 */
class Narration(
    val words: List<Word>,
    val clauses: List<Clause>,
    val understanding: Understanding,
    val prompt: String,
    /** The narration's own length (seconds): nothing is shown after it. */
    val duration: Double,
) {
    /** Persian on screen when most words are Persian. */
    val persian: Boolean = words.count { w -> w.text.any { it in '؀'..'ۿ' } } * 2 > words.size

    val language: String get() = if (persian) "fa" else "en"

    val texts: List<String> = words.map { it.text }

    /** Normalised word keys (Latin digits, no half-space, lower case, no punctuation), for cue matching. */
    val keys: List<String> = texts.map { NumberWords.normalize(it) }

    /** Seconds at which word [i] starts and ends. */
    fun startOf(i: Int): Double = words[i].range.startMs / MS
    fun endOf(i: Int): Double = words[i].range.endMs / MS

    /** Latin words the narration itself says (names it may show in their own spelling). */
    val spokenLatin: Set<String> = texts.map { it.trim { c -> !c.isLetterOrDigit() } }.filter { w -> w.any { it in 'A'..'Z' || it in 'a'..'z' } }
        .map { it.lowercase() }.toSet()

    companion object {
        private const val MS = 1000.0

        /**
         * The narration of [words]: clauses are the transcript's lines (sentence ends, real pauses,
         * long runs split at commas), each with the understanding's reading of it. [understanding]
         * defaults to the rules' reading.
         */
        fun of(
            words: List<Word>,
            prompt: String = "",
            understanding: Understanding? = null,
            pauses: List<ClosedFloatingPointRange<Float>> = emptyList(),
            duration: Double? = null,
        ): Narration {
            val lines = Lines.split(words, pauses = pauses)
            val transcript = Transcript(words.firstOrNull()?.language ?: Language.Persian, words)
            val u = understanding ?: RulesUnderstander.understand(transcript, prompt, lines)
            // A reading of other lines (an older cut) is not this clause's: then the clauses go without it.
            val reads = u.lines.takeIf { it.size == lines.size }.orEmpty()
            val clauses = lines.mapIndexed { k, l ->
                Clause(
                    k, l.first, l.last, words[l.first].range.startMs / MS, words[l.last].range.endMs / MS,
                    words.subList(l.first, l.last + 1).joinToString(" ") { it.text }, reads.getOrNull(k),
                )
            }
            val end = duration ?: (words.lastOrNull()?.range?.endMs?.div(MS) ?: 0.0)
            return Narration(words, clauses, u, prompt, maxOf(end, clauses.lastOrNull()?.end ?: 0.0))
        }
    }
}
