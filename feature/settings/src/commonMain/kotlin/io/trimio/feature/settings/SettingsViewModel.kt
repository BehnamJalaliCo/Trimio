package io.trimio.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.trimio.core.data.AppSettings
import io.trimio.core.data.CrashLog
import io.trimio.core.data.DeviceInfo
import io.trimio.core.data.Entitlements
import io.trimio.core.data.PurchaseResult
import io.trimio.core.data.SettingsRepository
import io.trimio.core.data.Sharer
import io.trimio.core.data.StoreProduct
import io.trimio.core.pipeline.DirectorBackend
import io.trimio.engine.llm.CloudModels
import io.trimio.engine.llm.CloudProvider
import io.trimio.engine.models.ModelManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

class SettingsViewModel(
    private val settings: SettingsRepository,
    val models: ModelManager,
    private val cloud: CloudModels,
    val device: DeviceInfo,
    private val crashLog: CrashLog? = null,
    private val sharer: Sharer? = null,
    private val entitlements: Entitlements? = null,
) : ViewModel() {
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
