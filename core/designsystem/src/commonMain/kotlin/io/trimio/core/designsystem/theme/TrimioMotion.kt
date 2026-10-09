package io.trimio.core.designsystem.theme

import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Motion tokens from docs/DESIGN.md §4. Springs only, so every animation is interruptible and
 * continues from its current velocity. Spatial motion may overshoot slightly; effects (colour,
 * alpha) never do.
 *
 * With reduced motion every spatial spring becomes near-instant and effects become short fades.
 */
@Immutable
class TrimioMotionScheme internal constructor(private val reduced: Boolean) {

    fun <T> spatialFast(): FiniteAnimationSpec<T> = spatial(0.8f, 1400f)
    fun <T> spatial(): FiniteAnimationSpec<T> = spatial(0.85f, 700f)
    fun <T> spatialSlow(): FiniteAnimationSpec<T> = spatial(0.9f, 300f)

    fun <T> effectsFast(): FiniteAnimationSpec<T> = effects(3800f)
    fun <T> effects(): FiniteAnimationSpec<T> = effects(1600f)

    /** Signature moments only: words arriving, completion, celebration. */
    fun <T> expressive(): FiniteAnimationSpec<T> = spatial(0.6f, 500f)

    /** Delay between list items entering; capped by [STAGGER_MAX_ITEMS]. */
    fun staggerMs(index: Int): Int = if (reduced) 0 else minOf(index, STAGGER_MAX_ITEMS) * STAGGER_MS

    val isReduced: Boolean get() = reduced

    private fun <T> spatial(damping: Float, stiffness: Float): FiniteAnimationSpec<T> =
        if (reduced) spring(Spring.DampingRatioNoBouncy, Spring.StiffnessHigh * 10) else spring(damping, stiffness)

    private fun <T> effects(stiffness: Float): FiniteAnimationSpec<T> =
        if (reduced) tween(120) else spring(Spring.DampingRatioNoBouncy, stiffness)

    companion object {
        const val STAGGER_MS = 40
        const val STAGGER_MAX_ITEMS = 8
        val Standard = TrimioMotionScheme(reduced = false)
        val Reduced = TrimioMotionScheme(reduced = true)
    }
}

object TrimioSpacing {
    val xxs: Dp = 2.dp
    val xs: Dp = 4.dp
    val sm: Dp = 8.dp
    val md: Dp = 12.dp
    val lg: Dp = 16.dp
    val xl: Dp = 24.dp
    val xxl: Dp = 32.dp
    val screenGutter: Dp = 20.dp

    /** Minimum touch target (accessibility). */
    val touchTarget: Dp = 48.dp
}

object TrimioRadius {
    val sm: Dp = 10.dp
    val md: Dp = 16.dp
    val lg: Dp = 24.dp
    val xl: Dp = 32.dp
}

/** Depth levels from docs/DESIGN.md §2. */
enum class Depth(val shadow: Dp) {
    Canvas(0.dp),
    Surface(0.dp),
    Glass(24.dp),
    Floating(40.dp),
}
