package io.trimio.feature.create

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
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

/**
 * What a video becomes: a fully directed reel, a plain reel (subtitles and its silences cut, nothing else), or a long
 * YouTube video with subtitles only, or with YouTube's devices too.
 */
enum class EditChoice(val fa: String, val en: String, val faNote: String, val enNote: String) {
    Reel("ریلز", "Reel", "ادیت کامل", "Full edit"),
    Simple("ریلز ساده", "Plain reel", "زیرنویس، بی‌سکوت", "Subtitles, no pauses"),
    YouTube("یوتیوب", "YouTube", "فقط زیرنویس", "Subtitles only"),
    YouTubePro("یوتیوب حرفه‌ای", "YouTube pro", "فصل و سابسکرایب", "Chapters, subscribe"),
    ;

    /** Whether the edit is its subtitles (a style is picked for them). */
    val subtitled: Boolean get() = this != Reel
}

/** The subtitle looks a subtitled edit can take (the engine's SubtitleStyle ids). */
enum class SubtitleChoice(val id: String, val fa: String, val en: String) {
    Classic("classic", "کلاسیک", "Classic"),
    Box("box", "جعبه‌ای", "Box"),
    Karaoke("karaoke", "کارائوکه", "Karaoke"),
    Pop("pop", "پاپ درشت", "Bold pop"),
    Highlight("highlight", "هایلایت", "Highlight"),
    Minimal("minimal", "مینیمال", "Minimal"),
    ;

    companion object {
        /** A reel reads best word by word; an hour of YouTube in calm lines. */
        fun default(edit: EditChoice): SubtitleChoice = if (edit == EditChoice.Simple) Pop else Classic
    }
}

/** «سبک زیرنویس»: the looks side by side, each named in its own words; the picked one inked. */
@Composable
internal fun SubtitleChoiceRow(selected: SubtitleChoice, onPick: (SubtitleChoice) -> Unit, modifier: Modifier = Modifier) {
    val c = Sig.colors
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(tr("سبک زیرنویس", "Subtitle style"), style = Sig.type.meta.copy(fontSize = 11.sp), color = c.muted)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SubtitleChoice.entries.forEach { choice ->
                val on = choice == selected
                Text(
                    tr(choice.fa, choice.en),
                    style = Sig.type.body.copy(fontSize = 13.sp, fontWeight = FontWeight(if (on) 800 else 600)),
                    color = if (on) c.canvas else c.ink, maxLines = 1,
                    modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(if (on) c.ink else c.surface)
                        .clickable { onPick(choice) }.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
        }
    }
}

/**
 * «ریلز / ریلز ساده / یوتیوب / یوتیوب حرفه‌ای» for a video: a reel is cut and directed; a plain reel is its subtitles
 * with the silences cut; a YouTube video plays as recorded with accurate subtitles (no cut, no silence removed), and
 * the pro kind adds the watermark, the subscribe ask and the chapter cards. Both YouTube kinds also give the subtitle
 * files, the chapters for the description and titles.
 */
@Composable
internal fun EditChoiceRow(selected: EditChoice, onEdit: (EditChoice) -> Unit, modifier: Modifier = Modifier) {
    val c = Sig.colors
    // Two by two: every name and its note in full on a phone.
    Column(
        modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(c.surface).background(c.ink.copy(alpha = 0.04f)).padding(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        EditChoice.entries.chunked(2).forEach { pair ->
            Row(Modifier.fillMaxWidth().height(50.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                pair.forEach { choice ->
                    val on = choice == selected
                    Column(
                        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(if (on) c.ink else c.surface.copy(alpha = 0f))
                            .clickable { onEdit(choice) }.padding(horizontal = 8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        Text(
                            tr(choice.fa, choice.en), style = Sig.type.body.copy(fontSize = 13.5.sp, fontWeight = FontWeight(800)),
                            color = if (on) c.canvas else c.ink, maxLines = 1, textAlign = TextAlign.Center,
                        )
                        Text(
                            tr(choice.faNote, choice.enNote), style = Sig.type.meta.copy(fontSize = 10.sp),
                            color = if (on) c.canvas.copy(alpha = 0.75f) else c.muted, maxLines = 1, textAlign = TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

/** What the video becomes and, for a subtitled edit, how its subtitles look: a section of the brief. */
@Composable
internal fun EditKindBlock(edit: EditChoice, subtitles: SubtitleChoice?, onEdit: (EditChoice) -> Unit, onSubtitles: (SubtitleChoice) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        io.trimio.core.designsystem.signature.SigSectionLabel(tr("نوع ادیت", "Kind of edit"))
        EditChoiceRow(edit, onEdit)
        if (edit.subtitled) SubtitleChoiceRow(subtitles ?: SubtitleChoice.default(edit), onSubtitles)
    }
}
