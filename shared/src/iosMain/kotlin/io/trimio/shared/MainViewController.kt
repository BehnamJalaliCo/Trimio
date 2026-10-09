package io.trimio.shared

import androidx.compose.ui.window.ComposeUIViewController
import io.trimio.shared.di.previewPlatformModule
import platform.Foundation.NSLocale
import platform.Foundation.currentLocale
import platform.Foundation.languageCode
import platform.UIKit.UIViewController

private var started = false

/**
 * iOS entry point, embedded by the Xcode app (`iosApp/`). Engines are wired in phase 11
 * (AVFoundation, Metal, the shared C++ engines); until then the full UI runs on the preview pipeline.
 */
fun MainViewController(): UIViewController {
    if (!started) {
        initTrimio(previewPlatformModule("iOS"))
        started = true
    }
    return ComposeUIViewController { TrimioApp(uiLanguageFor(NSLocale.currentLocale.languageCode)) }
}
