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
        fun of(voice: PcmAudio, transcript: Transcript, tightenCuts: Boolean = true): SpeechAnalysis {
            val duration = voice.samples.size / voice.sampleRate.toFloat()
            val speech = VoiceActivityDetector().detect(voice)
            val speaking = VoiceActivityDetector(hangoverMs = 70, mergeGapMs = 120, belowSpeechDb = 24f).detect(voice)
            val pauses = silences(speaking, duration)
            val aligned = EditPlan.alignToSpeech(transcript.words, pauses)
            val lufs = LoudnessMeter.measure(voice).integratedLufs.toFloat()
            val prosody = ProsodyAnalyzer()
            val stress = prosody.emphasis(prosody.features(voice, speech, lufs, 0f), aligned)
            val words = aligned.mapIndexed { i, w -> w.copy(emphasis = stress.getOrElse(i) { 0f }.coerceIn(0f, 1f)) }
            val edit = if (tightenCuts) EditPlan.tighten(words, pauses, duration) else EditPlan.identity(duration)
            return SpeechAnalysis(Transcript(transcript.language, words), edit, pauses, lufs, duration)
        }

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
