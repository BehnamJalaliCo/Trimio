package io.trimio.engine.motion.score

/**
 * Safe zones per format and a small solver that keeps beats from colliding: each zone holds one
 * beat at a time; a beat asking for a busy zone moves to the next free one, and when every zone
 * is taken the oldest occupant leaves early (but never before it has been readable).
 */
internal class Layout(val width: Int, val height: Int, private val subject: Subject? = null) {


    enum class Zone { Top, Center, Lower, Full }

    data class Slot(val zone: Zone, val x: Float, val y: Float, val w: Float, val h: Float)

    private val tall = height > width

    /**
     * Zones over footage with a subject: above the head and below the chin only; the centre
     * belongs to the face. Full-frame takeovers (scenes that cover the footage) use the normal zones.
     */
    /** Zones graphics may use over the speaker's footage. */
    val footageZones: Set<Zone> get() = if (subject == null) Zone.entries.toSet() else setOf(Zone.Top, Zone.Lower)

    fun zoneOverFootage(zone: Zone): Zone = if (subject == null) zone else when (zone) {
        Zone.Center, Zone.Full -> Zone.Top
        else -> zone
    }

    private fun extent(zone: Zone, aroundSubject: Boolean): Pair<Float, Float> {
        val sub = subject?.takeIf { aroundSubject } ?: return extent(zone)
        return when (zone) {
            Zone.Top, Zone.Center, Zone.Full -> 0.07f to (sub.top - 0.015f).coerceAtLeast(0.2f)
            // Under a talking head's chin is what he holds up and his microphone (the owner's captions sat on his mic):
            // vertical captions start lower, at the chest, where reels put them.
            Zone.Lower -> maxOf(if (tall) 0.7f else 0.72f, sub.bottom + 0.01f).coerceAtMost(0.78f) to if (tall) 0.87f else 0.92f
        }
    }

    /** Vertical extent of each zone, as fractions of the frame height. */
    private fun extent(zone: Zone): Pair<Float, Float> = when (zone) {
        Zone.Top -> if (tall) 0.1f to 0.31f else 0.08f to 0.3f
        Zone.Center -> if (tall) 0.33f to 0.7f else 0.3f to 0.7f
        Zone.Lower -> if (tall) 0.72f to 0.86f else 0.72f to 0.9f
        Zone.Full -> if (tall) 0.1f to 0.84f else 0.08f to 0.9f
    }

    /** Horizontal safe area: platform buttons sit on the right of vertical video. */
    private val left = width * 0.08f
    private val right = width * if (tall) 0.9f else 0.92f

    /**
     * Where a beat in [zone] is drawn. On a stage of its own ([stage]: the footage covered) the centre's graphic is the
     * scene, so it takes the whole band; with the speaker's card in the corner ([inset]) the bands start below the card.
     */
    fun slot(zone: Zone, preferredHeight: Float, aroundSubject: Boolean = false, stage: Boolean = false, inset: Boolean = false, floor: Float? = null): Slot {
        val (top, bottom) = when {
            floor != null && tall -> cutoutExtent(zone, floor)
            inset && tall -> insetExtent(zone)
            else -> extent(zone, aroundSubject)
        }
        val zoneH = (bottom - top) * height
        val h = if (stage && zone == Zone.Center) zoneH else minOf(zoneH, preferredHeight * height).coerceAtLeast(zoneH * 0.5f)
        return Slot(zone, (left + right) / 2f, (top + bottom) / 2f * height, right - left, h)
    }

    /**
     * Vertical extents on a stage the speaker stands in, cut out from the shoulders up at the foot of the frame: every band
     * keeps above their head ([floor], a share of the height), the centre's graphic taking the room there is.
     */
    private fun cutoutExtent(zone: Zone, floor: Float): Pair<Float, Float> = when (zone) {
        Zone.Top -> CUTOUT_TOP to minOf(floor, CUTOUT_TOP + CUTOUT_BAND)
        Zone.Center, Zone.Lower, Zone.Full -> CUTOUT_TOP to floor
    }

