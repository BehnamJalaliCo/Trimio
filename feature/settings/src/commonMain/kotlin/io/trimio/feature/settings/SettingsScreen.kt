package io.trimio.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.trimio.core.data.AppSettings
import io.trimio.core.data.DeviceInfo
import io.trimio.core.data.PurchaseResult
import io.trimio.core.data.ThemeMode
import io.trimio.core.designsystem.component.TrimioIcon
import io.trimio.core.designsystem.icon.TrimioIcons
import io.trimio.core.designsystem.signature.Hairline
import io.trimio.core.designsystem.signature.MicroLabel
import io.trimio.core.designsystem.signature.Sig
import io.trimio.core.designsystem.signature.SigActionSquare
import io.trimio.core.designsystem.signature.SigAdaptive
import io.trimio.core.designsystem.signature.SigButton
import io.trimio.core.designsystem.signature.SigChip
import io.trimio.core.designsystem.signature.SigHeroTitle
import io.trimio.core.designsystem.signature.SigScreen
import io.trimio.core.designsystem.signature.SigSegmented
import io.trimio.core.designsystem.signature.SigSpace
import io.trimio.core.designsystem.signature.SigTextButton
import io.trimio.core.designsystem.signature.SigTextField
import io.trimio.core.designsystem.signature.SigToggle
import io.trimio.core.designsystem.signature.SigTopRow
import io.trimio.core.designsystem.signature.gutter
import io.trimio.core.designsystem.theme.localizedNumber
import io.trimio.core.designsystem.theme.tr
import io.trimio.core.model.input.Resolution
import io.trimio.core.model.text.Language
import io.trimio.core.pipeline.DirectorBackend
import io.trimio.engine.llm.CloudProvider
import io.trimio.engine.models.DeviceMemory
import io.trimio.engine.models.ModelState

/** The on-device director as the settings row reads it: its tier and where it stands. */
data class DirectorLine(val tier: String, val state: ModelState, val installed: Boolean)

/** Everything the settings screen shows. */
data class SettingsUi(
    val settings: AppSettings,
    val keys: Map<CloudProvider, Boolean>,
    val device: DeviceInfo,
    /** The director that runs on this device; null when none is installed. */
    val director: DirectorLine? = null,
    val crashReports: Int = 0,
    val offer: ProOffer? = null,
) {
    val connected: List<CloudProvider> get() = keys.filterValues { it }.keys.toList()
}

/** What the settings screen can do. */
data class SettingsActions(
    val onBack: () -> Unit = {},
    val onUpdate: ((AppSettings) -> AppSettings) -> Unit = {},
    /** Opens the model manager; null hides the row's way in. */
    val onModels: (() -> Unit)? = null,
    val onSaveKey: (CloudProvider, String) -> Unit = { _, _ -> },
    val onRemoveKey: (CloudProvider) -> Unit = {},
    val onShareCrash: () -> Unit = {},
    val onBuyPro: () -> Unit = {},
    val onRestore: () -> Unit = {},
)

@Composable
fun SettingsRoute(viewModel: SettingsViewModel, onBack: () -> Unit, onModels: (() -> Unit)? = null) {
    val settings by viewModel.state.collectAsStateWithLifecycle()
    val states by viewModel.models.states.collectAsStateWithLifecycle()
    val keys by viewModel.keys.collectAsStateWithLifecycle()
    val offer by viewModel.offer.collectAsStateWithLifecycle()
    val device = viewModel.device
    val desktop = listOf("Android", "iOS", "Web").none { device.platform.startsWith(it) }
    val shelf = ModelShelf.build(viewModel.models.catalog, states, DeviceMemory(device.ramGb, desktop), settings.localModelId, settings.speechModelId)
    val director = (shelf.directors.firstOrNull { it.active } ?: shelf.recommended)?.let { DirectorLine(it.tier, it.state, it.installed) }
    SettingsScreen(
        SettingsUi(settings, keys, device, director, viewModel.crashReports, offer),
        SettingsActions(
            onBack = onBack, onUpdate = { viewModel.update(it) }, onModels = onModels,
            onSaveKey = { p, k -> viewModel.saveKey(p, k) }, onRemoveKey = { viewModel.removeKey(it) },
            onShareCrash = viewModel::shareLatestCrash, onBuyPro = viewModel::buyPro, onRestore = viewModel::restorePurchases,
        ),
    )
}

