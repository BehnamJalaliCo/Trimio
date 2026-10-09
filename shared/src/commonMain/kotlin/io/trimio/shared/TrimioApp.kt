package io.trimio.shared

import androidx.compose.runtime.Composable
import io.trimio.core.designsystem.theme.TrimioTheme
import io.trimio.core.model.text.Language
import io.trimio.feature.stream.BuildStreamRoute
import io.trimio.feature.stream.BuildStreamViewModel
import io.trimio.shared.di.appModule
import org.koin.compose.KoinMultiplatformApplication
import org.koin.compose.viewmodel.koinViewModel
import org.koin.dsl.koinConfiguration

/** Root of the app on every platform. Each platform entry point passes its system language. */
@Composable
fun TrimioApp(language: Language) {
    KoinMultiplatformApplication(config = koinConfiguration { modules(appModule) }) {
        TrimioTheme(language = language) {
            BuildStreamRoute(koinViewModel<BuildStreamViewModel>())
        }
    }
}

/** Maps a BCP-47 language tag to a UI language; the primary market is Persian. */
fun uiLanguageFor(tag: String): Language = Language.fromCode(tag.substringBefore('-').substringBefore('_')) ?: Language.Persian
