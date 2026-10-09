package io.trimio.android

import android.Manifest
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import io.trimio.core.designsystem.theme.TrimioPreferences
import io.trimio.shared.TrimioApp
import io.trimio.shared.di.ActivityMediaPicker
import io.trimio.shared.uiLanguageFor
import org.koin.android.ext.android.get
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        get<ActivityMediaPicker>().attach(this)
        // Build and download progress live in notifications (Android 13+ asks once).
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)

        // Reflects the per-app language chosen in system settings (Android 13+, see locales_config.xml).
        val language = uiLanguageFor(Locale.getDefault().toLanguageTag())
        val animationsOff = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        setContent { TrimioApp(language, TrimioPreferences(reduceMotion = animationsOff)) }
    }
}
