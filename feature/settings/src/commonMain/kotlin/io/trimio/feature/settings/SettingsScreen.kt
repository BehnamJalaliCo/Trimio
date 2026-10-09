package io.trimio.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import io.trimio.core.data.AppSettings
import io.trimio.core.data.CrashLog
import io.trimio.core.data.DeviceInfo
import io.trimio.core.data.Entitlements
import io.trimio.core.data.PurchaseResult
import io.trimio.core.data.StoreProduct
import io.trimio.core.data.Sharer
import io.trimio.core.data.SettingsRepository
import io.trimio.core.designsystem.component.ButtonKind
import io.trimio.core.designsystem.component.ComposerField
import io.trimio.core.designsystem.component.GlassChip
import io.trimio.core.designsystem.component.GlassIconButton
import io.trimio.core.designsystem.component.ListRow
import io.trimio.core.designsystem.component.PillSelector
import io.trimio.core.designsystem.component.ProgressLine
import io.trimio.core.designsystem.component.SectionLabel
import io.trimio.core.designsystem.component.SurfaceCard
import io.trimio.core.designsystem.component.TrimioScreen
import io.trimio.core.designsystem.component.TrimioButton
import io.trimio.core.designsystem.component.TrimioSwitch
import io.trimio.core.designsystem.component.TrimioTopBar
import io.trimio.core.designsystem.icon.TrimioIcons
import io.trimio.core.designsystem.theme.Trimio
import io.trimio.core.designsystem.theme.TrimioRadius
import io.trimio.core.designsystem.theme.TrimioSpacing
import io.trimio.core.designsystem.theme.localizedNumber
import io.trimio.core.designsystem.theme.tr
import io.trimio.core.model.input.Resolution
import io.trimio.core.model.text.Language
import io.trimio.core.pipeline.DirectorBackend
import io.trimio.engine.llm.CloudModels
import io.trimio.engine.llm.CloudProvider
import io.trimio.engine.models.ModelKind
import io.trimio.engine.models.ModelManager
import io.trimio.engine.models.ModelSpec
import io.trimio.engine.models.ModelState
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

@Composable
fun SettingsRoute(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val settings by viewModel.state.collectAsStateWithLifecycle()
    val modelStates by viewModel.models.states.collectAsStateWithLifecycle()
    val keys by viewModel.keys.collectAsStateWithLifecycle()
    val offer by viewModel.offer.collectAsStateWithLifecycle()
    SettingsScreen(
        settings = settings,
        catalog = viewModel.models.catalog,
        modelStates = modelStates,
        keys = keys,
        device = viewModel.device,
        onBack = onBack,
        onUpdate = { viewModel.update(it) },
        onDownload = viewModel.models::download,
        onCancel = viewModel.models::cancel,
        onDelete = viewModel.models::delete,
        onSaveKey = { p, k -> viewModel.saveKey(p, k) },
        onRemoveKey = { viewModel.removeKey(it) },
        crashReports = viewModel.crashReports,
        onShareCrash = viewModel::shareLatestCrash,
        offer = offer,
        onBuyPro = viewModel::buyPro,
        onRestore = viewModel::restorePurchases,
    )
}

/** What the store offers: [product] is null when the store cannot be reached. */
data class ProOffer(val product: StoreProduct?, val owned: Boolean, val busy: Boolean = false, val result: PurchaseResult? = null)

