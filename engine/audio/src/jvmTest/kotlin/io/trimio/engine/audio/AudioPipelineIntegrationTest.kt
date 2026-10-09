package io.trimio.engine.audio

import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.input.CanvasSpec
import io.trimio.core.model.input.InputSource
import io.trimio.core.model.input.MediaUri
import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.core.pipeline.Artifacts
import io.trimio.core.pipeline.JobSpec
import io.trimio.core.pipeline.JobStatus
import io.trimio.core.pipeline.LiveSignal
import io.trimio.core.pipeline.PipelineEvent
import io.trimio.core.pipeline.PipelineOrchestrator
import io.trimio.core.pipeline.PipelineStage
import io.trimio.core.pipeline.StageContext
import io.trimio.core.pipeline.StageId
import io.trimio.core.pipeline.StandardArtifacts
import io.trimio.engine.media.IngestStage
import io.trimio.engine.media.WavCodec
import io.trimio.engine.media.WavFileSource
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.measureTime

/**
 * Real stages end to end: a 44.1 kHz WAV on disk → Ingest → AudioCleanup → (stub transcription)
 * → Analysis, through the orchestrator, checking artifacts and live signals.
 */
class AudioPipelineIntegrationTest {

    private val rate = 44_100

    /** Five "words" with room tone between them; word 3 is stressed. */
    private fun recording(): Pair<PcmAudio, List<Word>> {
        val gap = { Synth.noise(-58.0, 200, rate) }
        val parts = listOf(
            Synth.voice(130.0, -22.0, 380, rate), Synth.voice(125.0, -22.0, 320, rate),
            Synth.voice(135.0, -23.0, 360, rate), Synth.voice(200.0, -10.0, 460, rate),
            Synth.voice(120.0, -24.0, 300, rate),
        )
        val pieces = mutableListOf(Synth.noise(-58.0, 600, rate))
        val words = mutableListOf<Word>()
        var cursor = 600L
        parts.forEachIndexed { i, p ->
            val ms = p.size * 1000L / rate
            words += Word("کلمه$i", TimeRange(cursor, cursor + ms), language = Language.Persian)
            pieces += p
            pieces += gap()
            cursor += ms + 200
        }
        pieces += Synth.noise(-58.0, 600, rate)
        return Synth.concat(rate, *pieces.toTypedArray()) to words
    }

    /** Stands in for Whisper until phase 2: publishes known word timings. */
    private class StubTranscription(private val words: List<Word>) : PipelineStage {
        override val id = StageId.Transcription
        override suspend fun run(context: StageContext) {
            context.artifacts[StandardArtifacts.Transcript] = Transcript(Language.Persian, words)
            context.progress(1f)
        }
    }

    @Test
    fun realAudioStagesProduceFeaturesEmphasisAndLiveSignals() = runBlocking {
        val (audio, words) = recording()
        val file = File.createTempFile("trimio-test", ".wav").apply { deleteOnExit(); writeBytes(WavCodec.encodePcm16(audio)) }
        val source = WavFileSource()
        var artifacts: Artifacts? = null
        val capture = object : PipelineStage {
            override val id = StageId.Export
            override suspend fun run(context: StageContext) { artifacts = context.artifacts }
        }
        val job = JobSpec("it", InputSource.AudioOnly(MediaUri(file.absolutePath), audio.durationMs, CanvasSpec()), prompt = "")

        val events = PipelineOrchestrator(
            listOf(IngestStage(source), AudioCleanupStage(source), StubTranscription(words), AnalysisStage(), capture),
        ).run(job).toList()

        val final = events.filterIsInstance<PipelineEvent.StateChanged>().last().state
        assertEquals(JobStatus.Completed, final.status, final.error)

        val a = assertNotNull(artifacts)
        val info = a.require(StandardArtifacts.MediaInfo)
        assertEquals(audio.durationMs, info.durationMs, "probe duration")
        assertEquals(rate, info.audio?.sampleRate)

        val clean = a.require(StandardArtifacts.CleanAudio)
        assertEquals(16_000, clean.sampleRate)

        val features = a.require(StandardArtifacts.AudioFeatures)
        assertTrue(features.appliedGainDb > 3f, "quiet recording should be boosted, got ${features.appliedGainDb}")
        // Words 200 ms apart form one phrase; it starts with the first word and covers the last.
        assertEquals(1, features.speech.size, "speech regions ${features.speech}")
        assertEquals(600.0, features.speech[0].startMs.toDouble(), 50.0)
        assertTrue(features.speech[0].endMs >= words.last().range.endMs)

        val emphasis = a.require(StandardArtifacts.Transcript).words.map { it.emphasis }
        assertEquals(3, emphasis.indices.maxBy { emphasis[it] }, "emphasis $emphasis")

        val signals = events.filterIsInstance<PipelineEvent.Live>().map { it.signal }
        assertTrue(signals.count { it is LiveSignal.Waveform } >= 20, "waveform should stream")
        assertTrue(signals.any { it is LiveSignal.EmphasisFound && it.wordIndex == 3 })
        assertTrue(signals.filterIsInstance<LiveSignal.Note>().any { "LUFS" in it.textEn })
    }

    @Test
    fun tenMinutesOfAudioIsAnalysedQuickly() {
        // Ten minutes of alternating speech and pauses at 48 kHz: the phone-camera worst case.
        val r = 48_000
        val block = Synth.concat(r, Synth.voice(140.0, -18.0, 4_000, r), Synth.noise(-55.0, 1_000, r)).samples
        val audio = PcmAudio(FloatArray(r * 600) { block[it % block.size] }, r)

        val elapsed = measureTime {
            val mono = Resampler().resample(audio, 16_000)
            val normalized = LoudnessNormalizer().normalize(mono)
            val speech = VoiceActivityDetector().detect(normalized.audio)
            ProsodyAnalyzer().features(normalized.audio, speech, normalized.measuredLufs.toFloat(), normalized.appliedGainDb.toFloat())
        }
        println("10 min analysis took $elapsed")
        assertTrue(elapsed.inWholeMilliseconds < 5_000, "10 min of audio took $elapsed (budget 5 s)")
    }
}
