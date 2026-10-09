package io.trimio.engine.asr

import io.trimio.core.model.text.Language
import io.trimio.core.model.text.Numerals
import io.trimio.core.pipeline.LiveSignal
import io.trimio.core.pipeline.PipelineStage
import io.trimio.core.pipeline.StageContext
import io.trimio.core.pipeline.StageId
import io.trimio.core.pipeline.StandardArtifacts

/** Recognises the clean audio and streams each word to the build screen as it is decoded. */
class TranscriptionStage(
    private val recognizer: SpeechRecognizer,
    private val threads: Int = 4,
) : PipelineStage {
    override val id = StageId.Transcription

    override suspend fun run(context: StageContext) {
        val audio = context.artifacts[StandardArtifacts.CleanAudio]
        if (audio == null) {
            context.progress(1f)
            return
        }
        val language = context.job.language
        val transcript = recognizer.transcribe(
            audio = audio,
            options = RecognitionOptions(language = language, initialPrompt = promptFor(language), threads = threads),
            onProgress = { context.progress(it * 0.98f) },
            onWord = { index, word -> context.emit(LiveSignal.WordRecognized(index, word)) },
        )
        context.artifacts[StandardArtifacts.Transcript] = transcript

        val fillers = FillerDetector.fillerIndices(transcript.words).size
        if (fillers > 0) {
            context.emit(
                LiveSignal.Note(
                    Numerals.toPersian("$fillers کلمه\u0654 مکث (ا\u0650، اوم) برای حذف پیشنهاد شد"),
                    "$fillers filler words suggested for cutting",
                ),
            )
        }
        context.progress(1f)
    }

    /**
     * Conditioning text: well-punctuated, in the target style. For Persian it nudges Whisper to
     * Persian (not Arabic) spelling and to use Persian punctuation.
     */
    private fun promptFor(language: Language?): String? = when (language) {
        Language.Persian, null -> "سلام دوستان، امروز می\u200Cخواهیم درباره\u200Cی موضوع مهمی صحبت کنیم."
        Language.English -> null
    }
}

/** Snaps word boundaries to syllable onsets and releases in the audio. */
class AlignmentStage(private val aligner: WordAligner = WordAligner()) : PipelineStage {
    override val id = StageId.Alignment

    override suspend fun run(context: StageContext) {
        val transcript = context.artifacts[StandardArtifacts.Transcript]
        val features = context.artifacts[StandardArtifacts.AudioFeatures]
        if (transcript != null && features != null) {
            context.artifacts[StandardArtifacts.Transcript] = transcript.copy(words = aligner.align(transcript.words, features))
        }
        context.progress(1f)
    }
}
