package io.trimio.core.designsystem.theme

import androidx.compose.runtime.Immutable

/**
 * System accessibility settings the design system honours (docs/DESIGN.md §12).
 * Each platform entry point reads them from the OS and passes them to [TrimioTheme].
 */
@Immutable
data class TrimioPreferences(
    /** Animations off or scaled to zero in system settings. */
    val reduceMotion: Boolean = false,
    /** Glass becomes solid high-contrast surfaces; also used on GPUs too weak for blur. */
    val reduceTransparency: Boolean = false,
    /** User switch in settings; system haptic settings are always honoured as well. */
    val haptics: Boolean = true,
)
