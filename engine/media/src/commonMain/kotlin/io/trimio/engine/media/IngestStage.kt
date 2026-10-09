package io.trimio.engine.media

import io.trimio.core.model.text.Numerals
import io.trimio.core.pipeline.LiveSignal
import io.trimio.core.pipeline.PipelineStage
import io.trimio.core.pipeline.StageContext
import io.trimio.core.pipeline.StageId
import io.trimio.core.pipeline.StandardArtifacts

/** Probes the input and publishes [StandardArtifacts.MediaInfo]. */
class IngestStage(private val probe: MediaProbe) : PipelineStage {
    override val id = StageId.Ingest

    override suspend fun run(context: StageContext) {
        val info = probe.probe(context.job.input.uri)
        context.progress(0.8f)
        if (!info.hasAudio && !info.hasVideo) throw MediaFormatException("The file has neither audio nor video")
        context.artifacts[StandardArtifacts.MediaInfo] = info

        val duration = formatDuration(info.durationMs)
        val (fa, en) = when (val v = info.video) {
            null -> "فایل صوتی، $duration" to "Audio file, $duration"
            else -> {
                val size = "${v.displayWidth}×${v.displayHeight}"
                val fps = v.frameRate.toInt()
                val hdr = if (v.isHdr) " HDR" else ""
                "ویدیو $size$hdr، $fps فریم، $duration" to "$size$hdr video, $fps fps, $duration"
            }
        }
        context.emit(LiveSignal.Note(Numerals.toPersian(fa), en))
        if (!info.hasAudio) {
            context.emit(LiveSignal.Note("ویدیو صدا ندارد؛ مراحل گفتار رد می\u200Cشوند", "No audio track; speech stages are skipped"))
        }
        context.progress(1f)
    }

    private fun formatDuration(ms: Long): String {
        val total = ms / 1000
        return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
    }
}
