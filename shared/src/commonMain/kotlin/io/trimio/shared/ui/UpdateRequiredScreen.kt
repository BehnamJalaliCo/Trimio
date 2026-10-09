package io.trimio.shared.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.trimio.core.designsystem.component.GlassPanel
import io.trimio.core.designsystem.component.TrimioButton
import io.trimio.core.designsystem.component.TrimioIcon
import io.trimio.core.designsystem.component.TrimioScreen
import io.trimio.core.designsystem.icon.TrimioIcons
import io.trimio.core.designsystem.theme.Trimio
import io.trimio.core.designsystem.theme.TrimioSpacing
import io.trimio.core.designsystem.theme.tr

/**
 * Shown instead of the app when the server says this build is no longer supported (a security
 * fix or a project format change). Projects stay on the phone; updating keeps them.
 */
@Composable
fun UpdateRequiredScreen(onUpdate: (() -> Unit)?) {
    TrimioScreen(dimAurora = 0.15f) {
        Box(Modifier.fillMaxSize().padding(TrimioSpacing.screenGutter), contentAlignment = Alignment.Center) {
            GlassPanel(Modifier.widthIn(max = 420.dp).fillMaxWidth()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(TrimioSpacing.md)) {
                    TrimioIcon(TrimioIcons.Sparkle, null, tint = Trimio.colors.accentCyan)
                    Text(tr("نسخهٔ تازه آماده است", "A new version is ready"), style = Trimio.type.title, color = Trimio.colors.textPrimary)
                    Text(
                        tr(
                            "این نسخه دیگر پشتیبانی نمی‌شود. پروژه‌هایت روی گوشی می‌مانند و بعد از به‌روزرسانی سر جایشان هستند.",
                            "This version is no longer supported. Your projects stay on this phone and will be there after the update.",
                        ),
                        style = Trimio.type.body, color = Trimio.colors.textSecondary, textAlign = TextAlign.Center,
                    )
                    if (onUpdate != null) TrimioButton(tr("به‌روزرسانی", "Update"), onUpdate, Modifier.fillMaxWidth())
                }
            }
        }
    }
}
