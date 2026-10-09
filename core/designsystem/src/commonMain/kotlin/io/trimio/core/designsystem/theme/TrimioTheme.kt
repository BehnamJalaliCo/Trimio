package io.trimio.core.designsystem.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.platform.LocalLayoutDirection
import io.trimio.core.model.text.Language
import io.trimio.core.model.text.Numerals

private val LocalTrimioColors = staticCompositionLocalOf { DarkTrimioColors }
private val LocalTrimioTypography = staticCompositionLocalOf<TrimioTypography> { error("TrimioTheme not applied") }
private val LocalTrimioLanguage = staticCompositionLocalOf { Language.Persian }

/**
 * Root theme. Sets colours, type, UI language and layout direction (Persian is RTL from the root,
 * not mirrored per screen). Material 3 components inherit a matching colour scheme.
 */
@Composable
fun TrimioTheme(
    language: Language = Language.Persian,
    dark: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colors = if (dark) DarkTrimioColors else LightTrimioColors
    val typography = trimioTypography()
    val material = if (dark) {
        darkColorScheme(
            primary = colors.primary, onPrimary = colors.onPrimary, background = colors.canvas,
            surface = colors.canvasRaised, onSurface = colors.textPrimary, error = colors.danger,
        )
    } else {
        lightColorScheme(
            primary = colors.primary, onPrimary = colors.onPrimary, background = colors.canvas,
            surface = colors.canvasRaised, onSurface = colors.textPrimary, error = colors.danger,
        )
    }
    CompositionLocalProvider(
        LocalTrimioColors provides colors,
        LocalTrimioTypography provides typography,
        LocalTrimioLanguage provides language,
        LocalLayoutDirection provides if (language.isRtl) LayoutDirection.Rtl else LayoutDirection.Ltr,
    ) {
        MaterialTheme(colorScheme = material, content = content)
    }
}

object Trimio {
    val colors: TrimioColors
        @Composable @ReadOnlyComposable get() = LocalTrimioColors.current
    val type: TrimioTypography
        @Composable @ReadOnlyComposable get() = LocalTrimioTypography.current
    val language: Language
        @Composable @ReadOnlyComposable get() = LocalTrimioLanguage.current
}

/** Picks the string for the current UI language. */
@Composable
@ReadOnlyComposable
fun tr(fa: String, en: String): String = if (Trimio.language == Language.Persian) fa else en

/** Localises digits for the current UI language (۴۲٪ vs 42%). */
@Composable
@ReadOnlyComposable
fun localizedNumber(text: String): String = Numerals.localize(text, Trimio.language)
