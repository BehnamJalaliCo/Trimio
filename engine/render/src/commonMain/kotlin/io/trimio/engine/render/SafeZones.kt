package io.trimio.engine.render

import androidx.compose.ui.geometry.Size
import io.trimio.core.model.timeline.Anchor

/**
 * Areas covered by Reels / TikTok / Shorts interface chrome on a vertical video: the header at the
 * top, the caption and buttons at the bottom, and the like/comment/share column on the right.
 * Captions and elements are laid out inside the remaining safe area.
 */
object SafeZones {
    /** Fractions of the frame height covered at the top and bottom of a portrait video. */
    const val TOP = 0.12f
    const val BOTTOM = 0.20f

    /** Fraction of the frame width covered by the action column on the right, in the lower half. */
    const val RIGHT_COLUMN = 0.14f

    /**
     * Widest a caption line may be, as a fraction of the frame width. Lines are centred, so a
     * bottom caption on a portrait video loses the action column's width on both sides.
     */
    fun captionWidth(size: Size, anchor: Anchor): Float {
        val portrait = size.height > size.width
        val bottom = anchor == Anchor.BottomStart || anchor == Anchor.BottomCenter || anchor == Anchor.BottomEnd
        return if (portrait && bottom) 1f - 2f * RIGHT_COLUMN else 0.84f
    }
}
