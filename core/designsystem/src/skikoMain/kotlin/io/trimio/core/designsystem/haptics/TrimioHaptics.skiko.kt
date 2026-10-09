package io.trimio.core.designsystem.haptics

import androidx.compose.runtime.Composable

/** Desktop and web have no haptic actuator; iOS will map to UIFeedbackGenerator when it ships. */
@Composable
actual fun rememberPlatformHaptics(): TrimioHaptics = TrimioHaptics.None
