package io.trimio.shared.navigation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.trimio.core.data.Connectivity
import io.trimio.core.data.DeviceInfo
import io.trimio.core.data.SettingsRepository
import io.trimio.core.designsystem.signature.gutter
import io.trimio.core.pipeline.DirectorBackend
import io.trimio.engine.models.DeviceMemory
import io.trimio.engine.models.ModelManager
import io.trimio.engine.production.DirectorChoice
import io.trimio.engine.production.ProductionRequest
import io.trimio.feature.settings.ModelShelf
import io.trimio.feature.settings.OfflineDirector
import io.trimio.feature.settings.OfflineSheet
import io.trimio.feature.settings.WaitingForNetwork
import io.trimio.feature.settings.designSeconds
import io.trimio.feature.settings.installed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.compose.getKoin
import org.koin.compose.koinInject

/**
 * Where a build starts: the settings' director goes into the order (the cloud when chosen), and a
 * cloud build asked for without internet stops at the offline sheet: continue with the director on
 * the device, or wait and start in the cloud once the connection is back.
 */
class DirectorGate internal constructor(
    private val settings: SettingsRepository,
    private val connectivity: Connectivity,
    private val scope: CoroutineScope,
) {
    /** [device] is the on-device director the order had before it went to the cloud (the chosen speed). */
    private class Pending(val request: ProductionRequest, val mediaSeconds: Float, val go: (ProductionRequest) -> Unit, val device: DirectorChoice = DirectorChoice.Auto)

    private var asked by mutableStateOf<Pending?>(null)
    private var waiting by mutableStateOf<Pending?>(null)
    private var waiter: Job? = null

    /** Starts [request] through [go], with the chosen director; offline cloud builds ask first. */
    fun start(request: ProductionRequest, mediaSeconds: Float, go: (ProductionRequest) -> Unit) {
        if (settings.settings.value.director != DirectorBackend.Cloud) return go(request)
        val cloud = request.copy(director = DirectorChoice.Cloud)
        scope.launch { if (online()) go(cloud) else asked = Pending(cloud, mediaSeconds, go, request.director) }
    }

    private suspend fun online() = runCatching { connectivity.online() }.getOrDefault(false)

    private fun onDevice() {
        val p = asked ?: return
        asked = null
        p.go(p.request.copy(director = p.device))
    }

    private fun waitForNetwork() {
        val p = asked ?: return
        asked = null
        waiting = p
        waiter?.cancel()
        waiter = scope.launch {
            while (!online()) delay(RETRY_MS)
            waiting = null
            p.go(p.request)
        }
    }

    private fun stopWaiting() {
        waiter?.cancel()
        waiting = null
    }

    /** The sheet (or the waiting bar) over the screen that starts builds. */
    @Composable
    fun Overlay(director: OfflineDirector?) {
        val p = asked
        if (p != null) {
            OfflineSheet(director?.copy(seconds = designSeconds(p.mediaSeconds)) ?: OfflineDirector(null, designSeconds(p.mediaSeconds)), ::onDevice, ::waitForNetwork, onDismiss = { asked = null })
        }
        if (waiting != null) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.navigationBars), contentAlignment = Alignment.BottomCenter) {
                WaitingForNetwork(::stopWaiting, Modifier.gutter().padding(bottom = 84.dp))
            }
        }
    }

    private companion object {
        const val RETRY_MS = 3_000L
    }
}

/** A gate for the create screen, and the screen with the gate's sheet over it. */
@Composable
internal fun DirectorGated(content: @Composable BoxScope.(DirectorGate) -> Unit) {
    val koin = getKoin()
    val settings = koinInject<SettingsRepository>()
    val scope = rememberCoroutineScope()
    val gate = remember { DirectorGate(settings, koin.getOrNull<Connectivity>() ?: Connectivity.Always, scope) }
    val models = koinInject<ModelManager>()
    val device = koinInject<DeviceInfo>()
    Box(Modifier.fillMaxSize()) {
        content(gate)
        val s = settings.settings.value
        val desktop = listOf("Android", "iOS", "Web").none { device.platform.startsWith(it) }
        val shelf = ModelShelf.build(models.catalog, models.states.value, DeviceMemory(device.ramGb, desktop), s.localModelId, s.speechModelId)
        val tier = shelf.directors.firstOrNull { it.active && it.installed }?.tier
        gate.Overlay(OfflineDirector(tier, 0))
    }
}
