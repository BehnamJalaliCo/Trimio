package io.trimio.core.model.input

import kotlinx.serialization.Serializable
import kotlin.math.abs

/** Output frame geometry. */
@Serializable
data class CanvasSpec(
    val aspect: AspectRatio = AspectRatio.Portrait9x16,
    val resolution: Resolution = Resolution.FullHd,
    val frameRate: Int = 30,
) {
    init {
        require(frameRate in SUPPORTED_FRAME_RATES) { "Unsupported frame rate $frameRate" }
    }

    /** Pixel size; the short edge equals [Resolution.shortEdge], both edges rounded to even for encoders. */
    val widthPx: Int get() = size().first
    val heightPx: Int get() = size().second

    private fun size(): Pair<Int, Int> {
        val short = resolution.shortEdge
        val long = (short.toLong() * aspect.long / aspect.short).toInt().roundToEven()
        return if (aspect.isPortrait) short to long else long to short
    }

    private fun Int.roundToEven() = if (this % 2 == 0) this else this + 1

    companion object {
        val SUPPORTED_FRAME_RATES = setOf(24, 25, 30, 50, 60)
    }
}

@Serializable
enum class AspectRatio(val w: Int, val h: Int) {
    Portrait9x16(9, 16),
    Portrait4x5(4, 5),
    Square1x1(1, 1),
    Landscape16x9(16, 9);

    val isPortrait: Boolean get() = w <= h
    internal val short: Int get() = minOf(w, h)
    internal val long: Int get() = maxOf(w, h)
    val ratio: Float get() = w.toFloat() / h

    companion object {
        fun closestTo(width: Int, height: Int): AspectRatio {
            val r = width.toFloat() / height
            return entries.minBy { abs(it.ratio - r) }
        }
    }
}

@Serializable
enum class Resolution(val shortEdge: Int) {
    Hd(720),
    FullHd(1080),
    Uhd4k(2160),
}
