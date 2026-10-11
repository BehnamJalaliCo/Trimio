package io.trimio.engine.autopilot.workshop

import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.text.Language
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.autopilot.Cta

/**
 * One synthetic video the director has never seen: what a creator said, what the recogniser
 * heard, the voice that said it and the brief they typed. Everything is deterministic: the same
 * scenario gives the same words, errors, voice and footage on every machine, so judges and the
 * regression archive can compare runs across commits.
 *
 * Stable fields (other agents build on them; add, never rename):
 * [id], [language], [domain], [prompt], [words], [truth], [voice], [footage], [ask], [seconds].
 */
class Scenario internal constructor(
    /** Stable, file-name safe ("fa-cooking-kuku"); the folder name under build/workshop. */
    val id: String,
    /** "fa" or "en": the spoken language (Persian pieces may mix English names). */
    val language: String,
    /** One of Understanding.DOMAINS: what the piece is about, as a human would file it. */
    val domain: String,
    /** The creator's brief, as typed: simple («یه ریلز جذاب بساز») to an expert's shot list. */
    val prompt: String,
    /** What the recogniser heard (errors included), on the source clock: the pipeline's input. */
    val words: List<Word>,
    /**
     * Ground truth: the words as they should be spelled, timed as spoken (fillers left out). Not
     * aligned one to one with [words] (the recogniser splits and merges), align by time.
     */
    val truth: List<Word>,
    /** The call to action the creator makes, as a human reads it; null when the piece asks nothing. */
    val ask: Cta?,
    /** True for a talking head over synthetic footage; false for an audio-only piece (all graphics). */
    val footage: Boolean,
    /** The recogniser errors injected, one line each ("سیصد → سی سد (number)"), for reports. */
    val errors: List<String>,
    /** Length of the source, in seconds. */
    val seconds: Float,
    attach: () -> List<SyntheticAssets.Attached> = { emptyList() },
    voiceTrack: () -> PcmAudio,
) {
    /** Media the creator attached beside the brief (synthetic), with what renders them; built on first use. */
    internal val attached: List<SyntheticAssets.Attached> by lazy { attach() }

    /** The attached media as the autopilot receives them. */
    val assets: List<io.trimio.engine.autopilot.UserAsset> get() = attached.map { it.asset }

    /** The voice, 48 kHz mono, synthesised on first use (syllable bursts, pauses, breaths, room tone). */
    val voice: PcmAudio by lazy { voiceTrack() }

    /** The spoken language as the transcript carries it. */
    val spoken: Language get() = if (language == "en") Language.English else Language.Persian

    /** [words] as the recogniser hands them to the autopilot. */
    val transcript: Transcript get() = Transcript(spoken, words)

    /** The clean text, for spelling scores. */
    val script: String get() = truth.joinToString(" ") { it.text }

    /** The brief asks for no captions at all. */
    val captionsOff: Boolean get() = listOf("بدون زیرنویس", "بدون کپشن", "no captions").any { it in prompt.lowercase() }

    override fun toString() = "$id (${language}, $domain, ${"%.1f".format(seconds)}s, ${words.size} words)"
}
