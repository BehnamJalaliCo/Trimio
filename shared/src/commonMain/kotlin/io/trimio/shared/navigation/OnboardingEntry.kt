package io.trimio.shared.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.trimio.core.data.DeviceInfo
import io.trimio.core.data.SettingsRepository
import io.trimio.core.data.StorageInfo
import io.trimio.engine.models.ModelKind
import io.trimio.engine.models.ModelManager
import io.trimio.engine.models.ModelState
import io.trimio.feature.studio.OnboardingDevice
import io.trimio.feature.studio.OnboardingScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.compose.getKoin
import org.koin.compose.koinInject

/**
 * First run with the real device and models: RAM, cores and free space from the platform, the
 * default models' download progress from the model manager, and the larger directors this device
 * could run later. Finishing (or connecting a cloud key) lands on home.
 */
@Composable
internal fun OnboardingEntry(scope: CoroutineScope, replaceTop: (Route) -> Unit, go: (Route) -> Unit) {
    val settingsRepo = koinInject<SettingsRepository>()
    val models = koinInject<ModelManager>()
    val device = koinInject<DeviceInfo>()
    val koin = getKoin()
    val storage = remember { koin.getOrNull<StorageInfo>() }
    val settings by settingsRepo.settings.collectAsStateWithLifecycle()
    val states by models.states.collectAsStateWithLifecycle()
    // Free space is read again whenever a model lands.
    val installed = states.values.count { it == ModelState.Installed }
    val free = remember(installed) { runCatching { storage?.freeBytes() }.getOrNull() }
    val catalog = models.catalog
    fun finish() {
        scope.launch { settingsRepo.update { it.copy(onboardingDone = true) } }
        replaceTop(Route.Studio)
    }
    OnboardingScreen(
        language = settings.language,
        defaults = if (device.previewOnly) emptyList() else catalog.filter { it.isDefault },
        states = states,
        device = OnboardingDevice(device.ramGb, device.cores, free),
        onLanguage = { lang -> scope.launch { settingsRepo.update { it.copy(language = lang) } } },
        onDownloadDefaults = models::downloadDefaults,
        onConnectCloud = {
            finish()
            go(Route.Settings)
        },
        onFinish = ::finish,
        stronger = if (device.previewOnly) {
            emptyList()
        } else {
            catalog.filter { it.kind == ModelKind.Language && !it.isDefault && !it.experimental && it.tier.minRamGb <= device.ramGb }
        },
    )
}
