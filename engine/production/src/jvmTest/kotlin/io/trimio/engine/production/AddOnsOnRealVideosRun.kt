package io.trimio.engine.production

import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.onnx.OrtEngine
import io.trimio.engine.perception.AddOnPass
import io.trimio.engine.perception.ModelFiles
import io.trimio.engine.perception.Perception
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.math.sqrt
import kotlin.test.Test

/**
 * Phase 9.3 on the creator's own videos, run by hand (no models or videos in CI): what the beat add-on hears (the
 * video's own music, which turns the edit's music off) and how many voices the speaker add-ons find (each gets its
 * caption colour). A talk with no music must have no tempo, and one speaker must stay one colour.
 *
 * ./gradlew :engine:production:jvmTest --tests '*AddOnsOnRealVideosRun*' -Paddons.dir=<models> -Paddons.videos=<a.mp4,b.mp4>
 */
class AddOnsOnRealVideosRun {
    @Test
    fun beatsAndVoicesOnRealVideos() = runBlocking {
        val dir = System.getProperty("addons.dir")?.let(::File)?.takeIf { it.isDirectory } ?: return@runBlocking
        val videos = System.getProperty("addons.videos")?.split(',')?.map(::File)?.filter { it.isFile }.orEmpty()
        val pass = AddOnPass(Perception(OrtEngine(), ModelFiles { spec -> File(dir, spec.fileName).takeIf { it.isFile }?.path }))
        val platform = JvmProductionPlatform(Files.createTempDirectory("addons").toFile())
        for (video in videos) {
            val voice = platform.audio(video.path, VOICE)
            val seconds = voice.samples.size / VOICE.toFloat()
            val beats = pass.beats(voice)
            val tempo = pass.sourceTempo(voice)
            val heard = platform.audio(video.path, WHISPER)
            val voices = pass.voices(Transcript(Language.Persian, sounding(heard)), heard, WHISPER)
            val speakers = voices.groupBy { it.speaker }.mapValues { (_, turns) -> turns.sumOf { (it.to - it.from).toDouble() } }
            println(
                "addons ${video.name}: ${"%.1f".format(seconds)} s | beats ${beats?.size ?: 0} | tempo ${tempo?.let { "%.1f bpm".format(it.first) } ?: "none"} | " +
                    "voices ${speakers.size.coerceAtLeast(1)} ${speakers.entries.joinToString { (k, v) -> "#$k ${"%.1f".format(v)} s" }}",
            )
        }
        pass.report.forEach { println("addons report: $it") }
    }

    /** A stand-in word for every half second with sound in it (the diarizer counts only voices that say words). */
    private fun sounding(audio: PcmAudio): List<Word> {
        val step = audio.sampleRate / 2
        return (0 until audio.samples.size / step).mapNotNull { k ->
            val from = k * step
            val rms = sqrt((from until from + step).sumOf { (audio.samples[it] * audio.samples[it]).toDouble() } / step)
            if (rms < QUIET) null else Word("و", TimeRange(k * HALF_MS, k * HALF_MS + HALF_MS - 50), language = Language.Persian)
        }
    }

    private companion object {
        const val VOICE = 48_000
        const val WHISPER = 16_000
        const val QUIET = 0.01
        const val HALF_MS = 500L
    }
}
