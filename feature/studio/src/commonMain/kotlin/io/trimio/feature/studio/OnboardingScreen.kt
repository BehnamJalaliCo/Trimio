package io.trimio.feature.studio

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.trimio.core.designsystem.component.ButtonKind
import io.trimio.core.designsystem.component.GlassPanel
import io.trimio.core.designsystem.component.LiquidProgressOrb
import io.trimio.core.designsystem.component.PillSelector
import io.trimio.core.designsystem.component.RingSegment
import io.trimio.core.designsystem.component.TrimioButton
import io.trimio.core.designsystem.component.TrimioIcon
import io.trimio.core.designsystem.component.TrimioScreen
import io.trimio.core.designsystem.icon.TrimioIcons
import io.trimio.core.designsystem.theme.Trimio
import io.trimio.core.designsystem.theme.TrimioSpacing
import io.trimio.core.designsystem.theme.localizedNumber
import io.trimio.core.designsystem.theme.tr
import io.trimio.core.model.text.Language
import io.trimio.engine.models.ModelSpec

/**
 * First run, three beats: what Trimio does (and the UI language), the on-device models it needs
 * (downloaded now on Wi-Fi or later), and the privacy promise with the optional cloud director.
 */
@Composable
fun OnboardingScreen(
    language: Language,
    defaults: List<ModelSpec>,
    onLanguage: (Language) -> Unit,
    onDownloadDefaults: () -> Unit,
    onConnectCloud: () -> Unit,
    onFinish: () -> Unit,
) {
    // Platforms that cannot store models (web) skip the download page.
    val pages = if (defaults.isEmpty()) listOf(PAGE_WELCOME, PAGE_PRIVACY) else listOf(PAGE_WELCOME, PAGE_MODELS, PAGE_PRIVACY)
    var index by remember { mutableIntStateOf(0) }
    val step = pages[index]
    fun next() { index = (index + 1).coerceAtMost(pages.lastIndex) }
    val rtl = language.isRtl
    val motion = Trimio.motion
    TrimioScreen(dimAurora = 0.05f, energy = { 0.35f + index * 0.2f }) {
        // Phone-width column, centred on tablets, desktops and the web.
        Column(
            Modifier.fillMaxHeight().widthIn(max = 560.dp).align(Alignment.Center).padding(TrimioSpacing.screenGutter),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Dots(index, pages.size)
            AnimatedContent(
                targetState = step,
                transitionSpec = {
                    val dir = if ((targetState > initialState) xor rtl) 1 else -1
                    (slideInHorizontally(motion.spatial()) { it / 3 * dir } + fadeIn(motion.effects())) togetherWith
                        (slideOutHorizontally(motion.spatial()) { -it / 3 * dir } + fadeOut(motion.effectsFast()))
                },
                modifier = Modifier.weight(1f),
                label = "onboarding",
            ) { page ->
                Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    when (page) {
                        PAGE_WELCOME -> Welcome(language, onLanguage)
                        PAGE_MODELS -> Models(defaults)
                        else -> Privacy()
                    }
                }
            }
            when (step) {
                PAGE_WELCOME -> TrimioButton(tr("شروع", "Get started"), ::next, Modifier.fillMaxWidth())
                PAGE_MODELS -> {
                    TrimioButton(
                        tr("دانلود مدل\u200Cها", "Download models"), { onDownloadDefaults(); next() }, Modifier.fillMaxWidth(),
                        leading = { TrimioIcon(TrimioIcons.Download, null, tint = Trimio.colors.onPrimary) },
                    )
                    TrimioButton(tr("بعدا\u064B", "Later"), ::next, Modifier.fillMaxWidth(), kind = ButtonKind.Ghost)
                }
                else -> {
                    TrimioButton(tr("بزن بریم", "Let's go"), onFinish, Modifier.fillMaxWidth())
                    TrimioButton(tr("کلید Claude / OpenAI دارم", "I have a Claude / OpenAI key"), onConnectCloud, Modifier.fillMaxWidth(), kind = ButtonKind.Ghost)
                }
            }
        }
    }
}

@Composable
private fun Dots(current: Int, count: Int) {
    Row(Modifier.padding(vertical = TrimioSpacing.md), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        repeat(count) { i ->
            Box(
                Modifier.height(6.dp).width(if (i == current) 22.dp else 6.dp).clip(RoundedCornerShape(3.dp))
                    .background(if (i == current) Trimio.colors.accentCyan else Trimio.colors.glassStroke),
            )
        }
    }
}