@Composable
fun SettingsScreen(
    settings: AppSettings,
    catalog: List<ModelSpec>,
    modelStates: Map<String, ModelState>,
    keys: Map<CloudProvider, Boolean>,
    device: DeviceInfo,
    onBack: () -> Unit,
    onUpdate: ((AppSettings) -> AppSettings) -> Unit,
    onDownload: (ModelSpec) -> Unit,
    onCancel: (ModelSpec) -> Unit,
    onDelete: (ModelSpec) -> Unit,
    onSaveKey: (CloudProvider, String) -> Unit,
    onRemoveKey: (CloudProvider) -> Unit,
    crashReports: Int = 0,
    onShareCrash: () -> Unit = {},
    offer: ProOffer? = null,
    onBuyPro: () -> Unit = {},
    onRestore: () -> Unit = {},
) {
    TrimioScreen(dimAurora = 0.65f) {
        Column(Modifier.fillMaxSize()) {
            TrimioTopBar(tr("تنظیمات", "Settings"), onBack = onBack, backLabel = tr("بازگشت", "Back"))
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = TrimioSpacing.screenGutter),
                verticalArrangement = Arrangement.spacedBy(TrimioSpacing.lg),
            ) {
                offer?.let { ProSection(it, onBuyPro, onRestore) }

                Section(tr("زبان برنامه", "App language")) {
                    PillSelector(
                        options = listOf<Language?>(null, Language.Persian, Language.English),
                        selected = settings.language,
                        label = { when (it) { null -> tr("مثل سیستم", "System"); Language.Persian -> "فارسی"; Language.English -> "English" } },
                        onSelect = { lang -> onUpdate { it.copy(language = lang) } },
                    )
                }

                Section(tr("کارگردان پیش\u200Cفرض", "Default director")) {
                    PillSelector(
                        options = DirectorBackend.entries,
                        selected = settings.director,
                        label = { if (it == DirectorBackend.OnDevice) tr("روی گوشی", "On device") else tr("ابری (کلید خودت)", "Cloud (your key)") },
                        leading = { if (it == DirectorBackend.OnDevice) TrimioIcons.Phone else TrimioIcons.Cloud },
                        onSelect = { d -> if (d == DirectorBackend.OnDevice || keys.values.any { it }) onUpdate { it.copy(director = d) } },
                    )
                }

                val ramHint = localizedNumber(tr("رم این گوشی: ${device.ramGb} گیگ", "This phone: ${device.ramGb} GB RAM"))
                if (!device.previewOnly) Section(tr("مدل\u200Cهای روی گوشی", "On-device models"), hint = ramHint) {
                    val groups = listOf(ModelKind.Speech to tr("تشخیص گفتار", "Speech"), ModelKind.Language to tr("کارگردان", "Director"))
                    groups.forEach { (kind, title) ->
                        Text(title, style = Trimio.type.label, color = Trimio.colors.accentCyan, modifier = Modifier.padding(top = TrimioSpacing.sm))
                        catalog.filter { it.kind == kind }.forEach { spec ->
                            val chosen = if (kind == ModelKind.Language) settings.localModelId == spec.id else settings.speechModelId == spec.id
                            ModelRow(
                                spec, modelStates[spec.id] ?: ModelState.NotInstalled, device.ramGb, chosen,
                                onChoose = { onUpdate { s -> if (kind == ModelKind.Language) s.copy(localModelId = spec.id) else s.copy(speechModelId = spec.id) } },
                                onDownload = { onDownload(spec) }, onCancel = { onCancel(spec) }, onDelete = { onDelete(spec) },
                            )
                        }
                    }
                }

                Section(tr("کلید API (اختیاری)", "API keys (optional)"), hint = tr("کلید فقط روی همین گوشی و رمزنگاری\u200Cشده نگه داشته می\u200Cشود.", "Keys stay on this phone, encrypted.")) {
                    CloudProvider.entries.forEach { provider -> KeyRow(provider, keys[provider] == true, onSaveKey, onRemoveKey) }
                }

                Section(tr("خروجی", "Export")) {
                    PillSelector(
                        options = Resolution.entries,
                        selected = settings.exportResolution,
                        label = { localizedNumber(if (it == Resolution.Uhd4k) "4K" else "${it.shortEdge}p") },
                        onSelect = { r -> onUpdate { it.copy(exportResolution = r) } },
                    )
                }

                Section(tr("دسترس\u200Cپذیری", "Accessibility")) {
                    ListRow(tr("حرکت کمتر", "Reduce motion"), description = tr("انیمیشن\u200Cها کوتاه و بدون جهش", "Short, bounce-free animations")) {
                        TrimioSwitch(settings.reduceMotion, { v -> onUpdate { it.copy(reduceMotion = v) } })
                    }
                    ListRow(tr("شفافیت کمتر", "Reduce transparency"), description = tr("سطوح جامد به جای شیشه", "Solid surfaces instead of glass")) {
                        TrimioSwitch(settings.reduceTransparency, { v -> onUpdate { it.copy(reduceTransparency = v) } })
                    }
                    ListRow(tr("لرزش لمسی", "Haptics")) {
                        TrimioSwitch(settings.haptics, { v -> onUpdate { it.copy(haptics = v) } })
                    }
                }

                Section(tr("حریم خصوصی", "Privacy")) {
                    ListRow(
                        tr("گزارش خطا", "Crash reports"),
                        description = tr("فقط خطای فنی و مدل گوشی؛ هیچ ویدیو، صدا یا متنی ارسال نمی\u200Cشود.", "Only the technical error and phone model; never your media, voice or text."),
                    ) { TrimioSwitch(settings.shareCrashReports, { v -> onUpdate { it.copy(shareCrashReports = v) } }) }
                    if (settings.shareCrashReports && crashReports > 0) {
                        ListRow(localizedNumber(tr("ارسال آخرین گزارش ($crashReports)", "Send the latest report ($crashReports)")), icon = TrimioIcons.Share, onClick = onShareCrash)
                    }
                }

                Section(tr("درباره", "About")) {
                    Text(localizedNumber("Trimio ${device.appVersion} · ${device.platform}"), style = Trimio.type.body, color = Trimio.colors.textSecondary)
                    Text(
                        tr("همه\u0654 صداها، موسیقی\u200Cها و آیکون\u200Cها اختصاصی\u200Cاند؛ خروجی شما بدون محدودیت حق نشر است.", "All sounds, music and icons are original: your exports carry no licence restrictions."),
                        style = Trimio.type.caption, color = Trimio.colors.textTertiary,
                    )
                }
                Spacer(Modifier.height(TrimioSpacing.xxl))
            }
        }
    }
}

