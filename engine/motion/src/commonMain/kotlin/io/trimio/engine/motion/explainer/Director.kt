package io.trimio.engine.motion.explainer

/** How a version came on stage, and what it leaves as. */
internal enum class Entry { Add, Replace, Morph }

/**
 * One version of a stage element: its content and everything that happens to it until it leaves
 * (cleared, replaced, morphed into the next version, or swept away at the chapter's end).
 */
internal class Version(
    val element: String,
    /** Place in the stage's reading order (kept through replaces and morphs). */
    val order: Int,
    var spec: Spec,
    val enter: Float,
    val entry: Entry,
    /** The version this one replaced or morphed out of. */
    val from: Version?,
    /** Order of creation within the chapter (seeds and names). */
    val serial: Int,
) {
    var leave: Float = Float.POSITIVE_INFINITY
    var gone: Float = Float.POSITIVE_INFINITY

    /** How it leaves: swept (cleared, chapter end), or handed over to a replace or a morph. */
    var exit: Entry = Entry.Add

    val reveals = HashMap<Int, Float>()
    val checks = ArrayList<Pair<Float, List<Int>>>()
    val picks = ArrayList<Pair<Float, List<Int>>>()
    val fills = ArrayList<FillStep>()
    val marks = ArrayList<Float>()

    /** Dim (true) or bring back (false), at times. */
    val dims = ArrayList<Pair<Float, Boolean>>()
    var build: Pair<Float, Float>? = null
    val ops = HashSet<StageOp>()

    /** A mark drawn around another element instead of taking a place of its own. */
    var target: Version? = null
    var loop: Boolean = false

    val overlay: Boolean get() = target != null

    fun live(t: Float) = t >= enter && t < gone

    fun track(): Track {
        val n = spec.items.size
        val reveals = List(n) { i -> reveals[i] ?: (enter + 0.18f + i * Track.STAGGER) }
        return Track(enter, minOf(leave, gone), reveals, checks.sortedBy { it.first }, picks.sortedBy { it.first }, fills.sortedBy { it.at }, marks.sorted(), build)
    }
}

/**
 * Reads a chapter's beats in time order and turns them into element versions: adds, builds,
 * checks, fills, highlights, dims, morphs, replaces and clears, each landing on the element it
 * names. Unknown elements are created when the beat says what they are; beats that name nothing
 * usable are noted and skipped.
 */
internal class Director(private val chapter: Chapter, private val notes: MutableList<String>) {
    val versions = ArrayList<Version>()
    private val live = LinkedHashMap<String, Version>()
    private val orders = HashMap<String, Int>()

    fun run(): List<Version> {
        val beats = chapter.beats.sortedBy { it.at }
        for ((i, b) in beats.withIndex()) apply(b, i)
        val end = chapter.end.toFloat()
        // Whatever is still up leaves together before the next chapter, last in first out.
        val up = versions.filter { it.leave == Float.POSITIVE_INFINITY }.sortedByDescending { it.order }
        for ((k, v) in up.withIndex()) {
            v.leave = maxOf(v.enter + MIN_LIFE, end - SWEEP - k * SWEEP_STAGGER)
            v.gone = v.leave + EXIT
        }
        versions.removeAll { it.enter >= end - MIN_LIFE }
        for (v in versions) v.spec = v.spec.copy(ops = v.ops)
        return versions
    }

    private fun lead(b: Beat): Float = (b.at.toFloat() - LEAD).coerceIn(chapter.start.toFloat(), (chapter.end.toFloat() - MIN_LIFE).coerceAtLeast(chapter.start.toFloat()))

    private fun apply(b: Beat, index: Int) {
        val at = lead(b)
        val until = maxOf(b.until.toFloat(), at + 0.6f)
        val id = b.element.trim().ifEmpty { "e$index" }
        if (id == ALL) return applyAll(b, at)
        val current = live[id]
        when (b.op) {
            StageOp.Add -> if (current == null) create(id, b, at, until, Entry.Add, null) else replaceOrMerge(current, b, at, until)
            StageOp.Build -> if (current == null) create(id, b, at, until, Entry.Add, null, build = true) else build(current, b, at, until)
            StageOp.Morph, StageOp.Replace -> {
                val entry = if (b.op == StageOp.Morph) Entry.Morph else Entry.Replace
                if (current == null) create(id, b, at, until, Entry.Add, null) else handOver(current, b, at, until, entry)
            }
            StageOp.Clear -> current?.let { sweep(it, at) } ?: notes.add("clear of unknown element '$id' at ${b.at}")
            else -> when {
                current != null -> act(current, b, at, until)
                b.primitive != null -> create(id, b, at, until, Entry.Add, null)
                else -> notes.add("${b.op} on unknown element '$id' at ${b.at}")
            }
        }
    }

    private fun applyAll(b: Beat, at: Float) {
        val all = live.values.toList()
        when (b.op) {
            StageOp.Clear -> all.forEach { sweep(it, at) }
            StageOp.Dim -> all.forEach { it.dims += at to true; it.ops += StageOp.Dim }
            StageOp.Highlight -> all.forEach { it.dims += at to false }
            else -> notes.add("${b.op} on all elements is not supported (at ${b.at})")
        }
    }

