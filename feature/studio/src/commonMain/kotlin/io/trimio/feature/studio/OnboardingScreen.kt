package io.trimio.feature.studio

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.trimio.core.designsystem.component.TrimioIcon
import io.trimio.core.designsystem.icon.TrimioIcons
import io.trimio.core.designsystem.signature.DirectorDot
import io.trimio.core.designsystem.signature.DisplayText
import io.trimio.core.designsystem.signature.FootagePlaceholder
import io.trimio.core.designsystem.signature.Hairline
import io.trimio.core.designsystem.signature.MicroLabel
import io.trimio.core.designsystem.signature.Sig
import io.trimio.core.designsystem.signature.SigButton
import io.trimio.core.designsystem.signature.SigChip
import io.trimio.core.designsystem.signature.SigGlass
import io.trimio.core.designsystem.signature.SigNoir
import io.trimio.core.designsystem.signature.SigScreen
import io.trimio.core.designsystem.signature.SigSpace
import io.trimio.core.designsystem.signature.SigSegmented
import io.trimio.core.designsystem.signature.gutter
import io.trimio.core.designsystem.theme.Trimio
import io.trimio.core.designsystem.theme.localizedNumber
import io.trimio.core.designsystem.theme.tr
import io.trimio.core.model.text.Language
import io.trimio.engine.models.ModelKind
import io.trimio.engine.models.ModelSpec
import io.trimio.engine.models.ModelState
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/** What the device card shows: real numbers, or nothing where the platform cannot tell. */
data class OnboardingDevice(val ramGb: Int, val cores: Int = 0, val freeBytes: Long? = null)

/**
 * First run (board 1, three screens): what Trimio does, with the UI language; the director on this
 * device (its RAM, cores and free space, and the models with their real download progress); and
 * the privacy promises with the optional cloud director. [language] is the setting (null follows
 * the system). Platforms that cannot store models (web) skip the second screen.
 */
@Composable
fun OnboardingScreen(
    language: Language?,
    defaults: List<ModelSpec>,
    states: Map<String, ModelState>,
    device: OnboardingDevice,
    onLanguage: (Language?) -> Unit,
    onDownloadDefaults: () -> Unit,
    onConnectCloud: () -> Unit,
    onFinish: () -> Unit,
    /** Larger optional directors this device can run, offered for later. */
    stronger: List<ModelSpec> = emptyList(),
    startPage: Int = 0,
) {
    val pages = if (defaults.isEmpty()) listOf(PAGE_WELCOME, PAGE_PRIVACY) else listOf(PAGE_WELCOME, PAGE_MODELS, PAGE_PRIVACY)
    var index by rememberSaveable { mutableIntStateOf(pages.indexOf(startPage).coerceAtLeast(0)) }
    val step = pages[index]
    fun next() { index = (index + 1).coerceAtMost(pages.lastIndex) }
    val counter = "0${index + 1} / 0${pages.size}"
    val rtl = Trimio.language.isRtl
    val motion = Trimio.motion
    SigScreen(
        background = {
            androidx.compose.animation.AnimatedVisibility(step == PAGE_WELCOME, enter = fadeIn(motion.effects()), exit = fadeOut(motion.effectsFast())) { WelcomeFootage() }
        },
    ) {
        AnimatedContent(
            targetState = step,
            transitionSpec = {
                val dir = if ((targetState > initialState) xor rtl) 1 else -1
                (slideInHorizontally(motion.spatial()) { it / 3 * dir } + fadeIn(motion.effects())) togetherWith
                    (slideOutHorizontally(motion.spatial()) { -it / 3 * dir } + fadeOut(motion.effectsFast()))
            },
            modifier = Modifier.fillMaxSize(),
            label = "onboarding",
        ) { page ->
            // A phone-width column, centred on tablets and desktops.
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 480.dp).fillMaxSize()) {
                    when (page) {
                        PAGE_WELCOME -> Welcome(counter, language, onLanguage, onSkip = onFinish, onNext = ::next)
                        PAGE_MODELS -> Models(counter, defaults, states, device, stronger, onLater = ::next) {
                            onDownloadDefaults()
                            next()
                        }
                        else -> Privacy(counter, onConnectCloud, onFinish)
                    }
                }
            }
        }
    }
}