@Composable
private fun ProSection(offer: ProOffer, onBuy: () -> Unit, onRestore: () -> Unit) {
    Section("Trimio Pro") {
        Column(verticalArrangement = Arrangement.spacedBy(TrimioSpacing.sm)) {
            when {
                offer.owned -> ListRow(tr("فعال است — ممنون از حمایتت", "Active — thank you for your support"), icon = TrimioIcons.Check)
                offer.product == null -> Text(
                    tr("فروشگاه در دسترس نیست؛ بعداً دوباره امتحان کن.", "The store is not reachable; try again later."),
                    style = Trimio.type.body, color = Trimio.colors.textSecondary,
                )
                else -> TrimioButton(
                    localizedNumber(tr("خرید · ${offer.product.price}", "Buy · ${offer.product.price}")),
                    onBuy, Modifier.fillMaxWidth(), enabled = !offer.busy,
                )
            }
            when (val r = offer.result) {
                is PurchaseResult.Pending -> Text(tr("پرداخت در حال تأیید است.", "Payment is being confirmed."), style = Trimio.type.caption, color = Trimio.colors.textTertiary)
                is PurchaseResult.Failed -> Text(tr("خرید انجام نشد.", "The purchase did not go through.") + " " + r.reason, style = Trimio.type.caption, color = Trimio.colors.textTertiary)
                else -> Unit
            }
            if (!offer.owned) TrimioButton(tr("بازگردانی خرید", "Restore purchase"), onRestore, Modifier.fillMaxWidth(), kind = ButtonKind.Ghost)
        }
    }
}

@Composable
private fun Section(title: String, hint: String? = null, content: @Composable () -> Unit) {
    Column {
        SectionLabel(title)
        SurfaceCard(Modifier.fillMaxWidth()) {
            hint?.let { Text(it, style = Trimio.type.caption, color = Trimio.colors.textTertiary, modifier = Modifier.padding(bottom = TrimioSpacing.sm)) }
            content()
        }
    }
}

