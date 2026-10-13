package io.trimio.engine.production

import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.transcript.Transcript
import io.trimio.engine.audio.Resampler
import io.trimio.engine.autopilot.SubjectFinder
import io.trimio.engine.llm.RgbImage
import io.trimio.engine.motion.score.Subject
import io.trimio.engine.perception.AddOnPass
import io.trimio.engine.perception.Frame
import io.trimio.engine.production.diagnostics.Fact

/*
 * The production's hooks for the perception add-ons ([AddOnPass]). A null pass (no add-on
 * installed) returns every input untouched and adds nothing to the report.
 */

/** How sure a face model's head is, against the pixel finder's own confidence. */
private const val FACE_CONFIDENCE = 0.9f

/** The face model's head when it saw one: layout then keeps text and graphics off the face. */
internal suspend fun AddOnPass?.located(frames: List<RgbImage>, found: SubjectFinder.Found?): SubjectFinder.Found? {
    if (this == null || frames.isEmpty()) return found
    val head = head(frames.mapIndexed { i, f -> Frame(f.width, f.height, f.rgb, i.toFloat()) }) ?: return found
    return SubjectFinder.Found(Subject(head[0], head[1], head[2], head[3]), FACE_CONFIDENCE)
}

/** Word times from the aligner and marks from the punctuation model, when installed; else [transcript] as it is. */
internal suspend fun AddOnPass?.refined(transcript: Transcript, voice: PcmAudio, rate: Int): Transcript {
    if (this == null || transcript.words.isEmpty()) return transcript
    val audio = if (voice.sampleRate == rate) voice else Resampler().resample(voice, rate)
    val words = words(transcript.words, audio, transcript.language)
    if (words === transcript.words) return transcript
    return try {
        transcript.copy(words = words)
    } catch (_: IllegalArgumentException) {
        transcript
    }
}

/** Which add-ons ran and how long each took, for the technical report. */
internal fun AddOnPass?.facts(): List<Fact> = this?.report.orEmpty().map { Fact("add-on", it) }

/** The marker the add-on steps run under: a death there names them. */
internal const val ADD_ONS = "add-ons"

/**
 * True when an earlier attempt of this run died inside an add-on (or, from 1.0.15, right after
 * decoding frames, where the face add-on ran unmarked): native code there can take the whole app
 * down, so the retry goes on without the add-ons instead of dying in the same place again.
 */
internal fun addOnDeath(recorder: io.trimio.engine.production.diagnostics.RunRecorder): Boolean =
    recorder.report.value.deaths.any { d -> d.step.startsWith(ADD_ONS) || d.step == "after decode frames" }