/** The board's top row: the page counter at the reading start, a quiet text action at the end. */
@Composable
private fun TopRow(counter: String, action: String?, onAction: () -> Unit, onMedia: Boolean = false) {
    val c = Sig.colors
    Row(Modifier.fillMaxWidth().height(TOP_ROW).gutter().padding(top = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        MicroLabel(counter, color = if (onMedia) c.onMedia else c.muted)
        if (action != null) {
            Text(
                action,
                Modifier.touchArea(12.dp).clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onAction).padding(horizontal = 6.dp, vertical = 12.dp),
                style = Sig.type.bodyStrong.copy(lineHeight = 20.sp),
                color = if (onMedia) c.onMedia.copy(alpha = 0.8f) else c.muted,
            )
        }
    }
}

/** Lays out as its content without [extra] above and below, while the touch target keeps them. */
internal fun Modifier.touchArea(extra: Dp): Modifier = layout { measurable, constraints ->
    val e = extra.roundToPx()
    val p = measurable.measure(if (constraints.hasBoundedHeight) constraints.copy(maxHeight = constraints.maxHeight + 2 * e) else constraints)
    layout(p.width, (p.height - 2 * e).coerceAtLeast(0)) { p.place(0, -e) }
}

/** Bottom space as the board draws it (from the screen's edge), less what the system bar already takes. */
@Composable
internal fun boardBottom(target: Dp, min: Dp = 10.dp): Dp =
    (target - WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()).coerceAtLeast(min)

/** The first screen's footage, full bleed under the status bar, fading into the canvas. */
@Composable
private fun WelcomeFootage() {
    val c = Sig.colors
    Box(Modifier.fillMaxWidth().fillMaxHeight(FOOTAGE_SHARE)) {
        FootagePlaceholder(Modifier.fillMaxSize())
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(
                    0f to SigNoir.canvas.copy(alpha = 0.35f), 0.3f to Color.Transparent, 0.6f to Color.Transparent, 1f to c.canvas,
                ),
            ),
        )
    }
}

@Composable
private fun ColumnScope.Welcome(counter: String, language: Language?, onLanguage: (Language?) -> Unit, onSkip: () -> Unit, onNext: () -> Unit) {
    val c = Sig.colors
    TopRow(counter, tr("رد کردن", "Skip"), onSkip, onMedia = true)
    Kinetic(Modifier.padding(start = 28.dp, top = 100.dp - TOP_ROW))
    Spacer(Modifier.weight(1f))
    Column(Modifier.gutter()) {
        DisplayText(
            buildAnnotatedString {
                append(tr("ویدیو بده.\nموشن بگیر", "Give a video.\nGet motion"))
                withStyle(SpanStyle(color = c.action)) { append(".") }
            },
            style = Sig.type.hero,
        )
        Text(
            tr(
                "یک ویدیو یا فقط صدایت، و یک جمله. کارگردانِ Trimio برایش فکر می‌کند، طراحی می‌کند و می‌سازد؛ روی همین گوشی.",
                "A video or just your voice, and one sentence. Trimio's director thinks it through, designs it and makes it, right on this phone.",
            ),
            style = Sig.type.body, color = c.muted, modifier = Modifier.padding(top = 14.dp),
        )
        Text(tr("زبان برنامه", "App language"), style = Sig.type.meta, color = c.muted, modifier = Modifier.padding(top = 22.dp))
        SigSegmented(
            options = listOf<Language?>(Language.Persian, Language.English, null),
            selected = language,
            label = { option ->
                when (option) {
                    Language.Persian -> "فارسی"
                    Language.English -> "English"
                    else -> tr("مثل سیستم", "System")
                }
            },
            onSelect = onLanguage,
            modifier = Modifier.padding(top = 8.dp),
        )
    }
    Spacer(Modifier.height(45.dp))
    BottomAction(tr("شروع", "Start"), onNext, trailing = TrimioIcons.ChevronForward)
}

