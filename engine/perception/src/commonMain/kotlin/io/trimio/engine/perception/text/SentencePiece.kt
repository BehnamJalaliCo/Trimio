package io.trimio.engine.perception.text

/**
 * A SentencePiece unigram tokenizer read straight from its `.model` protobuf, for models whose
 * normaliser is `identity` (no NFKC table): whitespace is collapsed, a "▁" dummy prefix added and
 * spaces escaped as "▁", then the highest-scoring segmentation is found by Viterbi over the
 * pieces, exactly as `SentencePieceProcessor.encode` does for such a model. Characters no piece
 * covers become `<unk>` (consecutive ones merge into one, as upstream).
 */
class SentencePiece private constructor(
    private val pieces: Map<String, Int>,
    private val scores: FloatArray,
    private val texts: List<String>,
    val unk: Int,
    val bos: Int,
    val eos: Int,
    val pad: Int,
) {
    private val longest = pieces.keys.maxOf { it.length }
    private val unkScore = scores.filterIndexed { i, _ -> i != unk }.minOrNull()?.minus(UNK_PENALTY) ?: -UNK_PENALTY

    val size: Int get() = texts.size

    fun piece(id: Int): String = texts[id]

    /** Token ids of [text] (no BOS/EOS). */
    fun encode(text: String): IntArray {
        val norm = WS.replace(text.trim(), " ")
        if (norm.isEmpty()) return IntArray(0)
        val s = SPACE + norm.replace(' ', SPACE)
        val n = s.length
        val best = FloatArray(n + 1) { Float.NEGATIVE_INFINITY }
        val from = IntArray(n + 1)
        val id = IntArray(n + 1)
        best[0] = 0f
        for (end in 1..n) {
            for (len in 1..minOf(longest, end)) {
                val start = end - len
                val piece = pieces[s.substring(start, end)]
                // A single uncovered character may be <unk>; longer unknown strings are not candidates.
                val score = piece?.let { scores[it] } ?: unkScore.takeIf { len == 1 } ?: Float.NEGATIVE_INFINITY
                val total = best[start] + score
                if (total > best[end]) {
                    best[end] = total
                    from[end] = start
                    id[end] = piece ?: unk
                }
            }
        }
        val out = ArrayList<Int>()
        var end = n
        while (end > 0) {
            out += id[end]
            end = from[end]
        }
        out.reverse()
        // Upstream merges runs of unknown characters into one <unk>.
        return out.filterIndexed { i, t -> !(t == unk && i > 0 && out[i - 1] == unk) }.toIntArray()
    }

    companion object {
        const val SPACE = '▁'
        private const val UNK_PENALTY = 10f
        private val WS = Regex("\\s+")
        private const val NORMAL = 1
        private const val UNKNOWN = 2
        private const val CONTROL = 3
        private const val USER_DEFINED = 4

        /** Parses a `.model` file (ModelProto: field 1 = repeated SentencePiece {1 piece, 2 score, 3 type}). */
        fun load(bytes: ByteArray): SentencePiece {
            val texts = ArrayList<String>()
            val scores = ArrayList<Float>()
            val types = ArrayList<Int>()
            val top = Proto(bytes, 0, bytes.size)
            while (top.more()) {
                val (field, wire) = top.key()
                if (field == 1 && wire == Proto.LEN) {
                    val msg = top.message()
                    var text = ""
                    var score = 0f
                    var type = NORMAL
                    while (msg.more()) {
                        val (f, w) = msg.key()
                        when {
                            f == 1 && w == Proto.LEN -> text = msg.string()
                            f == 2 && w == Proto.FIXED32 -> score = Float.fromBits(msg.fixed32())
                            f == 3 && w == Proto.VARINT -> type = msg.varint().toInt()
                            else -> msg.skip(w)
                        }
                    }
                    texts += text
                    scores += score
                    types += type
                } else {
                    top.skip(wire)
                }
            }
            require(texts.isNotEmpty()) { "no pieces in the SentencePiece model" }
            // Control pieces (<s>, </s>, <pad>) and <unk> never match text.
            val matchable = texts.indices.filter { types[it] == NORMAL || types[it] == USER_DEFINED }.associateBy { texts[it] }
            fun idOf(t: String, type: Int) = texts.indices.firstOrNull { texts[it] == t && types[it] == type } ?: -1
            return SentencePiece(
                matchable, scores.toFloatArray(), texts,
                unk = texts.indices.firstOrNull { types[it] == UNKNOWN } ?: 0,
                bos = idOf("<s>", CONTROL), eos = idOf("</s>", CONTROL), pad = idOf("<pad>", CONTROL),
            )
        }
    }
}

/** Just enough protobuf wire-format reading for tokenizer files. */
internal class Proto(private val b: ByteArray, private var pos: Int, private val end: Int) {
    fun more() = pos < end

    fun key(): Pair<Int, Int> {
        val k = varint().toInt()
        return (k ushr 3) to (k and 7)
    }

    fun varint(): Long {
        var shift = 0
        var result = 0L
        while (true) {
            val byte = b[pos++].toInt() and BYTE
            result = result or ((byte and LOW7).toLong() shl shift)
            if (byte and HIGH == 0) return result
            shift += 7
        }
    }

    fun fixed32(): Int {
        var v = 0
        for (i in 0 until 4) v = v or ((b[pos + i].toInt() and BYTE) shl (8 * i))
        pos += 4
        return v
    }

    fun message(): Proto {
        val len = varint().toInt()
        return Proto(b, pos, pos + len).also { pos += len }
    }

    fun string(): String {
        val len = varint().toInt()
        return b.decodeToString(pos, pos + len).also { pos += len }
    }

    fun skip(wire: Int) {
        when (wire) {
            VARINT -> varint()
            FIXED64 -> pos += 8
            LEN -> {
                // Read the length first: `pos += varint()` would add it to the position before the varint.
                val len = varint().toInt()
                pos += len
            }
            FIXED32 -> pos += 4
            else -> error("unsupported wire type $wire")
        }
    }

    companion object {
        const val VARINT = 0
        const val FIXED64 = 1
        const val LEN = 2
        const val FIXED32 = 5
        private const val BYTE = 0xFF
        private const val LOW7 = 0x7F
        private const val HIGH = 0x80
    }
}
