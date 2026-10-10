package io.trimio.engine.autopilot

import io.trimio.core.model.transcript.Word
import io.trimio.engine.motion.score.BeatScore
import io.trimio.engine.motion.score.NumberWords
import io.trimio.engine.motion.score.SceneScore
import io.trimio.engine.motion.score.Words

/**
 * The planner's sense of rhythm. Over the speaker, a visual event at least every [gap] seconds:
 * a beat landing or a cut. Where a stretch would hold only captions (the "empty stretches" an
 * editor notices at once), one event is added on the stressed word, rotating between a keyword
 * pop, a picture of what is named, a punch-in cut and (rarely, on high energy) a stamp — never a
 * third graphic on screen, never the same text treatment three times running. The hook, a call
 * to action and a list of names landing one by one keep the screen alive on their own while up;
 * full-frame scenes are graphics already and are left alone.
 *
 * [rng] is the planner's own stream for this, so the rest of a seed's edit does not move.
 */
internal class Rhythm(
    private val words: List<Word>,
    private val lines: List<Lines.Line>,
    private val reads: List<LineRead>,
    private val u: Understanding,
    private val taste: Taste,
    private val rng: Rng,
    /** The piece's energy (brief, mood, taste). */
    private val energy: Float,
    /** Recipes the edit uses, for taste learning; fills are added. */
    private val usedRecipes: MutableList<String>,
    /** Picture queries already on screen. */
    private val usedVisuals: MutableSet<String>,
    /** Where a quote is said inside a line (the planner's matcher). */
    private val locate: (String, Lines.Line) -> IntRange?,
) {
    val notes = mutableListOf<String>()
    private val texts = words.map { it.text }

    /** The longest a footage stretch may go without an event: wider for a sparse taste, tighter for a dense one. */
    val gap: Float = (RHYTHM_GAP / taste.density.coerceIn(0.5f, 1.5f)).coerceIn(MIN_RHYTHM_GAP, MAX_RHYTHM_GAP)
    private var lastFill: String? = null
    private var lastStampAt = -100f

    /** Fills the quiet stretches of footage scenes in [scenes] (a punch-in may split a scene in two). */
    fun keepAlive(scenes: MutableList<SceneScore>) {
        val counts = mutableMapOf<String, Int>()
        var k = 0
        var from = 0f
        var guard = 0
        while (k < scenes.size && guard++ < MAX_FILLS) {
            val hole = if (scenes[k].bg == null) firstHole(scenes, k, from) else null
            if (hole == null) {
                k++
                from = 0f
                continue
            }
            val kind = fillAt(scenes, k, hole)
            if (kind != null) counts[kind] = (counts[kind] ?: 0) + 1
            // Nothing fits (two graphics up, no word to land on): accept this stretch, look further.
            from = if (kind == null) hole + gap else hole
        }
        if (counts.isNotEmpty()) notes += "rhythm: every ${kotlin.math.round(gap * 10f) / 10f}s — " + counts.entries.joinToString { "${it.value} ${it.key}" }
    }

    /** A text treatment used three times running is swapped for a neighbour (the critic's rule, kept upfront). */
    fun vary(scenes: MutableList<SceneScore>) {
        data class At(val k: Int, val j: Int, val t: Float)
        val order = scenes.indices.flatMap { k ->
            scenes[k].beats.indices.filter { j -> scenes[k].beats[j].recipe in TEXT_RECIPES }.map { j -> At(k, j, beatStart(scenes[k].beats[j], scenes, k)) }
        }.sortedBy { it.t }
        for (n in 2 until order.size) {
            val r = order.subList(n - 2, n + 1).map { scenes[it.k].beats[it.j].recipe }
            if (r.toSet().size != 1) continue
            val (k, j) = order[n].let { it.k to it.j }
            val swap = ALTERNATE_TEXT.getValue(r[0])
            scenes[k] = scenes[k].copy(beats = scenes[k].beats.mapIndexed { i, b -> if (i == j) b.copy(recipe = swap) else b })
        }
    }

    // ------------------------------------------------------------ time

    private fun startOf(i: Int) = words[i.coerceIn(0, words.lastIndex)].range.startMs / 1000f
    private fun endOf(i: Int) = words[i.coerceIn(0, words.lastIndex)].range.endMs / 1000f
    private fun sceneStart(scenes: List<SceneScore>, k: Int) = if (k == 0) 0f else startOf(scenes[k].from ?: 0)
    private fun sceneEnd(scenes: List<SceneScore>, k: Int) = scenes.getOrNull(k + 1)?.from?.let(::startOf) ?: (endOf(words.lastIndex) + TAIL)
    private fun sceneWords(scenes: List<SceneScore>, k: Int) = (scenes[k].from ?: 0)..((scenes.getOrNull(k + 1)?.from ?: words.size) - 1).coerceAtLeast(scenes[k].from ?: 0)

    /** When a beat lands, as the compiler will place it. */
    private fun beatStart(b: BeatScore, scenes: List<SceneScore>, k: Int): Float {
        b.time?.let { return it }
        b.at?.let { return startOf(it) }
        val range = sceneWords(scenes, k)
        val quoted = b.text?.let { locate(it, Lines.Line(range.first, range.last)) }
        return quoted?.let { startOf(it.first) } ?: (sceneStart(scenes, k) + 0.2f)
    }

    /** Roughly when a beat leaves, as the compiler will hold it (elements stay for their scene). */
    private fun beatEnd(b: BeatScore, start: Float, sceneEnd: Float): Float {
        val hold = b.hold
        val until = b.until
        val end = when {
            hold != null -> start + hold
            until != null -> endOf(until) + LINGER
            b.recipe in TEXT_RECIPES -> start + maxOf(1f, 0.35f + 0.055f * (b.text?.length ?: TYPICAL_TEXT))
            else -> sceneEnd
        }
        return minOf(end, sceneEnd + 0.05f, start + MAX_HOLD)
    }

    /** Until when a beat keeps the screen alive by itself: the hook, the call to action, names landing one by one. */
    private fun aliveUntil(b: BeatScore, start: Float, sceneEnd: Float) = if (b.recipe in SELF_PACED || b.time != null) beatEnd(b, start, sceneEnd) else start

    /** The start of the first stretch in scene [k] after [from] longer than [gap] with no event, or null. */
    private fun firstHole(scenes: List<SceneScore>, k: Int, from: Float): Float? {
        val e = sceneEnd(scenes, k)
        val range = sceneWords(scenes, k)
        if (range.isEmpty()) return null
        val speechEnd = minOf(e, endOf(range.last))
        var t = maxOf(sceneStart(scenes, k), from)
        for ((a, alive) in scenes[k].beats.map { b -> beatStart(b, scenes, k).let { it to aliveUntil(b, it, e) } }.sortedBy { it.first }) {
            if (a - t > gap) return t
            t = maxOf(t, a, alive)
        }
        return if (speechEnd - t > gap) t else null
    }

    // ------------------------------------------------------------ filling

    /** Where an event could go in a stretch, by kind. */
    private class Spots(val word: Int?, val cut: Int?, val picture: Pair<Int, String>?, val energy: Float)

    /** Adds one event in scene [k] soon after [t0]; returns its kind, or null when nothing fits. */
    private fun fillAt(scenes: MutableList<SceneScore>, k: Int, t0: Float): String? {
        val spots = spots(scenes, k, t0) ?: return null
        val word = spots.word
        val options = listOfNotNull(
            ("pop" to 1f).takeIf { word != null },
            ("picture" to 1.3f).takeIf { spots.picture != null },
            ("punch" to 0.9f).takeIf { spots.cut != null },
            ("stamp" to 0.35f).takeIf { word != null && spots.energy >= 0.6f && words[word].emphasis >= 0.6f && startOf(word) - lastStampAt >= STAMP_SPACING },
        ).map { (kind, w) -> kind to w * (if (kind == lastFill) 0.25f else 1f) * (if (kind == "punch") 1f else taste.density.coerceIn(0.5f, 1.5f)) }
        if (options.isEmpty()) return null
        val kind = rng.weighted(options)
        lastFill = kind
        val beat = when (kind) {
            "punch" -> {
                val cut = spots.cut!!
                val (before, after) = scenes[k].beats.partition { beatStart(it, scenes, k) < startOf(cut) - 0.01f }
                scenes[k] = scenes[k].copy(beats = before)
                scenes.add(k + 1, SceneScore(from = cut, beats = after))
                null
            }
            "picture" -> spots.picture!!.let { (i, query) ->
                usedVisuals += query
                BeatScore(recipe = "object", at = i, visual = query, hold = FILL_HOLD, place = "top")
            }
            "stamp" -> {
                lastStampAt = startOf(word!!)
                BeatScore(recipe = "stamp", at = word, text = texts[word].trimEnd('.', '،', ','), hold = 1.4f, energy = 0.85f, place = "top")
            }
            else -> pop(scenes, k, word!!, spots.energy)
        }
        if (beat != null) {
            usedRecipes += beat.recipe
            scenes[k] = scenes[k].copy(beats = scenes[k].beats + beat)
        }
        return kind
    }

    /** The words an event could land on soon after [t0], or null when there are none. */
    private fun spots(scenes: List<SceneScore>, k: Int, t0: Float): Spots? {
        val e = sceneEnd(scenes, k)
        val s = sceneStart(scenes, k)
        val target = t0 + gap * 0.7f
        val candidates = sceneWords(scenes, k).filter { i -> startOf(i) in (t0 + MIN_LEAD)..(t0 + gap) && startOf(i) < e - MIN_TAIL }
        if (candidates.isEmpty()) return null
        val spans = scenes[k].beats.map { b -> beatStart(b, scenes, k).let { it to beatEnd(b, it, e) } }
        // At most two graphics at once.
        fun crowded(t: Float) = spans.count { (a, b) -> a < t + FILL_HOLD && b > t + 0.1f } >= 2
        fun near(i: Int) = -kotlin.math.abs(startOf(i) - target)
        val word = candidates.filter { i -> popWord(i) && !crowded(startOf(i)) }.maxByOrNull { i -> Words.weight(words[i]) + 0.15f * near(i) }
        // A cut only where nothing on screen would be cut short (beats end with their scene).
        val cut = candidates.filter { i -> startOf(i) - s >= MIN_SHOT && e - startOf(i) >= MIN_SHOT && spans.none { (a, b) -> a < startOf(i) - 0.05f && b > startOf(i) + 0.05f } }
            .maxByOrNull { i -> near(i) + (if (texts.getOrNull(i - 1)?.lastOrNull()?.let { it in ".،," } == true) 0.5f else 0f) + 0.3f * Words.weight(words[i]) }
        val picture = pictureNear(candidates)?.takeIf { !crowded(startOf(it.first)) }
        return Spots(word, cut, picture, word?.let(::lineEnergy) ?: 0f)
    }

    /** The stressed word (with a short neighbour) popped in a short text treatment. */
    private fun pop(scenes: List<SceneScore>, k: Int, i: Int, e: Float): BeatScore {
        val next = i + 1
        val two = NumberWords.normalize(texts[i]).length <= SHORT_WORD && next in sceneWords(scenes, k) && Words.isContent(texts[next]) && texts[i].last() !in ".،,"
        val text = (i..(if (two) next else i)).joinToString(" ") { texts[it].trimEnd('.', '،', ',') }
        return BeatScore(recipe = popRecipe(e, startOf(i), scenes), at = i, text = text, energy = e, place = "top")
    }

    /** A word worth popping: content, not a verb or filler, not a number, not the call to action's keyword. */
    private fun popWord(i: Int): Boolean {
        val n = NumberWords.normalize(texts[i]).trimEnd('.', '،', ',')
        val verb = texts[i].startsWith("می‌") || texts[i].startsWith("نمی‌") || n in LIGHT_WORDS
        val counted = NumberWords.at(texts, i) != null || texts[i].any { it.isDigit() } || Quantities.unitOf(texts[i]) != null
        return Words.isContent(texts[i]) && !verb && !counted && n.length >= MIN_POP && n != u.cta?.keyword?.let(NumberWords::normalize)
    }

    /** Something concrete named near these words, with a picture not shown yet. */
    private fun pictureNear(candidates: List<Int>): Pair<Int, String>? {
        val range = candidates.first()..candidates.last()
        u.entities.firstOrNull { it.at in range && it.visual.isNotBlank() && it.visual !in usedVisuals && it.kind in PICTURED }?.let { return it.at to it.visual }
        val k = lines.indexOfFirst { candidates.first() in it.range }.takeIf { it >= 0 } ?: return null
        val query = reads.getOrNull(k)?.visual?.substringBefore(',')?.trim()?.takeIf { q -> q.isNotEmpty() && q.split(' ').size in 1..3 && q.none { it.isDigit() } && q !in usedVisuals }
        return query?.let { (candidates.firstOrNull { Words.isContent(texts[it]) } ?: candidates.first()) to it }
    }

    private fun lineEnergy(i: Int): Float {
        val read = reads.getOrNull(lines.indexOfFirst { i in it.range }) ?: return energy
        return (read.energy * 0.6f + energy * 0.4f).coerceIn(0.2f, 1f)
    }

    /** A short text treatment for one word, never the third of a kind in a row. */
    private fun popRecipe(e: Float, at: Float, scenes: List<SceneScore>): String {
        val options = when {
            e >= 0.75f -> listOf("slam" to 1f, "flip" to 0.6f, "spread" to 0.4f, "glitch" to if (u.domain in TECH) 0.4f else 0f)
            e >= 0.5f -> listOf("mask-rise" to 1f, "flip" to 0.7f, "slam" to 0.4f, "type-on" to 0.3f)
            else -> listOf("blur-in" to 1f, "mask-rise" to 0.7f, "flip" to 0.3f)
        }
        // Like the critic, repetition is judged among text treatments only.
        val line = timeline(scenes).filter { it.second in TEXT_RECIPES }
        val before = line.filter { it.first < at }.map { it.second }.takeLast(2)
        val after = line.filter { it.first >= at }.map { it.second }.take(2)
        fun third(r: String) = (before.size == 2 && before.all { it == r }) || (before.lastOrNull() == r && after.firstOrNull() == r) ||
            (after.size == 2 && after.all { it == r })
        val live = options.filter { !third(it.first) }.map { (r, w) -> r to w * taste.recipe(r) * if (before.lastOrNull() == r) 0.3f else 1f }
        return rng.weighted(live.ifEmpty { listOf("mask-rise" to 1f, "flip" to 1f).filter { !third(it.first) }.ifEmpty { listOf("blur-in" to 1f) } })
    }

    /** Every beat of the edit in screen order: (time, recipe). */
    private fun timeline(scenes: List<SceneScore>): List<Pair<Float, String>> =
        scenes.indices.flatMap { k -> scenes[k].beats.map { beatStart(it, scenes, k) to it.recipe } }.sortedBy { it.first }

    companion object {
        val TEXT_RECIPES = setOf("slam", "mask-rise", "type-on", "blur-in", "flip", "spread", "stack", "glitch")

        /** Entity kinds worth a picture. */
        val PICTURED = setOf("object", "concept", "place", "product")

        private val TECH = setOf("tech", "software", "ai", "crypto", "gaming")
        private val ALTERNATE_TEXT = mapOf(
            "slam" to "flip", "mask-rise" to "flip", "type-on" to "mask-rise", "blur-in" to "mask-rise",
            "flip" to "mask-rise", "spread" to "slam", "stack" to "slam", "glitch" to "slam",
        )

        /** Beats that keep the screen alive on their own while up: the call to action, names landing one by one. */
        private val SELF_PACED = setOf("comment", "logos", "list", "objects")

        /** Light verbs and fillers: said with stress, but nothing to pop on screen. */
        private val LIGHT_WORDS = setOf(
            "کردم", "کردن", "کردیم", "کرد", "کنید", "بکنید", "بکنی", "بکنیم", "کنی", "کنم", "بکن", "شد", "شده", "شدن", "گذاشتم", "گذاشت", "دادن", "داد",
            "گرفتن", "گرفت", "نبود", "باشید", "باشین", "نیست", "بریم", "برو", "اومده", "رفتیم", "یعنی", "الان", "دیگه", "همون", "همونجا", "اینجوری", "اونجا",
            "really", "just", "actually", "going", "gonna", "doing",
        )

        private const val RHYTHM_GAP = 2.5f
        private const val MIN_RHYTHM_GAP = 1.8f
        private const val MAX_RHYTHM_GAP = 4.5f
        private const val MIN_LEAD = 0.8f
        private const val MIN_TAIL = 0.4f
        private const val MIN_SHOT = 1.0f
        private const val FILL_HOLD = 1.6f
        private const val STAMP_SPACING = 8f
        private const val MAX_FILLS = 400
        private const val SHORT_WORD = 3
        private const val MIN_POP = 3
        private const val TYPICAL_TEXT = 12

        // As the compiler holds beats.
        private const val LINGER = 0.55f
        private const val MAX_HOLD = 7f
        private const val TAIL = 0.45f
    }
}
