package io.trimio.engine.audio

import io.trimio.core.pipeline.LiveSignal
import io.trimio.core.pipeline.PipelineStage
import io.trimio.core.pipeline.StageContext
import io.trimio.core.pipeline.StageId
import io.trimio.core.pipeline.StandardArtifacts

/**
 * Scores the stress of every transcript word from the audio features and writes it back into
 * the transcript, so captions, the Director and the editor all see the same emphasis values.
 */
class AnalysisStage(
    private val prosody: ProsodyAnalyzer = ProsodyAnalyzer(),
    /** Words at or above this score are announced on the build screen. */
    private val highlightThreshold: Float = 0.6f,
) : PipelineStage {
    override val id = StageId.Analysis

    override suspend fun run(context: StageContext) {
        val transcript = context.artifacts[StandardArtifacts.Transcript]
        val features = context.artifacts[StandardArtifacts.AudioFeatures]
        if (transcript == null || features == null || transcript.words.isEmpty()) {
            context.progress(1f)
            return
        }

        val scores = prosody.emphasis(features, transcript.words)
        context.progress(0.5f)
        val updated = transcript.copy(words = transcript.words.mapIndexed { i, w -> w.copy(emphasis = scores[i].coerceIn(0f, 1f)) })
        context.artifacts[StandardArtifacts.Transcript] = updated

        val highlighted = updated.words.withIndex().filter { it.value.emphasis >= highlightThreshold }
        highlighted.forEachIndexed { n, (index, word) ->
            context.emit(LiveSignal.EmphasisFound(index, word.emphasis))
            context.progress(0.5f + 0.5f * (n + 1) / highlighted.size)
        }
        context.progress(1f)
    }
}