/**
 * Board 4, third screen: «تنظیمات.» in numbered sections. 01 the director (on the phone or in the
 * cloud, its model, the API keys), 02 the look (language, theme), 03 accessibility, 04 privacy;
 * then the export defaults, the paid tier when the store offers it, and what the app is. On wide
 * windows the sections sit in two columns.
 */
@Composable
fun SettingsScreen(ui: SettingsUi, actions: SettingsActions) {
    var keysOpen by remember { mutableStateOf(false) }
    SigScreen {
        SigAdaptive { width ->
            if (width.wide) {
                Row(Modifier.fillMaxSize().padding(horizontal = SigSpace.xl), horizontalArrangement = Arrangement.spacedBy(SigSpace.xxl)) {
                    Column(Modifier.weight(1f).widthIn(max = 560.dp).fillMaxHeight().verticalScroll(rememberScrollState())) {
                        Header(actions)
                        Column(Modifier.gutter()) {
                            DirectorSection(ui, actions, keysOpen) { keysOpen = !keysOpen }
                            AppearanceSection(ui, actions)
                        }
                    }
                    Column(Modifier.weight(1f).widthIn(max = 560.dp).fillMaxHeight().verticalScroll(rememberScrollState()).padding(top = 58.dp).gutter()) {
                        AccessibilitySection(ui, actions)
                        PrivacySection(ui, actions)
                        MoreSections(ui, actions)
                    }
                }
            } else {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
                    Header(actions)
                    Column(Modifier.gutter()) {
                        DirectorSection(ui, actions, keysOpen) { keysOpen = !keysOpen }
                        AppearanceSection(ui, actions)
                        AccessibilitySection(ui, actions)
                        PrivacySection(ui, actions)
                        MoreSections(ui, actions)
                    }
                }
            }
        }
    }
}

@Composable
private fun Header(actions: SettingsActions) {
    SigTopRow("SETTINGS", actions.onBack, backLabel = tr("بازگشت", "Back"))
    SigHeroTitle(tr("تنظیمات", "Settings"), Modifier.gutter().padding(top = 12.dp), size = 40)
}

/** A numbered section: its name at the start, the Latin index at the end, then its rows. */
@Composable
private fun Section(name: String, index: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 18.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text(name, style = Sig.type.meta, color = Sig.colors.muted)
            MicroLabel(index)
        }
        content()
    }
}

/** A row of the boards: title (and a quiet line), what it leads to or switches, a hairline under it. */
@Composable
private fun SettingRow(
    title: String,
    hint: String? = null,
    onClick: (() -> Unit)? = null,
    last: Boolean = false,
    minHeight: Int = 52,
    trailing: @Composable () -> Unit = {},
) {
    val c = Sig.colors
    Column(Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(role = Role.Button, onClick = onClick) else it }) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = minHeight.dp).padding(vertical = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(title, style = Sig.type.bodyStrong.copy(fontSize = 14.sp), color = c.ink)
                hint?.let { Text(it, style = Sig.type.meta.copy(fontSize = 11.5.sp), color = c.muted) }
            }
            trailing()
        }
        if (!last) Hairline()
    }
}

/** The quiet value at a row's end ("مدیریت", "فارسی") and the forward chevron. */
@Composable
private fun Lead(value: String?) {
    val c = Sig.colors
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        value?.let { Text(it, style = Sig.type.meta.copy(fontWeight = androidx.compose.ui.text.font.FontWeight(700)), color = c.ink) }
        TrimioIcon(TrimioIcons.ChevronForward, null, tint = c.muted, size = 16.dp)
    }
}