    /** Vertical extents on a stage that keeps the speaker's card at the top ([SceneScore.inset]): every band clears it. */
    private fun insetExtent(zone: Zone): Pair<Float, Float> = when (zone) {
        Zone.Top -> 0.24f to 0.4f
        Zone.Center -> 0.27f to 0.74f
        Zone.Lower -> 0.75f to 0.86f
        Zone.Full -> 0.25f to 0.84f
    }

    fun zoneOf(place: String?, fallback: Zone): Zone = when (place?.trim()?.lowercase()) {
        "top", "up", "header", "بالا" -> Zone.Top
        "center", "centre", "middle", "mid", "وسط" -> Zone.Center
        "lower", "bottom", "down", "پایین" -> Zone.Lower
        "full", "fullscreen", "all", "تمام" -> Zone.Full
        else -> fallback
    }

    class Occupant(val zone: Zone, val at: Float, var out: Float, val minOut: Float, val id: Int)

    private val occupants = mutableListOf<Occupant>()

    private fun overlaps(zone: Zone, a: Float, b: Float) = occupants.filter { o ->
        val clash = o.zone == zone || o.zone == Zone.Full || zone == Zone.Full
        clash && o.at < b - OVERLAP_TOLERANCE && a < o.out - OVERLAP_TOLERANCE
    }

    /**
     * Places a beat wanting [want] during [at]..[out]. Returns the zone it got; occupants that
     * had to leave early get a shorter [Occupant.out].
     */
    fun place(
        want: Zone, at: Float, out: Float, minOut: Float, id: Int, locked: Boolean = false, allowed: Set<Zone> = Zone.entries.toSet(),
        replace: Boolean = false, evict: Boolean = false,
    ): Zone {
        // On a stage the graphics are slides: the next one takes the last one's place once it has been read, instead of
        // squeezing into a band beside it.
        val clash = overlaps(want, at, out)
        // A storyboard card always takes its band: the card before it leaves as it lands (one card at a time, as the reference).
        if (evict && clash.isNotEmpty()) {
            clash.forEach { o -> o.out = minOf(o.out, at - 0.02f) }
            occupants += Occupant(want, at, out, minOut, id)
            return want
        }
        if (replace && clash.isNotEmpty() && clash.all { it.minOut <= at + REPLACE_SLACK }) {
            clash.forEach { o -> o.out = maxOf(o.minOut, at - 0.02f) }
            occupants += Occupant(want, at, out, minOut, id)
            return want
        }
        val order = if (locked) listOf(want) else (listOf(want) + fallbacks(want)).filter { it in allowed }
        val zone = order.firstOrNull { overlaps(it, at, out).isEmpty() } ?: run {
            // Everything is busy: the wanted zone's occupants leave as soon as they have been read.
            overlaps(want, at, out).forEach { o -> o.out = maxOf(o.minOut, at - 0.02f) }
            want
        }
        occupants += Occupant(zone, at, out, minOut, id)
        return zone
    }

    fun outOf(id: Int): Float? = occupants.firstOrNull { it.id == id }?.out

    fun busy(zone: Zone, a: Float, b: Float): Boolean = overlaps(zone, a, b).isNotEmpty()

    private fun fallbacks(z: Zone) = when (z) {
        Zone.Center -> listOf(Zone.Top, Zone.Lower)
        // Over footage the centre is the face: a busy top yields to the lower band instead.
        Zone.Top -> listOf(Zone.Center, Zone.Lower)
        Zone.Lower -> listOf(Zone.Center, Zone.Top)
        Zone.Full -> emptyList()
    }

    private companion object {
        const val OVERLAP_TOLERANCE = 0.08f
        const val REPLACE_SLACK = 0.4f

        /** Above a cut-out speaker: where the bands start under the platform's top bar, and a top band's height. */
        const val CUTOUT_TOP = 0.08f
        const val CUTOUT_BAND = 0.14f
    }
}