/** The motion on the footage: a hook stamp, a word, the lime block and the ember rule. */
@Composable
private fun Kinetic(modifier: Modifier) {
    val c = Sig.colors
    val word = Sig.type.hero.copy(fontSize = 56.sp, lineHeight = 56.sp)
    Column(modifier) {
        MicroLabel("00:03 · HOOK", color = c.emphasis)
        DisplayText(tr("موشن", "Real"), style = word, color = SigNoir.onMedia, modifier = Modifier.padding(top = 10.dp))
        Box(Modifier.padding(top = 14.dp).clip(RoundedCornerShape(6.dp)).background(c.emphasis).padding(start = 12.dp, end = 12.dp, top = 34.dp, bottom = 8.dp)) {
            DisplayText(tr("واقعی", "motion"), style = word.copy(lineHeight = (56 * 0.9f).sp), color = c.onEmphasis)
        }
        Box(Modifier.padding(top = 12.dp).size(70.dp, 3.dp).background(c.action))
    }
}

@Composable
private fun ColumnScope.Models(
    counter: String,
    defaults: List<ModelSpec>,
    states: Map<String, ModelState>,
    device: OnboardingDevice,
    stronger: List<ModelSpec>,
    onLater: () -> Unit,
    onContinue: () -> Unit,
) {
    val c = Sig.colors
    val speech = defaults.filter { it.kind == ModelKind.Speech }
    val director = defaults.filter { it.kind != ModelKind.Speech }
    TopRow(counter, tr("بعداً", "Later"), onLater)
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Column(Modifier.gutter().padding(top = 26.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                DirectorDot(size = 14.dp)
                Text(tr("کارگردان", "The director"), style = Sig.type.meta.copy(fontWeight = FontWeight(700), brush = c.directorInkBrush))
            }
            DisplayText(
                buildAnnotatedString {
                    append(tr("روی گوشی\nخودت", "On your\nown phone"))
                    withStyle(SpanStyle(color = c.action)) { append(".") }
                },
                style = Sig.type.hero.copy(fontSize = 44.sp, lineHeight = 44.sp),
                modifier = Modifier.padding(top = 12.dp),
            )
            Text(
                tr(
                    "بدون اشتراک و بدون اینترنت کار می‌کند. یک بار دانلود؛ اگر قطع شد، از همان‌جا ادامه می‌دهد.",
                    "No subscription, no internet needed. One download; if it stops, it resumes where it left off.",
                ),
                style = Sig.type.body, color = c.muted, modifier = Modifier.padding(top = 12.dp),
            )
        }
        DeviceCard(device, Modifier.gutter().padding(top = 20.dp))
        Column(Modifier.gutter().padding(top = 18.dp)) {
            Hairline(strong = true)
            if (speech.isNotEmpty()) {
                ModelRow(TrimioIcons.Wave, tr("شنیدن و نوشتن", "Listening and writing"), tr("کلمه‌به‌کلمه، فارسی و انگلیسی", "Word by word, Persian and English"), speech, states)
                Hairline()
            }
            if (director.isNotEmpty()) {
                val params = director.firstOrNull { it.kind == ModelKind.Language }?.let(::paramsOf)
                val title = params?.let { localizedNumber(tr("کارگردان ($it میلیارد پارامتر)", "Director ($it billion parameters)")) } ?: tr("کارگردان", "Director")
                ModelRow(TrimioIcons.Sparkle, title, tr("طراحی صحنه، تأکید، صدا", "Scenes, emphasis, sound"), director, states)
            }
            // Only directors larger than the default one are "stronger".
            val bigger = stronger.filter { s -> (paramsOf(s)?.toFloatOrNull() ?: 0f) > (director.firstOrNull { it.kind == ModelKind.Language }?.let(::paramsOf)?.toFloatOrNull() ?: 0f) }
            if (bigger.isNotEmpty()) {
                Hairline()
                StrongerRow(bigger)
            }
        }
        Spacer(Modifier.height(16.dp))
    }
    BottomAction(tr("ادامه", "Continue"), onContinue, note = tr("دانلود در پس‌زمینه ادامه پیدا می‌کند", "Downloads keep going in the background"))
}

