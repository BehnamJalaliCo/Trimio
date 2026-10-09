package io.trimio.engine.render

import io.trimio.core.model.style.StyleSpec
import io.trimio.core.model.timeline.CaptionClip
import io.trimio.core.model.timeline.Timeline

/**
 * "Punch-in" zoom on footage when an emphasised word lands: a quick spring push to ~6% that eases
 * back over half a second. The classic short-form editing move, derived from caption emphasis so
 * the Director does not need to place camera keys by hand.
 */
internal class CameraMotion(timeline: Timeline, style: StyleSpec) {
    private val enabled = style.motion.cameraPunch
    private val strength = 0.04f + 0.04f * style.motion.energy
    private val punches: List<Long> = timeline.clipsOf<CaptionClip>()
        .filter { it.emphasis >= style.captions.emphasis.threshold }
        .map { it.range.startMs }
        .sorted()

    fun zoomAt(timeMs: Long): Float {
        if (!enabled) return 1f
        val last = punches.lastOrNull { it <= timeMs } ?: return 1f
        val dt = (timeMs - last).toFloat()
        val attack = Motion.spring(dt, damping = 0.7f, stiffness = 900f)
        val release = 1f - Motion.easeInOutSine(Motion.progress(dt - 250f, 650f))
        return 1f + strength * attack * release
    }
}
