package io.trimio.feature.editor

import androidx.compose.runtime.Composable
import io.trimio.core.designsystem.theme.tr
import io.trimio.engine.production.Opened

/** Why a finished edit does not open in the editor or the export screen. */
enum class NotOpen {
    /** The creator's video or voice recording (or an attachment) is no longer on the device. */
    SourceMissing,

    /** Nothing was kept to open it again from (made by an earlier version of the app). */
    NotKept,

    /** This platform does not change or export edits (web previews). */
    Unsupported,

    /** An explainer film made from a voice alone: the editor does not change these yet. */
    Explainer,

    ;

    /** The line that says what happened. */
    val title: String
        @Composable get() = when (this) {
            SourceMissing -> tr("فایل اصلی پیدا نشد", "The original file is gone")
            NotKept -> tr("این ادیت دوباره باز نمی‌شود", "This edit can't be opened again")
            Unsupported -> tr("این ادیت را این‌جا نمی‌شود تغییر داد", "This edit can't be changed here")
            Explainer -> tr("ویرایش فیلم توضیحی به‌زودی", "Editing explainer films is coming soon")
        }

    /** What it means, and what the creator can still do. */
    val body: String
        @Composable get() = when (this) {
            SourceMissing -> tr(
                "ویرایش و خروجی از روی ویدیو یا صدای خودت ساخته می‌شوند و آن فایل دیگر روی این دستگاه نیست (پاک یا جابه‌جا شده). ویدیوی آماده سر جایش است و ذخیره و اشتراکش کار می‌کند.",
                "Editing and exports are made from your own video or recording, and that file is no longer on this device (deleted or moved). The finished video is still here to save and share.",
            )
            NotKept -> tr(
                "این ادیت با نسخهٔ قدیمی‌تر برنامه ساخته شده و چیزی که برای باز کردن دوباره‌اش لازم است نگه داشته نشده. از صفحهٔ نتیجه یک نسخهٔ تازه بساز؛ ویدیوی قبلی سر جایش می‌ماند.",
                "This edit was made by an earlier version of the app, which didn't keep what opening it again needs. Make a new version from the result; the earlier video stays.",
            )
            Unsupported -> tr(
                "روی این دستگاه ادیت خودکار انجام نمی‌شود؛ ویرایش و خروجی روی اندروید و ویندوز است.",
                "Automatic edits don't run here; editing and exports are on Android and Windows.",
            )
            Explainer -> tr(
                "این فیلم از روی صدای تو ساخته شده و ویرایشگر هنوز فیلم‌های توضیحی را تغییر نمی‌دهد. فیلم آماده سر جایش است و ذخیره و اشتراکش کار می‌کند؛ برای نگاهی تازه یک نسخهٔ دیگر بساز.",
                "This film was made from your voice, and the editor doesn't change explainer films yet. The finished film is here to save and share; make another version for a fresh look.",
            )
        }

    companion object {
        fun of(opened: Opened): NotOpen = when (opened) {
            Opened.SourceMissing -> SourceMissing
            Opened.Unsupported -> Unsupported
            Opened.Explainer -> Explainer
            Opened.NotKept, is Opened.Ready -> NotKept
        }
    }
}
