package io.trimio.engine.motion

import androidx.compose.ui.graphics.Color

/** What a text animator moves independently. */
enum class TextUnit { Char, Word, Line, All }

/** The order units start in, relative to reading order. */
enum class Order { Forward, Backward, CenterOut, EdgesIn, Random }

/** How progress maps to the animated state. */
enum class Shape {
    /** From [TextAnimator.state] to rest (entrances). */
    In,
    /** From rest to [TextAnimator.state] (exits). */
    Out,
    /** Rest → state → rest (pops on the spoken word, beat pulses). */
    Pulse,
}

/**
 * The offset of one unit from its resting place. Distances and blur are in em (multiples of the
 * font size), so recipes read the same at any size.
 */
data class UnitState(
    val dx: Float = 0f,
    val dy: Float = 0f,
    val scale: Float = 1f,
    val scaleX: Float = 1f,
    val scaleY: Float = 1f,
    /** Degrees. */
    val rotation: Float = 0f,
    /** Degrees, 3D flip around the unit's baseline. */
    val rotationX: Float = 0f,
    val opacity: Float = 1f,
    val blur: Float = 0f,
) {
    fun mix(p: Float): UnitState = UnitState(
        dx = dx * (1 - p), dy = dy * (1 - p),
        scale = scale + (1 - scale) * p, scaleX = scaleX + (1 - scaleX) * p, scaleY = scaleY + (1 - scaleY) * p,
        rotation = rotation * (1 - p), rotationX = rotationX * (1 - p),
        opacity = opacity + (1 - opacity) * p, blur = blur * (1 - p),
    )

    /** Layers two states (entrance + pulse + exit on the same unit). */
    operator fun plus(o: UnitState) = UnitState(
        dx + o.dx, dy + o.dy, scale * o.scale, scaleX * o.scaleX, scaleY * o.scaleY,
        rotation + o.rotation, rotationX + o.rotationX, opacity * o.opacity, blur + o.blur,
    )

    val isRest: Boolean get() = this == REST

    companion object {
        val REST = UnitState()
    }
}

/**
 * Animates the units of a text node: unit i starts at `at + rank(i) * stagger` (or at `times[i]`
 * when given — word-synced captions), runs for [duration] with [ease].
 */
data class TextAnimator(
    val unit: TextUnit,
    val state: UnitState,
    val at: Float,
    val duration: Float = 0.5f,
    val stagger: Float = 0.05f,
    val ease: Easing = Easing.Enter,
    val order: Order = Order.Forward,
    val shape: Shape = Shape.In,
    /** Absolute start per unit, overriding [at]/[stagger]/[order]. */
    val times: List<Float>? = null,
    /** Units travel behind their line's box (the classic mask rise). */
    val clipToLine: Boolean = false,
    val seed: Int = 3,
) {
    fun startOf(index: Int, count: Int): Float {
        times?.let { ts -> return ts.getOrElse(index) { ts.lastOrNull() ?: at } }
        val rank = when (order) {
            Order.Forward -> index
            Order.Backward -> count - 1 - index
            Order.CenterOut -> kotlin.math.abs(index - (count - 1) / 2f).let { (it * 2).toInt() / 2 }
            Order.EdgesIn -> minOf(index, count - 1 - index)
            Order.Random -> shuffled(count)[index]
        }
        return at + rank * stagger
    }

    fun stateAt(t: Float, index: Int, count: Int): UnitState {
        val s = startOf(index, count)
        val raw = ((t - s) / duration).coerceIn(0f, 1f)
        return when (shape) {
            Shape.In -> if (raw >= 1f) UnitState.REST else state.mix(ease.at(raw))
            Shape.Out -> if (raw <= 0f) UnitState.REST else state.mix(1f - ease.at(raw))
            Shape.Pulse -> if (raw <= 0f || raw >= 1f) UnitState.REST else state.mix(1f - kotlin.math.sin(raw * kotlin.math.PI.toFloat()).let { ease.at(it) })
        }
    }

    private fun shuffled(count: Int): IntArray {
        val ranks = IntArray(count) { it }
        var r = seed * 1103515245 + 12345
        for (i in count - 1 downTo 1) {
            r = r * 1103515245 + 12345
            val j = ((r ushr 8) and 0x7FFFFF) % (i + 1)
            val tmp = ranks[i]; ranks[i] = ranks[j]; ranks[j] = tmp
        }
        return ranks
    }
}

/** Per-word marks: how the eye is pulled to what matters. Ranges are word indices, inclusive. */
sealed interface Decoration {
    val words: IntRange
    val at: Float

    /** Highlighter block wiping in reading order; the text it covers flips to [textColor]. */
    data class Block(
        override val words: IntRange,
        override val at: Float,
        val color: Color,
        val textColor: Color,
        val duration: Float = 0.32f,
        val ease: Easing = Easing.Standard,
        /** em */
        val padX: Float = 0.12f,
        val padTop: Float = -0.02f,
        val padBottom: Float = 0.02f,
        val radius: Float = 0.06f,
        /** Degrees of slant on the block, for a marker feel. */
        val skew: Float = 0f,
    ) : Decoration

    /** A stroke drawn under the words in reading order. */
    data class Underline(
        override val words: IntRange,
        override val at: Float,
        val color: Color,
        val duration: Float = 0.35f,
        val thickness: Float = 0.08f,
        /** em below the baseline. */
        val offset: Float = 0.14f,
        val ease: Easing = Easing.Enter,
    ) : Decoration

    /** The words change colour (karaoke ink). */
    data class Ink(
        override val words: IntRange,
        override val at: Float,
        val color: Color,
        val duration: Float = 0.12f,
    ) : Decoration

    /** A hand-drawn loop around the words, drawn on. */
    data class Circle(
        override val words: IntRange,
        override val at: Float,
        val color: Color,
        val duration: Float = 0.55f,
        val thickness: Float = 0.07f,
    ) : Decoration
}
