package io.trimio.core.designsystem.haptics

import androidx.compose.runtime.Composable

/**
 * The app's whole haptic vocabulary (docs/DESIGN.md §7). Few, clear, meaningful effects;
 * nothing on ordinary scrolling. Implementations respect the user's touch-feedback setting.
 */
interface TrimioHaptics {
    /** Primary button press. */
    fun click()

    /** Crossing a word boundary while scrubbing; selection ticks. */
    fun tick()

    /** Snapping into place (trim handles, sheet detents). */
    fun snap()

    /** A finished build: rising tick-click-thud synchronised with the celebration animation. */
    fun success()

    /** Invalid action or error. */
    fun reject()

    companion object {
        val None: TrimioHaptics = object : TrimioHaptics {
            override fun click() = Unit
            override fun tick() = Unit
            override fun snap() = Unit
            override fun success() = Unit
            override fun reject() = Unit
        }
    }
}

/** Platform haptics bound to the current window. */
@Composable
expect fun rememberPlatformHaptics(): TrimioHaptics
