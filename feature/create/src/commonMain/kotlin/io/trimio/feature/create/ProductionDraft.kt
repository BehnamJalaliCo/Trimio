package io.trimio.feature.create

import androidx.compose.ui.graphics.ImageBitmap
import io.trimio.core.data.MediaKind

/** What a user attaches next to the prompt; the director places it where the speaker talks about it. */
enum class AttachmentKind { Picture, Clip, Logo }

/** One attached file, with an optional caption that tells the director what it is. */
data class Attachment(
    val id: String,
    val kind: AttachmentKind,
    val uri: String,
    val name: String,
    val caption: String = "",
    /** A small still for the card; null shows a designed placeholder for the kind. */
    val thumbnail: ImageBitmap? = null,
)

/** Picks an attachment from the system; null when the user backs out or the platform cannot. */
fun interface AttachmentPicker {
    suspend fun pick(kind: AttachmentKind): Attachment?

    companion object {
        val None = AttachmentPicker { null }
    }
}

/**
 * The text the engine reads: the user's prompt, then the glossary and the attachments in the
 * forms the director's reading already understands (`Glossary.read`, `UserMedia`): sites as bare
 * domains, terms in «guillemets» with their meaning after «یعنی», entries split by «؛» so a
 * meaning never runs into the next term.
 */
object EnginePrompt {
    /** A glossary entry: "tetherland.com", "RSI", or "ووچر = کد هدیه" (term and meaning). */
    data class Term(val text: String, val meaning: String?)

    private val SITE = Regex("(?i)^(?:https?://)?(?:www\\.)?[a-z0-9][a-z0-9-]*(?:\\.[a-z0-9-]+)*\\.[a-z]{2,6}/?$")
    private val SEPARATOR = Regex("\\s*(?:=|:|یعنی)\\s*")

    /** Splits what the user typed into a chip ("RSI = شاخص قدرت نسبی") into term and meaning. */
    fun term(raw: String): Term {
        val parts = raw.trim().split(SEPARATOR, limit = 2)
        return Term(parts[0].trim().trim('«', '»'), parts.getOrNull(1)?.trim()?.takeIf { it.isNotEmpty() })
    }

    fun isSite(text: String): Boolean = SITE.matches(text.trim())

    fun compose(prompt: String, glossary: List<String>, attachments: List<Attachment>, persian: Boolean = true): String = buildString {
        append(prompt.trim())
        val terms = glossary.map(::term).filter { it.text.isNotEmpty() }
        if (terms.isNotEmpty()) {
            appendSection(if (persian) "واژه‌نامه (املای دقیق)" else "Glossary (exact spelling)")
            append(
                terms.joinToString(if (persian) "؛ " else "; ") { t ->
                    val name = if (isSite(t.text)) t.text.removePrefix("https://").removePrefix("http://").trimEnd('/') else "«${t.text}»"
                    if (t.meaning == null) name else "$name ${if (persian) "یعنی" else "means"} ${t.meaning}"
                },
            )
        }
        if (attachments.isNotEmpty()) {
            appendSection(if (persian) "پیوست‌ها" else "Attachments")
            append(attachments.joinToString(if (persian) "؛ " else "; ") { a -> "${kindName(a.kind, persian)} ${a.name}" + a.caption.trim().takeIf { it.isNotEmpty() }?.let { " ($it)" }.orEmpty() })
        }
    }.trim()

    private fun StringBuilder.appendSection(title: String) {
        if (isNotEmpty()) append("\n")
        append(title).append(": ")
    }

    private fun kindName(kind: AttachmentKind, persian: Boolean): String = when (kind) {
        AttachmentKind.Picture -> if (persian) "تصویر" else "picture"
        AttachmentKind.Clip -> if (persian) "کلیپ" else "clip"
        AttachmentKind.Logo -> if (persian) "لوگو" else "logo"
    }
}

/** Everything the create screen can ask for; one holder keeps the screen's signature short. */
data class CreateActions(
    val onBack: () -> Unit = {},
    val onPickMedia: (MediaKind) -> Unit = {},
    val onPrompt: (String) -> Unit = {},
    val onExpert: (Boolean) -> Unit = {},
    val onAddTerm: (String) -> Unit = {},
    val onRemoveTerm: (String) -> Unit = {},
    val onAttach: (AttachmentKind) -> Unit = {},
    val onDetach: (String) -> Unit = {},
    val onCaption: (String, String) -> Unit = { _, _ -> },
    val onStyle: (String?) -> Unit = {},
    /** A speed (fast, balanced, pro); one whose model is not installed opens the models screen. */
    val onSpeed: (io.trimio.engine.models.SpeedMode) -> Unit = {},
    /** Reel or a long YouTube video (videos only). */
    val onEdit: (EditChoice) -> Unit = {},
    /** The subtitle look of a subtitled edit. */
    val onSubtitles: (SubtitleChoice) -> Unit = {},
    val onCreate: () -> Unit = {},
)
