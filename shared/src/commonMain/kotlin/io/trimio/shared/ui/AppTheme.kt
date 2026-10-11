package io.trimio.shared.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import io.trimio.core.data.AppSettings
import io.trimio.core.data.ThemeMode
import io.trimio.core.designsystem.signature.SignatureTheme
import io.trimio.core.designsystem.theme.TrimioPreferences
import io.trimio.core.model.text.Language

/** Whether the app wears paper (light) for the creator's choice: تیره, روشن or سیستم. */
@Composable
fun paperFor(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.Dark -> false
    ThemeMode.Light -> true
    ThemeMode.System -> !isSystemInDarkTheme()
}

/**
 * The app's look everywhere: Signature (noir by default, paper when chosen) over the base theme
 * that still serves older components (language and direction, preferences, haptics). The OS
 * accessibility settings and the app's own switches both count.
 */
@Composable
fun TrimioAppTheme(settings: AppSettings, systemLanguage: Language, systemPreferences: TrimioPreferences, content: @Composable () -> Unit) {
    val preferences = TrimioPreferences(
        reduceMotion = systemPreferences.reduceMotion || settings.reduceMotion,
        reduceTransparency = systemPreferences.reduceTransparency || settings.reduceTransparency,
        haptics = settings.haptics,
    )
    SignatureTheme(language = settings.language ?: systemLanguage, paper = paperFor(settings.theme), preferences = preferences, content = content)
}
