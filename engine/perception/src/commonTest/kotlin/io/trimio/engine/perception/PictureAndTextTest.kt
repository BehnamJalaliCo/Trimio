package io.trimio.engine.perception

import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Word
import io.trimio.engine.onnx.Accelerator
import io.trimio.engine.onnx.Network
import io.trimio.engine.onnx.Tensor
import io.trimio.engine.perception.judge.AestheticFeatures
import io.trimio.engine.perception.judge.AestheticHead
import io.trimio.engine.perception.judge.AestheticTrainer
import io.trimio.engine.perception.judge.RatedFrame
import io.trimio.engine.perception.ocr.OcrText
import io.trimio.engine.perception.ocr.PpOcr
import io.trimio.engine.perception.ocr.TextLine
import io.trimio.engine.perception.prosody.Delivery
import io.trimio.engine.perception.shots.Cut
import io.trimio.engine.perception.shots.HistogramCuts
import io.trimio.engine.perception.shots.ShotCuts
import io.trimio.engine.perception.shots.TransNet
import io.trimio.engine.perception.text.Marks
import io.trimio.engine.perception.text.PunctCapSeg
import io.trimio.engine.perception.text.SentencePiece
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PictureAndTextTest {
    private fun word(text: String, s: Long, e: Long) = Word(text, TimeRange(s, e), language = Language.Persian)

    @Test
    fun transNetRunsMarkOneCutEach() {
        val single = FloatArray(100)
        val many = FloatArray(100)
        single[24] = 0.9f // hard cut after frame 24
        for (f in 60..64) single[f] = 0.7f // a dissolve
        val cuts = TransNet.cuts(single, many, fps = 25f)
        assertEquals(2, cuts.size)
        assertEquals(1.0f, cuts[0].time, 1e-4f)
        assertTrue(!cuts[0].gradual && cuts[1].gradual)
    }

    @Test
    fun histogramFallbackFindsAHardCut() {
        val frames = (0 until 50).map { i -> if (i < 30) Frame.solid(48, 27, 200, 30, 30, i / 25f) else Frame.solid(48, 27, 20, 40, 220, i / 25f) }
        val cuts = HistogramCuts.detect(frames, 25f)
        assertEquals(1, cuts.size)
        assertEquals(30 / 25f, cuts.single().time, 1e-4f)
    }

    @Test
    fun shotsSnapAndBrollStaysInsideAShot() {
        val shots = ShotCuts(listOf(Cut(4f, 0.9f), Cut(5f, 0.8f)), duration = 12f, measured = true)
        assertEquals(listOf(0f..4f, 4f..5f, 5f..12f), shots.shots())
        assertEquals(4f, shots.snap(4.1f)?.time)
        val windows = shots.brollWindows(minLength = 1.5f)
        assertEquals(2, windows.size, "the 1 s shot is too short: $windows")
        assertTrue(windows.all { w -> shots.shots().any { w.start >= it.start && w.endInclusive <= it.endInclusive } })
    }

    /** A tiny SentencePiece model: <unk>, <s>, </s>, <pad>, ▁, ▁سلام, سلام, ▁دنیا, a. */
    private fun spModel(): ByteArray {
        fun varint(v: Int): List<Byte> {
            val out = ArrayList<Byte>()
            var x = v
            while (x >= 0x80) {
                out += ((x and 0x7F) or 0x80).toByte()
                x = x ushr 7
            }
            out += x.toByte()
            return out
        }
        fun piece(text: String, score: Float, type: Int): List<Byte> {
            val t = text.encodeToByteArray().toList()
            val bits = score.toRawBits()
            val body = listOf<Byte>(0x0A) + varint(t.size) + t + listOf<Byte>(0x15) + (0 until 4).map { (bits ushr (8 * it)).toByte() } + listOf<Byte>(0x18) + varint(type)
            return listOf<Byte>(0x0A) + varint(body.size) + body
        }
        // A trainer spec (field 2, skipped) between the pieces, with a two-byte length.
        val spec = listOf<Byte>(0x12) + varint(200) + List(200) { 0x08 }
        val pieces = listOf("<unk>" to 2, "<s>" to 3, "</s>" to 3, "<pad>" to 3).map { piece(it.first, 0f, it.second) } + listOf(spec) +
            listOf(piece("▁", -3f, 1), piece("▁سلام", -2f, 1), piece("سلام", -4f, 1), piece("▁دنیا", -2.5f, 1), piece("a", -5f, 1))
        return pieces.flatten().toByteArray()
    }

    @Test
    fun sentencePieceParsesTheProtobufAndPicksTheBestSegmentation() {
        val sp = SentencePiece.load(spModel())
        assertEquals(1, sp.bos)
        assertEquals(2, sp.eos)
        assertEquals(3, sp.pad)
        assertContentEquals(intArrayOf(5, 7), sp.encode("سلام  دنیا"))
        // "x" is unknown: ▁ then one <unk> for the run "xx".
        assertContentEquals(intArrayOf(4, 0), sp.encode("xx"))
    }

    @Test
    fun punctuationLabelsLandOnEachWordsLastPiece() {
        val sp = SentencePiece.load(spModel())
        val net = object : Network {
            override val accelerator = Accelerator.Cpu
            override val inputNames = listOf("input_ids")
            override val outputNames = listOf("pre_preds", "post_preds", "cap_preds", "seg_preds")

            override fun run(inputs: Map<String, Tensor>, outputs: List<String>?): Map<String, Tensor> {
                val n = inputs.getValue("input_ids").count
                // BOS, ▁سلام → "،", ▁دنیا → "؟" and a sentence end, EOS.
                val post = LongArray(n).also { it[1] = 11; it[2] = 10 }
                val seg = ByteArray(n).also { it[2] = 1 }
                return mapOf("post_preds" to Tensor.ofLongs(longArrayOf(1, n.toLong()), post), "seg_preds" to Tensor.ofBytes(longArrayOf(1, n.toLong()), seg))
            }

            override fun close() = Unit
        }
        assertEquals(listOf("،" to false, "؟" to true), PunctCapSeg.predict(net, sp, listOf("سلام", "دنیا")))
    }

    @Test
    fun rulesCloseSentencesAtPausesAndAskWithPersianMarks() {
        val words = listOf(word("چرا", 0, 300), word("رفتی", 300, 700), word("من", 1600, 1800), word("ماندم", 1800, 2200))
        val p = Marks.byRules(words, Language.Persian)
        assertEquals(listOf("چرا", "رفتی؟", "من", "ماندم."), p.words.map { it.text })
        assertEquals(listOf(false, true, false, true), p.sentenceEnds)
        assertEquals("،", Marks.localize(",", Language.Persian))
        assertEquals(",", Marks.localize(",", Language.English))
    }

    @Test
    fun ocrRunsReverseIntoLogicalOrderKeepingLatinRuns() {
        // The recogniser reads right-to-left script in visual order (left to right); Latin and digit runs stay as they are.
        assertEquals("رشد امروز", PpOcr.logical("زورما دشر"))
        assertEquals("رشد+5%", PpOcr.logical("+5%دشر"))
    }

    @Test
    fun ocrDecodesCtcAndFindsBoxes() {
        val ocr = PpOcr(listOf("ا", "ب"))
        // classes: blank, ا, ب, space; steps: ب ب blank ا → visual "ببا"? no: collapse → "ب" "ا" = "با" visually → logical "اب".
        val probs = floatArrayOf(0f, 0f, 1f, 0f, 0f, 0f, 1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f, 0f, 0f)
        assertEquals("اب", ocr.decode(probs, 4).first)
        val w = 40
        val h = 20
        val map = FloatArray(w * h)
        for (y in 5 until 10) for (x in 10 until 30) map[y * w + x] = 0.9f
        val boxes = ocr.boxes(map, w, h)
        assertEquals(1, boxes.size)
        val b = boxes.single().first
        assertTrue(b.x < 10f / w && b.right > 30f / w && b.y < 5f / h && b.bottom > 10f / h, "unclipped box $b")
    }

    @Test
    fun ocrReadsBackOurOwnTextFoldingPersianForms() {
        val seen = OcrText(listOf(TextLine(Box(0.1f, 0.1f, 0.5f, 0.1f), "سيگنال", 0.9f), TextLine(Box(0.1f, 0.2f, 0.5f, 0.1f), "خريد", 0.9f)), measured = true)
        assertEquals(1f, seen.readBack("سیگنال"))
        assertTrue(seen.readBack("سیگنال خرید") > 0.95f)
        assertTrue(seen.readBack("فروش") < 0.5f)
        assertTrue(seen.overlaps(Box(0.4f, 0.15f, 0.2f, 0.2f)))
    }

    @Test
    fun charsetComesFromTheYaml() {
        val yaml = "PostProcess:\n  name: CTCLabelDecode\n  character_dict:\n  - '!'\n  - ب\n  - ''''\n  - '0'\n"
        assertEquals(listOf("!", "ب", "'", "0"), PpOcr.charsetFromYaml(yaml))
    }

    @Test
    fun aestheticFeaturesSeeContrastAndClutter() {
        val flat = AestheticFeatures.of(Frame.solid(64, 64, 128, 128, 128))
        val px = ByteArray(64 * 64 * 3) { i -> if ((i / 3 % 64 + i / 3 / 64) % 2 == 0) 0 else 255.toByte() }
        val busy = AestheticFeatures.of(Frame(64, 64, px))
        val contrast = AestheticFeatures.NAMES.indexOf("contrast")
        val sharp = AestheticFeatures.NAMES.indexOf("sharpness")
        assertEquals(AestheticFeatures.NAMES.size, flat.size)
        assertTrue(busy[contrast] > flat[contrast] && busy[sharp] > flat[sharp])
    }

    @Test
    fun trainerLearnsARankingFromRatedFrames() {
        val rnd = Random(3)
        val d = AestheticFeatures.NAMES.size
        val data = (0 until 120).map {
            val f = FloatArray(d) { rnd.nextFloat() }
            // The jury likes contrast and dislikes covered faces.
            RatedFrame(f, (5f + 4f * f[1] - 3f * f[9] + (rnd.nextFloat() - 0.5f) * 0.5f).coerceIn(0f, 10f))
        }
        val head = AestheticTrainer().train(data)
        assertTrue(head.heldOutSpearman > 0.8f, "held-out spearman ${head.heldOutSpearman}")
        val round = AestheticHead.fromJson(head.toJson())
        val good = FloatArray(d) { 0.5f }.also { it[1] = 0.9f; it[9] = 0f }
        val bad = FloatArray(d) { 0.5f }.also { it[1] = 0.1f; it[9] = 1f }
        assertTrue(round.score(good) > round.score(bad))
    }

    @Test
    fun hitsAreSpacedAndPaceIsRelative() {
        val words = (0 until 10).map { word("کلمه", it * 400L, it * 400L + 350) }
        val emphasis = listOf(0.9f, 0.85f, 0.1f, 0.2f, 0.7f, 0.1f, 0.95f, 0.1f, 0.1f, 0.65f)
        val hits = Delivery.hits(words, emphasis)
        assertTrue(hits.zipWithNext().all { (a, b) -> words[b].range.startMs - words[a].range.startMs >= 1200 }, "$hits")
        assertTrue(6 in hits)
        val lines = Delivery.of(words, emphasis, null, listOf(0..4, 5..9))
        assertEquals(1f, lines[0].pace, 1e-3f)
        assertEquals(listOf(0, 1), lines[0].stressed)
    }
}
