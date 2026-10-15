package io.trimio.engine.production

import io.trimio.engine.motion.MattedMedia
import io.trimio.engine.motion.MediaSource
import io.trimio.engine.perception.Frame
import io.trimio.engine.perception.Perception
import io.trimio.engine.perception.matte.SelfieMatte

/**
 * [media] with the speaker found in its frames when the person-matte add-on is installed ([MattedMedia]: text set behind
 * them, a speaker cut out over a stage); [media] itself otherwise, and the edit draws those layers as nothing.
 */
internal fun matted(media: MediaSource, perception: Perception?): MediaSource {
    val matte = runCatching { perception?.personMatte() }.getOrNull() ?: return media
    val size = SelfieMatte.SIZE
    return MattedMedia(media, size, { rgb, time -> runCatching { matte.alpha(Frame(size, size, rgb, time)) }.getOrNull() }, { matte.close() })
}

/** The top of the speaker's head in [frames] (a share of the frame's height) through the person-matte add-on; null without it. */
internal fun crownOf(frames: List<io.trimio.engine.llm.RgbImage>, perception: Perception?): Float? {
    val matte = runCatching { perception?.personMatte() }.getOrNull() ?: return null
    return try {
        runCatching { matte.crown(frames.map { Frame(it.width, it.height, it.rgb) }) }.getOrNull()
    } finally {
        matte.close()
    }
}