@Composable
private fun Welcome(language: Language, onLanguage: (Language) -> Unit) {
    LiquidProgressOrb(progress = 0.62f, segments = List(9) { RingSegment(1f, if (it < 6) 1f else 0f, active = it == 6) }, label = "", size = 220.dp, showValue = false)
    Spacer(Modifier.height(TrimioSpacing.xl))
    Text(
        tr("ویدیو یا صدا بده،\nموشن\u200Cگرافی بگیر", "Bring a video or a voice.\nGet motion graphics."),
        style = Trimio.type.display.copy(textAlign = TextAlign.Center),
        color = Trimio.colors.textPrimary,
    )
    Spacer(Modifier.height(TrimioSpacing.md))
    Text(
        tr(
            "زیرنویس کلمه\u200Cبه\u200Cکلمه، تأکید، المان، صدا و موسیقی — با یک پرامپت ساده یا یک بریف کامل.",
            "Word-accurate captions, emphasis, elements, sound and music — from a simple prompt or a full brief.",
        ),
        style = Trimio.type.body.copy(textAlign = TextAlign.Center),
        color = Trimio.colors.textSecondary,
    )
    Spacer(Modifier.height(TrimioSpacing.xl))
    PillSelector(
        options = listOf(Language.Persian, Language.English),
        selected = language,
        label = { if (it == Language.Persian) "فارسی" else "English" },
        onSelect = onLanguage,
    )
}

@Composable
private fun Models(defaults: List<ModelSpec>) {
    Feature(TrimioIcons.Phone, tr("هوش مصنوعی روی گوشی خودت", "AI that runs on your phone"))
    Spacer(Modifier.height(TrimioSpacing.lg))
    GlassPanel(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(TrimioSpacing.md)) {
            defaults.forEach { spec ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TrimioIcon(if (spec.titleEn.contains("speech", true)) TrimioIcons.Mic else TrimioIcons.Sparkle, null, tint = Trimio.colors.accentCyan)
                    Spacer(Modifier.width(TrimioSpacing.md))
                    Text(tr(spec.titleFa, spec.titleEn), style = Trimio.type.title, color = Trimio.colors.textPrimary, modifier = Modifier.weight(1f))
                    Text(localizedNumber(size(spec.sizeBytes)), style = Trimio.type.numeric, color = Trimio.colors.textSecondary)
                }
            }
            Text(
                tr("یک\u200Cبار دانلود می\u200Cشود، روی Wi‑Fi پیشنهاد می\u200Cشود و اگر قطع شد از همان\u200Cجا ادامه می\u200Cدهد.", "Downloaded once. Wi‑Fi recommended; it resumes if interrupted."),
                style = Trimio.type.caption, color = Trimio.colors.textTertiary,
            )
        }
    }
}

@Composable
private fun Privacy() {
    Feature(TrimioIcons.Key, tr("ویدیوی تو، فقط مال خودت", "Your video stays yours"))
    Spacer(Modifier.height(TrimioSpacing.lg))
    Text(
        tr(
            "همه\u200Cچیز روی گوشی پردازش می\u200Cشود؛ بدون اشتراک و بدون سرور. اگر خواستی، کارگردان ابری Claude یا OpenAI را با کلید خودت وصل کن.",
            "Everything is processed on your phone: no subscription, no server. If you like, connect a Claude or OpenAI director with your own key.",
        ),
        style = Trimio.type.body.copy(textAlign = TextAlign.Center),
        color = Trimio.colors.textSecondary,
    )
}

@Composable
private fun Feature(icon: ImageVector, title: String) {
    Box(
        Modifier.size(88.dp).clip(CircleShape).background(Brush.radialGradient(listOf(Trimio.colors.primary, Trimio.colors.accentMagenta.copy(alpha = 0.2f)))),
        contentAlignment = Alignment.Center,
    ) { TrimioIcon(icon, null, tint = Trimio.colors.onPrimary, size = 38.dp) }
    Spacer(Modifier.height(TrimioSpacing.lg))
    Text(title, style = Trimio.type.headline.copy(textAlign = TextAlign.Center), color = Trimio.colors.textPrimary)
}

/** Persian units read naturally in an RTL line ("۲٫۷ گیگ"); Latin units would be reordered by bidi. */
@Composable
private fun size(bytes: Long): String {
    val tenths = kotlin.math.round(bytes / 100_000_000.0).toLong()
    return if (tenths >= 10) tr("${tenths / 10}٫${tenths % 10} گیگ", "${tenths / 10}.${tenths % 10} GB") else tr("${bytes / 1_000_000} مگ", "${bytes / 1_000_000} MB")
}

private const val PAGE_WELCOME = 0
private const val PAGE_MODELS = 1
private const val PAGE_PRIVACY = 2
