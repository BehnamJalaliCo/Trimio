package io.trimio.feature.stream

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.trimio.core.designsystem.icon.TrimioIcons
import io.trimio.core.designsystem.signature.MicroLabel
import io.trimio.core.designsystem.signature.Sig
import io.trimio.core.designsystem.signature.SigButton
import io.trimio.core.designsystem.signature.SigOptionRow
import io.trimio.core.designsystem.signature.SigRoundButton
import io.trimio.core.designsystem.signature.SigScreen
import io.trimio.core.designsystem.signature.SigStateBlock
import io.trimio.core.designsystem.signature.SigTextButton
import io.trimio.core.designsystem.signature.gutter
import io.trimio.core.designsystem.theme.tr

/**
 * Board 2, last screen: an honest failure. Where it stopped, one plain sentence of what happened,
 * and numbered ways forward (the director's recommendation marked); never a dead end.
 */
@Composable
fun BuildFailureScreen(
    failure: BuildFailure,
    onOption: (Int) -> Unit,
    onPrimary: () -> Unit,
    onClose: () -> Unit,
    /** «جزئیات فنی»: the run's whole technical report; null shows the engine's message in place instead. */
    onDetails: (() -> Unit)? = null,
) {
    var details by remember { mutableStateOf(false) }
    SigScreen {
        Column(Modifier.fillMaxSize().widthIn(max = 620.dp).align(Alignment.TopCenter)) {
            Row(Modifier.fillMaxWidth().height(58.dp).gutter(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                SigRoundButton(TrimioIcons.Close, tr("بستن", "Close"), onClose, overMedia = false)
                MicroLabel("STAGE 0${failure.stage.ordinal + 1} / 0${BuildStage.entries.size}")
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).gutter().padding(top = 36.dp)) {
                SigStateBlock(if (failure.stage == BuildStage.Listen) TrimioIcons.Wave else TrimioIcons.Sparkle, failure.title, failure.body)
                Text(tr("چه کار کنیم؟", "What now?"), style = Sig.type.meta.copy(fontSize = 11.sp), color = Sig.colors.muted, modifier = Modifier.padding(top = 30.dp, bottom = 6.dp))
                failure.options.forEachIndexed { i, o -> SigOptionRow(i + 1, o.title, o.hint, { onOption(i) }, director = o.recommended) }
                if (details) failure.detail?.let { Text(it, style = Sig.type.meta.copy(fontSize = 11.sp), color = Sig.colors.faint, modifier = Modifier.padding(top = 14.dp)) }
            }
            Column(Modifier.gutter().padding(vertical = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                SigButton(failure.primary, onPrimary, Modifier.fillMaxWidth(), trailing = TrimioIcons.Undo)
                if (onDetails != null || failure.detail != null) {
                    Box(Modifier.padding(top = 4.dp)) { SigTextButton(tr("جزئیات فنی", "Technical details"), onDetails ?: { details = !details }) }
                }
            }
        }
    }
}

/** The failures the creator can meet, written as the boards write them. */
object BuildFailures {
    /** Reads the engine's error into an actionable failure at [stage]. */
    fun of(error: String?, stage: BuildStage, persian: Boolean): BuildFailure {
        val e = error.orEmpty().lowercase()
        return when {
            e.startsWith(DIED) -> died(error.orEmpty().removePrefix(DIED), persian)
            "speech model" in e || "no model" in e || "not installed" in e -> missingModel(stage, error, persian)
            stage == BuildStage.Listen -> unclearVoice(error, persian)
            else -> generic(stage, error, persian)
        }
    }

    fun cancelled(stage: BuildStage, persian: Boolean) = BuildFailure(
        stage = stage,
        title = t(persian, "ساخت متوقف شد", "Build stopped"),
        body = t(
            persian,
            "هر چه تا این‌جا شنیده و فهمیده شده نگه داشته شده؛ ادامه از همان‌جا شروع می‌شود.",
            "What was heard and understood is kept; continuing starts from there.",
        ),
        options = listOf(
            BuildFailure.Option(
                t(persian, "ادامه از همان‌جا", "Continue where it stopped"),
                t(persian, "کلمه‌ها و برداشت کارگردان دوباره استفاده می‌شود", "Reuses the words and the director's reading"), recommended = true,
            ),
            BuildFailure.Option(
                t(persian, "پرامپت را عوض کن", "Change the prompt"),
                t(persian, "برگشت به صفحهٔ ساخت", "Back to the brief"),
            ),
        ),
        primary = t(persian, "ادامهٔ ساخت", "Continue"),
    )

    /** The engine's error for a run that gave up after the app ended twice in one step (engine ProductionState.DIED). */
    const val DIED = "died-in:"

    /** The stage on screen for an engine step a process died in ("llm understand 12/48", "transcribe", "render"). */
    fun stageOf(step: String): BuildStage {
        val s = step.removePrefix("after ").removePrefix("llm ").trim()
        return when {
            s.startsWith("transcribe") || s.startsWith("decode") || s.startsWith("start") || s.startsWith("prepare") -> BuildStage.Listen
            s.startsWith("render") -> BuildStage.Render
            else -> BuildStage.of("Direct", s)
        }
    }

    /**
     * The app ended twice in the same step (the system closed it for memory, or the model's engine
     * crashed): no third try in a loop, an honest sentence and lighter ways on instead.
     */
    fun died(step: String, persian: Boolean): BuildFailure {
        val stage = stageOf(step)
        return BuildFailure(
            stage = stage,
            title = t(persian, "ساخت دو بار در مرحلهٔ «${stage.fa}» بسته شد", "The build closed twice while ${stage.en.lowercase()}"),
            body = t(
                persian,
                "برنامه دو بار در همین مرحله بسته شد؛ معمولاً یعنی حافظهٔ این دستگاه برای این مدل کافی نیست. " +
                    "برای این‌که دوباره همان‌جا گیر نکند، خودکار تکرارش نمی‌کنم. " +
                    "با مدل کوچک‌تر یا کارگردان ابری ادامه بده؛ کلمه‌ها و برداشت تا این‌جا حفظ شده‌اند.",
                "The app closed twice at this same step; usually this device does not have enough memory for this model. " +
                    "So it is not retried in a loop. Continue with a smaller model or the cloud director; the words and the reading so far are kept.",
            ),
            options = listOf(
                BuildFailure.Option(
                    t(persian, "ادامه با مدل کوچک‌تر", "Continue with a smaller model"),
                    t(persian, "سبک‌تر برای حافظهٔ این دستگاه؛ اگر نبود، با قواعد", "Lighter on this device's memory; the rules if there is none"), recommended = true,
                ),
                BuildFailure.Option(
                    t(persian, "کارگردان ابری", "Cloud director"),
                    t(persian, "سریع و بدون فشار به دستگاه؛ کلید در تنظیمات", "Fast, nothing heavy on the device; key in settings"),
                ),
                BuildFailure.Option(
                    t(persian, "ساخت با قواعد، بدون مدل", "Build with rules, no model"),
                    t(persian, "سریع‌ترین، با تنوع کمتر", "Fastest, less varied"),
                ),
            ),
            primary = t(persian, "ادامه با مدل کوچک‌تر", "Continue with a smaller model"),
            detail = step,
        )
    }

    private fun unclearVoice(error: String?, persian: Boolean) = BuildFailure(
        stage = BuildStage.Listen,
        title = t(persian, "صدا را واضح نشنیدم", "I couldn't hear the voice clearly"),
        body = t(
            persian,
            "بیشتر ویدیو بی‌صداست یا صدا خیلی آرام است، برای همین نتوانستم کلمه‌ها را دقیق بنویسم. پروژه ذخیره شده و چیزی از دست نرفته.",
            "Most of the video is silent or very quiet, so I couldn't write the words accurately. The project is saved; nothing is lost.",
        ),
        options = listOf(
            BuildFailure.Option(
                t(persian, "صدا را تقویت کن و دوباره بساز", "Boost the voice and build again"),
                t(persian, "پیشنهاد کارگردان · تقویت تا \u2066-14 LUFS\u2069", "Director's pick · boost to -14 LUFS"), recommended = true,
            ),
            BuildFailure.Option(
                t(persian, "بدون زیرنویس بساز", "Build without captions"),
                t(persian, "فقط موشن، موسیقی و برش روی تصویر", "Motion, music and cuts only"),
            ),
            BuildFailure.Option(
                t(persian, "فایل دیگری انتخاب کن", "Choose another file"),
                t(persian, "ویدیو یا فقط صدا", "A video or audio only"),
            ),
        ),
        primary = t(persian, "تقویت صدا و ساخت دوباره", "Boost and build again"),
        detail = error,
    )

    private fun missingModel(stage: BuildStage, error: String?, persian: Boolean) = BuildFailure(
        stage = stage,
        title = t(persian, "مدل روی دستگاه نیست", "The model isn't on this device"),
        body = t(
            persian,
            "برای شنیدن و کارگردانی روی همین دستگاه، یک بار دانلود مدل لازم است؛ بعد همه‌چیز آفلاین کار می‌کند.",
            "Hearing and directing on this device needs a one-time model download; then everything works offline.",
        ),
        options = listOf(
            BuildFailure.Option(
                t(persian, "دانلود مدل پیشنهادی", "Download the recommended model"),
                t(persian, "مناسب همین دستگاه", "Fits this device"), recommended = true,
            ),
            BuildFailure.Option(
                t(persian, "ساخت با قواعد، بدون مدل", "Build with rules, no model"),
                t(persian, "سریع‌تر، با تنوع کمتر", "Faster, less varied"),
            ),
        ),
        primary = t(persian, "رفتن به مدل‌ها", "Open models"),
        detail = error,
    )

    private fun generic(stage: BuildStage, error: String?, persian: Boolean) = BuildFailure(
        stage = stage,
        title = t(persian, "ساخت کامل نشد", "The build didn't finish"),
        body = t(
            persian,
            "در مرحلهٔ «${stage.fa}» مشکلی پیش آمد. کارهای انجام‌شده ذخیره شده‌اند و دوباره ساختن از همان‌جا ادامه می‌دهد.",
            "Something went wrong while ${stage.en.lowercase()}. Finished work is saved; building again continues from there.",
        ),
        options = listOf(
            BuildFailure.Option(
                t(persian, "دوباره بساز", "Build again"),
                t(persian, "از آخرین مرحلهٔ ذخیره‌شده", "From the last saved stage"), recommended = true,
            ),
            BuildFailure.Option(
                t(persian, "با سبک دیگری بساز", "Try another style"),
                t(persian, "برگشت به صفحهٔ ساخت", "Back to the brief"),
            ),
        ),
        primary = t(persian, "ساخت دوباره", "Build again"),
        detail = error,
    )

    private fun t(persian: Boolean, fa: String, en: String) = if (persian) fa else en
}
