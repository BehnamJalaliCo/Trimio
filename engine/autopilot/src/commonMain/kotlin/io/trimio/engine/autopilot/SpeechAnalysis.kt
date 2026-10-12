package io.trimio.engine.autopilot

import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.audio.LoudnessMeter
import io.trimio.engine.audio.ProsodyAnalyzer
import io.trimio.engine.audio.VoiceActivityDetector
import io.trimio.engine.motion.score.EditPlan

/**
 * What the voice itself says, beyond the words: where the speaker pauses, which words they lean
 * on, and the cut list that removes dead air. Everything is on the source clock; [edit] maps it to
 * the output.
 */
class SpeechAnalysis(
    /** Words clipped to real speech, with prosodic stress in [Word.emphasis] (0..1). */
    val transcript: Transcript,
    val edit: EditPlan,
    val pauses: List<ClosedFloatingPointRange<Float>>,
    val loudnessLufs: Float,
    /** Source duration in seconds. */
    val duration: Float,
) {
    /** The transcript on the output clock. */
    val output: Transcript get() = edit.remap(transcript)

    companion object {
        /**
         * Analyses [voice] against the recogniser's [words]. Pauses are judged against the
         * speaker's own level (a quiet room tone is not speech), recognisers' stretched words are
         * clipped to speech, and every pause longer than a breath is cut.
         */
        fun of(voice: PcmAudio, transcript: Transcript, tightenCuts: Boolean = true, strict: Boolean = false): SpeechAnalysis {
            val duration = voice.samples.size / voice.sampleRate.toFloat()
            val speech = VoiceActivityDetector().detect(voice)
            val speaking = VoiceActivityDetector(hangoverMs = 70, mergeGapMs = 120, belowSpeechDb = 24f).detect(voice)
            val pauses = silences(speaking, duration)
            val aligned = EditPlan.alignToSpeech(transcript.words, pauses)
            val lufs = LoudnessMeter.measure(voice).integratedLufs.toFloat()
            val prosody = ProsodyAnalyzer()
            val stress = prosody.emphasis(prosody.features(voice, speech, lufs, 0f), aligned)
            val words = aligned.mapIndexed { i, w -> w.copy(emphasis = stress.getOrElse(i) { 0f }.coerceIn(0f, 1f)) }
            // Asked to cut every pause («همهٔ سکوت‌ها و مکث‌ها حذف»): the quiet gaps between words go too, not only real silence.
            val cuts = if (strict) merge(pauses + quietGaps(words, voice)) else pauses
            val edit = if (tightenCuts) EditPlan.tighten(words, cuts, duration, minGap = if (strict) STRICT_GAP else LOOSE_GAP) else EditPlan.identity(duration)
            return SpeechAnalysis(Transcript(transcript.language, words), edit, pauses, lufs, duration)
        }

        /** Whether the brief asks for every pause to go («همهٔ سکوت‌ها و مکث‌ها حذف», "cut all the pauses"). */
        fun strict(prompt: String): Boolean {
            val p = prompt.lowercase().replace("\u200C", " ")
            val pauses = listOf("سکوت", "مکث", "silence", "pause", "dead air").any { it in p }
            val all = listOf("همه", "همهٔ", "تمام", "حذف", "کامل", "all", "every", "remove", "cut").any { it in p }
            return pauses && all
        }

        /**
         * Gaps between two words of at least [STRICT_GAP] whose middle is quiet on the voice itself
         * ([QUIET_DB] under the speaker's level, every 20 ms of it): a pause the activity detector
         * kept as speech (a breath, room noise) but nobody would miss.
         */
        private fun quietGaps(words: List<Word>, voice: PcmAudio): List<ClosedFloatingPointRange<Float>> {
            val cuts = io.trimio.engine.motion.score.CutPoints(voice)
            return words.zipWithNext().mapNotNull { (a, b) ->
                val from = a.range.endMs / MS + EDGE
                val to = b.range.startMs / MS - EDGE
                if (to - from < STRICT_GAP - 2 * EDGE) return@mapNotNull null
                val steps = ((to - from) / PROBE).toInt().coerceAtLeast(1)
                val quiet = (0 until steps).all { k -> cuts.db(from + k * PROBE, from + (k + 1) * PROBE) < cuts.speechDb - QUIET_DB }
                (a.range.endMs / MS..b.range.startMs / MS).takeIf { quiet }
            }
        }

        private fun merge(ranges: List<ClosedFloatingPointRange<Float>>): List<ClosedFloatingPointRange<Float>> {
            val out = mutableListOf<ClosedFloatingPointRange<Float>>()
            for (r in ranges.sortedBy { it.start }) {
                val last = out.lastOrNull()
                if (last != null && r.start <= last.endInclusive) out[out.lastIndex] = last.start..maxOf(last.endInclusive, r.endInclusive) else out += r
            }
            return out
        }

        private const val MS = 1000f
        private const val EDGE = 0.06f
        private const val PROBE = 0.02f
        private const val QUIET_DB = 18f
        private const val STRICT_GAP = 0.22f
        private const val LOOSE_GAP = 0.25f

        /** Gaps longer than 0.2 s between speech ranges, including head and tail. */
        fun silences(speech: List<TimeRange>, duration: Float): List<ClosedFloatingPointRange<Float>> {
            val gaps = mutableListOf<ClosedFloatingPointRange<Float>>()
            var cursor = 0f
            for (r in speech) {
                val a = r.startMs / 1000f
                if (a - cursor > MIN_GAP) gaps += cursor..a
                cursor = maxOf(cursor, r.endMs / 1000f)
            }
            if (duration - cursor > MIN_GAP) gaps += cursor..duration
            return gaps
        }

        private const val MIN_GAP = 0.2f
    }
}

/** A transcript with the understanding step's spelling fixes applied (timing unchanged). */
fun Transcript.withFixes(fixes: Map<Int, String>): Transcript =
    if (fixes.isEmpty()) this else Transcript(language, words.mapIndexed { i, w -> fixes[i]?.let { w.copy(text = it) } ?: w })

internal fun List<Word>.textOf(range: IntRange): String = range.filter { it in indices }.joinToString(" ") { this[it].text }