@Composable
private fun ModelRow(
    spec: ModelSpec,
    state: ModelState,
    ramGb: Int,
    chosen: Boolean,
    onChoose: () -> Unit,
    onDownload: () -> Unit,
    onCancel: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = Trimio.colors
    val fits = spec.tier.minRamGb <= ramGb
    val installed = state is ModelState.Installed
    Column(Modifier.fillMaxWidth().padding(vertical = TrimioSpacing.xs)) {
        ListRow(
            title = tr(spec.titleFa, spec.titleEn),
            description = localizedNumber(
                listOfNotNull(
                    gb(spec.sizeBytes),
                    spec.activeParamsB?.let { tr("MoE · $it میلیارد فعال", "MoE · ${it}B active") },
                    if (!fits) tr("نیاز به ${spec.tier.minRamGb} گیگ رم", "needs ${spec.tier.minRamGb} GB RAM") else null,
                ).joinToString(" · "),
            ),
            icon = if (spec.kind == ModelKind.Speech) TrimioIcons.Mic else TrimioIcons.Sparkle,
            onClick = if (installed) onChoose else null,
        ) {
            if (spec.isDefault) GlassChip(tr("پیش\u200Cفرض", "Default"), accent = colors.accentCyan)
            if (chosen && installed) GlassChip(tr("فعال", "Active"), accent = colors.success)
            when (state) {
                ModelState.Installed -> GlassIconButton(TrimioIcons.Trash, tr("حذف مدل", "Delete model"), onDelete, tint = colors.danger)
                is ModelState.Downloading -> GlassIconButton(TrimioIcons.Close, tr("لغو دانلود", "Cancel download"), onCancel)
                else -> if (fits) GlassIconButton(TrimioIcons.Download, tr("دانلود", "Download"), onDownload, tint = colors.accentCyan)
            }
        }
        when (state) {
            is ModelState.Downloading -> ProgressLine(state.fraction)
            is ModelState.Failed -> Text(tr("دانلود ناموفق؛ دوباره تلاش کن (از همان\u200Cجا ادامه می\u200Cدهد)", "Download failed; try again (it resumes)"), style = Trimio.type.caption, color = colors.danger)
            else -> Unit
        }
        HorizontalDivider(color = colors.glassStroke, modifier = Modifier.padding(top = TrimioSpacing.xs))
    }
}

@Composable
private fun KeyRow(provider: CloudProvider, connected: Boolean, onSave: (CloudProvider, String) -> Unit, onRemove: (CloudProvider) -> Unit) {
    val colors = Trimio.colors
    var draft by remember(provider, connected) { mutableStateOf("") }
    ListRow(
        title = provider.title,
        description = if (connected) tr("متصل · ${provider.defaultModel}", "Connected · ${provider.defaultModel}") else tr("وصل نیست", "Not connected"),
        icon = TrimioIcons.Key,
    ) {
        if (connected) GlassIconButton(TrimioIcons.Trash, tr("حذف کلید", "Remove key"), { onRemove(provider) }, tint = colors.danger)
    }
    if (!connected) {
        Row(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(TrimioRadius.md)).background(colors.glassFill).padding(PaddingValues(start = TrimioSpacing.md)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ComposerField(draft, { draft = it }, tr("کلید API را اینجا بچسبان", "Paste your API key"), Modifier.weight(1f).padding(vertical = TrimioSpacing.md), singleLine = true, obscured = true)
            GlassIconButton(TrimioIcons.Check, tr("ذخیره", "Save"), { onSave(provider, draft) }, tint = colors.success)
        }
        Spacer(Modifier.height(TrimioSpacing.sm))
    }
}

@Composable
private fun gb(bytes: Long): String {
    val tenths = (bytes / 100_000_000.0).let { kotlin.math.round(it).toLong() }
    return if (tenths >= 10) tr("${tenths / 10}٫${tenths % 10} گیگ", "${tenths / 10}.${tenths % 10} GB") else tr("${bytes / 1_000_000} مگ", "${bytes / 1_000_000} MB")
}