    private fun create(id: String, b: Beat, at: Float, until: Float, entry: Entry, from: Version?, build: Boolean = false): Version {
        val primitive = b.primitive ?: from?.spec?.primitive ?: Pieces.guess(b.content)
        var spec = Spec.of(primitive, b.content)
        if (from != null && spec.items.isEmpty() && spec.text.isEmpty()) spec = spec.copy(text = from.spec.text, items = from.spec.items, caption = spec.caption.ifEmpty { from.spec.caption })
        val order = orders.getOrPut(id) { orders.size }
        val v = Version(id, order, spec, at, entry, from, versions.size)
        v.ops += b.op
        if (build) spread(v, 0 until spec.items.size, at, until).also { if (spec.items.isEmpty()) v.build = at to until }
        if (primitive == Primitive.Mark) aim(v, b)
        versions += v
        live[id] = v
        if (b.op in ACTS) act(v, b, at + 0.45f, until)
        return v
    }

    /** A mark sits on the element its text names, else on the latest element still up. */
    private fun aim(v: Version, b: Beat) {
        val named = live[b.content.text.trim()]
        val target = named ?: live.values.filter { !it.overlay && it.leave == Float.POSITIVE_INFINITY }.maxByOrNull { it.enter }
        if (target == null || target.spec.primitive == Primitive.Mark) return
        v.target = target
        val style = b.content.visual.lowercase()
        v.loop = when {
            "circle" in style || "loop" in style || "ring" in style -> true
            "under" in style || "line" in style -> false
            else -> target.spec.primitive !in Pieces.wordy
        }
    }

    /** Reveal [range] of [v]'s items one by one across [at]..[until]. */
    private fun spread(v: Version, range: IntRange, at: Float, until: Float) {
        val k = range.count()
        if (k <= 0) return
        val step = ((until - at - 0.3f) / k).coerceIn(MIN_STEP, MAX_STEP)
        for ((j, i) in range.withIndex()) v.reveals[i] = at + j * step
    }

    private fun build(v: Version, b: Beat, at: Float, until: Float) {
        v.ops += StageOp.Build
        val fresh = b.content.items.filter { item -> v.spec.items.none { it.label == item.label } }
        if (fresh.isNotEmpty()) {
            val start = v.spec.items.size
            v.spec = v.spec.copy(items = v.spec.items + fresh)
            spread(v, start until v.spec.items.size, at, until)
        } else if (b.content.items.isEmpty() && v.spec.items.isEmpty()) {
            if (v.build == null && at - v.enter < 0.5f) v.build = at to until else v.marks += at
        } else {
            v.marks += at
        }
    }

    private fun replaceOrMerge(v: Version, b: Beat, at: Float, until: Float) {
        if (b.primitive != null && b.primitive != v.spec.primitive) return handOver(v, b, at, until, Entry.Replace)
        build(v, b, at, until)
    }

    private fun handOver(old: Version, b: Beat, at: Float, until: Float, entry: Entry) {
        old.leave = at
        old.gone = at
        old.exit = entry
        for (m in versions) if (m.target === old && m.leave == Float.POSITIVE_INFINITY) {
            m.leave = at
            m.gone = at + EXIT
            live.remove(m.element)
        }
        val v = create(old.element, b, at, until, entry, old)
        v.ops += b.op
        // A morph keeps what was already shown: items it shares appear as they land.
        if (entry == Entry.Morph) for (i in v.spec.items.indices) v.reveals.putIfAbsent(i, at + MORPH_LAND + i * 0.04f)
    }

    private fun sweep(v: Version, at: Float) {
        if (v.leave != Float.POSITIVE_INFINITY) return
        v.leave = maxOf(at, v.enter + MIN_LIFE)
        v.gone = v.leave + EXIT
        v.ops += StageOp.Clear
        live.remove(v.element)
        // Marks leave with what they mark.
        for (m in versions) if (m.target === v && m.leave == Float.POSITIVE_INFINITY) {
            m.leave = v.leave
            m.gone = v.gone
            live.remove(m.element)
        }
    }

    /** Check, fill, highlight or dim an element that is up. */
    private fun act(v: Version, b: Beat, at: Float, until: Float) {
        v.ops += b.op
        val picks = picks(v, b.content)
        when (b.op) {
            StageOp.Check -> v.checks += at to picks
            StageOp.Fill -> if (v.spec.primitive in Pieces.fillable) {
                val dur = ((until - at) * 0.6f).coerceIn(MIN_FILL, MAX_FILL)
                v.fills += FillStep(at, dur, b.content.value, b.content.items, picks)
            } else {
                v.marks += at
            }
            StageOp.Highlight -> {
                if (picks.isEmpty()) v.marks += at else v.picks += at to picks
                if (v.dims.lastOrNull()?.second == true) v.dims += at to false
            }
            StageOp.Dim -> v.dims += at to true
            else -> Unit
        }
    }

    /** Which items a beat acts on: its picks, else its items found by label. */
    private fun picks(v: Version, c: BeatContent): List<Int> {
        val n = v.spec.items.size
        if (c.picks.isNotEmpty()) return c.picks.filter { it in 0 until maxOf(n, 1) || n == 0 }
        if (c.items.isEmpty()) return emptyList()
        return c.items.mapNotNull { item -> v.spec.items.indexOfFirst { it.label == item.label }.takeIf { it >= 0 } }
    }

    companion object {
        const val ALL = "*"
        /** Ops that act on an element already up (a create for them acts once it has landed). */
        val ACTS = setOf(StageOp.Check, StageOp.Fill, StageOp.Highlight, StageOp.Dim)
        const val LEAD = 0.08f
        const val EXIT = 0.38f
        const val SWEEP = 0.62f
        const val SWEEP_STAGGER = 0.06f
        const val MIN_LIFE = 0.5f
        const val MIN_STEP = 0.16f
        const val MAX_STEP = 0.75f
        const val MIN_FILL = 0.6f
        const val MAX_FILL = 1.6f
        const val MORPH_LAND = 0.45f
    }
}