/** This device: RAM, cores and free space as big figures, with how high it can aim. */
@Composable
private fun DeviceCard(device: OnboardingDevice, modifier: Modifier) {
    val c = Sig.colors
    SigGlass(modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
        Column {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(tr("این گوشی", "This phone"), style = Sig.type.meta, color = c.muted)
                SigChip(
                    when {
                        device.ramGb >= ULTRA_RAM -> tr("مناسب بالاترین سطح", "Fits the top tier")
                        device.ramGb >= HIGH_RAM -> tr("مناسب سطح بالا", "Fits the high tier")
                        else -> tr("مناسب کارگردان پیش‌فرض", "Fits the default director")
                    },
                    container = c.emphasisSoft, content = c.emphasisSoftContent,
                    leading = { Box(Modifier.size(5.dp).clip(RoundedCornerShape(3.dp)).background(c.emphasisSoftContent)) },
                )
            }
            Row(Modifier.padding(top = 12.dp).height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                val figures = listOfNotNull(
                    Triple(device.ramGb.toString(), "GB", tr("حافظهٔ رم", "RAM")),
                    device.cores.takeIf { it > 0 }?.let { Triple(it.toString(), "cores", tr("پردازنده", "Processor")) },
                    device.freeBytes?.let { Triple((it / GB).toString(), "GB", tr("فضای خالی", "Free space")) },
                )
                figures.forEachIndexed { i, (value, unit, label) ->
                    if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(c.lineStrong))
                    Figure(value, unit, label)
                }
            }
        }
    }
}

@Composable
private fun Figure(value: String, unit: String, label: String) {
    val c = Sig.colors
    Column {
        Text(
            buildAnnotatedString {
                append("⁦$value")
                withStyle(SpanStyle(fontSize = 14.sp, color = c.muted)) { append(" $unit") }
                append("⁩")
            },
            style = Sig.type.figure.copy(fontSize = 30.sp, lineHeight = 30.sp), color = c.ink,
        )
        Text(label, style = Sig.type.meta.copy(fontSize = 11.sp), color = c.muted, modifier = Modifier.padding(top = 4.dp))
    }
}

/**
 * One model (with the files that go with it): what it does, its size, and its real state; while
 * it downloads, a bar with the bytes and the speed.
 */
@Composable
private fun ModelRow(icon: ImageVector, title: String, hint: String, specs: List<ModelSpec>, states: Map<String, ModelState>) {
    val c = Sig.colors
    val total = specs.sumOf { it.sizeBytes }
    val done = specs.sumOf { s ->
        when (val st = states[s.id]) {
            ModelState.Installed, is ModelState.UpdateAvailable -> s.sizeBytes
            is ModelState.Downloading -> st.bytes
            else -> 0L
        }
    }
    val all = specs.map { states[it.id] }
    val installed = all.all { it == ModelState.Installed || it is ModelState.UpdateAvailable }
    val downloading = all.any { it is ModelState.Downloading }
    val failed = all.any { it is ModelState.Failed }
    val speed = rememberSpeed(done, downloading)
    Column(Modifier.fillMaxWidth().heightIn(min = 70.dp).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            TrimioIcon(icon, null, tint = c.ink)
            Column(Modifier.weight(1f)) {
                Text(title, style = Sig.type.bodyStrong.copy(fontSize = 15.sp), color = c.ink)
                Text(hint, style = Sig.type.meta.copy(fontSize = 11.5.sp), color = c.muted)
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("⁦${size(total)}⁩", style = Sig.type.micro.copy(fontSize = 13.sp, fontWeight = FontWeight(700), letterSpacing = 0.sp), color = c.ink)
                when {
                    installed -> Text(tr("✓ نصب شد", "✓ Installed"), style = Sig.type.meta.copy(fontSize = 11.sp), color = c.emphasisSoftContent)
                    failed -> Text(tr("ناتمام ماند", "Stopped"), style = Sig.type.meta.copy(fontSize = 11.sp), color = c.action)
                    !downloading -> Text(tr("دانلود نشده", "Not downloaded"), style = Sig.type.meta.copy(fontSize = 11.sp), color = c.faint)
                }
            }
        }
        if (downloading) {
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.ink.copy(alpha = 0.1f))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth((done.toFloat() / total.coerceAtLeast(1)).coerceIn(0f, 1f)).background(c.action))
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(tr("در حال دانلود", "Downloading"), style = Sig.type.meta.copy(fontSize = 11.sp), color = c.muted)
                Text(
                    "⁦${gb(done)} / ${gb(total)} GB" + (speed?.let { " · $it MB/s" } ?: "") + "⁩",
                    style = Sig.type.micro.copy(fontSize = 11.sp, letterSpacing = 0.sp), color = c.muted,
                )
            }
        }
    }
}

