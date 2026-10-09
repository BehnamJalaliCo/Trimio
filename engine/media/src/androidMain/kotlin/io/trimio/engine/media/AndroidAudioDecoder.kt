package io.trimio.engine.media

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.input.MediaUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder

/**
 * Hardware/software decode of the first audio track through MediaCodec (synchronous mode),
 * downmixed to mono float. Handles 16-bit and float decoder output and mid-stream format changes.
 */
class AndroidAudioDecoder(private val context: Context) : AudioDecoder {

    override suspend fun decodeMono(uri: MediaUri, onProgress: (Float) -> Unit): PcmAudio = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            extractor.setDataSource(context, Uri.parse(uri.value), null)
            val track = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME).orEmpty().startsWith("audio/")
            } ?: throw MediaFormatException("No audio track in ${uri.value}")
            extractor.selectTrack(track)

            val input = extractor.getTrackFormat(track)
            val mime = input.getString(MediaFormat.KEY_MIME)!!
            val durationUs = input.longOr(MediaFormat.KEY_DURATION, 0L).coerceAtLeast(1L)
            var sampleRate = input.intOr(MediaFormat.KEY_SAMPLE_RATE, 48_000)
            var channels = input.intOr(MediaFormat.KEY_CHANNEL_COUNT, 1)
            var floatPcm = false

            codec = MediaCodec.createDecoderByType(mime).apply {
                configure(input, null, null, 0)
                start()
            }
            val out = GrowableFloats((durationUs * sampleRate / 1_000_000L + sampleRate).toInt())
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (inIndex >= 0) {
                        val buffer = codec.getInputBuffer(inIndex)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(inIndex, 0, size, extractor.sampleTime, 0)
                            onProgress((extractor.sampleTime.toFloat() / durationUs).coerceIn(0f, 1f))
                            extractor.advance()
                        }
                    }
                }

                val outIndex = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        val f = codec.outputFormat
                        sampleRate = f.intOr(MediaFormat.KEY_SAMPLE_RATE, sampleRate)
                        channels = f.intOr(MediaFormat.KEY_CHANNEL_COUNT, channels)
                        floatPcm = f.intOr(MediaFormat.KEY_PCM_ENCODING, AudioFormat.ENCODING_PCM_16BIT) == AudioFormat.ENCODING_PCM_FLOAT
                    }
                    outIndex >= 0 -> {
                        val buffer = codec.getOutputBuffer(outIndex)!!.order(ByteOrder.nativeOrder())
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        if (floatPcm) {
                            val fb = buffer.asFloatBuffer()
                            while (fb.remaining() >= channels) {
                                var sum = 0f
                                repeat(channels) { sum += fb.get() }
                                out.add(sum / channels)
                            }
                        } else {
                            val sb = buffer.asShortBuffer()
                            while (sb.remaining() >= channels) {
                                var sum = 0
                                repeat(channels) { sum += sb.get() }
                                out.add(sum / (32768f * channels))
                            }
                        }
                        codec.releaseOutputBuffer(outIndex, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            }
            onProgress(1f)
            PcmAudio(out.toArray(), sampleRate)
        } catch (e: MediaFormatException) {
            throw e
        } catch (e: IllegalStateException) {
            throw MediaFormatException("Audio decoding failed: ${e.message}", e)
        } finally {
            codec?.runCatching { stop(); release() }
            extractor.release()
        }
    }

    private class GrowableFloats(initial: Int) {
        private var data = FloatArray(initial.coerceAtLeast(1024))
        private var size = 0

        fun add(v: Float) {
            if (size == data.size) data = data.copyOf(data.size + data.size / 2)
            data[size++] = v
        }

        fun toArray(): FloatArray = data.copyOf(size)
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
    }
}