@Composable
private fun DirectorSection(ui: SettingsUi, actions: SettingsActions, keysOpen: Boolean, onKeys: () -> Unit) {
    val c = Sig.colors
    val canCloud = ui.connected.isNotEmpty()
    Section(tr("کارگردان", "Director"), "01") {
        SigSegmented(
            DirectorBackend.entries, ui.settings.director,
            label = { if (it == DirectorBackend.OnDevice) tr("روی گوشی", "On device") else tr("ابری", "Cloud") },
            // The cloud needs a key: without one, choosing it opens the keys instead.
            onSelect = { d -> if (d == DirectorBackend.OnDevice || canCloud) actions.onUpdate { it.copy(director = d) } else if (!keysOpen) onKeys() },
            modifier = Modifier.padding(top = 8.dp),
            icon = { if (it == DirectorBackend.OnDevice) TrimioIcons.Phone else TrimioIcons.Cloud },
        )
        SettingRow(tr("مدل کارگردان", "Director model"), directorHint(ui.director), onClick = actions.onModels) {
            if (actions.onModels != null) Lead(tr("مدیریت", "Manage"))
        }
        SettingRow(tr("کلیدهای API", "API keys"), keysHint(ui), onClick = onKeys, last = keysOpen) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (canCloud) {
                    Box(Modifier.size(24.dp).clip(CircleShape).background(c.emphasisSoft), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(6.dp).clip(CircleShape).background(c.emphasisSoftContent))
                    }
                }
                Lead(null)
            }
        }
        if (keysOpen) {
            Column(Modifier.padding(top = 4.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CloudProvider.entries.forEach { p -> KeyRow(p, ui.keys[p] == true, actions) }
                Text(tr("کلید فقط روی همین دستگاه و رمزنگاری‌شده نگه داشته می‌شود.", "Keys stay on this device, encrypted."), style = Sig.type.meta.copy(fontSize = 11.sp), color = c.muted)
            }
            Hairline()
        }
    }
}

/** One provider: connected (its model, remove) or a field to paste a key. */
@Composable
private fun KeyRow(provider: CloudProvider, connected: Boolean, actions: SettingsActions) {
    val c = Sig.colors
    var draft by remember(provider, connected) { mutableStateOf("") }
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(c.ink.copy(alpha = 0.05f)).padding(PaddingValues(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)),
        horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        TrimioIcon(TrimioIcons.Key, null, tint = c.muted, size = 18.dp)
        if (connected) {
            Column(Modifier.weight(1f)) {
                Text(provider.title, style = Sig.type.bodyStrong.copy(fontSize = 13.sp), color = c.ink)
                Text("⁦${provider.defaultModel}⁩ · ${tr("متصل", "connected")}", style = Sig.type.meta.copy(fontSize = 11.sp), color = c.muted)
            }
            SigTextButton(tr("حذف", "Remove"), { actions.onRemoveKey(provider) })
        } else {
            SigTextField(
                draft, { draft = it }, tr("کلید ${provider.title} را اینجا بچسبان", "Paste your ${provider.title} key"), Modifier.weight(1f),
                style = Sig.type.body.copy(fontSize = 13.sp), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            SigActionSquare(TrimioIcons.Check, tr("ذخیره", "Save"), { actions.onSaveKey(provider, draft) }, size = 38.dp)
        }
    }
}

