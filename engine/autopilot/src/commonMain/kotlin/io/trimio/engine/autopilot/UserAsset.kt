package io.trimio.engine.autopilot

import io.trimio.engine.llm.RgbImage

/**
 * A picture or clip the creator attached beside the prompt: their logo, a product photo, a
 * screenshot, a short clip. The director reads what it is ([AssetReader]) and places it where the
 * speaker talks about it ([UserMedia]); the renderer draws it from [source] under [id].
 */
class UserAsset(
    /** The media source id the renderer knows it by (unique in the request). */
    val id: String,
    val kind: Kind,
    /** A small picture of it (a clip's first frame), for the eyes and the pixel cues. */
    val thumbnail: RgbImage,
    /** The thumbnail's alpha, one byte a pixel (0 clear … 255 opaque), when the file has transparency. */
    val alpha: ByteArray? = null,
    /** A few frames of a clip, for the eyes. */
    val frames: List<RgbImage> = emptyList(),
    /** Seconds, for a clip. */
    val duration: Float? = null,
    /** The creator's own words about it, if they wrote any. */
    val caption: String? = null,
    /** The file path or platform source id it is rendered from; its file name is a clue to what it is. */
    val source: String = id,
) {
    enum class Kind { Image, Video, Logo }

    /** Width ÷ height. */
    val aspect: Float get() = thumbnail.width / thumbnail.height.coerceAtLeast(1).toFloat()

    /** The file name without its folder ("nike-logo.png"). */
    val fileName: String get() = source.substringAfterLast('/').substringAfterLast('\\')
}

/** What one attached asset is, and what the brief says about it. */
data class AssetReading(
    val id: String,
    val role: Role,
    /** What it shows, in a few words. */
    val subject: String = "",
    /** Text or a brand it shows. */
    val text: String = "",
    val brand: String = "",
    /** Words a line would say about it, in the transcript's language and in English. */
    val keywords: List<String> = emptyList(),
    /** Words the brief ties it to («عکس محصول رو وقتی گفتم سرم بذار»): the line saying them is its moment. */
    val cue: List<String> = emptyList(),
    /** Where the brief puts it when it names no words. */
    val where: Where? = null,
    /** The form the brief asks for a clip: picture-in-picture rather than a full-frame cut. */
    val inset: Boolean = false,
    /** What the eyes saw a reference do ([Role.Reference]): its framing, captions and kinds of graphics. */
    val devices: ReferenceStyle.Devices? = null,
    /** A light mark (white on clear): it needs a dark tile. */
    val light: Boolean = false,
    /** How it was read: "eyes" or "rules". */
    val by: String = "rules",
) {
    /** What an asset is; a [Reference] is not shown at all: the edit takes its palette and its pace ([ReferenceStyle]). */
    enum class Role { Logo, Product, Screenshot, Broll, Photo, Reference }
    enum class Where { Start, End }
}
