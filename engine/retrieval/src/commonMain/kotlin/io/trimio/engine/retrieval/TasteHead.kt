package io.trimio.engine.retrieval

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.exp
import kotlin.math.ln
import kotlin.random.Random

/**
 * A small head trained on the device's CPU from jury scores, in place of a fine-tune: logistic
 * regression on how a sentence and a candidate meet — the elementwise product of their unit
 * vectors (where they agree), their cosine, the BM25 and reranker scores — predicting whether
 * the jury gave the edit a good score with that pick. Seconds to train on a few thousand
 * examples; a few kilobytes to keep; ~a microsecond per candidate.
 */
@Serializable
class TasteHead(val dims: Int, val weights: FloatArray, val bias: Float = 0f, val trained: Int = 0) {
    /** One labelled pair: the sentence's and the pick's vectors, the other signals, and whether the jury liked it. */
    class Example(val query: FloatArray, val pick: FloatArray, val signals: FloatArray, val good: Boolean)

    /** P(the jury likes this pick for this sentence). */
    fun predict(query: FloatArray, pick: FloatArray, signals: FloatArray): Float = sigmoid(raw(query, pick, signals))

    private fun raw(query: FloatArray, pick: FloatArray, signals: FloatArray): Float {
        val x = features(query, pick, signals, dims)
        var z = bias
        for (j in x.indices) z += weights[j] * x[j]
        return z
    }

    fun write(): String = json.encodeToString(serializer(), this)

    companion object {
        /** Signals beside the vectors: cosine, BM25 squash, rerank P(yes). */
        const val SIGNALS = 3
        private val json = Json { ignoreUnknownKeys = true }

        fun read(text: String): TasteHead = json.decodeFromString(serializer(), text)

        private fun sigmoid(z: Float) = (1.0 / (1.0 + exp(-z.toDouble()))).toFloat()

        /** Agreement per dimension (scaled so each is about ±1, the cosine being their mean), then the signals. */
        private fun features(query: FloatArray, pick: FloatArray, signals: FloatArray, dims: Int): FloatArray {
            val x = FloatArray(dims + SIGNALS)
            for (j in 0 until dims) x[j] = query[j] * pick[j] * dims
            for (j in 0 until minOf(SIGNALS, signals.size)) x[dims + j] = signals[j]
            return x
        }

        /**
         * Trains by stochastic gradient descent with L2 decay ([epochs] passes, shuffled with [seed]),
         * balancing good and bad examples so a mostly-good archive does not teach "always yes".
         */
        fun train(examples: List<Example>, dims: Int, epochs: Int = 30, rate: Float = 0.05f, decay: Float = 1e-3f, seed: Int = 7): TasteHead {
            val w = FloatArray(dims + SIGNALS)
            var b = 0f
            val pos = examples.count { it.good }.coerceAtLeast(1)
            val neg = (examples.size - pos).coerceAtLeast(1)
            val rnd = Random(seed)
            val order = examples.indices.toMutableList()
            repeat(epochs) { epoch ->
                order.shuffle(rnd)
                val lr = rate / (1 + epoch * 0.1f)
                for (i in order) {
                    val e = examples[i]
                    val weight = if (e.good) examples.size / (2f * pos) else examples.size / (2f * neg)
                    val x = features(e.query, e.pick, e.signals, dims)
                    var z = b
                    for (j in x.indices) z += w[j] * x[j]
                    val g = (sigmoid(z) - if (e.good) 1f else 0f) * weight
                    for (j in x.indices) w[j] -= lr * (g * x[j] + decay * w[j])
                    b -= lr * g
                }
            }
            return TasteHead(dims, w, b, examples.size)
        }

        /** Mean log loss of [head] on [examples] (for tests and the trainer's report). */
        fun loss(head: TasteHead, examples: List<Example>): Float = examples.map { e ->
            val p = head.predict(e.query, e.pick, e.signals).coerceIn(1e-6f, 1 - 1e-6f)
            -ln(if (e.good) p else 1 - p)
        }.average().toFloat()
    }
}

/**
 * Collects what the jury thought of each pick (the line, the picture's id, the signals it was
 * picked with, and whether the edit's relevance judges scored it well) and trains the [TasteHead]
 * on the device's CPU once enough has been seen. Examples keep text and ids, not vectors: lines are
 * embedded again when training, pictures come from the index.
 */
class TasteTrainer(private val store: MemoryStore, private val capacity: Int = CAPACITY) {
    @Serializable
    data class Seen(val line: String, val pick: String, val signals: List<Float>, val good: Boolean)

    @Serializable
    private data class File(val seen: List<Seen> = emptyList())

    private val lock = Mutex()
    private var cached: List<Seen>? = null
    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun load(): List<Seen> = cached ?: (store.read()?.let { runCatching { json.decodeFromString(File.serializer(), it).seen }.getOrNull() }.orEmpty()).also { cached = it }

    suspend fun seen(): List<Seen> = lock.withLock { load() }

    /** Adds the picks of one judged edit; the oldest fall out past [capacity]. */
    suspend fun record(picks: List<Seen>) = lock.withLock {
        val next = (load() + picks).takeLast(capacity)
        cached = next
        store.write(json.encodeToString(File.serializer(), File(next)))
    }

    /**
     * A head trained on what was seen, or null with too few examples of either kind. [embedLines]
     * embeds the lines (the retrieval's embedder, query role); pictures absent from [index] are skipped.
     */
    suspend fun train(index: CorpusIndex, embedLines: suspend (List<String>) -> List<FloatArray>): TasteHead? {
        val all = seen()
        val vectors = index.vectors ?: return null
        val usable = all.mapNotNull { s -> index.indexOf(s.pick)?.let { s to it } }
        if (usable.count { it.first.good } < MIN_EACH || usable.count { !it.first.good } < MIN_EACH) return null
        val lines = usable.map { it.first.line }.distinct()
        val byLine = lines.zip(embedLines(lines)).toMap()
        val examples = usable.map { (s, i) -> TasteHead.Example(byLine.getValue(s.line), vectors.vector(i), s.signals.toFloatArray(), s.good) }
        return TasteHead.train(examples, vectors.dims)
    }

    private companion object {
        const val CAPACITY = 4000
        const val MIN_EACH = 20
    }
}
