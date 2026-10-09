package io.trimio.core.model.timeline

import io.trimio.core.model.input.CanvasSpec
import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The edit decision document. Single source of truth for preview, export and
 * Premiere/DaVinci XML. Produced by the Director, editable by the user, rendered by the engine.
 *
 * Bump [CURRENT_VERSION] on any breaking schema change and add a migration.
 */
@Serializable
data class Timeline(
    val version: Int = CURRENT_VERSION,
    /** Style pack id, e.g. "liquid-glass". Packs downloaded later may not exist in [io.trimio.core.model.style.DesignStyle]. */
    val styleId: String,
    /** Seeds every random choice so the same input renders the same output. */
    val seed: Long,
    val canvas: CanvasSpec,
    val durationMs: Long,
    val clips: List<Clip>,
) {
    inline fun <reified T : Clip> clipsOf(): List<T> = clips.filterIsInstance<T>()

    fun activeAt(timeMs: Long): List<Clip> = clips.filter { timeMs in it.range }

    companion object {
        const val CURRENT_VERSION = 1
    }
}

/** Anchor in logical coordinates: Start/End flip automatically for right-to-left layouts. */
@Serializable
enum class Anchor { TopStart, TopCenter, TopEnd, CenterStart, Center, CenterEnd, BottomStart, BottomCenter, BottomEnd }

@Serializable
sealed interface Clip {
    val range: TimeRange

    /** Higher layers draw on top. Audio clips ignore it. */
    val layer: Int
}

/** A caption word or phrase with its animation preset (e.g. "pop-scale", "typewriter", "mask-reveal"). */
@Serializable
@SerialName("caption")
data class CaptionClip(
    override val range: TimeRange,
    override val layer: Int = Layers.CAPTIONS,
    val text: String,
    val language: Language,
    val preset: String,
    /** 0..1, drives scale/colour/weight of the animation. */
    val emphasis: Float = 0f,
    /** Index of the first transcript word this caption covers; links edits back to the transcript. */
    val wordIndex: Int,
    val anchor: Anchor = Anchor.BottomCenter,
    /** Captions sharing a group are laid out together as one on-screen line/phrase. */
    val group: Int = wordIndex,
) : Clip

/** A visual element from the asset library: icon, Lottie, sticker, 3D object, number counter. */
@Serializable
@SerialName("element")
data class ElementClip(
    override val range: TimeRange,
    override val layer: Int = Layers.ELEMENTS,
    val assetId: String,
    val preset: String,
    val anchor: Anchor = Anchor.Center,
    val scale: Float = 1f,
    val params: Map<String, String> = emptyMap(),
) : Clip

/** A one-shot sound effect. [range] spans the sample's playback. */
@Serializable
@SerialName("sfx")
data class SfxClip(
    override val range: TimeRange,
    val assetId: String,
    val gainDb: Float = -6f,
) : Clip {
    override val layer: Int get() = Layers.AUDIO
}

/** Background music bed. Ducked under speech when [duckUnderSpeech] is set. */
@Serializable
@SerialName("music")
data class MusicClip(
    override val range: TimeRange,
    val assetId: String,
    val gainDb: Float = -18f,
    val duckUnderSpeech: Boolean = true,
) : Clip {
    override val layer: Int get() = Layers.AUDIO
}

/**
 * Generated backdrop (aurora, grain, bento grid, audio-reactive waves...).
 * Required for audio-only input, optional over footage.
 */
@Serializable
@SerialName("background")
data class BackgroundClip(
    override val range: TimeRange,
    override val layer: Int = Layers.BACKGROUND,
    val preset: String,
    val audioReactive: Boolean = false,
    val params: Map<String, Float> = emptyMap(),
) : Clip {
    companion object {
        /** Use the active style's own background preset. */
        const val STYLE_PRESET = "style"
    }
}

/** A segment removed from the output (silence, filler words, retakes). Its range is in **source** time. */
@Serializable
@SerialName("cut")
data class CutClip(
    override val range: TimeRange,
    val reason: CutReason,
) : Clip {
    override val layer: Int get() = Layers.EDIT
}

@Serializable
enum class CutReason { Silence, Filler, Retake, Manual }

object Layers {
    const val EDIT = -100
    const val AUDIO = -50
    const val BACKGROUND = 0
    const val FOOTAGE = 10
    const val ELEMENTS = 20
    const val CAPTIONS = 30
    const val OVERLAY = 40
}
