package io.trimio.engine.asr.whisper

import io.trimio.core.model.text.Language
import io.trimio.core.model.transcript.Word
import io.trimio.engine.asr.RecognitionOptions
import io.trimio.engine.media.WavCodec
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * Real whisper.cpp through JNI on the host. Runs only with `-Ptrimio.nativeTests`
 * (builds the library and downloads the tiny model first).
 */
class WhisperNativeTest {

    private val enabled = System.getProperty("trimio.nativeTests") == "true"

    @Test
    fun transcribesReferenceSpeechWithWordTimings() {
        if (!enabled) return println("skipped: run with -Ptrimio.nativeTests")
        val audio = WavCodec.decodeMono(File(System.getProperty("trimio.whisper.sample")).readBytes())
        assertEquals(16_000, audio.sampleRate)
        println(WhisperCppRecognizer.systemInfo())

        val streamed = mutableListOf<Word>()
        val progress = mutableListOf<Float>()
        WhisperCppRecognizer(System.getProperty("trimio.whisper.model")).use { recognizer ->
            val transcript: io.trimio.core.model.transcript.Transcript
            val took = measureTime {
                transcript = runBlocking {
                    recognizer.transcribe(audio, RecognitionOptions(language = Language.English), onProgress = { progress += it }) { _, w -> streamed += w }
                }
            }
            println("JFK sample (${audio.durationMs} ms) took $took: ${transcript.text}")
            transcript.words.forEach { println("  ${it.range.startMs}-${it.range.endMs} ${it.text} (${"%.2f".format(it.confidence)})") }

            val text = transcript.text.lowercase()
            assertTrue("ask not what your country can do for you" in text.replace(",", ""), text)
            assertEquals(Language.English, transcript.language)
            assertTrue(transcript.words.size in 18..26, "word count ${transcript.words.size}")
            assertTrue(transcript.words.zipWithNext().all { (a, b) -> a.range.endMs <= b.range.startMs }, "ordered, non-overlapping")
            assertTrue(transcript.words.last().range.endMs <= audio.durationMs + 200)
            assertTrue(streamed.isNotEmpty(), "words should stream while decoding")
            assertEquals(1f, progress.last())
        }
    }

    /**
     * Silero VAD through our JNI: the JFK sample with 6 s of silence before it and 4 s inside it.
     * Only the speech is decoded, and every word keeps its time on the padded (source) timeline.
     */
    @Test
    fun speechOnlyDecodingKeepsSourceTimes() {
        if (!enabled) return println("skipped: run with -Ptrimio.nativeTests")
        val jfk = WavCodec.decodeMono(File(System.getProperty("trimio.whisper.sample")).readBytes())
        val rate = jfk.sampleRate
        val cut = (4.8f * rate).toInt() // inside the pause after "ask not"
        val padded = FloatArray(6 * rate) + jfk.samples.copyOfRange(0, cut) + FloatArray(4 * rate) + jfk.samples.copyOfRange(cut, jfk.size)
        val audio = io.trimio.core.model.audio.PcmAudio(padded, rate)
        val options = RecognitionOptions(language = Language.English)
        val model = System.getProperty("trimio.whisper.model")
        // Reference: the unpadded sample; on the padded timeline its words sit 6 s later, and 4 s more after the cut.
        val reference = WhisperCppRecognizer(model).use { runBlocking { it.transcribe(jfk, options) } }
        fun expected(w: Word) = w.range.startMs + 6_000 + if (w.range.startMs >= cut * 1000L / rate) 4_000 else 0
        val plain = WhisperCppRecognizer(model).use { runBlocking { it.transcribe(audio, options) } }
        val vad = WhisperCppRecognizer(model, vadModelPath = System.getProperty("trimio.whisper.vad"))
        val (speech, facts) = vad.use { r -> runBlocking { r.transcribe(audio, options) } to r.runtime!!.facts }
        println("vad: ${facts.filter { it.first == "vad" }}")
        println("plain: " + plain.words.joinToString(" ") { "${it.text}@${it.range.startMs}" })
        println("vad:   " + speech.words.joinToString(" ") { "${it.text}@${it.range.startMs}" })
        assertTrue(facts.any { it.first == "vad" }, "the VAD cut the silence")
        assertTrue("ask not what your country can do for you" in speech.text.lowercase().replace(",", ""), speech.text)
        val want = reference.words.first { "country" in it.text.lowercase() }
        val got = speech.words.first { "country" in it.text.lowercase() }
        val noVad = plain.words.first { "country" in it.text.lowercase() }
        println("country: expected ${expected(want)}, speech-only ${got.range.startMs}, whole audio ${noVad.range.startMs}")
        // The tiny model's own token times jitter by a few hundred ms, so the reference is loose; whole-audio decoding drifts further.
        val error = kotlin.math.abs(got.range.startMs - expected(want))
        assertTrue(error < 400, "country at ${got.range}, expected ${expected(want)}")
        assertTrue(error < kotlin.math.abs(noVad.range.startMs - expected(want)), "speech-only timing beats decoding the silence too")
        assertTrue(kotlin.math.abs(speech.words.first().range.startMs - expected(reference.words.first())) < 400, "first word ${speech.words.first().range}")
        assertTrue(speech.words.zipWithNext().all { (a, b) -> a.range.startMs <= b.range.startMs })
    }
}
