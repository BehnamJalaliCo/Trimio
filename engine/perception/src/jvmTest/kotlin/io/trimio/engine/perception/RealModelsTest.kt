package io.trimio.engine.perception

import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Word
import io.trimio.engine.models.MediaAddOns
import io.trimio.engine.models.ModelSpec
import io.trimio.engine.onnx.OrtEngine
import io.trimio.engine.perception.align.ForcedAligner
import io.trimio.engine.perception.beats.BeatFinder
import io.trimio.engine.perception.face.FaceZoneFinder
import io.trimio.engine.perception.ocr.TextReader
import io.trimio.engine.perception.shots.ShotDetector
import io.trimio.engine.perception.shots.TransNet
import io.trimio.engine.perception.speakers.Diarizer
import io.trimio.engine.perception.text.Punctuator
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The add-ons on real models and real media (never committed). Opt-in:
 * `./gradlew :engine:perception:jvmTest -Ptrimio.mediaModels=<dir> -Ptrimio.mediaTest=<dir>`; the
 * model directory holds the catalogue's file names, the media directory PNG frames (`f26`, `f66`),
 * 16 kHz mono WAVs (`a26.wav`, `two-speakers-en.wav`) and PNG renders with Persian text (`ocr`).
 * Timings are printed as "server" numbers: this machine's CPU, not a phone.
 */
class RealModelsTest {
    private val models = System.getProperty("trimio.media.models")?.let(::File)?.takeIf { it.isDirectory }
    private val media = System.getProperty("trimio.media.test")?.let(::File)?.takeIf { it.isDirectory }
    private val perception by lazy {
        Perception(OrtEngine(), ModelFiles { spec: ModelSpec -> File(models, spec.fileName).takeIf { it.isFile }?.path })
    }

