package io.trimio.engine.render

import io.trimio.core.model.style.StyleSpec
import io.trimio.core.model.timeline.EditMap
import io.trimio.core.pipeline.ArtifactKey
import io.trimio.core.pipeline.LiveSignal
import io.trimio.core.pipeline.PipelineStage
import io.trimio.core.pipeline.StageContext
import io.trimio.core.pipeline.StageId
import io.trimio.core.pipeline.StandardArtifacts
import io.trimio.engine.audio.AudioAssetSource
import io.trimio.engine.audio.AudioMixer
import io.trimio.engine.media.AudioDecoder

object RenderArtifacts {
    /** The resolved style for the job; set by the Director. */
    val Style: ArtifactKey<StyleSpec> = StandardArtifacts.Style

    /** Encoded file before publishing. */
    val RenderedFile = ArtifactKey<String>("rendered-file")
}

/**
 * Mixes the soundtrack and renders + encodes the edit. Frames are streamed to the build screen's
 * film strip as they are composited.
 */
class RenderStage(
    private val exporter: VideoExporter,
    private val decoder: AudioDecoder,
    private val assets: AudioAssetSource,
    private val outputPath: (jobId: String) -> String,
    private val mixer: AudioMixer = AudioMixer(),
) : PipelineStage {
    override val id = StageId.Render

    override suspend fun run(context: StageContext) {
        val timeline = context.artifacts.require(StandardArtifacts.Timeline)
        val style = context.artifacts.require(RenderArtifacts.Style)
        val input = context.job.input
        val info = context.artifacts[StandardArtifacts.MediaInfo]
        val edit = EditMap.of(timeline, input.durationMs)
        val features = context.artifacts[StandardArtifacts.AudioFeatures]

        // Full-quality source audio for the mix (the 16 kHz CleanAudio is analysis-only).
        val source = if (info?.hasAudio != false) decoder.decodeMono(input.uri) else null
        val soundtrack = mixer.mix(timeline, source, input.durationMs, OutputTime.speech(features, edit), assets)
        context.progress(0.05f)

        val request = ExportRequest(
            timeline = timeline,
            style = style,
            input = input,
            soundtrack = soundtrack,
            features = OutputTime.features(features, edit),
            outputPath = outputPath(context.job.id),
        )
        var lastSignal = -1
        val path = exporter.export(request) { index, total ->
            context.progress(0.05f + 0.95f * (index + 1) / total)
            // A film-strip update every half second of output is plenty for the UI.
            if (index - lastSignal >= request.fps / 2 || index == total - 1) {
                lastSignal = index
                context.emit(LiveSignal.FrameRendered(index + 1, total))
            }
        }
        context.artifacts[RenderArtifacts.RenderedFile] = path
        context.progress(1f)
    }
}

/** Publishes the rendered file to the user's gallery. */
class ExportStage(private val publisher: OutputPublisher) : PipelineStage {
    override val id = StageId.Export

    override suspend fun run(context: StageContext) {
        val file = context.artifacts.require(RenderArtifacts.RenderedFile)
        context.progress(0.3f)
        val published = publisher.publish(file, "Trimio-${context.job.id}.mp4")
        context.artifacts[StandardArtifacts.Output] = published
        context.emit(LiveSignal.Note("ویدیو در گالری ذخیره شد", "Saved to your gallery"))
        context.progress(1f)
    }
}
