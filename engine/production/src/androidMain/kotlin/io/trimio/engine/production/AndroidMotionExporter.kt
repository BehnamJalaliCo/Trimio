package io.trimio.engine.production

import android.content.Context
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.view.Surface
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.trimio.core.model.audio.PcmAudio
import io.trimio.engine.motion.Composition
import io.trimio.engine.motion.MediaSource
import io.trimio.engine.motion.MotionFonts
import io.trimio.engine.motion.MotionRenderer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The motion engine's own export on Android: every frame drawn by [MotionRenderer] straight onto
 * the H.264 encoder's input surface through a hardware canvas (runtime shaders render as in the
 * preview), the mastered soundtrack encoded to AAC (or a long-form edit's source sound, copied as recorded), both
 * muxed by [MediaMuxer] into an MP4. The master is tagged BT.709 limited (players otherwise guess
 * full-range BT.601 and wash the picture out) and the soundtrack is AAC-LC at desktop parity.
 *
 * The encoder runs without B-frames, so its output is in input order and every sample gets its
 * exact frame time (a canvas-posted buffer carries the wall clock, not the edit's).
 */
class AndroidMotionExporter(private val context: Context) {

    private class Sample(val data: ByteArray, val ptsUs: Long, val flags: Int)

    suspend fun export(
        composition: Composition,
        media: MediaSource,
        sound: PcmAudio,
        out: File,
        onFrame: (Int, Int) -> Unit,
        /** Output pixels per composition pixel: 2 for 4K, 2/3 for 720p from the 1080-wide layout. */
        outputScale: Float = 1f,
        /** A source whose AAC track goes in untouched instead of [sound] (a long-form edit plays as recorded). */
        sourceAudio: Uri? = null,
    ): File = withContext(Dispatchers.Default) {
        val source = sourceAudio?.let { SourceAudio.open(context, it) }
        val (audioFormat, audio) = source?.let { it.format to it } ?: encodeAudio(sound).let { (f, samples) -> f to samples.iterator() }
        out.absoluteFile.parentFile?.mkdirs()
        val fps = composition.fps
        val w = even(composition.width * outputScale)
        val h = even(composition.height * outputScale)
        val sx = w / composition.width.toFloat()
        val sy = h / composition.height.toFloat()
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, w, h).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitrate(w, h, fps))
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            setInteger(MediaFormat.KEY_MAX_B_FRAMES, 0)
            // Untagged output reads as full-range BT.601 in half the players: washed-out blacks.
            EncodeParams.colourTags().forEach { (key, value) -> setInteger(key, value) }
        }
        val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        val surface = encoder.createInputSurface()
        encoder.start()
        val muxer = MediaMuxer(out.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        val writer = Writer(muxer, audioFormat, audio, fps)
        try {
            val measurer = TextMeasurer(createFontFamilyResolver(context), Density(1f), LayoutDirection.Ltr)
            val renderer = MotionRenderer(composition, measurer, MotionFonts.load(), media)
            val scope = CanvasDrawScope()
            val total = ceil(composition.duration * fps).toInt().coerceAtLeast(1)
            for (i in 0 until total) {
                currentCoroutineContext().ensureActive()
                // Drawn at the output size (text and shapes stay sharp at 4K), not rendered small and stretched.
                draw(surface, scope, w, h) { scale(sx, sy, pivot = Offset.Zero) { renderer.render(this, i / fps.toFloat()) } }
                writer.drain(encoder, end = false)
                onFrame(i + 1, total)
            }
            encoder.signalEndOfInputStream()
            writer.drain(encoder, end = true)
            writer.finish()
        } finally {
            runCatching { encoder.stop() }
            encoder.release()
            surface.release()
            runCatching { if (writer.started) muxer.stop() }
            muxer.release()
            source?.close()
        }
        out
    }

    private fun draw(surface: Surface, scope: CanvasDrawScope, w: Int, h: Int, block: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) {
        val canvas = surface.lockHardwareCanvas()
        try {
            scope.draw(Density(1f), LayoutDirection.Ltr, Canvas(canvas), Size(w.toFloat(), h.toFloat()), block)
        } finally {
            surface.unlockCanvasAndPost(canvas)
        }
    }

    /** Muxes encoded video as it comes and the audio samples up to each video time between them. */
    private class Writer(private val muxer: MediaMuxer, private val audioFormat: MediaFormat, private val audio: Iterator<Sample>, private val fps: Int) {
        private val info = MediaCodec.BufferInfo()
        private var videoTrack = -1
        private var audioTrack = -1
        private var frames = 0L
        private var held: Sample? = null
        var started = false
            private set

        fun drain(encoder: MediaCodec, end: Boolean) {
            while (true) {
                val o = encoder.dequeueOutputBuffer(info, if (end) END_TIMEOUT_US else 0L)
                when {
                    o == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return
                    o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> start(encoder.outputFormat)
                    o >= 0 -> if (write(encoder, o)) return
                }
            }
        }

        /** Writes output buffer [o]; true at the end of the stream. */
        private fun write(encoder: MediaCodec, o: Int): Boolean {
            val buffer = encoder.getOutputBuffer(o)!!
            val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            if (!config && info.size > 0 && started) {
                info.presentationTimeUs = frames * US / fps
                frames++
                buffer.position(info.offset).limit(info.offset + info.size)
                muxer.writeSampleData(videoTrack, buffer, info)
                audioUpTo(info.presentationTimeUs)
            }
            encoder.releaseOutputBuffer(o, false)
            return info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
        }

        private fun start(videoFormat: MediaFormat) {
            if (started) return
            videoTrack = muxer.addTrack(videoFormat)
            audioTrack = muxer.addTrack(audioFormat)
            muxer.start()
            started = true
        }

        private fun audioUpTo(us: Long) {
            val info = MediaCodec.BufferInfo()
            while (true) {
                val s = held ?: (if (audio.hasNext()) audio.next() else return)
                if (s.ptsUs > us) {
                    held = s
                    return
                }
                held = null
                info.set(0, s.data.size, s.ptsUs, s.flags)
                muxer.writeSampleData(audioTrack, ByteBuffer.wrap(s.data), info)
            }
        }

        fun finish() = audioUpTo(Long.MAX_VALUE)
    }

    /**
     * A source's AAC track, read one sample at a time as the video catches up: a long video's sound is never held
     * whole. [open] gives null for a file without one (or another codec), and the edit's own soundtrack is used.
     */
    private class SourceAudio private constructor(private val extractor: MediaExtractor, val format: MediaFormat) : Iterator<Sample>, AutoCloseable {
        private val buffer = ByteBuffer.allocate(if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE) else MAX_SAMPLE)
        private var last = -1L
        private var next: Sample? = read()

        private fun read(): Sample? {
            buffer.clear()
            val n = extractor.readSampleData(buffer, 0)
            if (n < 0) return null
            val bytes = ByteArray(n)
            buffer.duplicate().apply { position(0); limit(n) }.get(bytes)
            val sync = extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0
            // The muxer takes rising times only (a priming sample can sit before zero).
            last = maxOf(extractor.sampleTime, last + 1)
            return Sample(bytes, last, if (sync) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0).also { extractor.advance() }
        }

        override fun hasNext(): Boolean = next != null

        override fun next(): Sample = (next ?: throw NoSuchElementException()).also { next = read() }

        override fun close() = extractor.release()

        companion object {
            private const val MAX_SAMPLE = 1 shl 16

            fun open(context: Context, uri: Uri): SourceAudio? {
                val extractor = MediaExtractor()
                return runCatching {
                    extractor.setDataSource(context, uri, null)
                    val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC }
                    extractor.selectTrack(track)
                    SourceAudio(extractor, extractor.getTrackFormat(track)).takeIf { it.hasNext() }
                }.getOrNull() ?: run {
                    extractor.release()
                    null
                }
            }
        }
    }

    /** The mono soundtrack to AAC-LC at [EncodeParams.AUDIO_RATE], all of it, with its format. */
    private fun encodeAudio(sound: PcmAudio): Pair<MediaFormat, List<Sample>> {
        val rate = EncodeParams.AUDIO_RATE
        val pcm = PcmAudio(EncodeParams.resample(sound.samples, sound.sampleRate, rate), rate)
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, 1).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, EncodeParams.AUDIO_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, CHUNK * 2)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val pump = AudioPump(codec, pcm)
        try {
            while (!pump.done) pump.step()
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
        return (pump.format ?: error("The AAC encoder gave no format")) to pump.samples
    }

    /** Feeds 16-bit PCM to the AAC encoder and keeps every encoded sample. */
    private class AudioPump(private val codec: MediaCodec, private val sound: PcmAudio) {
        val samples = ArrayList<Sample>()
        var format: MediaFormat? = null
        var done = false
            private set
        private val info = MediaCodec.BufferInfo()
        private var read = 0
        private var inputDone = false

        fun step() {
            if (!inputDone) feed()
            val o = codec.dequeueOutputBuffer(info, TIMEOUT_US)
            if (o == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) format = codec.outputFormat
            if (o < 0) return
            val buffer = codec.getOutputBuffer(o)!!
            if (info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                val bytes = ByteArray(info.size)
                buffer.position(info.offset)
                buffer.get(bytes)
                samples += Sample(bytes, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
            }
            done = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
            codec.releaseOutputBuffer(o, false)
        }

        private fun feed() {
            val i = codec.dequeueInputBuffer(TIMEOUT_US)
            if (i < 0) return
            val n = minOf(CHUNK, sound.size - read)
            val pts = read.toLong() * US / sound.sampleRate
            if (n <= 0) {
                codec.queueInputBuffer(i, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                inputDone = true
                return
            }
            val buffer = codec.getInputBuffer(i)!!.order(ByteOrder.LITTLE_ENDIAN)
            buffer.clear()
            for (k in read until read + n) buffer.putShort((sound.samples[k] * PCM_MAX).roundToInt().coerceIn(-PCM_MAX.toInt() - 1, PCM_MAX.toInt()).toShort())
            codec.queueInputBuffer(i, 0, n * 2, pts, 0)
            read += n
        }
    }

    companion object {
        private const val US = 1_000_000L
        private const val TIMEOUT_US = 10_000L
        private const val END_TIMEOUT_US = 100_000L
        private const val CHUNK = 4096
        private const val PCM_MAX = 32_767f

        /** About 0.27 bits a pixel at 30 fps: 16 Mbps for 1080×1920, generous for graphics edges; up to 80 Mbps for 4K60. */
        private fun bitrate(w: Int, h: Int, fps: Int): Int = (w.toLong() * h * fps * 27 / 100).coerceIn(4_000_000L, 80_000_000L).toInt()

        private fun even(v: Float): Int = ((v / 2f).roundToInt() * 2).coerceAtLeast(2)

        /** Whether a hardware (or any) AVC encoder on this phone takes [w]×[h] at [fps]. */
        fun supports(w: Int, h: Int, fps: Int): Boolean = encoders.any { video ->
            runCatching { video.areSizeAndRateSupported(w, h, fps.toDouble()) || video.areSizeAndRateSupported(h, w, fps.toDouble()) }.getOrDefault(false)
        }

        /** The phone's AVC encoders' video limits, read once. */
        private val encoders by lazy {
            MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
                .filter { it.isEncoder && MediaFormat.MIMETYPE_VIDEO_AVC in it.supportedTypes }
                .mapNotNull { runCatching { it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities }.getOrNull() }
        }
    }
}