@Composable
private fun AppearanceSection(ui: SettingsUi, actions: SettingsActions) {
    var languages by remember { mutableStateOf(false) }
    Section(tr("ظاهر", "Appearance"), "02") {
        SettingRow(tr("زبان", "Language"), onClick = { languages = !languages }, last = false) { Lead(languageName(ui.settings.language)) }
        if (languages) {
            SigSegmented(
                listOf(Language.Persian, Language.English, null), ui.settings.language,
                label = { languageName(it) }, onSelect = { lang -> actions.onUpdate { it.copy(language = lang) } },
                modifier = Modifier.padding(vertical = 8.dp), height = 38.dp,
            )
            Hairline()
        }
        SettingRow(tr("تم", "Theme"), last = true, minHeight = 58) {
            SigSegmented(
                ThemeMode.entries, ui.settings.theme,
                label = { when (it) { ThemeMode.Dark -> tr("تیره", "Dark"); ThemeMode.Light -> tr("روشن", "Light"); ThemeMode.System -> tr("سیستم", "System") } },
                onSelect = { t -> actions.onUpdate { it.copy(theme = t) } },
                modifier = Modifier.width(190.dp), height = 38.dp,
            )
        }
    }
}

@Composable
private fun AccessibilitySection(ui: SettingsUi, actions: SettingsActions) {
    val s = ui.settings
    Section(tr("دسترس‌پذیری", "Accessibility"), "03") {
        SettingRow(tr("حرکت کمتر", "Reduce motion")) {
            SigToggle(s.reduceMotion, { v -> actions.onUpdate { it.copy(reduceMotion = v) } }, label = tr("حرکت کمتر", "Reduce motion"))
        }
        SettingRow(tr("شفافیت کمتر", "Reduce transparency")) {
            SigToggle(s.reduceTransparency, { v -> actions.onUpdate { it.copy(reduceTransparency = v) } }, label = tr("شفافیت کمتر", "Reduce transparency"))
        }
        SettingRow(tr("لرزش لمسی", "Haptics"), last = true) {
            SigToggle(s.haptics, { v -> actions.onUpdate { it.copy(haptics = v) } }, label = tr("لرزش لمسی", "Haptics"))
        }
    }
}

@Composable
private fun PrivacySection(ui: SettingsUi, actions: SettingsActions) {
    val s = ui.settings
    val share = s.shareCrashReports && ui.crashReports > 0
    Section(tr("حریم خصوصی", "Privacy"), "04") {
        SettingRow(tr("ارسال گزارش خطا", "Send crash reports"), tr("فقط با تأیید تو؛ بدون ویدیو، صدا یا متن", "Only with your consent; never video, voice or text"), last = !share, minHeight = 58) {
            SigToggle(s.shareCrashReports, { v -> actions.onUpdate { it.copy(shareCrashReports = v) } }, label = tr("ارسال گزارش خطا", "Send crash reports"))
        }
        if (share) {
            SettingRow(localizedNumber(tr("ارسال آخرین گزارش (${ui.crashReports})", "Send the latest report (${ui.crashReports})")), onClick = actions.onShareCrash, last = true) {
                TrimioIcon(TrimioIcons.Share, null, tint = Sig.colors.muted, size = 18.dp)
            }
        }
    }
}

/** Beyond the board's four: the export defaults, the paid tier (when sold) and what the app is. */
@Composable
private fun MoreSections(ui: SettingsUi, actions: SettingsActions) {
    val c = Sig.colors
    val s = ui.settings
    Section(tr("خروجی", "Export"), "05") {
        Text(tr("کیفیت و نرخ فریم پیش‌فرض صفحهٔ خروجی", "The export screen's default quality and frame rate"), style = Sig.type.meta.copy(fontSize = 11.5.sp), color = c.muted, modifier = Modifier.padding(top = 4.dp))
        SigSegmented(
            Resolution.entries, s.exportResolution,
            label = { if (it == Resolution.Uhd4k) "4K" else if (it == Resolution.Qhd) "2K" else "${it.shortEdge}p" },
            onSelect = { r -> actions.onUpdate { it.copy(exportResolution = r) } }, modifier = Modifier.padding(top = 8.dp), height = 40.dp,
        )
        SigSegmented(
            listOf(24, 30, 60), s.exportFrameRate, label = { localizedNumber("$it fps") },
            onSelect = { r -> actions.onUpdate { it.copy(exportFrameRate = r) } }, modifier = Modifier.padding(top = 8.dp), height = 40.dp,
        )
    }
    ui.offer?.let { Pro(it, actions) }
    Section(tr("درباره", "About"), if (ui.offer != null) "07" else "06") {
        Text("⁦Trimio ${ui.device.appVersion} · ${ui.device.platform}⁩", style = Sig.type.body.copy(fontSize = 13.sp), color = c.ink, modifier = Modifier.padding(top = 6.dp))
        Text(
            tr("همهٔ صداها، موسیقی‌ها و آیکون‌ها اختصاصی‌اند؛ خروجی تو بدون محدودیت حق نشر است.", "All sounds, music and icons are original: your exports carry no licence restrictions."),
            style = Sig.type.meta.copy(fontSize = 11.5.sp), color = c.muted, modifier = Modifier.padding(top = 4.dp, bottom = 32.dp),
        )
    }
}

