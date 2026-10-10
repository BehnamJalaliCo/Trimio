package io.trimio.android

import android.Manifest
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import io.trimio.android.billing.ActivityBilling
import io.trimio.core.data.Billing
import io.trimio.core.designsystem.theme.TrimioPreferences
import io.trimio.shared.TrimioApp
import io.trimio.shared.di.ActivityMediaPicker
import io.trimio.shared.uiLanguageFor
import org.koin.android.ext.android.get
import java.util.Locale

class MainActivity : ComponentActivity() {
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        applyModelUpdatesIfAsked(intent)
    }

    /** From the "update" action of a model-update notification: the app is visible, so downloads may start. */
    private fun applyModelUpdatesIfAsked(intent: Intent?) {
        if (intent?.getBooleanExtra(Notifications.EXTRA_APPLY_MODEL_UPDATES, false) != true) return
        intent.removeExtra(Notifications.EXTRA_APPLY_MODEL_UPDATES)
        get<io.trimio.engine.models.ModelManager>().applyAllUpdates()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        get<ActivityMediaPicker>().attach(this)
        (get<Billing>() as? ActivityBilling)?.attach(this)
        applyModelUpdatesIfAsked(intent)
        // Build and download progress live in notifications (Android 13+ asks once).
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {}.launch(Manifest.permission.POST_NOTIFICATIONS)

        // Reflects the per-app language chosen in system settings (Android 13+, see locales_config.xml).
        val language = uiLanguageFor(Locale.getDefault().toLanguageTag())
        val animationsOff = Settings.Global.getFloat(contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
        setContent { TrimioApp(language, TrimioPreferences(reduceMotion = animationsOff)) }
    }
}
