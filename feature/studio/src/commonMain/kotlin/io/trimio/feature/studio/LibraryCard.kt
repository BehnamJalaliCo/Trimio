package io.trimio.feature.studio

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.trimio.core.designsystem.component.TrimioIcon
import io.trimio.core.designsystem.icon.TrimioIcons
import io.trimio.core.designsystem.signature.DirectorDot
import io.trimio.core.designsystem.signature.Sig
import io.trimio.core.designsystem.signature.SigGlass
import io.trimio.core.designsystem.theme.localizedNumber
import io.trimio.core.designsystem.theme.tr

/** The suggestion on home once setup is done: the libraries that make edits richer, how many and how big. */
data class LibraryHint(val packs: Int, val sizeBytes: Long)

/** «کتابخانه‌ها را بگیر»: opens the library screen; the close mark dismisses it for good. */
@Composable
internal fun LibraryCard(hint: LibraryHint, onOpen: () -> Unit, onDismiss: () -> Unit, modifier: Modifier = Modifier) {
    val c = Sig.colors
    val mb = (hint.sizeBytes + (1L shl 19)) shr 20
    SigGlass(modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).clickable(role = Role.Button, onClick = onOpen), contentPadding = PaddingValues(16.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Top) {
            DirectorDot(Modifier.padding(top = 3.dp), size = 12.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(tr("ادیت‌های حرفه‌ای‌تر با کتابخانه‌ها", "Richer edits with the libraries"), style = Sig.type.bodyStrong, color = c.ink)
                Text(
                    localizedNumber(
                        tr(
                            "${hint.packs} کتابخانهٔ پیشنهادی (ایموجی و آیکون متحرک، افکت صوتی) · حدود $mb مگابایت؛ بعد از دانلود بدون اینترنت کار می‌کند.",
                            "${hint.packs} suggested libraries (animated emoji and icons, sound effects) · about $mb MB; offline once downloaded.",
                        ),
                    ),
                    style = Sig.type.meta.copy(fontSize = 12.sp, lineHeight = 19.sp), color = c.muted,
                )
                Text(tr("دیدن کتابخانه‌ها", "See the libraries"), style = Sig.type.meta.copy(fontSize = 12.sp), color = c.action)
            }
            TrimioIcon(TrimioIcons.Close, tr("بستن", "Dismiss"), tint = c.muted, size = 16.dp, modifier = Modifier.clip(RoundedCornerShape(8.dp)).clickable(role = Role.Button, onClick = onDismiss))
        }
    }
}
