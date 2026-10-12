package io.trimio.feature.create

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.trimio.core.designsystem.signature.Sig
import io.trimio.core.designsystem.theme.localizedNumber
import io.trimio.core.designsystem.theme.tr
import io.trimio.engine.models.ModelFit
import io.trimio.engine.models.ModelSpec
import io.trimio.engine.models.SpeedMode
import io.trimio.engine.models.SpeedModes
import io.trimio.engine.models.SpeedOption
import io.trimio.engine.models.SpeedPlan

/**
 * «سریع / متعادل / حرفه‌ای» above the make button: each speed with the director it runs on this
 * device (and, on a desktop with a known card, how much of it the card holds). A speed whose
 * director is not installed says so and leads to the models screen. On a phone, a director that
 * only just fits gets a plain warning above the choice.
 */
@Composable
internal fun SpeedChoice(plan: SpeedPlan, selected: SpeedMode, onSpeed: (SpeedMode) -> Unit, modifier: Modifier = Modifier) {
    val c = Sig.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (plan.tight(selected)) TightWarning(plan.of(selected).fit)
        Row(
            // Opaque: on a phone the choice sits over the scrolled brief.
            Modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(14.dp)).background(c.surface).background(c.ink.copy(alpha = 0.04f)).padding(4.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            plan.options.forEach { option ->
                Segment(option, on = option.mode == selected, suggested = option.mode == plan.default, desktop = plan.desktop, Modifier.weight(1f)) { onSpeed(option.mode) }
            }
        }
    }
}

@Composable
private fun Segment(option: SpeedOption, on: Boolean, suggested: Boolean, desktop: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val c = Sig.colors
    val live = option.available
    val ink = when {
        on -> c.canvas
        live -> c.ink
        else -> c.faint
    }
    Box(
        modifier.fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(if (on) c.ink else Color.Transparent)
            .clickable(role = Role.Tab, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(name(option.mode), style = Sig.type.bodyStrong.copy(fontSize = 13.sp), color = ink, maxLines = 1)
            Text(
                detail(option, desktop), style = Sig.type.meta.copy(fontSize = 10.sp, fontWeight = FontWeight(600)),
                color = if (on) c.canvas.copy(alpha = 0.7f) else if (live) c.muted else c.faint, maxLines = 1,
            )
        }
        // The speed this device runs best, while another is chosen.
        if (suggested && !on && live) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(5.dp).clip(CircleShape).background(c.live))
    }
}

@Composable
private fun TightWarning(fit: ModelFit?) {
    val c = Sig.colors
    Text(
        if (fit == ModelFit.Fits) {
            tr(
                "این مدل روی این گوشی فقط «جا می‌شود»؛ ممکن است اندروید ساخت را نیمه‌کاره ببندد. «متعادل» امن‌تر است.",
                "This director only just fits this phone: Android may close other apps, or Trimio mid-build. Balanced is safer.",
            )
        } else {
            tr(
                "این مدل برای این گوشی «آزمایشی» است و ممکن است ساخت نیمه‌کاره بماند. «متعادل» امن‌تر است.",
                "This director is experimental on this phone and the build may stop halfway. Balanced is safer.",
            )
        },
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.emphasisSoft).padding(horizontal = 12.dp, vertical = 8.dp),
        style = Sig.type.meta.copy(fontSize = 11.5.sp, fontWeight = FontWeight(600)), color = c.emphasisSoftContent,
    )
}

@Composable
private fun name(mode: SpeedMode) = when (mode) {
    SpeedMode.Fast -> tr("سریع", "Fast")
    SpeedMode.Balanced -> tr("متعادل", "Balanced")
    SpeedMode.Pro -> tr("حرفه‌ای", "Pro")
}

/** "MoE", "4B", "9B" (with the card's share on a desktop), or what is missing. */
@Composable
private fun detail(option: SpeedOption, desktop: Boolean): String {
    val model = option.model ?: return if (option.suggested != null) tr("دانلود", "Download") else tr("روی این دستگاه نیست", "Not here")
    val share = option.gpuShare
    // Model sizes and the card's share stay Latin, as the models screen writes them.
    return if (desktop && share != null) "⁦${tier(model)} · ${percent(share)}% GPU⁩" else "⁦${tier(model)}⁩"
}

/** The line under the make button: the chosen speed's director, how it fits, and the card it runs on. */
@Composable
internal fun speedLine(plan: SpeedPlan, mode: SpeedMode): String {
    val option = plan.of(mode)
    val model = option.model ?: return if (option.suggested != null) {
        tr("مدل این سرعت نصب نیست؛ لمسش کن تا دانلود شود", "This speed's director is not installed; tap it to download")
    } else {
        tr("این سرعت روی این دستگاه نیست", "This speed is not available on this device")
    }
    val gpu = plan.gpu
    val share = option.gpuShare
    if (plan.desktop && gpu != null && share != null) {
        // The card's name stays as the system writes it; only the share is written in the reader's digits.
        val card = "⁦${short(gpu.names.first())} · ${(gpu.totalBytes + HALF_GB) shr GB_SHIFT} GB⁩"
        val pct = localizedNumber("${percent(share)}")
        val rest = if (share < SpeedModes.WHOLE_ON_GPU) tr("، بقیه روی پردازنده", ", the rest on the CPU") else ""
        val advice = if (mode != plan.default) tr(" · پیشنهاد این کارت: «${name(plan.default)}»", " · best for this card: ${name(plan.default)}") else ""
        return tr("$card: $pct٪ مدل روی کارت گرافیک$rest$advice", "$card: $pct% of the model on the graphics card$rest$advice")
    }
    val fit = when (option.fit) {
        ModelFit.Recommended -> tr("مناسب این دستگاه", "right for this device")
        ModelFit.Fits -> tr("جا می‌شود", "fits")
        else -> tr("آزمایشی", "experimental")
    }
    return "⁦${tier(model)}⁩ · $fit · " + tr("روی همین دستگاه، بدون اینترنت", "on this device, offline")
}

private fun tier(spec: ModelSpec): String =
    if (spec.activeParamsB != null) "MoE" else TIER.find(spec.id)?.value?.uppercase() ?: spec.titleEn

/** "NVIDIA GeForce RTX 3050 Ti Laptop GPU" → "RTX 3050 Ti". */
private fun short(name: String) = name.replace("NVIDIA", "").replace("GeForce", "").replace("Laptop GPU", "").replace("GPU", "").trim().ifEmpty { name }

private fun percent(share: Float) = (share * 100).toInt()

private val TIER = Regex("(?i)\\be?\\d+(?:\\.\\d+)?b\\b")
private const val GB_SHIFT = 30
private const val HALF_GB = 1L shl 29