    private fun frame(file: File, time: Float = 0f): Frame {
        val img = ImageIO.read(file)
        val px = ByteArray(img.width * img.height * 3)
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val p = img.getRGB(x, y)
            val i = (y * img.width + x) * 3
            px[i] = (p shr 16).toByte()
            px[i + 1] = (p shr 8).toByte()
            px[i + 2] = p.toByte()
        }
        return Frame(img.width, img.height, px, time)
    }

    private fun wav(file: File): PcmAudio {
        val bytes = file.readBytes()
        // Canonical 44-byte header, 16-bit mono.
        val data = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val rate = ByteBuffer.wrap(bytes, 24, 4).order(ByteOrder.LITTLE_ENDIAN).int
        return PcmAudio(FloatArray(data.remaining()) { data.get(it) / 32768f }, rate)
    }

    private inline fun <T> timed(label: String, block: () -> T): T {
        val t = System.nanoTime()
        return block().also { println("server: $label in ${(System.nanoTime() - t) / 1_000_000} ms") }
    }

    private fun ready(vararg specs: ModelSpec) = models != null && media != null && specs.all { File(models, it.fileName).isFile }

    @Test
    fun facesKeepTextOffTheSpeaker() = runBlocking {
        if (!ready(MediaAddOns.faces)) return@runBlocking
        val frames = File(media, "f26").listFiles()!!.sorted().mapIndexed { i, f -> frame(f, i.toFloat()) }
        val zones = timed("faces on ${frames.size} frames") { FaceZoneFinder(perception).zones(frames) }
        assertTrue(zones.measured, perception.notes.toString())
        val seen = zones.samples.count { it.faces.isNotEmpty() }
        println("faces: $seen of ${frames.size} frames; first ${zones.samples.first().faces}")
        assertTrue(seen >= frames.size * 0.9, "the speaker is in every frame")
        val face = zones.faces().first()
        assertTrue(face.centerX in 0.3f..0.7f && face.centerY in 0.3f..0.75f, "speaker's face in the middle: $face")
        val p = zones.place(0.8f, 0.1f)!!
        assertTrue(zones.faces().none { it.intersects(p.box) }, "caption ${p.box} clear of the face")
        println("caption at ${p.box}, eye line ${zones.eyeLine()}")
    }

    @Test
    fun shotsOnASpliceOfTwoVideos() = runBlocking {
        if (!ready(MediaAddOns.shots)) return@runBlocking
        // f26 then f66 frames upscaled: one hard cut between the two talks.
        val a = File(media, "f26").listFiles()!!.sorted().take(12).map { frame(it).resized(TransNet.WIDTH, TransNet.HEIGHT) }
        val b = File(media, "f66").listFiles()!!.sorted().take(12).map { frame(it).resized(TransNet.WIDTH, TransNet.HEIGHT) }
        // Hold each still for 5 frames (1 s sampled at 5 fps) so the network sees steady shots.
        val frames = (a + b).flatMap { f -> List(5) { f } }
        val cuts = timed("shots on ${frames.size} frames") { ShotDetector(perception).detect(frames, fps = 5f) }
        println("cuts: ${cuts.cuts}")
        assertTrue(cuts.measured)
        assertTrue(cuts.cuts.any { abs(it.time - 12f) < 0.5f }, "the splice at 12 s")
    }

    @Test
    fun beatsOfAClickTrack() = runBlocking {
        if (!ready(MediaAddOns.beats, MediaAddOns.beatsFrontend)) return@runBlocking
        // 20 s at 120 bpm: a low thump on every beat, louder on every fourth.
        val rate = 22_050
        val x = FloatArray(20 * rate)
        for (k in 0 until 40) {
            val start = k * rate / 2
            val gain = if (k % 4 == 0) 0.9f else 0.5f
            for (i in 0 until 2_000) x[start + i] += gain * kotlin.math.sin(2 * Math.PI * 80 * i / rate).toFloat() * kotlin.math.exp(-i / 400f)
        }
        val beats = timed("beats on 20 s") { BeatFinder(perception).beats(PcmAudio(x, rate)) }
        println("beats ${beats.beats.take(8)} … bpm ${beats.bpm}, downbeats ${beats.downbeats.take(4)}")
        assertTrue(beats.measured, perception.notes.toString())
        assertTrue(abs(beats.bpm!! - 120.0) < 3.0, "bpm ${beats.bpm}")
    }

    @Test
    fun sentencePieceMatchesTheReferenceTokenizer() {
        if (!ready(MediaAddOns.punctuationTokenizer)) return
        val sp = io.trimio.engine.perception.text.SentencePiece.load(File(models, MediaAddOns.punctuationTokenizer.fileName).readBytes())
        // Ids from the reference `sentencepiece` 0.2 Python package on the same file.
        assertEquals(listOf(22186, 2449, 910, 404, 27009), sp.encode("قابلیت\u200cهای این پروژه").toList())
        assertEquals(listOf(4672, 5550, 5, 11220, 80, 829, 14, 138, 3550), sp.encode("claude code و chatgpt").toList())
        assertEquals(listOf(11866, 3984, 7781, 5, 22213, 9424, 8945, 5016), sp.encode("۹۰ درصد صرفه\u200cجویی").toList())
    }

    @Test
    fun punctuationOnTheBenchmark() = runBlocking {
        if (!ready(MediaAddOns.punctuation, MediaAddOns.punctuationTokenizer)) return@runBlocking
        val gold = goldWords() ?: return@runBlocking
        val bare = gold.map { it.copy(text = it.text.trimEnd('.', '،', '؟', '?', ',')) }
        val out = timed("punctuation of ${gold.size} words") { Punctuator(perception).punctuate(bare, Language.Persian) }
        assertTrue(out.measured, perception.notes.toString())
        val marks = gold.indices.filter { gold[it].text.last() in ".،؟" }
        val hit = marks.count { out.words[it].text.last() == gold[it].text.last() }
        val extra = gold.indices.count { it !in marks && out.words[it].text.last() in ".،؟" }
        println("punctuation: $hit of ${marks.size} gold marks restored, $extra extra; ${out.words.joinToString(" ") { it.text }}")
        assertTrue(hit >= marks.size * 0.6)
    }

    /**
     * There is no hand-timed reference, so the aligner is checked by what it must do: follow the
     * audio. 700 ms of silence is inserted after word 30 while the input times stay where they were;
     * words after the gap must move by 700 ms and words before it must not.
     */
    @Test
    fun alignmentFollowsTheAudio() = runBlocking {
        if (!ready(MediaAddOns.aligner, MediaAddOns.alignerTokens)) return@runBlocking
        val gold = goldWords() ?: return@runBlocking
        val audio = wav(File(media, "a26.wav"))
        val aligner = ForcedAligner(perception)
        val plain = timed("alignment of ${gold.size} words, 26 s") { aligner.align(gold, audio) }
        assertTrue(plain.measured, perception.notes.toString())
        val k = 30
        val at = ((gold[k].range.endMs + gold[k + 1].range.startMs) / 2 * audio.sampleRate / 1000).toInt()
        val gap = FloatArray(audio.sampleRate * 7 / 10)
        val longer = PcmAudio(audio.samples.copyOfRange(0, at) + gap + audio.samples.copyOfRange(at, audio.size), audio.sampleRate)
        // Stale times: words after the gap keep their old times (+350 ms, half the gap, as a rough recogniser might).
        val stale = gold.mapIndexed { i, w -> if (i <= k) w else w.copy(range = TimeRange(w.range.startMs + 350, w.range.endMs + 350)) }
        val shifted = aligner.align(stale, longer)
        val before = (0..k - 2).map { abs(shifted.words[it].range.startMs - plain.words[it].range.startMs) }.sorted()
        val after = (k + 2 until gold.size).map { abs(shifted.words[it].range.startMs - plain.words[it].range.startMs - 700) }.sorted()
        val offset = gold.indices.map { plain.words[it].range.startMs - gold[it].range.startMs }.sorted()
        println("alignment: before the gap median ${before[before.size / 2]} ms, after it median ${after[after.size / 2]} ms (p90 ${after[after.size * 9 / 10]})")
        println("alignment: median offset against the recogniser's times ${offset[offset.size / 2]} ms")
        assertTrue(before[before.size / 2] <= 40 && after[after.size / 2] <= 40, "aligned times follow the audio")
    }

    @Test
    fun twoSpeakersAreTold() = runBlocking {
        if (!ready(MediaAddOns.speakerTurns, MediaAddOns.speakerVoices)) return@runBlocking
        val file = File(media, "two-speakers-en.wav").takeIf { it.isFile } ?: return@runBlocking
        val d = timed("diarization of 16 s") { Diarizer(perception).diarize(wav(file)) }
        println("speakers ${d.speakers}: ${d.turns}")
        assertTrue(d.measured)
        assertTrue(d.speakers == 2, "two voices")
        // One speaker on the Persian talk.
        val solo = Diarizer(perception).diarize(wav(File(media, "a26.wav")))
        println("persian talk: ${solo.speakers} speaker(s), ${solo.turns.size} turns")
        assertTrue(solo.speakers == 1)
    }

    @Test
    fun ocrReadsPersianRenders() = runBlocking {
        if (!ready(MediaAddOns.ocrDetector, MediaAddOns.ocrRecognizer, MediaAddOns.ocrDictionary)) return@runBlocking
        val dir = File(media, "ocr").takeIf { it.isDirectory } ?: return@runBlocking
        val frames = dir.listFiles()!!.filter { it.extension == "png" }.sorted().map { frame(it) }
        run {
            val engine = OrtEngine()
            val det = timed("ocr det load") { engine.load(File(models, MediaAddOns.ocrDetector.fileName).path) }
            val ocr = io.trimio.engine.perception.ocr.PpOcr(listOf("a"))
            val (input, w, h) = timed("ocr det input") { ocr.detectorInput(frames.first()) }
            val map = timed("ocr det run 1") { det.run(mapOf(det.inputNames.first() to input)).values.first().requireFloats() }
            timed("ocr det run 2") { det.run(mapOf(det.inputNames.first() to input)) }
            val boxes = timed("ocr boxes") { ocr.boxes(map, w, h) }
            det.close()
            val rec = timed("ocr rec load") { engine.load(File(models, MediaAddOns.ocrRecognizer.fileName).path) }
            boxes.forEach { (b, _) -> timed("ocr rec ${b.w}") { rec.run(mapOf(rec.inputNames.first() to ocr.recognizerInput(frames.first(), b))) } }
            rec.close()
        }
        val read = timed("ocr of ${frames.size} frames") { TextReader(perception).read(frames) }
        read.forEachIndexed { i, t -> println("ocr ${dir.listFiles()!!.filter { it.extension == "png" }.sorted()[i].name}: ${t.lines.map { it.text to it.confidence }}") }
        assertTrue(read.all { it.measured })
        val c1 = read.first()
        assertTrue(c1.readBack("رشد امروز") > 0.9f, "reads our own caption")
        assertTrue(c1.readBack("فروش دیروز") < 0.6f)
    }

    private fun goldWords(): List<Word>? {
        val file = File("../../docs/benchmark/01-repo-map/words.json").takeIf { it.isFile } ?: return null
        return Json.parseToJsonElement(file.readText()).jsonArray.map { e ->
            val o = e.jsonObject
            Word(
                o.getValue("w").jsonPrimitive.content,
                TimeRange((o.getValue("s").jsonPrimitive.float * 1000).toLong(), (o.getValue("e").jsonPrimitive.float * 1000).toLong()),
                language = Language.Persian,
            )
        }
    }
}
