package io.trimio.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.trimio.core.designsystem.component.TrimioIcon
import io.trimio.core.designsystem.icon.TrimioIcons
import io.trimio.core.designsystem.signature.DirectorDot
import io.trimio.core.designsystem.signature.LiveDot
import io.trimio.core.designsystem.signature.Sig
import io.trimio.core.designsystem.signature.SigAdaptive
import io.trimio.core.designsystem.signature.SigButton
import io.trimio.core.designsystem.signature.SigChip
import io.trimio.core.designsystem.signature.SigGlass
import io.trimio.core.designsystem.signature.SigSpace
import io.trimio.core.designsystem.signature.SigTextButton
import io.trimio.core.designsystem.theme.localizedNumber
import io.trimio.core.designsystem.theme.tr

/**
 * What the offline sheet says about the director on this device: its tier ("4B") when one is
 * installed (null: the rules direct, no model), and about how long it takes to design this video.
 */
data class OfflineDirector(val tier: String?, val seconds: Int)

/**
 * Board 4, last phone: the cloud director was chosen but there is no internet. The director on the
 * device is ready and does the same work offline; or the build waits and starts in the cloud once
 * the connection is back. A bottom sheet on phones, a centred card on wide windows.
 */
@Composable
fun OfflineSheet(director: OfflineDirector, onDevice: () -> Unit, onWait: () -> Unit, onDismiss: () -> Unit) {
    SigAdaptive { width ->
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
            contentAlignment = if (width.wide) Alignment.Center else Alignment.BottomCenter,
        ) {
            val c = Sig.colors
            val shape = if (width.wide) RoundedCornerShape(28.dp) else RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            Column(
                Modifier.then(if (width.wide) Modifier.widthIn(max = 460.dp).fillMaxWidth(0.9f) else Modifier.fillMaxWidth())
                    .shadow(40.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
                    .clip(shape).background(c.surface).border(1.dp, c.glassRim.copy(alpha = 0.5f), shape)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(bottom = if (width.wide) 22.dp else 34.dp),
            ) {
                if (!width.wide) Box(Modifier.fillMaxWidth().padding(top = 10.dp), contentAlignment = Alignment.Center) {
                    Box(Modifier.size(40.dp, 5.dp).clip(CircleShape).background(c.lineStrong))
                }
                Column(Modifier.padding(horizontal = SigSpace.gutter).padding(top = 22.dp)) {
                    Box(Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(c.ink.copy(alpha = 0.08f)), contentAlignment = Alignment.Center) {
                        TrimioIcon(TrimioIcons.WifiOff, null, tint = c.ink, size = 24.dp)
                    }
                    Text(tr("اینترنت در دسترس نیست", "No internet connection"), Modifier.padding(top = 16.dp), style = Sig.type.title.copy(fontSize = 24.sp), color = c.ink)
                    Text(
                        tr(
                            "کارگردان ابری به اینترنت نیاز دارد. کارگردان روی گوشی همین حالا آماده است و همین کار را آفلاین انجام می‌دهد.",
                            "The cloud director needs the internet. The director on this device is ready now and does the same work offline.",
                        ),
                        Modifier.padding(top = 8.dp), style = Sig.type.body, color = c.muted,
                    )
                    DeviceCard(director, Modifier.padding(top = 18.dp))
                }
                Column(Modifier.padding(horizontal = SigSpace.gutter).padding(top = 40.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    SigButton(tr("ادامه روی گوشی", "Continue on device"), onDevice, Modifier.fillMaxWidth(), trailing = TrimioIcons.Phone)
                    Box(
                        Modifier.fillMaxWidth().height(52.dp).clip(RoundedCornerShape(16.dp)).border(1.dp, c.lineStrong, RoundedCornerShape(16.dp))
                            .clickable(role = Role.Button, onClick = onWait),
                        contentAlignment = Alignment.Center,
                    ) { Text(tr("وقتی وصل شد، با ابری بساز", "Build in the cloud once connected"), style = Sig.type.button.copy(fontSize = 15.sp), color = c.ink) }
                }
            }
        }
    }
}

/** The on-device director, iridescent: its tier and the time it needs, and that it is ready. */
@Composable
private fun DeviceCard(director: OfflineDirector, modifier: Modifier) {
    val c = Sig.colors
    SigGlass(modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            DirectorDot(size = 14.dp)
            Column(Modifier.weight(1f)) {
                val title = director.tier?.let { tr("کارگردان روی گوشی · ⁦${localizedNumber(it)}⁩", "On-device director · $it") } ?: tr("قواعد روی گوشی، بدون مدل", "On-device rules, no model")
                Text(title, style = Sig.type.bodyStrong.copy(fontSize = 14.sp), color = c.ink, maxLines = 1)
                Text(
                    localizedNumber(tr("حدود ${director.seconds} ثانیه برای طراحی این ویدیو", "About ${director.seconds} s to design this video")),
                    style = Sig.type.meta.copy(fontSize = 11.5.sp), color = c.muted, maxLines = 1,
                )
            }
            SigChip(tr("آماده", "Ready"), Modifier.height(24.dp), container = c.emphasisSoft, content = c.emphasisSoftContent)
        }
    }
}

/** While a cloud build waits for the connection: a quiet bar with the way out. */
@Composable
fun WaitingForNetwork(onCancel: () -> Unit, modifier: Modifier = Modifier) {
    val c = Sig.colors
    SigGlass(modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp), contentPadding = PaddingValues(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            LiveDot(size = 8.dp)
            Text(
                tr("منتظر اینترنت؛ با وصل شدن، ساخت ابری شروع می‌شود", "Waiting for the internet; the cloud build starts once connected"),
                Modifier.weight(1f), style = Sig.type.meta.copy(fontSize = 12.sp), color = c.ink, maxLines = 2,
            )
            SigTextButton(tr("لغو", "Cancel"), onCancel)
        }
    }
}

/** About how long the on-device director takes to design a video of [mediaSeconds] (a 36 s reel: about 20 s). */
fun designSeconds(mediaSeconds: Float): Int = (mediaSeconds * DESIGN_SHARE).toInt().coerceIn(MIN_SECONDS, MAX_SECONDS)

private const val DESIGN_SHARE = 0.55f
private const val MIN_SECONDS = 8
private const val MAX_SECONDS = 180
