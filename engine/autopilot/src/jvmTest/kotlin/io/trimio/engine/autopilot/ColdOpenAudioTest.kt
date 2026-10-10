package io.trimio.engine.autopilot

import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.assets.MusicMood
import io.trimio.engine.motion.recipe.Sfx
import io.trimio.engine.motion.recipe.SfxKind
import io.trimio.engine.motion.score.CutPoints
import io.trimio.engine.motion.score.EditPlan
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test

/**
 * The cold open's cut points on a real voice, for tuning by ear and eye. Skipped unless pointed at
 * one (never commit the audio):
 *
 * ./gradlew :engine:autopilot:jvmTest --tests '*ColdOpenAudioTest*' -Pcoldopen.wav=voice.wav
 *   -Pcoldopen.transcript=transcript.json [-Pauto.prompt=prompt.txt] [-Pcoldopen.out=dir]
 *
 * Prints the opening's words, every cut (before: the recogniser's edges with a fixed breath;
 * after: on the waveform) with the energy there, and with an out dir writes the first seconds of
 * both soundtracks to listen to.
 */
class ColdOpenAudioTest {
    @Serializable
    data class W(val w: String, val s: Float, val e: Float)

    @Test
    fun cutPointsOnTheRealVoice() {
        val wav = System.getProperty("coldopen.wav")?.let(::File)?.takeIf { it.isFile } ?: return
        val json = File(System.getProperty("coldopen.transcript") ?: return).readText()
        val prompt = System.getProperty("auto.prompt")?.let { p -> File(p).takeIf { it.isFile }?.readText() ?: p } ?: "ثانیه اول"
        val raw = Json { ignoreUnknownKeys = true }.decodeFromString(ListSerializer(W.serializer()), json)
        val voice = Media.audio(wav, RATE)
        val heard = SpeechAnalysis.of(voice, Transcript(Language.Persian, raw.map { Word(it.w, TimeRange((it.s * 1000).toLong(), (it.e * 1000).toLong()), language = Language.Persian) }))
        val texts = heard.transcript.words.map { it.text }
        val spelled = Proofreader.fromBrief(texts, prompt)
        val lexical = runBlocking { Proofreader.fromLexicon(texts, prompt, spelled) }
        val words = heard.transcript.withSpelling(spelled + lexical).words
        val lines = Lines.split(words, pauses = heard.pauses)
        val cuts = CutPoints(voice)
        println("speaker level ${f(cuts.speechDb)} dBFS")

        val after = ColdOpen.choose(words, lines, Understanding(), prompt, seed = 1, voice = voice) ?: return println("no cold open")
        // Before: the line's figure up to the first breath, cut on the recogniser's edges with a 60 ms lead and 120 ms tail.
        val line = lines[after.line]
        val beforeLast = (after.words.first..minOf(after.words.last, line.last)).last
        val beforeIn = words[after.words.first].range.startMs / 1000f - 0.06f
        val beforeOut = minOf(words[beforeLast].range.endMs / 1000f + 0.12f, words.getOrNull(beforeLast + 1)?.range?.startMs?.div(1000f) ?: Float.MAX_VALUE)
        println("before «${(after.words.first..beforeLast).joinToString(" ") { words[it].text }}»")
        println("  in  ${f(beforeIn)} s  ${f(cuts.energyDb(beforeIn))} dBFS (${f(cuts.energyDb(beforeIn) - cuts.speechDb)} dB)")
        println("  out ${f(beforeOut)} s  ${f(cuts.energyDb(beforeOut))} dBFS (${f(cuts.energyDb(beforeOut) - cuts.speechDb)} dB)")
        println("after «${after.words.joinToString(" ") { words[it].text }}» ${f(after.length)} s")
        after.words.forEach { i -> println("  ${words[i].text.padEnd(10)} ${f(words[i].range.startMs / 1000f)}–${f(words[i].range.endMs / 1000f)}") }
        after.cuts?.let { (a, b) ->
            println("  in  ${f(a.at)} s  fade ${f(a.db)} dBFS (${f(a.relative)} dB) clean=${a.clean}, at the cut ${f(cuts.energyDb(a.at))} dBFS")
            println("  out ${f(b.at)} s  fade ${f(b.db)} dBFS (${f(b.relative)} dB) clean=${b.clean}, at the cut ${f(cuts.energyDb(b.at))} dBFS")
        }
        println("  pieces ${after.pieces.joinToString { "${f(it.start)}–${f(it.endInclusive)}" }}")
        val restart = after.restart
        if (restart != null) {
            println("  body restarts at ${f(restart)} s (${f(cuts.energyDb(restart))} dBFS), first word «${words[0].text}» heard at ${f(words[0].range.startMs / 1000f)} s")
        }

        val out = System.getProperty("coldopen.out")?.let(::File) ?: return
        val body = heard.edit
        listen(voice, body.withColdOpen(beforeIn..beforeOut, GAP), File(out, "coldopen-before.wav"))
        listen(voice, after.applyTo(body, GAP), File(out, "coldopen-after.wav"))
    }

    /** The first seconds of the mastered soundtrack: voice, the restart's cues and the music bed. */
    private fun listen(voice: PcmAudio, edit: EditPlan, file: File) {
        val r = edit.preludeLength
        val seconds = minOf(edit.duration, r + LISTEN_AFTER)
        val cues = listOf(Sfx(r - 0.36f, SfxKind.Rewind, 1f), Sfx(r, SfxKind.Hit, 0.5f))
        val mastered = Soundtrack(RATE).master(voice, edit, emptyList(), seconds, MusicMood.entries.first(), musicSeed = 1, cues = cues)
        Media.writeWav(mastered.audio, file)
        println("wrote $file (restart at ${f(r)} s, body from ${f(edit.bodyStart)} s)")
    }

    private fun f(v: Float) = "%.3f".format(v)

    private companion object {
        const val RATE = 48_000
        const val GAP = 0.25f
        const val LISTEN_AFTER = 5f
    }
}
