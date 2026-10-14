package io.trimio.engine.perception

import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.text.Language
import io.trimio.core.model.transcript.Word
import io.trimio.engine.models.MediaAddOns
import io.trimio.engine.perception.align.ForcedAligner
import io.trimio.engine.perception.beats.BeatFinder
import io.trimio.engine.perception.face.FaceZoneFinder
import io.trimio.engine.perception.speakers.Diarizer
import io.trimio.engine.perception.text.Marks
import io.trimio.engine.perception.text.Punctuator
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.TimeSource

/**
 * The perception add-ons the production runs, each only when its model is installed: with none
 * installed every call returns its input untouched and records nothing, so an edit is exactly the
 * one made without add-ons. Any failure inside an add-on falls back to the input and is recorded
 * in [report] (the technical report), never thrown.
 */
class AddOnPass(private val perception: Perception) {
    private val log = ArrayList<String>()

    /** "faces: yunet-2023mar on Cpu, 5 frames, 230 ms" lines, in order; empty when no add-on ran. */
    val report: List<String> get() = log.toList()

    /**
     * The speaker's head (left, top, right, bottom as frame fractions, hair to chin) from the face
     * model; null when it is not installed, saw no face, or failed: the caller keeps its own box.
     */
    suspend fun head(frames: List<Frame>): FloatArray? {
        if (frames.isEmpty() || !perception.has(MediaAddOns.faces)) return null
        return timed("faces") {
            val zones = FaceZoneFinder(perception).zones(frames)
            val faces = if (zones.measured) zones.faces() else emptyList()
            if (faces.isEmpty()) {
                null
            } else {
                val b = faces.reduce { a, c -> a.union(c) }
                // The face box runs brow to chin; the head the layout keeps clear of runs from the top of the hair.
                floatArrayOf(b.x.coerceIn(0f, 1f), (b.y - b.h * HAIR).coerceIn(0f, 1f), b.right.coerceIn(0f, 1f), b.bottom.coerceIn(0f, 1f))
                    .takeIf { it[2] > it[0] && it[3] > it[1] }
            }
        }
    }

    /**
     * Recognised words with the installed add-ons applied, in order: word times from the forced
     * aligner (before any cut is planned), then the punctuation model's marks on words that have
     * none of their own (the recogniser's marks are never removed). [audio16k]: 16 kHz mono.
     */
    suspend fun words(words: List<Word>, audio16k: PcmAudio, language: Language): List<Word> {
        if (words.isEmpty()) return words
        var out = words
        if (perception.has(MediaAddOns.aligner, MediaAddOns.alignerTokens)) {
            out = timed("alignment") { ForcedAligner(perception).align(out, audio16k).takeIf { it.measured }?.words?.takeIf { it.size == out.size } } ?: out
        }
        if (perception.has(MediaAddOns.punctuation, MediaAddOns.punctuationTokenizer)) {
            out = timed("punctuation") { punctuate(out, language) } ?: out
        }
        return out
    }

    /**
     * Who speaks when (turns numbered 0, 1, … in order of first speaking), when the speaker add-ons are installed
     * and hear two voices or more over [words]; null for one voice or without the add-ons (nothing then changes).
     * [audio16k]: 16 kHz mono.
     */
    suspend fun speakers(words: List<Word>, audio16k: PcmAudio): List<io.trimio.engine.perception.speakers.Turn>? {
        if (words.isEmpty() || !perception.has(MediaAddOns.speakerTurns, MediaAddOns.speakerVoices)) return null
        return timed("speakers") {
            val d = Diarizer(perception).diarize(audio16k, maxSpeakers = MAX_SPEAKERS)
            if (!d.measured || d.speakers < 2) return@timed null
            // Only voices that say words count (a cough is no speaker); numbered in order of first speaking.
            val order = d.speakersOf(words).distinct()
            if (order.size < 2) return@timed null
            d.turns.filter { it.speaker in order }.map { it.copy(speaker = order.indexOf(it.speaker)) }
        }
    }

    /** The beats Beat This! hears in [audio] (seconds), when its add-on is installed; null otherwise or when it found none. */
    suspend fun beats(audio: PcmAudio): List<Double>? {
        if (!perception.has(MediaAddOns.beats, MediaAddOns.beatsFrontend)) return null
        return timed("beats") { BeatFinder(perception).beats(audio).takeIf { it.measured && it.beats.isNotEmpty() }?.beats }
    }

    private suspend fun punctuate(words: List<Word>, language: Language): List<Word>? {
        val p = Punctuator(perception).punctuate(words, language)
        if (!p.measured || p.words.size != words.size) return null
        return words.mapIndexed { i, w -> if (Marks.strip(w.text) != w.text || Marks.strip(w.text).isEmpty()) w else p.words[i] }
    }

    /** Runs one add-on, timing it; a failure (or a null answer) leaves the input as it was. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun <T> timed(feature: String, block: suspend () -> T?): T? {
        val start = TimeSource.Monotonic.markNow()
        val before = perception.notes.size
        val result = try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            log += "$feature: failed (${e::class.simpleName}: ${e.message?.take(MAX_REASON)}), skipped"
            return null
        }
        val ms = start.elapsedNow().inWholeMilliseconds
        val how = perception.notes.drop(before).lastOrNull()?.substringAfter(": ") ?: "ran"
        log += "$feature: $how, ${if (result == null) "no change" else "applied"}, $ms ms"
        return result
    }

    private companion object {
        const val HAIR = 0.35f
        const val MAX_SPEAKERS = 4
        const val MAX_REASON = 120
    }
}