/** Download speed in MB/s from the bytes seen over at least a second; null until there is a measure. */
@Composable
private fun rememberSpeed(bytes: Long, active: Boolean): Int? {
    var speed by remember { mutableStateOf<Int?>(null) }
    val sample = remember { arrayOfNulls<Pair<Long, TimeMark>>(1) }
    LaunchedEffect(bytes, active) {
        val last = sample[0]
        when {
            !active -> {
                speed = null
                sample[0] = null
            }
            last == null || bytes < last.first -> sample[0] = bytes to TimeSource.Monotonic.markNow()
            last.second.elapsedNow().inWholeMilliseconds >= MS -> {
                speed = ((bytes - last.first) * MS / last.second.elapsedNow().inWholeMilliseconds / MB).toInt()
                sample[0] = bytes to TimeSource.Monotonic.markNow()
            }
        }
    }
    return speed?.takeIf { active }
}

/** Bigger directors for later (installed in Settings → Models). */
@Composable
private fun StrongerRow(stronger: List<ModelSpec>) {
    val c = Sig.colors
    val params = stronger.mapNotNull(::paramsOf).distinct().sortedBy { it.toFloatOrNull() ?: 0f }
    val minRam = stronger.minOf { it.tier.minRamGb }
    val sizes = when (params.size) {
        0 -> ""
        1 -> params[0]
        else -> params.dropLast(1).joinToString(tr("، ", ", ")) + tr(" و ", " and ") + params.last()
    }
    Row(Modifier.fillMaxWidth().heightIn(min = 62.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
        TrimioIcon(TrimioIcons.Layers, null, tint = c.muted)
        Column(Modifier.weight(1f)) {
            Text(tr("کارگردان قوی‌تر (اختیاری)", "A stronger director (optional)"), style = Sig.type.bodyStrong.copy(fontSize = 15.sp), color = c.muted)
            Text(
                localizedNumber(tr("$sizes میلیارد · برای $minRam گیگ رم به بالا", "$sizes billion · for $minRam GB RAM and up")),
                style = Sig.type.meta.copy(fontSize = 11.5.sp), color = c.muted,
            )
        }
        SigChip(tr("بعداً", "Later"), container = Color.Transparent, content = c.ink, stroke = c.ink)
    }
}

@Composable
private fun ColumnScope.Privacy(counter: String, onConnectCloud: () -> Unit, onFinish: () -> Unit) {
    val c = Sig.colors
    TopRow(counter, null, {})
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        DisplayText(
            buildAnnotatedString {
                append(tr("ویدیوی تو\nاز گوشی\nبیرون نمی‌رود", "Your video\nnever leaves\nyour phone"))
                withStyle(SpanStyle(color = c.action)) { append(".") }
            },
            style = Sig.type.hero.copy(fontSize = 42.sp, lineHeight = 42.sp),
            modifier = Modifier.gutter().padding(top = 26.dp),
        )
        Column(Modifier.gutter().padding(top = 22.dp)) {
            Hairline(strong = true)
            Promise(1, tr("همه چیز روی گوشی ساخته می‌شود", "Everything is made on the phone"), tr("شنیدن، طراحی و رندر؛ بدون سرور.", "Listening, design and render; no server."))
            Promise(2, tr("بدون حساب، بدون تبلیغ", "No account, no ads"), tr("هیچ داده‌ای جمع نمی‌کنیم.", "We collect no data."))
            Promise(3, tr("خروجی بدون محدودیت حق نشر", "Output free of copyright limits"), tr("صداها، موسیقی و المان‌ها اختصاصی‌اند.", "The sounds, music and elements are our own."))
        }
        CloudCard(onConnectCloud, Modifier.gutter().padding(top = 20.dp))
        Spacer(Modifier.height(16.dp))
    }
    BottomAction(tr("بزن بریم", "Let's go"), onFinish)
}

