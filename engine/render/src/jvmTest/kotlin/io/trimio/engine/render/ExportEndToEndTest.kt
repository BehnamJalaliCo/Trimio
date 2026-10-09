package io.trimio.engine.render

import io.trimio.core.model.input.AspectRatio
import io.trimio.core.model.input.CanvasSpec
import io.trimio.core.model.input.InputSource
import io.trimio.core.model.input.MediaUri
import io.trimio.core.model.input.Resolution
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.timeline.CutClip
import io.trimio.core.model.timeline.CutReason
import io.trimio.core.pipeline.JobSpec
import io.trimio.core.pipeline.JobStatus
import io.trimio.core.pipeline.LiveSignal
import io.trimio.core.pipeline.PipelineEvent
import io.trimio.core.pipeline.PipelineOrchestrator
import io.trimio.core.pipeline.PipelineStage
import io.trimio.core.pipeline.StageContext
import io.trimio.core.pipeline.StageId
import io.trimio.core.pipeline.StandardArtifacts
import io.trimio.engine.audio.AudioCleanupStage
import io.trimio.engine.media.FfmpegMediaSource
import io.trimio.engine.media.IngestStage
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A real edit end to end on desktop: a landscape test video with a tone → Ingest → AudioCleanup →
 * (fixed timeline with a cut) → Render (mix + composite + encode) → Export, then ffprobe checks.
 */
class ExportEndToEndTest {

    private val out = File("build/render-previews").apply { mkdirs() }

    @Test
    fun cutCaptionAndExportARealVideo(): Unit = runBlocking {
        if (!FfmpegVideoWriter.isAvailable()) return@runBlocking println("ffmpeg not installed; skipping")
        val source = File(out, "source-landscape.mp4")
        check(
            ProcessBuilder(
                "ffmpeg", "-y", "-loglevel", "error",
                "-f", "lavfi", "-i", "testsrc2=size=1280x720:rate=30:duration=10",
                "-f", "lavfi", "-i", "sine=frequency=330:duration=10",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", source.absolutePath,
            ).start().waitFor() == 0,
        )

        val media = FfmpegMediaSource()
        val fonts = RenderFonts.vazirmatn()
        val canvas = CanvasSpec(AspectRatio.Portrait9x16, Resolution.Hd, 30)
        // Sample edit (9 s) plus a 1 s cut from the 10 s source.
        // Over footage there is no generated backdrop: drop the sample's audio-only background clip.
        val base = SampleTimelines.cryptoSignal(canvas)
        val timeline = base.copy(clips = base.clips.filterNot { it is io.trimio.core.model.timeline.BackgroundClip } + CutClip(TimeRange(4_000, 5_000), CutReason.Silence))
        val job = JobSpec("e2e", InputSource.Video(MediaUri(source.absolutePath), 10_000, io.trimio.core.model.input.VideoFormat(1280, 720, 30f)), prompt = "")

        val direct = object : PipelineStage {
            override val id = StageId.Direction
            override suspend fun run(context: StageContext) {
                context.artifacts[StandardArtifacts.Timeline] = timeline
                context.artifacts[RenderArtifacts.Style] = SampleTimelines.previewStyle
            }
        }
        val output = File(out, "e2e-export.mp4")
        val stages = listOf(
            IngestStage(media),
            AudioCleanupStage(media),
            direct,
            RenderStage(DesktopVideoExporter(fonts), media, { null }, { output.absolutePath }),
            ExportStage { path, _ -> path },
        )
        val events = PipelineOrchestrator(stages).run(job).toList()
        val final = events.filterIsInstance<PipelineEvent.StateChanged>().last().state
        assertEquals(JobStatus.Completed, final.status, final.error)
        assertTrue(events.filterIsInstance<PipelineEvent.Live>().any { it.signal is LiveSignal.FrameRendered })

        val probe = runBlocking { media.probe(MediaUri(output.absolutePath)) }
        assertEquals(720, probe.video?.width)
        assertEquals(1280, probe.video?.height)
        assertTrue(probe.hasAudio, "soundtrack must be muxed")
        assertEquals(9_000.0, probe.durationMs.toDouble(), 150.0)

        // A frame for visual review.
        ProcessBuilder("ffmpeg", "-y", "-loglevel", "error", "-ss", "2.9", "-i", output.absolutePath, "-frames:v", "1", File(out, "e2e-frame.png").absolutePath).start().waitFor()
        Unit
    }
}
