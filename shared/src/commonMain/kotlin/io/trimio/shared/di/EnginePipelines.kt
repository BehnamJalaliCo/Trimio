package io.trimio.shared.di

import io.trimio.core.pipeline.InMemoryCheckpointStore
import io.trimio.core.pipeline.PipelineOrchestrator
import io.trimio.engine.asr.AlignmentStage
import io.trimio.engine.asr.SpeechRecognizer
import io.trimio.engine.asr.TranscriptionStage
import io.trimio.engine.assets.AssetLibrary
import io.trimio.engine.assets.AssetMatchingStage
import io.trimio.engine.audio.AnalysisStage
import io.trimio.engine.audio.AudioCleanupStage
import io.trimio.engine.director.DirectionStage
import io.trimio.engine.director.DirectorModels
import io.trimio.engine.media.AudioDecoder
import io.trimio.engine.media.IngestStage
import io.trimio.engine.media.MediaProbe
import io.trimio.engine.render.ExportStage
import io.trimio.engine.render.OutputPublisher
import io.trimio.engine.render.RenderStage
import io.trimio.engine.render.VideoExporter
import io.trimio.engine.styles.StylePackRepository

/** The production pipelines, assembled from whatever engines the platform provides. */
class EnginePipelines(
    private val probe: MediaProbe,
    private val decoder: AudioDecoder,
    private val recognizer: SpeechRecognizer,
    private val styles: StylePackRepository,
    private val directors: DirectorModels,
    private val library: AssetLibrary,
    private val exporter: VideoExporter,
    private val publisher: OutputPublisher,
    private val outputPath: (jobId: String) -> String,
    /** Evaluated when each job starts, so a hot phone starts lighter jobs. */
    private val threads: () -> Int = { 4 },
) : PipelineFactory {
    private val checkpoints = InMemoryCheckpointStore()

    override fun build() = PipelineOrchestrator(
        listOf(
            IngestStage(probe),
            AudioCleanupStage(decoder),
            TranscriptionStage(recognizer, threads()),
            AlignmentStage(),
            AnalysisStage(),
            DirectionStage(styles, directors),
            AssetMatchingStage(library),
            RenderStage(exporter, decoder, library, outputPath),
            ExportStage(publisher),
        ),
        checkpoints,
    )

    override fun export() = PipelineOrchestrator(listOf(RenderStage(exporter, decoder, library, outputPath), ExportStage(publisher)))
}
