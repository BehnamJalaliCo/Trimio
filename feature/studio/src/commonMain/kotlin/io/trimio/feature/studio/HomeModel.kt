package io.trimio.feature.studio

import androidx.compose.ui.graphics.ImageBitmap
import io.trimio.core.data.MediaKind
import io.trimio.core.data.ProjectStatus

/** One row of the home list, built by the app from the stored project and the live run. */
data class HomeProject(
    val id: String,
    val title: String,
    val styleName: String? = null,
    val audioOnly: Boolean = false,
    /** The finished edit's length; 0 until it is made. */
    val durationMs: Long = 0,
    val updatedAt: Long,
    val status: ProjectStatus,
    /** 0..1 while it is being made. */
    val progress: Float = 0f,
    /** Finished and not opened yet. */
    val fresh: Boolean = false,
    /** The run was stopped (by the creator, another production, or the app closing) rather than failing. */
    val interrupted: Boolean = false,
    val poster: ImageBitmap? = null,
)

/** Whether the director can work now, and whether the device is online: the status chip on home. */
data class HomeReadiness(val director: Director = Director.Ready, val online: Boolean = false, val progress: Float = 0f) {
    enum class Director {
        /** The on-device models are installed. */
        Ready,

        /** The creator's cloud director is connected and reachable (and the speech model installed). */
        Cloud,

        /** The default models are downloading ([progress] of the bytes are here). */
        Downloading,

        /** A speech or director model is still needed. */
        Missing,

        /** Engines are simulated on this platform (web previews). */
        Preview,
    }
}

/** Everything home shows; a pure model so the screen renders the same in tests and on every platform. */
data class HomeState(
    val projects: List<HomeProject> = emptyList(),
    val readiness: HomeReadiness = HomeReadiness(),
    /** A short note from the team (remote config) or the preview notice. */
    val announcement: String? = null,
    /** Wall clock (ms) for the date line and "yesterday". */
    val now: Long = 0,
)

data class HomeActions(
    /** Pick a video or a voice recording and open the brief. */
    val onNew: (MediaKind) -> Unit = {},
    /** An idea: pick its kind of media, then open the brief with the idea's words and style. */
    val onIdea: (Idea) -> Unit = {},
    /** The composer's wish: pick media, then open the brief with it. */
    val onCompose: (String) -> Unit = {},
    val onOpen: (String) -> Unit = {},
    val onDelete: (String) -> Unit = {},
    val onGallery: () -> Unit = {},
    val onSettings: () -> Unit = {},
    val onModels: () -> Unit = {},
)

/** A starting point on the empty home: what it makes, the brief it writes, and the style it asks for. */
data class Idea(
    val id: String,
    val kind: MediaKind,
    val styleId: String,
    val titleFa: String,
    val titleEn: String,
    val hintFa: String,
    val hintEn: String,
    val briefFa: String,
    val briefEn: String,
) {
    companion object {
        /** What a wish typed in the composer starts from: a voice when it speaks of one, otherwise a video. */
        fun kindFor(wish: String): MediaKind =
            if (AUDIO_WORDS.any { wish.contains(it, ignoreCase = true) }) MediaKind.Audio else MediaKind.Video

        private val AUDIO_WORDS = listOf("فقط صدا", "پادکست", "ویس", "صدای", "podcast", "audio", "voice")

        val all = listOf(
            Idea(
                "crypto", MediaKind.Video, "finance-terminal",
                "ریلز پرانرژی کریپتو", "Energetic crypto Reel",
                "هوک قوی، تأکید روی اعداد", "Strong hook, the numbers stand out",
                "یک ریلز پرانرژی دربارهٔ کریپتو بساز؛ هوک قوی در سه ثانیهٔ اول و تأکید روی اعداد و درصدها",
                "Make an energetic crypto Reel: a strong hook in the first three seconds, emphasise the numbers and percentages",
            ),
            Idea(
                "podcast", MediaKind.Audio, "kinetic-poster",
                "پادکست با زیرنویس کینتیک", "Podcast with kinetic captions",
                "فقط صدا · تصویر را سبک می‌سازد", "Audio only · the style makes the picture",
                "پادکست با زیرنویس کینتیک کلمه‌به‌کلمه؛ روی جمله‌های کلیدی تأکید کن",
                "A podcast with word-by-word kinetic captions; emphasise the key sentences",
            ),
            Idea(
                "product", MediaKind.Video, "lumen",
                "معرفی محصول، آرام و لوکس", "Product intro, calm and premium",
                "بدون موسیقی، ترنزیشن نرم", "No music, soft transitions",
                "معرفی محصول، آرام و لوکس؛ بدون موسیقی و با ترنزیشن‌های نرم",
                "A calm, premium product intro; no music, soft transitions",
            ),
        )
    }
}
