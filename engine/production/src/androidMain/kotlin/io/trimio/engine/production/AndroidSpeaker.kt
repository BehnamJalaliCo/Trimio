package io.trimio.engine.production

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import io.trimio.core.model.audio.PcmAudio
import kotlin.concurrent.thread

/**
 * The editor's sound on Android: the mix as float PCM through a streaming [AudioTrack], written
 * from its own thread; the playhead is the track's own head position, so picture follows sound.
 */
class AndroidSpeaker : Speaker {
    @Volatile private var track: AudioTrack? = null
    @Volatile private var writer: Thread? = null
    @Volatile private var from = 0f
    @Volatile private var rate = 0

    override fun play(audio: PcmAudio, from: Float) {
        pause()
        val format = AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
            .setSampleRate(audio.sampleRate)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
            .build()
        val min = AudioTrack.getMinBufferSize(audio.sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val out = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
                .setAudioFormat(format)
                .setBufferSizeInBytes(maxOf(min, audio.sampleRate * FLOAT_BYTES / BUFFERS))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull() ?: return
        this.from = from
        rate = audio.sampleRate
        track = out
        out.play()
        writer = thread(name = "trimio-speaker", isDaemon = true) {
            val samples = audio.samples
            var i = (from * audio.sampleRate).toInt().coerceIn(0, samples.size)
            while (i < samples.size && track === out && !Thread.currentThread().isInterrupted) {
                val n = minOf(CHUNK, samples.size - i)
                val written = out.write(samples, i, n, AudioTrack.WRITE_BLOCKING)
                if (written <= 0) break
                i += written
            }
        }
    }

    override fun pause() {
        val out = track ?: return
        track = null
        writer?.interrupt()
        writer = null
        runCatching {
            out.pause()
            out.flush()
            out.release()
        }
    }

    override val position: Float?
        get() = track?.let { from + it.playbackHeadPosition / rate.toFloat() }

    private companion object {
        const val CHUNK = 2048
        const val FLOAT_BYTES = 4
        const val BUFFERS = 10
    }
}
