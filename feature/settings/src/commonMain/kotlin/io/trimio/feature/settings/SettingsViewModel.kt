package io.trimio.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.trimio.core.data.AppSettings
import io.trimio.core.data.CrashLog
import io.trimio.core.data.DeviceInfo
import io.trimio.core.data.EngineFacts
import io.trimio.core.data.Entitlements
import io.trimio.core.data.PurchaseResult
import io.trimio.core.data.SettingsRepository
import io.trimio.core.data.Sharer
import io.trimio.core.data.StoreProduct
import io.trimio.core.pipeline.DirectorBackend
import io.trimio.engine.llm.CloudModels
import io.trimio.engine.llm.CloudProvider
import io.trimio.engine.models.ModelManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsViewModel(
    private val settings: SettingsRepository,
    val models: ModelManager,
    private val cloud: CloudModels,
    val device: DeviceInfo,
    private val crashLog: CrashLog? = null,
    private val sharer: Sharer? = null,
    private val entitlements: Entitlements? = null,
    /** The engines as the latest build measured them, for the models screen's hardware line. */
    val engine: EngineFacts? = null,
    /** The CPU threads models run on here, shown before any build has measured the engine. */
    val threads: Int? = null,
    /** What the on-demand animations folder holds on disk, in bytes. */
    private val animationBytes: () -> Long = { 0L },
    /** «پاک کردن کش انیمیشن‌ها». */
    private val clearAnimations: suspend () -> Unit = {},
    /** «سنجش سرعت این دستگاه»; null where models do not run (the web). */
    private val speedTest: io.trimio.engine.models.SpeedTest? = null,
) : ViewModel() {
    private val _speed = MutableStateFlow(SpeedUi())

    /** The speed test's rows as they come, and whether it is running. */
    val speed: StateFlow<SpeedUi> = _speed.asStateFlow()

    val canTestSpeed: Boolean get() = speedTest != null

    /** Runs the speed test once (a second tap while it runs does nothing); the best ways are kept for the builds. */
    fun testSpeed() {
        val test = speedTest ?: return
        if (_speed.value.running) return
        _speed.value = SpeedUi(running = true)
        viewModelScope.launch {
            runCatching { test.run { row -> _speed.update { it.copy(rows = it.rows + row) } } }
                .onFailure { e -> _speed.update { it.copy(rows = it.rows + io.trimio.engine.models.SpeedRow(io.trimio.engine.models.SpeedRow.Kind.Note, "خطا", e.message ?: "")) } }
            _speed.update { it.copy(running = false, done = true) }
        }
    }

    private val _animations = MutableStateFlow<Long?>(null)

    /** The animations folder's size (null until measured). */
    val animations: StateFlow<Long?> = _animations.asStateFlow()

    /** Measures the animations folder again (off the main thread). */
    fun refreshAnimations() {
        viewModelScope.launch { _animations.value = withContext(Dispatchers.Default) { animationBytes() } }
    }

    /** Empties the animations folder; the director fetches again only what a later edit needs. */
    fun clearAnimationCache() {
        viewModelScope.launch {
            withContext(Dispatchers.Default) { clearAnimations() }
            _animations.value = withContext(Dispatchers.Default) { animationBytes() }
        }
    }

    private val _offer = MutableStateFlow<ProOffer?>(null)

    /** The paid tier as the store sells it; null while the paywall is off (everything is free). */
    val offer: StateFlow<ProOffer?> = _offer.asStateFlow()

    fun buyPro() {
        val e = entitlements ?: return
        val id = e.product.value ?: return
        viewModelScope.launch {
            _offer.update { it?.copy(busy = true, result = null) }
            val result = e.billing.purchase(id)
            _offer.update { it?.copy(busy = false, result = result) }
        }
    }

    fun restorePurchases() {
        val e = entitlements ?: return
        viewModelScope.launch { e.billing.refresh() }
    }

    /** Number of stored crash reports, shown next to the consent switch. */
    val crashReports: Int get() = crashLog?.reports()?.size ?: 0

    /** Shares the newest crash report (only offered once the user has consented). */
    fun shareLatestCrash() {
        val log = crashLog ?: return
        val latest = log.reports().firstOrNull() ?: return
        sharer?.shareFiles(mapOf(latest.name to log.read(latest)), "Trimio crash report")
    }

    val state: StateFlow<AppSettings> = settings.settings
    private val _keys = MutableStateFlow(CloudProvider.entries.associateWith { false })
    val keys: StateFlow<Map<CloudProvider, Boolean>> = _keys.asStateFlow()

    init {
        refreshAnimations()
        models.refresh()
        viewModelScope.launch { refreshKeys() }
        entitlements?.let { e ->
            viewModelScope.launch {
                combine(e.product, e.billing.owned) { id, owned -> id to owned }.collect { (id, owned) ->
                    _offer.value = id?.let { ProOffer(e.billing.product(it), owned = it in owned, result = _offer.value?.result) }
                }
            }
        }
    }

    fun update(change: (AppSettings) -> AppSettings) = viewModelScope.launch { settings.update(change) }

    fun saveKey(provider: CloudProvider, key: String) = viewModelScope.launch {
        if (key.isBlank()) return@launch
        cloud.saveKey(provider, key)
        refreshKeys()
    }

    fun removeKey(provider: CloudProvider) = viewModelScope.launch {
        cloud.removeKey(provider)
        refreshKeys()
        if (_keys.value.values.none { it }) settings.update { it.copy(director = DirectorBackend.OnDevice) }
    }

    private suspend fun refreshKeys() {
        _keys.update { CloudProvider.entries.associateWith { cloud.hasKey(it) } }
    }
}

/** What the store offers: [product] is null when the store cannot be reached. */
data class ProOffer(val product: StoreProduct?, val owned: Boolean, val busy: Boolean = false, val result: PurchaseResult? = null)

/** The speed test on the models screen: its rows so far, running, finished. */
data class SpeedUi(val rows: List<io.trimio.engine.models.SpeedRow> = emptyList(), val running: Boolean = false, val done: Boolean = false)
