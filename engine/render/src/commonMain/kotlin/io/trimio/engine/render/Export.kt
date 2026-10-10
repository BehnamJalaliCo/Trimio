package io.trimio.engine.render

import io.trimio.core.model.audio.AudioFeatures
import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.input.InputSource
import io.trimio.core.model.style.StyleSpec
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.timeline.EditMap
import io.trimio.core.model.timeline.Timeline

enum class VideoCodec { H264, HEVC }

/** Everything needed to turn an edit into a file. */
class ExportRequest(
    val timeline: Timeline,
    val style: StyleSpec,
    val input: InputSource,
    /** Final mixed soundtrack in output time (see [io.trimio.engine.audio.AudioMixer]). */
    val soundtrack: PcmAudio,
    /** Features in **output** time, for audio-reactive visuals. */
    val features: AudioFeatures?,
    val outputPath: String,
    val codec: VideoCodec = VideoCodec.H264,
    /** Bits per second; null picks a quality-based default for the resolution. */
    val bitrate: Int? = null,
) {
    val editMap: EditMap get() = EditMap.of(timeline, input.durationMs)
    val fps: Int get() = timeline.canvas.frameRate
    val frameCount: Int get() = ((timeline.durationMs * fps + 999) / 1000).toInt()

    fun defaultBitrate(): Int {
        val pixels = timeline.canvas.widthPx.toLong() * timeline.canvas.heightPx
        // Upload quality (research P0): ~0.3 bits per pixel per frame for H.264, i.e. ~19 Mbps at
        // 1080p30, capped at 25 Mbps; platforms re-encode, so the master must be near transparent.
        // HEVC needs ~40% less for the same quality.
        val h264 = (pixels * fps * 0.3).toLong().coerceAtMost(MAX_H264_BPS).toInt()
        return if (codec == VideoCodec.HEVC) (h264 * 0.6).toInt() else h264
    }

    private companion object {
        const val MAX_H264_BPS = 25_000_000L
    }
}

/** Renders and encodes an [ExportRequest]. Android: Media3 Transformer; desktop: Skia + ffmpeg. */
interface VideoExporter {
    /**
     * Writes the file and returns its path. [onFrame] is called as frames are composited
     * (frameIndex, total, optional preview handle) so the build screen can show them.
     */
    suspend fun export(request: ExportRequest, onFrame: suspend (index: Int, total: Int) -> Unit = { _, _ -> }): String
}

/** Moves a finished file somewhere the user sees it (gallery on Android, Downloads on desktop). */
fun interface OutputPublisher {
    suspend fun publish(path: String, displayName: String): String
}

/** Maps source-time features/speech into output time for the renderer and the music ducker. */
object OutputTime {
    fun speech(features: AudioFeatures?, edit: EditMap): List<TimeRange> =
        features?.speech?.mapNotNull { edit.toOutput(it) }.orEmpty()

    /** Re-samples a feature track along output time, so visuals follow what is actually heard. */
    fun features(features: AudioFeatures?, edit: EditMap): AudioFeatures? {
        features ?: return null
        if (edit.cuts.isEmpty()) return features
        val frames = (edit.outputDurationMs / features.hopMs).toInt()
        val energy = FloatArray(frames) { features.energyDb[features.frameAt(edit.toSource(it.toLong() * features.hopMs))] }
        val pitch = FloatArray(frames) { features.pitchHz[features.frameAt(edit.toSource(it.toLong() * features.hopMs))] }
        return AudioFeatures(features.hopMs, energy, pitch, features.integratedLufs, features.appliedGainDb, speech(features, edit))
    }
}
