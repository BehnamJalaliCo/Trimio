package io.trimio.core.designsystem.haptics

import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.view.HapticFeedbackConstants
import android.view.View
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalView

@Composable
actual fun rememberPlatformHaptics(): TrimioHaptics {
    val view = LocalView.current
    return remember(view) { AndroidHaptics(view) }
}

/**
 * Prefers View haptic constants (no VIBRATE permission, follows the system touch-feedback setting).
 * The success effect uses composition primitives when the device's vibrator supports them,
 * as recommended for rich haptics; otherwise it falls back to a confirm constant.
 */
private class AndroidHaptics(private val view: View) : TrimioHaptics {
    private val vibrator: Vibrator? = view.context.getSystemService(Vibrator::class.java)

    override fun click() {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    override fun tick() {
        view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
    }

    override fun snap() {
        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            HapticFeedbackConstants.SEGMENT_TICK
        } else {
            HapticFeedbackConstants.CONTEXT_CLICK
        }
        view.performHapticFeedback(effect)
    }

    override fun reject() {
        view.performHapticFeedback(HapticFeedbackConstants.REJECT)
    }

    override fun success() {
        val primitives = intArrayOf(
            VibrationEffect.Composition.PRIMITIVE_TICK,
            VibrationEffect.Composition.PRIMITIVE_CLICK,
            VibrationEffect.Composition.PRIMITIVE_THUD,
        )
        val v = vibrator
        if (v != null && view.isHapticFeedbackEnabled && v.areAllPrimitivesSupported(*primitives)) {
            v.vibrate(
                VibrationEffect.startComposition()
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_TICK, 0.4f)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_CLICK, 0.7f, 60)
                    .addPrimitive(VibrationEffect.Composition.PRIMITIVE_THUD, 1f, 90)
                    .compose(),
            )
        } else {
            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
        }
    }
}
