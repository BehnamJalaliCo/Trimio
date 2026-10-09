package io.trimio.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import io.trimio.shared.TrimioApp
import io.trimio.shared.uiLanguageFor
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // Reflects the per-app language chosen in system settings (Android 13+, see locales_config.xml).
        val language = uiLanguageFor(Locale.getDefault().toLanguageTag())
        setContent { TrimioApp(language) }
    }
}
