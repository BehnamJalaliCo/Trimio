package io.trimio.engine.perception.matte

import io.trimio.engine.onnx.Network
import io.trimio.engine.onnx.Tensor
import io.trimio.engine.perception.Frame

/**
 * MediaPipe Selfie Segmentation (Google, Apache-2.0; the ONNX community export, 0.46 MB): where the person is in a frame,
 * for text set behind the speaker and the speaker cut out over a stage. The frame is stretched to the network's 256×256
 * RGB input (0..1, channels first); its one output is the person's alpha at 256×256.
 */
object SelfieMatte {
    const val SIZE = 256

    /** The network input for [frame]. */
    fun input(frame: Frame): Tensor {
        val small = frame.resized(SIZE, SIZE)
        val plane = SIZE * SIZE
        val data = FloatArray(Frame.CHANNELS * plane)
        for (y in 0 until SIZE) {
            for (x in 0 until SIZE) {
                val i = y * SIZE + x
                data[i] = small.r(x, y) / BYTE
                data[plane + i] = small.g(x, y) / BYTE
                data[2 * plane + i] = small.b(x, y) / BYTE
            }
        }
        return Tensor.of(longArrayOf(1, Frame.CHANNELS.toLong(), SIZE.toLong(), SIZE.toLong()), data)
    }

    private const val BYTE = 255f
}

/**
 * A person's matte over a run of frames from one clip: the network's alpha, steadied against the frame before (an edge
 * that flickers from frame to frame reads as a cut-out gone wrong) and hardened a little (the network's soft fringe
 * would let the wall show through the speaker's outline).
 */
class PersonMatte(private val network: Network) : AutoCloseable {
    private var last: FloatArray? = null
    private var lastTime = Float.NaN

    /** The person's alpha (0..1) on a [SelfieMatte.SIZE]² grid for [frame]. */
    fun alpha(frame: Frame): FloatArray {
        val input = network.inputNames.first()
        val raw = network.run(mapOf(input to SelfieMatte.input(frame))).values.first().requireFloats()
        val sharp = FloatArray(raw.size) { i -> ((raw[i] - LOW) / (HIGH - LOW)).coerceIn(0f, 1f) }
        // Only the frame right before steadies this one: after a cut or a seek the picture is another.
        val previous = last?.takeIf { !lastTime.isNaN() && frame.time - lastTime in 0f..STEADY_GAP }
        val out = if (previous == null) sharp else FloatArray(sharp.size) { i -> sharp[i] * (1f - STEADY) + previous[i] * STEADY }
        last = out
        lastTime = frame.time
        return out
    }

    override fun close() = network.close()

    /**
     * The top of the speaker's head across [frames] of a clip, as a share of the frame's height (the median of each
     * frame's: a head moves), or null when no frame shows a person. A frame's crown is the first row, from the top,
     * where the person fills a run of the middle third: a hand raised or a stray fleck of the wall is no head.
     */
    fun crown(frames: List<Frame>): Float? {
        val tops = frames.mapNotNull { f ->
            last = null
            val a = alpha(f)
            val n = SelfieMatte.SIZE
            val from = n / 3
            val to = n - n / 3
            (0 until n).firstOrNull { y -> (from until to).count { x -> a[y * n + x] > SOLID } >= MIN_RUN }?.let { it.toFloat() / n }
        }.sorted()
        return tops.getOrNull(tops.size / 2)
    }

    private companion object {
        /** Alpha under this is the wall, over [HIGH] the person; between, a short ramp. */
        const val LOW = 0.25f
        const val HIGH = 0.75f

        /** The share of the frame before in this one's matte. */
        const val STEADY = 0.35f

        /** Seconds between two frames that still count as one shot. */
        const val STEADY_GAP = 0.1f

        /** A pixel that is the person, and how many of them in a row make a head's outline. */
        const val SOLID = 0.5f
        const val MIN_RUN = 6
    }
}
