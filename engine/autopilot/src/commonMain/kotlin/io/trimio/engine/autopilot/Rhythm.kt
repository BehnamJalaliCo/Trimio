package io.trimio.engine.autopilot

import io.trimio.core.model.transcript.Word
import io.trimio.engine.motion.score.BeatScore
import io.trimio.engine.motion.score.NumberWords
import io.trimio.engine.motion.score.SceneScore
import io.trimio.engine.motion.score.Words

/**
 * The planner's sense of rhythm. Over the speaker, a visual event at least every [gap] seconds:
 * a beat landing or a cut. Where a stretch would hold only captions (the "empty stretches" an
 * editor notices at once), one event is added, rotating between a picture of what is named, a
 * pop of what the line means (its title, or a noun phrase — never a lone verb, adverb or time
 * word), a punch-in cut and (rarely, on high energy) a stamp — never a third graphic on screen,
 * never the same text treatment three times running, never a graphic that lives under 1.5 s. The hook, a call
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
    /** The call to action's keyword as said: never popped on its own. */
    private val keyword: String?,
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
            // Nothing fits (two graphics up, no word to land on): look a little later.
            from = if (kind == null) hole + RETRY_STEP else hole
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

    /**
     * Beats a cut would leave on screen for less than they need ([least]; [MIN_LIFE] at the
     * least) are dropped — a flash reads as a glitch; short spoken text that has the room is held
     * to [MIN_LIFE]. The call to action stays.
     */
    fun prune(scenes: MutableList<SceneScore>, least: (BeatScore) -> Float = { MIN_LIFE }) {
        var dropped = 0
        for (k in scenes.indices) {
            val e = sceneEnd(scenes, k)
            val kept = scenes[k].beats.mapNotNull { b ->
                val start = beatStart(b, scenes, k)
                val room = minOf(e + 0.05f, start + MAX_HOLD) - start
                val spoken = spokenEnd(b, scenes, k)?.let { it - start }
                val early = if (room < MIN_LIFE && b.at != null && b.recipe in MOVABLE) earlier(b, scenes, k, e) else null
                when {
                    b.recipe == "comment" -> b
                    // Words said at the very end of a scene come up a little before they are said.
                    early != null -> early
                    room < maxOf(MIN_LIFE, least(b)) - 0.01f -> null.also { dropped++ }
                    b.hold == null && spoken != null && spoken < MIN_LIFE -> b.copy(hold = MIN_LIFE)
                    else -> b
                }
            }
            if (kept != scenes[k].beats) scenes[k] = scenes[k].copy(beats = kept)
        }
        if (dropped > 0) notes += "rhythm: $dropped beats too short for their scene dropped"
    }

    /** [b] landing early enough in scene [k] to live [MIN_LIFE] before its end [e], or null. */
    private fun earlier(b: BeatScore, scenes: List<SceneScore>, k: Int, e: Float): BeatScore? {
        val range = sceneWords(scenes, k)
        val i = range.lastOrNull { startOf(it) <= e + 0.05f - MIN_LIFE }?.takeIf { it < b.at!! } ?: return null
        return b.copy(at = i, until = null, hold = b.hold ?: MIN_LIFE)
    }

    // ------------------------------------------------------------ time

    private fun startOf(i: Int) = words[i.coerceIn(0, words.lastIndex)].range.startMs / 1000f
    private fun endOf(i: Int) = words[i.coerceIn(0, words.lastIndex)].range.endMs / 1000f
    private fun sceneStart(scenes: List<SceneScore>, k: Int) = if (k == 0) 0f else startOf(scenes[k].from ?: 0)
    /** Where the compiler starts the next scene (a little before its first word), or the end of the piece. */
    private fun sceneEnd(scenes: List<SceneScore>, k: Int) = scenes.getOrNull(k + 1)?.let { n -> n.time ?: n.from?.let { startOf(it) - SCENE_LEAD } } ?: (endOf(words.lastIndex) + TAIL)
    private fun sceneWords(scenes: List<SceneScore>, k: Int): IntRange {
        // A scene placed by time with no words of its own (a cold open: its words are not on this clock).
        val from = scenes[k].from ?: if (scenes[k].time != null && k == 0 && scenes.size > 1) return IntRange.EMPTY else 0
        return from..((scenes.getOrNull(k + 1)?.from ?: words.size) - 1).coerceAtLeast(from)
    }

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

    /** When text tied to speech leaves on its own (the spoken words plus a linger), or null for other beats. */
    private fun spokenEnd(b: BeatScore, scenes: List<SceneScore>, k: Int): Float? {
        if (b.recipe !in TEXT_RECIPES || b.time != null) return null
        val range = sceneWords(scenes, k)
        val last = b.until ?: b.at?.let { at -> at + (b.text?.split(' ')?.count { it.isNotBlank() } ?: 1) - 1 }
            ?: b.text?.let { locate(it, Lines.Line(range.first, range.last)) }?.last ?: return null
        return endOf(last) + LINGER
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

    /** Where an event could go in a stretch, by kind: a pop is a landing word and what it says. */
    private class Spots(val pop: Pair<Int, String>?, val stress: Int?, val cut: Int?, val picture: Pair<Int, String>?, val energy: Float) {
        val any: Boolean get() = pop != null || cut != null || picture != null
    }

    /** Adds one event in scene [k] soon after [t0]; returns its kind, or null when nothing fits. */
    private fun fillAt(scenes: MutableList<SceneScore>, k: Int, t0: Float): String? {
        // Where nothing fits, look again a little earlier and allow a quicker cut.
        val spots = spots(scenes, k, t0, relaxed = false)?.takeIf { it.any } ?: spots(scenes, k, t0, relaxed = true) ?: return null
        val options = listOfNotNull(
            // A picture of what is named says more than any word on screen.
            ("picture" to 2.5f).takeIf { spots.picture != null },
            ("pop" to 1f).takeIf { spots.pop != null },
            ("punch" to 0.9f).takeIf { spots.cut != null },
            ("stamp" to 0.3f).takeIf { stampable(spots) },
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
            "stamp" -> spots.pop!!.let { (i, text) ->
                lastStampAt = startOf(i)
                BeatScore(recipe = "stamp", at = i, text = text, hold = FILL_HOLD, energy = 0.85f, place = "top")
            }
            else -> spots.pop!!.let { (i, text) ->
                BeatScore(recipe = popRecipe(spots.energy, startOf(i), scenes), at = i, text = text, hold = FILL_HOLD, energy = spots.energy, place = "top")
            }
        }
        if (beat != null) {
            usedRecipes += beat.recipe
            scenes[k] = scenes[k].copy(beats = scenes[k].beats + beat)
        }
        return kind
    }

    /** A stamp says what a pop would, short and loud: high energy, a stressed landing, two words at most, rarely. */
    private fun stampable(spots: Spots): Boolean {
        val (i, text) = spots.pop ?: return false
        return spots.energy >= 0.6f && words[i].emphasis >= 0.6f && text.split(' ').size <= 2 && startOf(i) - lastStampAt >= STAMP_SPACING
    }

    /** The words an event could land on soon after [t0], or null when there are none. */
    private fun spots(scenes: List<SceneScore>, k: Int, t0: Float, relaxed: Boolean): Spots? {
        val e = sceneEnd(scenes, k)
        val s = sceneStart(scenes, k)
        val target = t0 + gap * (if (relaxed) 0.5f else 0.7f)
        val shot = if (relaxed) MIN_SHOT * 0.7f else MIN_SHOT
        // A graphic must have room to live: no landing in the last moments of a scene.
        val candidates = sceneWords(scenes, k).filter { i -> startOf(i) in (t0 + if (relaxed) MIN_LEAD / 2 else MIN_LEAD)..(t0 + gap) && startOf(i) < e - shot }
        if (candidates.isEmpty()) return null
        val spans = scenes[k].beats.map { b -> beatStart(b, scenes, k).let { it to beatEnd(b, it, e) } }
        // At most two graphics at once.
        fun crowded(t: Float) = spans.count { (a, b) -> a < t + FILL_HOLD && b > t + 0.1f } >= 2
        fun near(i: Int) = -kotlin.math.abs(startOf(i) - target)
        val free = candidates.filter { !crowded(startOf(it)) && startOf(it) < e - MIN_LIFE }
        val stress = free.filter { nounish(it) }.maxByOrNull { i -> Words.weight(words[i]) + 0.15f * near(i) }
        // A cut only where nothing on screen would be cut short (beats end with their scene).
        val cut = candidates.filter { i -> startOf(i) - s >= shot && e - startOf(i) >= shot && spans.none { (a, b) -> a < startOf(i) - 0.05f && b > startOf(i) + 0.05f } }
            .maxByOrNull { i -> near(i) + (if (texts.getOrNull(i - 1)?.lastOrNull()?.let { it in ".،," } == true) 0.5f else 0f) + 0.3f * Words.weight(words[i]) }
        val picture = pictureNear(candidates)?.takeIf { !crowded(startOf(it.first)) && startOf(it.first) < e - MIN_LIFE }
        val pop = if (free.isEmpty()) null else popFor(free, stress, onScreen(scenes))
        return Spots(pop, stress, cut, picture, (pop?.first ?: stress)?.let(::lineEnergy) ?: 0f)
    }

    /**
     * What a pop says, and where it lands: the line's own title when it is said here (or is a
     * short paraphrase in the piece's language), else a two- or three-word noun phrase around the
     * stressed noun. Never a lone verb, adverb or time word; never what is already on screen.
     */
    private fun popFor(free: List<Int>, stress: Int?, shown: Set<String>): Pair<Int, String>? {
        val k = lines.indexOfFirst { free.first() in it.range }
        val title = reads.getOrNull(k)?.title?.trim()?.takeIf { it.isNotEmpty() && fresh(it, shown) }
        if (title != null && meaningful(title.split(' '))) {
            val said = locate(title, lines[k])
            when {
                said != null && said.first in free -> return said.first to title
                said == null && stress != null && stress in lines[k].range && title.split(' ').size <= MAX_PARAPHRASE -> return stress to title
            }
        }
        stress ?: return null
        val phrase = phraseAt(stress)
        // A lone word only when it names something (an entity): «کلیپت» alone says nothing.
        val named = u.entities.any { stress in it.at..maxOf(it.at, it.until) }
        return phrase.takeIf { fresh(it, shown) && (' ' in it || (named && NumberWords.normalize(it).length >= LONE_NOUN)) }?.let { stress to it }
    }

    /** Two or three words around a noun: the noun and the nouns or adjectives after (or before) it, within its line. */
    private fun phraseAt(i: Int): String {
        val line = lines.firstOrNull { i in it.range } ?: Lines.Line(i, i)
        var end = i
        // Words join a phrase within a clause: never across a comma or a sentence end.
        fun joins(a: Int, b: Int) = b in line.range && texts[a].last() !in ".،," && nounish(b)
        while (end - i < 2 && joins(end, end + 1)) end++
        val start = if (end == i && joins(i - 1, i - 1)) i - 1 else i
        return (start..end).joinToString(" ") { texts[it].trimEnd('.', '،', ',') }
    }

    /** A phrase that carries meaning: a noun in it, in the piece's language, not a lone verb or filler. */
    private fun meaningful(parts: List<String>): Boolean {
        val native = !rtl || parts.any { p -> p.any { it in '؀'..'ۿ' } }
        return native && parts.none { p -> p.any { it.isDigit() } } && parts.any { nounishWord(it) } && !(parts.size == 1 && !nounishWord(parts[0]))
    }

    private val rtl = texts.count { w -> w.any { it in '؀'..'ۿ' } } * 2 > texts.size

    private fun fresh(text: String, shown: Set<String>) = NumberWords.normalize(text.replace(" ", "")) !in shown

    /** Every text and label already on screen in the edit, normalised (a pop never repeats a voucher's «سرمایه اولیه»). */
    private fun onScreen(scenes: List<SceneScore>): Set<String> =
        scenes.flatMap { sc -> sc.beats.flatMap { listOfNotNull(it.text, it.label) } }.map { NumberWords.normalize(it.replace(" ", "")) }.toSet()

    private fun nounish(i: Int): Boolean = nounishWord(texts[i]) && NumberWords.at(texts, i) == null

    /** A noun or adjective worth the screen: not a function word, verb, adverb, time word, number or unit. */
    private fun nounishWord(w: String): Boolean {
        val n = NumberWords.normalize(w).trimEnd('.', '،', ',')
        return Words.isContent(w) && n.length >= MIN_POP && !verb(n) && n !in STOP_WORDS && w.none { it.isDigit() } &&
            Quantities.unitOf(w) == null && n != keyword?.let(NumberWords::normalize)
    }

    /** A conjugated verb («می‌گفتیم», «بکنی», «گذاشتم», «شده»), normalised: not something to show. */
    private fun verb(n: String): Boolean = ((n.startsWith("می") || n.startsWith("نمی")) && n.length >= MIN_VERB) || VERB.matches(n)

    /** Something concrete named near these words, with a picture not shown yet. */
    private fun pictureNear(candidates: List<Int>): Pair<Int, String>? {
        val range = candidates.first()..candidates.last()
        u.entities.firstOrNull { it.at in range && pictured(it.visual) && it.kind in PICTURED }?.let { return it.at to it.visual }
        val k = lines.indexOfFirst { candidates.first() in it.range }.takeIf { it >= 0 } ?: return null
        val query = reads.getOrNull(k)?.visual?.substringBefore(',')?.trim()?.takeIf { q -> pictured(q) && q.split(' ').size in 1..3 }
        return query?.let { (candidates.firstOrNull { Words.isContent(texts[it]) } ?: candidates.first()) to it }
    }

    /** A picture query worth showing: concrete, unused, not a logo (brands are logo tiles, not stickers). */
    private fun pictured(q: String) = isPicture(q) && q !in usedVisuals

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

        /** A picture query worth showing: concrete, not a logo (brands are logo tiles, not stickers) or an abstract icon. */
        fun isPicture(q: String) = q.isNotBlank() && q.none { it.isDigit() } && NOT_PICTURES.none { it in q.lowercase() }

        /** Entity kinds worth a picture. */
        val PICTURED = setOf("object", "concept", "place", "product")

        /** Beats that may land a little before their words (text and stamps), rather than flash. */
        private val MOVABLE = TEXT_RECIPES + "stamp"
        private val TECH = setOf("tech", "software", "ai", "crypto", "gaming")
        private val ALTERNATE_TEXT = mapOf(
            "slam" to "flip", "mask-rise" to "flip", "type-on" to "mask-rise", "blur-in" to "mask-rise",
            "flip" to "mask-rise", "spread" to "slam", "stack" to "slam", "glitch" to "slam",
        )

        /**
         * Beats that keep the screen alive on their own while up: the call to action, names landing
         * one by one, a chat thread's bubbles, and the figures (counting up, filling, ticking, the
         * voucher's reveal).
         */
        private val SELF_PACED = setOf("comment", "logos", "list", "objects", "countdown", "counter", "voucher", "stats", "progress", "message")

        /** Adverbs, time words and fillers: said with stress, but nothing to pop on screen. */
        private val STOP_WORDS = setOf(
            "دیروز", "امروز", "فردا", "پریروز", "الان", "الآن", "حالا", "هنوز", "همیشه", "هرگز", "فقط", "خیلی", "راحت", "سریع", "درجا", "اول", "آخر", "دیگه",
            "دیگر", "فعال", "دریافت", "باز", "تموم", "تمام", "پر", "یعنی", "بعد", "قبل", "اینجا", "اونجا", "همونجا", "اینجوری", "اونجوری", "واقعا", "اصلا",
            "کلا", "دقیقا", "عزیزم", "بابا", "فلان", "همون", "همین", "یکی", "بیشتر", "کمتر", "نیازی", "مخصوص", "اسمش", "اولش", "برام", "براتون", "موفق",
            "really", "just", "actually", "going", "gonna", "doing", "today", "yesterday", "now", "very",
        )

        /** Conjugations of everyday verbs («بکنی», «گذاشتم», «شده», «بریم», «نبود»), normalised. */
        private val VERB = Regex(
            "^(ن|ب|نمی|می)?(کن|کرد|شد|شو|دار|داشت|ذار|ذاشت|زار|گذار|گذاشت|گیر|گرفت|داد|ده|رو|رفت|ر|گو|گ|گفت|خور|خورد|تون|تونست|خواه|خواست|" +
                "بین|دید|یا|اومد|آمد|باش|بود|هست|است|نیست|زن|زد|فرست|فرستاد|نویس|نوشت|کش|کشید)(م|ی|ه|ید|یم|ند|ین|ن|ش|یت|مون|تون|شون)?$",
        )

        /** Picture queries that are not pictures: logos and abstract icons. */
        private val NOT_PICTURES = listOf("logo", "icon", "interface", "overlay", "screen showing")

        private const val RHYTHM_GAP = 2.5f
        private const val MIN_RHYTHM_GAP = 1.8f
        private const val MAX_RHYTHM_GAP = 4.5f
        private const val MIN_LEAD = 0.8f
        private const val MIN_LIFE = 1.5f
        private const val RETRY_STEP = 0.5f
        private const val MAX_PARAPHRASE = 3
        private const val LONE_NOUN = 4
        private const val MIN_VERB = 4
        private const val MIN_SHOT = 1.0f
        private const val FILL_HOLD = 1.6f
        private const val STAMP_SPACING = 8f
        private const val MAX_FILLS = 400
        private const val MIN_POP = 3
        private const val TYPICAL_TEXT = 12

        // As the compiler holds beats.
        private const val LINGER = 0.55f
        private const val MAX_HOLD = 7f
        private const val TAIL = 0.45f
        private const val SCENE_LEAD = 0.12f
    }
}