@Composable
private fun Promise(index: Int, title: String, hint: String) {
    val c = Sig.colors
    Column {
        Row(Modifier.fillMaxWidth().heightIn(min = 66.dp).padding(top = 14.dp, bottom = 6.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("⁦0$index⁩", style = Sig.type.index.copy(fontSize = 22.sp, lineHeight = 26.sp), color = c.muted, modifier = Modifier.width(36.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = Sig.type.bodyStrong.copy(fontSize = 15.sp), color = c.ink)
                Text(hint, style = Sig.type.meta, color = c.muted, modifier = Modifier.padding(top = 3.dp))
            }
        }
        Hairline()
    }
}

/** The optional cloud director, with the creator's own key. */
@Composable
private fun CloudCard(onConnectCloud: () -> Unit, modifier: Modifier) {
    val c = Sig.colors
    SigGlass(modifier.fillMaxWidth(), contentPadding = PaddingValues(16.dp)) {
        Column {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                DirectorDot()
                Column(Modifier.weight(1f)) {
                    Text(tr("کارگردان ابری (اختیاری)", "Cloud director (optional)"), style = Sig.type.bodyStrong.copy(fontSize = 15.sp), color = c.ink)
                    Text(
                        tr("با کلید Claude یا OpenAI خودت؛ مستقیم، نه از سرور ما.", "With your own Claude or OpenAI key; direct, never through our server."),
                        style = Sig.type.meta.copy(fontSize = 11.5.sp), color = c.muted, modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
            val shape = RoundedCornerShape(SigSpace.radiusMd)
            Row(
                Modifier.padding(top = 12.dp).fillMaxWidth().height(44.dp).clip(shape).border(1.dp, c.lineStrong, shape).clickable(role = Role.Button, onClick = onConnectCloud),
                horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TrimioIcon(TrimioIcons.Key, null, tint = c.ink, size = 18.dp)
                Text(tr("افزودن کلید", "Add a key"), style = Sig.type.button.copy(fontSize = 13.5.sp, fontWeight = FontWeight(700)), color = c.ink)
            }
        }
    }
}

/** The page's ember action at the foot, with an optional note under it. */
@Composable
private fun BottomAction(text: String, onClick: () -> Unit, trailing: ImageVector? = null, note: String? = null) {
    Column(Modifier.fillMaxWidth().gutter().padding(top = 12.dp, bottom = boardBottom(34.dp))) {
        SigButton(text, onClick, Modifier.fillMaxWidth(), trailing = trailing)
        note?.let {
            Text(it, style = Sig.type.meta.copy(fontSize = 11.5.sp), color = Sig.colors.muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(top = 10.dp))
        }
    }
}

/** "4" from "qwen3.5-4b-q4km", "8" from "lfm2.5-8b-a1b-q4km": the parameter class in billions. */
private fun paramsOf(spec: ModelSpec): String? = PARAMS.find(spec.id.substringAfter('-'))?.groupValues?.get(1)

private fun size(bytes: Long): String = if (bytes >= GB) "${(bytes * 10 + GB / 2) / GB / 10.0} GB" else "${bytes / MB} MB"

private fun gb(bytes: Long): String {
    val hundredths = (bytes * 100 + GB / 2) / GB
    return "${hundredths / 100}.${(hundredths % 100).toString().padStart(2, '0')}".trimEnd('0').trimEnd('.').ifEmpty { "0" }
}

private val PARAMS = Regex("(\\d+(?:\\.\\d+)?)b")
private const val GB = 1_000_000_000L
private const val MB = 1_000_000L
private const val MS = 1000L
private const val HIGH_RAM = 12
private const val ULTRA_RAM = 16
private const val FOOTAGE_SHARE = 0.56f
private val TOP_ROW = 29.dp
const val PAGE_WELCOME = 0
const val PAGE_MODELS = 1
const val PAGE_PRIVACY = 2
