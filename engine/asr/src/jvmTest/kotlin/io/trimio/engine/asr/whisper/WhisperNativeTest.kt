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
}