@Composable
private fun Pro(offer: ProOffer, actions: SettingsActions) {
    val c = Sig.colors
    Section("Trimio Pro", "06") {
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val product = offer.product
            when {
                offer.owned -> SigChip(
                    tr("فعال است — ممنون از حمایتت", "Active — thank you for your support"), container = c.emphasisSoft, content = c.emphasisSoftContent,
                    leading = { TrimioIcon(TrimioIcons.Check, null, tint = c.emphasisSoftContent, size = 13.dp) },
                )
                product == null -> Text(tr("فروشگاه در دسترس نیست؛ بعداً دوباره امتحان کن.", "The store is not reachable; try again later."), style = Sig.type.meta, color = c.muted)
                else -> SigButton(localizedNumber(tr("خرید · ${product.price}", "Buy · ${product.price}")), actions.onBuyPro, Modifier.fillMaxWidth(), enabled = !offer.busy)
            }
            when (val r = offer.result) {
                is PurchaseResult.Pending -> Text(tr("پرداخت در حال تأیید است.", "Payment is being confirmed."), style = Sig.type.meta, color = c.muted)
                is PurchaseResult.Failed -> Text(tr("خرید انجام نشد.", "The purchase did not go through.") + " " + r.reason, style = Sig.type.meta, color = c.muted)
                else -> Unit
            }
            if (!offer.owned) SigTextButton(tr("بازگردانی خرید", "Restore purchase"), actions.onRestore)
        }
    }
}

@Composable
private fun languageName(lang: Language?): String = when (lang) {
    null -> tr("مثل سیستم", "System")
    Language.Persian -> "فارسی"
    Language.English -> "English"
    else -> lang.name
}

/** «۴ میلیارد · نصب شده», or what keeps it from running. */
@Composable
private fun directorHint(line: DirectorLine?): String {
    if (line == null) return tr("نصب نشده؛ ساخت با قواعد", "Not installed; builds use the rules")
    val size = TIER.find(line.tier)?.groupValues?.get(1)?.let { n -> tr("$n میلیارد", "${n}B") } ?: line.tier
    val state = when (val s = line.state) {
        is ModelState.Downloading -> tr("در حال دانلود ${(s.fraction * PERCENT).toInt()}٪", "downloading ${(s.fraction * PERCENT).toInt()}%")
        is ModelState.UpdateAvailable -> tr("نسخهٔ تازه آماده است", "update available")
        else -> if (line.installed) tr("نصب شده", "installed") else tr("پیشنهادی · نصب نشده", "recommended · not installed")
    }
    return localizedNumber("$size · $state")
}

/** «Claude · متصل», or that none is. */
@Composable
private fun keysHint(ui: SettingsUi): String =
    if (ui.connected.isEmpty()) tr("وصل نیست · برای کارگردان ابری", "None connected · for the cloud director")
    else ui.connected.joinToString("، ") { it.title } + " · " + tr("متصل", "connected")

private val TIER = Regex("^(\\d+(?:\\.\\d+)?)B")
private const val PERCENT = 100
