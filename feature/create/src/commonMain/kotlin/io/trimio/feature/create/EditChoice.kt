package io.trimio.feature.create

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.trimio.core.designsystem.signature.Sig
import io.trimio.core.designsystem.theme.tr

/** What a video becomes: a reel, or a long YouTube video with subtitles only, or with YouTube's devices too. */
enum class EditChoice(val fa: String, val en: String, val faNote: String, val enNote: String) {
    Reel("ریلز", "Reel", "ادیت کامل", "Full edit"),
    YouTube("یوتیوب", "YouTube", "فقط زیرنویس", "Subtitles only"),
    YouTubePro("یوتیوب حرفه‌ای", "YouTube pro", "زیرنویس + فصل و سابسکرایب", "Subtitles + chapters, subscribe"),
}

/**
 * «ریلز / یوتیوب / یوتیوب حرفه‌ای» for a video: a reel is cut and directed; a YouTube video plays as recorded with
 * accurate subtitles (no cut, no silence removed), and the pro kind adds the watermark, the subscribe ask and the
 * chapter cards. Both YouTube kinds also give the subtitle files, the chapters for the description and titles.
 */
@Composable
internal fun EditChoiceRow(selected: EditChoice, onEdit: (EditChoice) -> Unit, modifier: Modifier = Modifier) {
    val c = Sig.colors
    Row(
        modifier.fillMaxWidth().height(54.dp).clip(RoundedCornerShape(14.dp)).background(c.surface).background(c.ink.copy(alpha = 0.04f)).padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        EditChoice.entries.forEach { choice ->
            val on = choice == selected
            Column(
                Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(if (on) c.ink else c.surface.copy(alpha = 0f))
                    .clickable { onEdit(choice) }.padding(horizontal = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    tr(choice.fa, choice.en), style = Sig.type.body.copy(fontSize = 13.5.sp, fontWeight = FontWeight(800)),
                    color = if (on) c.canvas else c.ink, maxLines = 1, textAlign = TextAlign.Center,
                )
                Text(
                    tr(choice.faNote, choice.enNote), style = Sig.type.meta.copy(fontSize = 9.5.sp),
                    color = if (on) c.canvas.copy(alpha = 0.75f) else c.muted, maxLines = 1, textAlign = TextAlign.Center,
                )
            }
        }
    }
}
