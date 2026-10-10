package io.trimio.engine.autopilot

import io.trimio.core.model.transcript.Word
import io.trimio.engine.assets.MusicMood
import io.trimio.engine.motion.score.BeatScore
import io.trimio.engine.motion.score.CaptionScore
import io.trimio.engine.motion.score.NumberWords
import io.trimio.engine.motion.score.SceneScore
import io.trimio.engine.motion.score.Score
import io.trimio.engine.motion.score.Words

/**
 * Turns an [Understanding] into a [Score] the way a senior motion designer would cut it, with a
 * seed: every seed gives a different edit (look, recipes, transitions, which lines become
 * full-screen graphics, hook form, caption rhythm, emphasis marks, camera) and every one of them
 * obeys the same craft rules, so variety never costs quality:
 *
 * - the payoff opens the piece; something changes on screen every 2–3 seconds;
 * - the speaker stays on screen for most of it; full-frame graphics never follow each other;
 * - every graphic lands on the word it pictures; one highlight per caption line;
 * - names become logos, numbers counters, concrete things pictures, lists rows; numbers with a
 *   meaning get the graphic that says it ([Quantities]): money given a voucher, views and comments
 *   a stats board, hours left a countdown, a quota that filled a progress bar;
 * - over the speaker no stretch goes without a visual event ([keepAlive]);
 * - the call to action is the clearest thing on the last screen.
 */
class Planner(private val taste: Taste = Taste(), private val brief: String = "") {

    data class Plan(val score: Score, val music: MusicMood?, val choices: Choices, val notes: List<String>)

    /**
     * [words] are on the output clock (after silence cuts); [footage] is false for audio-only
     * pieces, which are all graphics.
     */
    fun plan(words: List<Word>, lines: List<Lines.Line>, u: Understanding, seed: Long, footage: Boolean = true): Plan =
        Session(words, lines, u, seed, footage).run()

    private inner class Session(val words: List<Word>, val lines: List<Lines.Line>, val u: Understanding, val seed: Long, val footage: Boolean) {
        val notes = mutableListOf<String>()
        val texts = words.map { it.text }
        val norm = texts.map(NumberWords::normalize)

        // Forks in a fixed order: each decision area has its own stream.
        val root = Rng(seed)
        val lookRng = root.fork(1)
        val showRng = root.fork(2)
        val recipeRng = root.fork(3)
        val transitionRng = root.fork(4)
        val takeoverRng = root.fork(5)
        val captionRng = root.fork(6)
        val markRng = root.fork(7)
        val hookRng = root.fork(8)
        val paletteRng = root.fork(9)
        val fillRng = root.fork(10)

        /** Each line's numbers with their units and cues. */
        val senses = lines.map { Quantities.sense(texts.subList(it.first, it.last + 1), it.first) }

        val energy = (if (u.brief.energy >= 0f) u.brief.energy else moodEnergy(u.mood)) + taste.energy

        /** The brief's energy outranks the model's mood guess ("luxury" for a hype reel is a misread). */
        val mood = when {
            u.brief.energy >= 0.75f -> "energetic"
            u.brief.energy in 0f..0.35f -> "calm"
            else -> u.mood
        }

        /**
         * The payoff line: the model's pick, unless it holds no number, or another line's number
         * says far more (money given beats a count; see [Quantities.Sense.weight]).
         */
        val payoffLine: Int? = run {
            val strongest = lines.indices.filter { k -> senses[k].quantities.any { it.kind == Quantities.Kind.Percent || it.value >= 10 } }
                .maxByOrNull { senses[it].weight() }
            val picked = u.hook?.line?.takeIf { it in lines.indices }
            val pickedWeight = picked?.let { senses[it].weight() } ?: -1.0
            when {
                picked != null && senses[picked].quantities.isNotEmpty() && (strongest == null || pickedWeight * 2 >= senses[strongest].weight()) -> picked
                strongest != null -> strongest
                else -> picked
            }
        }
        val look = chooseLook()
        val music = chooseMusic()
        val mark = markRng.weighted(markOptions(look))
        val usedRecipes = mutableListOf<String>()
        val usedTransitions = mutableListOf<String>()
        var ctaPlaced = false

        /** Where the call to action's keyword landed (word index). */
        var ctaAt: Int? = null
        val shownLogos = mutableSetOf<String>()

        /** The call to action's own beats (the DM step), kept even inside a running scene. */
        val ctaBeats = mutableListOf<BeatScore>()

        /** Rich graphics already shown (a voucher said twice is shown once). */
        val shownRich = mutableSetOf<String>()

        /** Picture queries already on screen. */
        val usedVisuals = mutableSetOf<String>()
        var reads: List<LineRead> = emptyList()
        val rtl = texts.count { w -> w.any { it in '\u0600'..'\u06FF' } } * 2 > texts.size

        fun run(): Plan {
            if (words.isEmpty() || lines.isEmpty()) {
                return Plan(Score(look = look, captions = CaptionScore(show = false)), music, Choices(seed, look, music?.id, emptyList(), emptyList()), notes)
            }
            reads = lines.indices.map { k -> refine(k, u.lines.getOrNull(k) ?: LineRead(show = "none")) }
            val takeovers = chooseTakeovers(reads)
            val scenes = mutableListOf<SceneScore>()
            var previousTakeover = false
            // A beat that runs on past its line (a list of names) keeps its scene until it is done.
            var coveredUntil = -1
            for ((k, line) in lines.withIndex()) {
                val read = reads[k]
                val covered = line.first <= coveredUntil && scenes.isNotEmpty()
                // A line merged into a running beat can bring two full-frame scenes together: never.
                val takeover = k in takeovers && !covered && !previousTakeover
                val beats = mutableListOf<BeatScore>()
                if (k == 0) hook(reads)?.let { beats += it }
                // A line inside a running beat adds nothing of its own (only the call to action).
                beats += beatsFor(k, line, read, takeover, hasHook = beats.isNotEmpty()).filter { !covered || it.recipe == "comment" || it in ctaBeats }
                coveredUntil = maxOf(coveredUntil, beats.maxOfOrNull { it.until ?: it.at ?: -1 } ?: -1)
                if (covered) {
                    scenes[scenes.lastIndex] = scenes.last().let { it.copy(beats = it.beats + beats) }
                    continue
                }
                scenes += scene(k, line, read, takeover, previousTakeover, beats)
                previousTakeover = takeover
            }
            keepAlive(scenes)
            vary(scenes)
            val captionWords = taste.captionWords ?: when (u.brief.captions) {
                "word" -> captionRng.pick(listOf(2, 3, 3))
                "phrase" -> captionRng.pick(listOf(4, 5))
                else -> captionRng.pick(listOf(3, 3, 4))
            }
            val showCaptions = taste.captions && u.brief.captions != "none"
            val accent = paletteRng.pick(io.trimio.engine.motion.recipe.Look.accents[look] ?: listOf(null))
            val score = Score(
                look = look,
                accent = accent,
                bpm = music?.bpm?.toFloat(),
                captions = CaptionScore(show = showCaptions, maxWords = captionWords),
                scenes = scenes,
                auto = false,
            )
            notes += "seed $seed: look $look $accent, music ${music?.id}, mark $mark, captions $captionWords, takeovers ${takeovers.sorted()}"
            return Plan(score, music, Choices(seed, look, music?.id, usedRecipes.toList(), usedTransitions.toList()), notes)
        }

        private fun scene(k: Int, line: Lines.Line, read: LineRead, takeover: Boolean, afterTakeover: Boolean, beats: List<BeatScore>): SceneScore {
            val transition = when {
                k == 0 -> null
                takeover -> transitionRng.weighted(intoTakeover()).also { usedTransitions += it }
                afterTakeover -> transitionRng.weighted(outOfTakeover()).also { usedTransitions += it }
                else -> null
            }
            return SceneScore(
                from = line.first,
                bg = if (takeover) (if (recipeRng.chance(0.5f) || read.show == "network") "grid" else "aurora") else null,
                camera = if (takeover) null else camera(read),
                transition = transition,
                beats = beats,
            )
        }

        // ------------------------------------------------------------ reading each line

        /** The model's reading checked against what the line actually says. */
        private fun refine(k: Int, raw: LineRead): LineRead {
            val line = lines[k]
            val read = localised(raw)
            val number = NumberWords.findAll(texts.subList(line.first, line.last + 1)).firstOrNull()
            // A list of names often runs on into the next line ("…به Claude Code، | Codex یا OpenCode…"):
            // the logos start with the first name and carry the rest.
            val own = brandsIn(line)
            val after = if (own.isEmpty()) emptyList() else (k + 1..minOf(lines.lastIndex, k + 2)).asSequence()
                .map { brandsIn(lines[it]) }.takeWhile { it.isNotEmpty() }.flatten().toList()
            val brands = (own + after).distinctBy { it.lowercase() }
            val show = showFor(k, line, read, number, brands)
            if (show != "logos") return once(k, read.copy(show = show))
            val items = (brands + read.items.filter { it.isLogoName() && locate(it, line) != null }).distinctBy { it.lowercase() }
            // Names already shown as logos by an earlier line are not shown again.
            val fresh = items.filter { it.lowercase() !in shownLogos }
            if (fresh.size * 2 < items.size || fresh.isEmpty()) return read.copy(show = if (read.title.isNotBlank()) "headline" else "none", items = emptyList())
            shownLogos += fresh.map { it.lowercase() }
            return read.copy(show = show, items = items)
        }

        /** Titles speak the video's language: an English title on a Persian piece is dropped (names excepted). */
        private fun localised(raw: LineRead): LineRead {
            val foreign = raw.title.isNotBlank() && raw.title.none { it in '\u0600'..'\u06FF' }
            val name = u.entities.any { it.name.equals(raw.title, true) }
            return if (rtl && foreign && !name) raw.copy(title = "") else raw
        }

        private fun showFor(k: Int, line: Lines.Line, read: LineRead, number: NumberWords.Found?, brands: List<String>): String {
            val bigNumber = number != null && (number.percent || number.value >= 10)
            val plain = read.show == "none" || read.show == "headline"
            val sense = senses[k]
            val rich = sense.richShow()
            return when {
                // A list of names said in the line is always shown as logos, whatever was asked.
                brands.size >= 2 -> "logos"
                // A voucher, stats, countdown or progress needs its numbers in the line.
                read.show in RICH_SHOWS && !sense.supports(read.show) -> rich ?: if (bigNumber) "counter" else plainShow(line, read)
                // When the numbers clearly say more than a headline or a bare counter, show what they say.
                rich != null && read.show in UPGRADABLE -> rich
                !grounded(line, read, number != null, brands.isNotEmpty()) -> plainShow(line, read)
                read.show in setOf("counter", "meter") && number == null -> "headline"
                read.show == "logos" && brands.isEmpty() && read.items.isEmpty() -> "headline"
                read.show == "comment" && u.cta == null -> "headline"
                plain && bigNumber -> if (showRng.chance(0.75f)) "counter" else read.show
                else -> read.show
            }
        }

        private fun plainShow(line: Lines.Line, read: LineRead) = if (read.title.isNotBlank() && locate(read.title, line) != null) "headline" else "none"

        /**
         * A rich graphic is shown once: the same voucher or quota said again becomes a stamp of
         * what changed («پر شد») or a plain headline, so the piece moves forward.
         */
        private fun once(k: Int, read: LineRead): LineRead {
            val s = senses[k]
            val key = when (read.show) {
                "voucher" -> s.money?.let { "voucher ${it.value}" }
                "countdown" -> (s.deadline ?: s.quantities.firstOrNull { it.kind == Quantities.Kind.Duration })?.let { "countdown ${it.value}" }
                "progress" -> "progress ${s.quota?.value}"
                "stats" -> "stats ${s.social.map { it.value }}"
                else -> null
            } ?: return read
            if (shownRich.add(key)) return read
            val filled = s.filled
            return when {
                read.show == "progress" && filled != null -> read.copy(show = "stamp", title = filled.joinToString(" ") { texts[it].trimEnd('.', '،', ',') })
                else -> read.copy(show = if (read.title.isNotBlank()) "headline" else "none")
            }
        }

        /**
         * Whether the model's choice for this line is about this line: its title or items are
         * said in it, or it names a number or a brand. Small models sometimes repeat an earlier
         * line's reading; an ungrounded graphic is worse than none.
         */
        private fun grounded(line: Lines.Line, read: LineRead, number: Boolean, brands: Boolean): Boolean = when (read.show) {
            "none", "headline", "lower-third", "stamp" -> true
            "counter", "meter" -> number
            "logos" -> brands || read.items.any { locate(it, line) != null }
            "comment" -> u.cta != null && line.last >= words.size * 2 / 3
            // A list is of things, not the line's own words in order.
            "list", "objects" -> read.items.size >= 2 && read.items.count { item -> item.length >= 3 } >= 2 &&
                !read.items.all { item -> line.range.any { texts[it].trimEnd('،', ',', '.') == item } } &&
                // Items speak the video's language (or are names said in the line), never picture prompts.
                read.items.all { item -> !rtl || item.any { it in '\u0600'..'\u06FF' } || locate(item, line) != null }
            "chart" -> number || (line.first..line.last).any { norm[it] in GROWTH || norm[it] in DECLINE }
            in RICH_SHOWS -> sense(line).supports(read.show)
            else -> locate(read.title, line) != null || read.items.any { locate(it, line) != null } ||
                u.entities.any { it.at in line.range && it.visual.isNotBlank() } || (read.gist.isNotBlank() && read.visual.isNotBlank() && line.size >= 4 && repeatOf(read) == null)
        }

        /** An earlier line with the very same reading (a model loop). */
        private fun repeatOf(read: LineRead): Int? = u.lines.indexOfFirst { it !== read && it.show == read.show && it.title == read.title && it.items == read.items }
            .takeIf { it >= 0 && u.lines.indexOf(read) > it }

        /** Brand, app and product names said in [line], canonical spelling. */
        private fun brandsIn(line: Lines.Line) = u.entities
            .filter { it.at in line.range && it.kind in setOf("brand", "app", "product", "organization") && it.name.isLogoName() }
            .map { it.name }.distinctBy { it.lowercase() } +
            // Names in Latin script said in the line, even if the model missed them.
            line.range.filter { texts[it].trimEnd('،', ',', '.').let { w -> w.isNotEmpty() && w.first().isUpperCase() } }
                .map { texts[it].trimEnd('،', ',', '.') }.filter { n -> u.entities.none { e -> n in e.name } }

        // ------------------------------------------------------------ structure

        /** Which lines become full-frame graphics: the strongest candidates, never adjacent, within budget. */
        private fun chooseTakeovers(reads: List<LineRead>): Set<Int> {
            if (!footage) return lines.indices.toSet()
            val total = duration(0, lines.lastIndex)
            // The share of full-frame graphics varies by seed (a calmer or a busier cut of the same piece).
            val budget = total * taste.takeover * takeoverRng.range(0.6f, 1.35f)
            // Not in the first seconds: the viewer first meets the speaker (and the hook).
            val candidates = reads.indices.filter { k -> k > 0 && k < lines.lastIndex && reads[k].show in TAKEOVER_SHOWS && words[lines[k].first].range.startMs >= EARLIEST_TAKEOVER_MS }
                .sortedByDescending { k -> TAKEOVER_SHOWS.getValue(reads[k].show) * takeoverRng.range(0.5f, 1.5f) }
            val chosen = mutableSetOf<Int>()
            var used = 0f
            for (k in candidates) {
                val d = duration(k, k)
                val neighbour = k - 1 in chosen || k + 1 in chosen
                if (neighbour || used + d > budget || d < MIN_TAKEOVER_S) continue
                chosen += k
                used += d
            }
            return chosen
        }

        private fun duration(a: Int, b: Int) = (words[lines[b].last].range.endMs - words[lines[a].first].range.startMs) / 1000f

        // ------------------------------------------------------------ the hook

        /** The payoff, before anything else: a counter for a number, else a slammed title. */
        private fun hook(reads: List<LineRead>): BeatScore? {
            val k = payoffLine?.takeIf { it > 0 && u.brief.payoffFirst } ?: return null
            val line = lines[k]
            val number = NumberWords.findAll(texts.subList(line.first, line.last + 1)).firstOrNull()
            val title = (u.hook?.title?.takeIf { u.hook.line == k } ?: reads[k].title).ifBlank { u.hook?.title.orEmpty() }.trim()
            notes += "hook: payoff of line $k first (${number?.value ?: title})"
            val counter = number != null && (number.percent || number.value >= 2) && hookRng.chance(0.8f)
            // The number that pays off: the amount given, the time left, the views; else the first said.
            val sense = senses[k]
            val q = sense.voucher ?: sense.deadline ?: sense.social.firstOrNull()
            return when {
                counter && q != null -> quantityHook(k, q)
                counter -> counterHook(number!!.value, number.decimals, number.percent, title)
                title.isNotBlank() -> textHook(title)
                else -> null
            }
        }

        /** A meaningful number opens on its own graphic half the time (a voucher), else a counter with its unit. */
        private fun quantityHook(k: Int, q: Quantities.Quantity): BeatScore {
            val line = lines[k]
            val label = listOfNotNull(q.unit.ifBlank { null }, if (q.kind == Quantities.Kind.Money) voucherLabel(line, q, reads[k]) else null)
                .joinToString(" ").ifBlank { null }
            if (q.kind == Quantities.Kind.Money && hookRng.chance(0.5f)) {
                usedRecipes += "voucher"
                return voucherBeat(q, reads[k], voucherLabel(line, q, reads[k]), place = "top").copy(at = null, until = null, time = 0.05f, hold = 2.4f, energy = 0.95f)
            }
            return counterHook(q.value, q.decimals, false, label ?: reads[k].title)
        }

        private fun counterHook(value: Double, decimals: Int, percent: Boolean, title: String): BeatScore {
            usedRecipes += "counter"
            // The counter shows the number: its label keeps only the words.
            val label = title.split(' ').filter { w -> w.none { it.isDigit() } && w !in setOf("٪", "%", "درصد") }.joinToString(" ")
            return BeatScore(
                recipe = "counter", time = 0.05f, hold = 2.3f, value = value.toFloat(), decimals = decimals,
                suffix = if (percent) "٪" else "", label = label.ifBlank { null }, energy = 0.95f, place = "top",
            )
        }

        private fun textHook(title: String): BeatScore {
            val recipe = recipeRng.weighted(listOf("slam" to 1f, "stack" to 0.6f, "spread" to 0.4f).map { (r, w) -> r to w * taste.recipe(r) })
            usedRecipes += recipe
            return BeatScore(recipe = recipe, time = 0.05f, hold = 2.2f, text = title, energy = 0.95f, place = "top", mark = mark)
        }

        // ------------------------------------------------------------ beats

        private fun beatsFor(k: Int, line: Lines.Line, read: LineRead, takeover: Boolean, hasHook: Boolean): List<BeatScore> {
            val e = (read.energy * 0.6f + energy * 0.4f).coerceIn(0.2f, 1f)
            val primary = primary(line, read, e, takeover)
            val out = mutableListOf<BeatScore>()
            // The hook owns the first seconds: line 0's own graphic waits unless it is the CTA.
            val waits = hasHook && k == 0 && read.show !in setOf("comment", "lower-third")
            if (primary != null && !waits) out += primary
            if (!(hasHook && k == 0)) extra(line, read, takeover, primary != null, out.size)?.let { out += it }
            if (k == lines.lastIndex && !ctaPlaced && out.none { it.recipe == "comment" }) {
                u.cta?.let { cta -> comment(line, read.copy(title = cta.keyword))?.let { out += it } }
            }
            callToAction(k, line, read, out)
            usedRecipes += out.map { it.recipe }
            return out
        }

        /**
         * The call to action in two steps: the comment field types the keyword, then, when the
         * speaker sends viewers to their DMs, a short stamp says so while the field stays up. Said
         * long before the end, the field comes back on the last line, so the ask owns the last screen.
         */
        private fun callToAction(k: Int, line: Lines.Line, read: LineRead, out: MutableList<BeatScore>) {
            val placed = out.indexOfFirst { it.recipe == "comment" }
            if (placed >= 0 && !ctaPlaced) {
                ctaPlaced = true
                val at = out[placed].at ?: line.first
                ctaAt = at
                dmStep(at, lines.getOrNull(k + 1)?.last ?: line.last)?.let { dm ->
                    // The field holds through the DM step (its scene runs on to it).
                    out[placed] = out[placed].copy(until = dm.at)
                    out += dm
                    ctaBeats += dm
                }
                return
            }
            val first = ctaAt ?: return
            val late = startOf(line.first) - startOf(first) > CTA_REPRISE_S
            if (k == lines.lastIndex && placed < 0 && late) {
                u.cta?.let { cta -> comment(line, read.copy(title = cta.keyword))?.let { out += it } }
            }
        }

        /** «بعد برو دایرکتتو چک کن»: a stamp of the DM step, below the comment field. */
        private fun dmStep(after: Int, until: Int): BeatScore? {
            val i = (after + 1..minOf(until, words.lastIndex)).firstOrNull { j -> DM_WORDS.any { norm[j].trimEnd('،', ',', '.').startsWith(it) } } ?: return null
            val end = (i + 1..minOf(i + 2, words.lastIndex)).firstOrNull { norm[it].trimEnd('،', ',', '.') in CHECK_VERBS }
            val text = if (end != null) (i..end).joinToString(" ") { texts[it].trimEnd('.', '،', ',') } else if (rtl) "دایرکت" else "DM"
            return BeatScore(recipe = "stamp", at = i, text = text, hold = 1.8f, energy = 0.8f, place = "lower")
        }

        private fun primary(line: Lines.Line, read: LineRead, e: Float, takeover: Boolean): BeatScore? = when (compact(read, takeover)) {
            "counter" -> counter(line, read, e, takeover)
            "logos" -> logos(line, read)
            "terminal" -> terminal(line, read, takeover)
            "network" -> network(line, read, takeover)
            "meter" -> meter(line, read, takeover)
            "chart" -> chart(line, read, takeover)
            "object" -> objectBeat(line, read, takeover)
            "objects" -> objects(line, read, takeover)
            "list" -> list(line, read, takeover)
            "comment" -> if (ctaPlaced) null else comment(line, read)
            "lower-third" -> lowerThird(line, read)
            "stamp" -> stamp(line, read, e)
            "voucher" -> voucher(line, read, e, takeover)
            "stats" -> stats(line, read, e, takeover)
            "countdown" -> countdown(line, read, e, takeover)
            "progress" -> progress(line, read, e, takeover)
            "headline" -> headline(line, read, e, takeover)
            else -> null
        }

        /**
         * Over the speaker only compact graphics fit (the face owns the middle): a map, a meter, a
         * chart or a list needs the whole frame, so on footage it becomes its compact cousin.
         */
        private fun compact(read: LineRead, takeover: Boolean): String = if (takeover) read.show else when (read.show) {
            "network" -> if (read.visual.isNotBlank()) "object" else "headline"
            "meter" -> "counter"
            "chart" -> "counter"
            "list" -> "headline"
            else -> read.show
        }

        /** A takeover gets a title over its graphic; a footage line may get a picture of what it names. */
        private fun extra(line: Lines.Line, read: LineRead, takeover: Boolean, hasPrimary: Boolean, count: Int): BeatScore? {
            // Rich graphics carry their own label: a title above them would say it twice.
            val titled = takeover && hasPrimary && read.title.isNotBlank() && read.show !in setOf("headline", "stamp") && read.show !in RICH_SHOWS
            if (titled) return BeatScore(recipe = textRecipe(0.5f), text = read.title, place = "top", energy = 0.55f, mark = mark, at = line.first)
            // A picture only where the line has no graphic of its own (two things at the top would fight).
            val room = !takeover && count == 0
            return if (room && showRng.chance(PICTURE_CHANCE * taste.density)) pictureFor(line, read) else null
        }

        private fun headline(line: Lines.Line, read: LineRead, e: Float, takeover: Boolean): BeatScore? {
            val title = read.title.ifBlank { strongestPhrase(line) ?: return null }
            val quoted = locate(title, line) != null
            return BeatScore(
                recipe = textRecipe(e), text = title, at = if (quoted) null else line.first, energy = e, mark = mark,
                place = if (takeover) "center" else showRng.pick(listOf("top", "top", "center")),
                emphasis = emphasisIn(title),
            )
        }

        private fun counter(line: Lines.Line, read: LineRead, e: Float, takeover: Boolean): BeatScore? {
            val quantities = sense(line).quantities
            // The number with a meaning (people, views, money) over a bare one said first.
            val q = quantities.firstOrNull { it.unit.isNotEmpty() && it.value >= 10 } ?: quantities.firstOrNull() ?: return headline(line, read, e, takeover)
            val at = q.at
            val percent = q.kind == Quantities.Kind.Percent
            val growth = (at until minOf(line.last + 1, at + q.count + 3)).any { norm[it] in GROWTH }
            return BeatScore(
                recipe = "counter", at = at, until = minOf(line.last, at + q.count), value = q.value.toFloat(), decimals = q.decimals,
                suffix = if (percent) "٪" else "", prefix = if ((percent && growth) || q.opened) "+" else "",
                label = unitLabel(q) ?: read.title.ifBlank { null }, energy = maxOf(e, 0.7f), place = if (takeover) "center" else "top",
            )
        }

        /** What a counted thing is, for the label under its number («نفر اول», «ظرفیت جدید», «بازدید»). */
        private fun unitLabel(q: Quantities.Quantity): String? = when (q.kind) {
            Quantities.Kind.People -> if (q.first) (if (rtl) "${q.unit} اول" else "first ${q.unit}") else q.unit
            Quantities.Kind.Capacity -> if (q.opened) (if (rtl) "ظرفیت جدید" else "new ${q.unit}") else q.unit
            Quantities.Kind.Percent, Quantities.Kind.Plain -> null
            else -> q.unit.ifBlank { null }
        }

        /** Money viewers get: the amount, its unit, what it is, and the coin's mark. */
        private fun voucher(line: Lines.Line, read: LineRead, e: Float, takeover: Boolean): BeatScore? {
            val q = sense(line).let { it.voucher ?: it.money } ?: return counter(line, read, e, takeover)
            return voucherBeat(q, read, voucherLabel(line, q, read), place = if (takeover) "center" else "top").copy(until = line.last, energy = maxOf(e, 0.8f))
        }

        private fun voucherBeat(q: Quantities.Quantity, read: LineRead, label: String?, place: String): BeatScore {
            // The compiler attaches the brand's mark to the first item: the coin's canonical name.
            val brand = q.brand ?: read.items.firstOrNull { it.isLogoName() }
            return BeatScore(
                recipe = "voucher", at = q.at, value = q.value.toFloat(), decimals = q.decimals, suffix = q.unit.ifBlank { null },
                label = label, items = listOfNotNull(brand), place = place,
            )
        }

        /** What the money is: the words right after the unit («سرمایه اولیه»), else "voucher" if said, else the line's title. */
        private fun voucherLabel(line: Lines.Line, q: Quantities.Quantity, read: LineRead): String? {
            val after = mutableListOf<String>()
            var i = q.last + 1
            while (i <= line.last && after.size < 2 && Words.isContent(texts[i]) && texts[i].none { it.isDigit() } && Quantities.unitOf(texts[i]) == null) {
                after += texts[i].trimEnd('.', '،', ',')
                if (texts[i].last() in ".،,") break
                i++
            }
            if (after.isNotEmpty()) return after.joinToString(" ")
            val named = line.range.firstOrNull { norm[it].trimEnd('.', '،', ',') in VOUCHER_WORDS }
            if (named != null) return texts[named].trimEnd('.', '،', ',')
            return read.title.takeIf { t -> t.isNotBlank() && t.none { it.isDigit() } && locate(t, Lines.Line(q.at, q.last)) == null }
        }

        /** Views and comments (or any two or three numbers said together) on one board. */
        private fun stats(line: Lines.Line, read: LineRead, e: Float, takeover: Boolean): BeatScore? {
            val sense = sense(line)
            val qs = sense.social.ifEmpty { sense.quantities.filter { it.value >= 1 && it.kind != Quantities.Kind.Duration } }.take(MAX_STATS)
            if (qs.size < 2) return counter(line, read, e, takeover)
            val labels = qs.mapIndexed { i, q -> q.unit.ifBlank { read.items.getOrNull(i).orEmpty() } }
            // The time it took, when said («کمتر از دوازده ساعت»), rides along as the board's label.
            val span = sense.quantities.firstOrNull { it.kind == Quantities.Kind.Duration && !it.remaining }?.let { d ->
                val less = (maxOf(line.first, d.at - 2) until d.at).any { norm[it] in LESS }
                val n = Quantities.digits(d.value, rtl)
                if (rtl) (if (less) "کمتر از $n ${d.unit}" else "در $n ${d.unit}") else (if (less) "in under $n ${d.unit}" else "in $n ${d.unit}")
            }
            return BeatScore(
                recipe = "stats", at = qs.first().at, until = line.last, items = labels, points = qs.map { it.value.toFloat() },
                label = span, energy = maxOf(e, 0.75f), place = if (takeover) "center" else "top",
            )
        }

        /** Time left: hours or minutes ticking down to what ends. */
        private fun countdown(line: Lines.Line, read: LineRead, e: Float, takeover: Boolean): BeatScore? {
            val sense = sense(line)
            val q = sense.deadline ?: sense.quantities.firstOrNull { it.kind == Quantities.Kind.Duration } ?: return counter(line, read, e, takeover)
            return BeatScore(
                recipe = "countdown", at = q.at, until = line.last, value = q.value.toFloat(), decimals = q.decimals, suffix = q.unit.ifBlank { null },
                label = deadlineLabel(line), energy = maxOf(e, 0.8f), place = if (takeover) "center" else "top",
            )
        }

        /** «تا پایان کمپین»: what the time runs out on, from the words of the line. */
        private fun deadlineLabel(line: Lines.Line): String {
            val noun = line.range.firstNotNullOfOrNull { i -> DEADLINES.entries.firstOrNull { norm[i].startsWith(it.key) }?.value }
            return when {
                noun != null && rtl -> "تا پایان $noun"
                noun != null -> "until the $noun ends"
                rtl -> "زمان باقی‌مانده"
                else -> "left"
            }
        }

        /** A quota filling up (the first thousand people), stamped full when it is; over the speaker, at the top. */
        private fun progress(line: Lines.Line, read: LineRead, e: Float, takeover: Boolean): BeatScore? {
            val sense = sense(line)
            val q = sense.quota ?: sense.quantities.firstOrNull { it.kind in setOf(Quantities.Kind.People, Quantities.Kind.Capacity) }
            val filled = sense.filled
            if (q == null && filled == null) return counter(line, read, e, takeover)
            val value = if (q?.kind == Quantities.Kind.Percent) q.value.toFloat().coerceIn(0f, 100f) else 100f
            val counted = q?.takeIf { it.kind != Quantities.Kind.Percent } ?: sense.quantities.firstOrNull { it.kind in setOf(Quantities.Kind.People, Quantities.Kind.Capacity) }
            val label = counted?.let { c ->
                val n = Quantities.digits(c.value, rtl)
                when {
                    rtl -> listOfNotNull(n, c.unit.ifBlank { null }, "اول".takeIf { c.first }).joinToString(" ")
                    else -> listOfNotNull("first".takeIf { c.first }, n, c.unit.ifBlank { null }).joinToString(" ")
                }
            } ?: read.title.ifBlank { null }
            return BeatScore(
                recipe = "progress", at = q?.at ?: filled!!.first, until = line.last, value = value, from = 0f, label = label,
                text = if (value >= 100f) (if (rtl) "تکمیل" else "FULL") else null, energy = maxOf(e, 0.7f), place = if (takeover) "center" else "top",
            )
        }

        private fun logos(line: Lines.Line, read: LineRead): BeatScore? {
            val names = read.items.take(5).ifEmpty { return null }
            // From the first name said to the last, which may be on the next line.
            val k = lines.indexOf(line)
            val span = Lines.Line(line.first, lines[minOf(lines.lastIndex, k + 2)].last)
            val first = names.firstNotNullOfOrNull { locate(it, span)?.first } ?: line.first
            val last = names.mapNotNull { locate(it, span)?.last }.maxOrNull() ?: line.last
            return BeatScore(recipe = "logos", at = first, until = maxOf(line.last, last), items = names, place = "top")
        }

        private fun terminal(line: Lines.Line, read: LineRead, takeover: Boolean): BeatScore = BeatScore(
            recipe = "terminal", at = line.first, until = line.last,
            label = read.items.getOrNull(0)?.takeIf { it.isCommand() } ?: "install ${slug(u.topic)}",
            items = listOf(read.items.getOrNull(1)?.takeIf { it.isAscii() && !it.isCommand() } ?: "done"), place = if (takeover) "center" else "top",
        )

        private fun network(line: Lines.Line, read: LineRead, takeover: Boolean): BeatScore = BeatScore(
            recipe = "network", at = focus(line), items = nodes(read),
            icon = showRng.pick(listOf("eye", "target", "spark", "bolt")), place = if (takeover) "center" else "top",
        )

        /** Five to nine node labels: the model's, then names from the piece, then the domain's usual parts. */
        private fun nodes(read: LineRead): List<String> {
            // Parts of the thing mapped, not the brands already shown as logos.
            val own = read.items.filter { it.length in 2..14 && it.lowercase() !in shownLogos && u.entities.none { e -> e.name.equals(it, true) } }
            val names = u.entities.map { it.name }.filter { it.length in 2..14 }
            val usual = NETWORK_DEFAULTS[u.domain] ?: NETWORK_DEFAULTS.getValue("tech")
            val parts = if (own.size >= MIN_NODES - 2) own else own + usual
            return (parts + names.filter { it.lowercase() !in shownLogos }).distinctBy { it.lowercase() }.take(maxOf(MIN_NODES, own.size).coerceAtMost(MAX_NODES))
        }

        private fun meter(line: Lines.Line, read: LineRead, takeover: Boolean): BeatScore? {
            val n = NumberWords.findAll(texts.subList(line.first, line.last + 1)).firstOrNull() ?: return null
            return BeatScore(
                recipe = "meter", at = line.first + n.start, until = line.last, value = n.value.toFloat().coerceIn(1f, 99f),
                items = read.items.take(2).takeIf { it.size == 2 && it.all { i -> !rtl || i.any { c -> c in '\u0600'..'\u06FF' } } } ?: emptyList(),
                // On a full-frame scene the title rides above; the meter keeps only its number.
                label = read.title.ifBlank { null }.takeIf { !takeover }, place = if (takeover) "center" else "top",
            )
        }

        private fun chart(line: Lines.Line, read: LineRead, takeover: Boolean): BeatScore {
            val down = (line.first..line.last).any { norm[it] in DECLINE }
            val walk = generateSequence(3f) { it + recipeRng.range(-0.8f, 1.9f) }.take(8).toList()
            return BeatScore(
                recipe = "chart", at = focus(line), points = if (down) walk.reversed() else walk, label = read.title.ifBlank { null },
                place = if (takeover) "center" else "top",
            )
        }

        private fun objectBeat(line: Lines.Line, read: LineRead, takeover: Boolean): BeatScore? {
            val visual = read.visual.ifBlank { entityVisual(line) } ?: return null
            return BeatScore(
                recipe = "object", at = entityAt(line) ?: focus(line), visual = visual.substringBefore(','),
                label = read.title.ifBlank { null }, place = if (takeover) "center" else "top",
            )
        }

        private fun objects(line: Lines.Line, read: LineRead, takeover: Boolean): BeatScore? {
            val queries = read.visual.split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val items = read.items.take(4)
            if (items.isEmpty()) return objectBeat(line, read, takeover)
            val visuals = items.indices.map { i -> queries.getOrNull(i) ?: entityVisualFor(items[i]) ?: items[i] }
            return BeatScore(recipe = "objects", at = line.first, until = line.last, items = items, visuals = visuals, place = if (takeover) "center" else "top")
        }

        private fun list(line: Lines.Line, read: LineRead, takeover: Boolean): BeatScore? {
            val items = read.items.take(4).ifEmpty { return null }
            return BeatScore(recipe = "list", at = line.first, until = line.last, text = items.joinToString("|"), place = if (takeover) "center" else "top")
        }

        private fun comment(line: Lines.Line, read: LineRead): BeatScore? {
            val keyword = u.cta?.keyword?.trim('«', '»', '"', ' ')?.ifBlank { null }?.let { k -> Proofreader.fromBrief(listOf(k), brief)[0] ?: k }
                ?: read.title.ifBlank { null } ?: return null
            val at = locate(keyword, line)?.first ?: line.first
            val end = words.last().range.endMs / 1000f + CTA_TAIL
            return BeatScore(
                recipe = "comment", at = at, text = keyword, hold = maxOf(3.5f, end - words[at].range.startMs / 1000f),
                label = CTA_LABELS[u.cta?.action] ?: CTA_LABELS.getValue("comment"), place = "top",
            )
        }

        private fun lowerThird(line: Lines.Line, read: LineRead): BeatScore? {
            val person = u.entities.firstOrNull { it.kind == "person" && it.at in line.range }?.name
            val name = person ?: read.title.ifBlank { return null }
            return BeatScore(recipe = "lower-third", at = line.first, text = name, label = read.title.takeIf { person != null && it.isNotBlank() }, place = "lower")
        }

        private fun stamp(line: Lines.Line, read: LineRead, e: Float): BeatScore? =
            read.title.ifBlank { null }?.let { BeatScore(recipe = "stamp", at = locate(it, line)?.first ?: focus(line), text = it, energy = maxOf(e, 0.75f), place = "top") }

        /** A sticker of the most concrete thing the line names, if the vocabulary has one. */
        private fun pictureFor(line: Lines.Line, read: LineRead): BeatScore? {
            val entity = u.entities.firstOrNull { it.at in line.range && it.visual.isNotBlank() && it.kind in PICTURED && it.visual !in usedVisuals }
            if (entity != null) return BeatScore(recipe = "object", at = entity.at, visual = entity.visual, hold = 1.8f, place = "top").also { usedVisuals += entity.visual }
            // The line's own picture, when it names something concrete (a short noun phrase).
            val query = read.visual.substringBefore(',').trim().takeIf { q -> q.split(' ').size in 1..3 && q.none { it.isDigit() } && q !in usedVisuals } ?: return null
            usedVisuals += query
            return BeatScore(recipe = "object", at = focus(line), visual = query, hold = 1.8f, place = "top", label = read.title.ifBlank { null })
        }

        // ------------------------------------------------------------ rhythm

        /**
         * Over the speaker, a visual event at least every [rhythmGap] seconds: a beat landing or a
         * cut. Where a stretch would hold only captions, one event is added on the stressed word,
         * rotating between a keyword pop, a picture of what is named, a punch-in cut and (rarely,
         * on high energy) a stamp — never a third graphic on screen, never the same treatment
         * three times running. A call to action on screen owns its stretch; so does a list of
         * names landing one by one, and full-frame scenes are graphics already.
         */
        private fun keepAlive(scenes: MutableList<SceneScore>) {
            if (!footage) return
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
                from = if (kind == null) hole + rhythmGap else hole
            }
            if (counts.isNotEmpty()) notes += "rhythm: every ${round1(rhythmGap)}s — " + counts.entries.joinToString { "${it.value} ${it.key}" }
        }

        /** The longest a footage stretch may go without an event: wider for a sparse taste, tighter for a dense one. */
        val rhythmGap: Float = (RHYTHM_GAP / taste.density.coerceIn(0.5f, 1.5f)).coerceIn(MIN_RHYTHM_GAP, MAX_RHYTHM_GAP)
        var lastFill: String? = null
        var lastStampAt = -100f

        private fun sceneStart(scenes: List<SceneScore>, k: Int) = if (k == 0) 0f else startOf(scenes[k].from ?: 0)
        private fun sceneEnd(scenes: List<SceneScore>, k: Int) = scenes.getOrNull(k + 1)?.from?.let(::startOf) ?: (endOf(words.lastIndex) + CTA_TAIL)
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

        /** The start of the first stretch in scene [k] after [from] longer than [rhythmGap] with no event, or null. */
        private fun firstHole(scenes: List<SceneScore>, k: Int, from: Float): Float? {
            val s = sceneStart(scenes, k)
            val e = sceneEnd(scenes, k)
            val range = sceneWords(scenes, k)
            if (range.isEmpty()) return null
            val speechEnd = minOf(e, endOf(range.last))
            var t = maxOf(s, from)
            for ((a, alive) in scenes[k].beats.map { b -> beatStart(b, scenes, k).let { it to aliveUntil(b, it, e) } }.sortedBy { it.first }) {
                if (a - t > rhythmGap) return t
                t = maxOf(t, a, alive)
            }
            return if (speechEnd - t > rhythmGap) t else null
        }

        /** Adds one event in scene [k] soon after [t0]; returns its kind, or null when nothing fits. */
        private fun fillAt(scenes: MutableList<SceneScore>, k: Int, t0: Float): String? {
            val e = sceneEnd(scenes, k)
            val s = sceneStart(scenes, k)
            val target = t0 + rhythmGap * 0.7f
            val candidates = sceneWords(scenes, k).filter { i -> startOf(i) in (t0 + MIN_LEAD)..(t0 + rhythmGap) && startOf(i) < e - MIN_TAIL }
            if (candidates.isEmpty()) return null
            val spans = scenes[k].beats.map { b -> beatStart(b, scenes, k).let { it to beatEnd(b, it, e) } }
            fun crowded(t: Float) = spans.count { (a, b) -> a < t + FILL_HOLD && b > t + 0.1f } >= 2
            val word = candidates.filter { i -> popWord(i) && !crowded(startOf(i)) }.maxByOrNull { i -> Words.weight(words[i]) - 0.15f * kotlin.math.abs(startOf(i) - target) }
            val cut = candidates.filter { i -> startOf(i) - s >= MIN_SHOT && e - startOf(i) >= MIN_SHOT && spans.none { (a, b) -> a < startOf(i) - 0.05f && b > startOf(i) + 0.05f } }
                .maxByOrNull { i -> -kotlin.math.abs(startOf(i) - target) + (if (texts.getOrNull(i - 1)?.lastOrNull()?.let { it in ".،," } == true) 0.5f else 0f) + 0.3f * Words.weight(words[i]) }
            val picture = pictureNear(candidates)?.takeIf { !crowded(startOf(it.first)) }
            val energyAt = word?.let { lineEnergy(it) } ?: 0f
            val options = listOfNotNull(
                ("pop" to 1f).takeIf { word != null },
                ("picture" to 1.3f).takeIf { picture != null },
                ("punch" to 0.9f).takeIf { cut != null },
                ("stamp" to 0.35f).takeIf { word != null && energyAt >= 0.6f && words[word].emphasis >= 0.6f && startOf(word) - lastStampAt >= STAMP_SPACING },
            ).map { (kind, w) -> kind to w * (if (kind == lastFill) 0.25f else 1f) * (if (kind == "punch") 1f else taste.density.coerceIn(0.5f, 1.5f)) }
            if (options.isEmpty()) return null
            val kind = fillRng.weighted(options)
            lastFill = kind
            when (kind) {
                "punch" -> {
                    val at = startOf(cut!!)
                    val (before, after) = scenes[k].beats.partition { beatStart(it, scenes, k) < at - 0.01f }
                    scenes[k] = scenes[k].copy(beats = before)
                    scenes.add(k + 1, SceneScore(from = cut, beats = after))
                }
                "picture" -> {
                    val (i, query) = picture!!
                    usedVisuals += query
                    scenes[k] = scenes[k].copy(beats = scenes[k].beats + BeatScore(recipe = "object", at = i, visual = query, hold = FILL_HOLD, place = "top").also { usedRecipes += it.recipe })
                }
                "stamp" -> {
                    lastStampAt = startOf(word!!)
                    val beat = BeatScore(recipe = "stamp", at = word, text = texts[word].trimEnd('.', '،', ','), hold = 1.4f, energy = 0.85f, place = "top")
                    scenes[k] = scenes[k].copy(beats = scenes[k].beats + beat.also { usedRecipes += it.recipe })
                }
                else -> {
                    val i = word!!
                    val two = NumberWords.normalize(texts[i]).length <= SHORT_WORD && i + 1 in sceneWords(scenes, k) && Words.isContent(texts[i + 1]) && texts[i].last() !in ".،,"
                    val text = (i..(if (two) i + 1 else i)).joinToString(" ") { texts[it].trimEnd('.', '،', ',') }
                    val recipe = popRecipe(energyAt, startOf(i), scenes)
                    scenes[k] = scenes[k].copy(beats = scenes[k].beats + BeatScore(recipe = recipe, at = i, text = text, energy = energyAt, place = "top").also { usedRecipes += recipe })
                }
            }
            return kind
        }

        /** A word worth popping: content, not a verb or filler, not a number, not the call to action's keyword. */
        private fun popWord(i: Int): Boolean {
            val n = NumberWords.normalize(texts[i]).trimEnd('.', '،', ',')
            val verb = texts[i].startsWith("می\u200c") || texts[i].startsWith("نمی\u200c") || n in LIGHT_WORDS
            return Words.isContent(texts[i]) && !verb && n.length >= MIN_POP && NumberWords.at(texts, i) == null && texts[i].none { it.isDigit() } &&
                n != u.cta?.keyword?.let(NumberWords::normalize) && Quantities.unitOf(texts[i]) == null
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
            return fillRng.weighted(live.ifEmpty { listOf("mask-rise" to 1f, "flip" to 1f).filter { !third(it.first) }.ifEmpty { listOf("blur-in" to 1f) } })
        }

        /** Every beat of the edit in screen order: (time, recipe). */
        private fun timeline(scenes: List<SceneScore>): List<Pair<Float, String>> =
            scenes.indices.flatMap { k -> scenes[k].beats.map { beatStart(it, scenes, k) to it.recipe } }.sortedBy { it.first }

        /** A text treatment used three times running is swapped for a neighbour (the critic's rule, kept upfront). */
        private fun vary(scenes: MutableList<SceneScore>) {
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

        // ------------------------------------------------------------ choices

        private fun textRecipe(e: Float): String {
            val options = when {
                e >= 0.8f -> listOf("slam" to 1f, "stack" to 0.7f, "spread" to 0.5f, "glitch" to if (u.domain in TECH) 0.5f else 0f, "flip" to 0.4f)
                e >= 0.5f -> listOf("mask-rise" to 1f, "flip" to 0.6f, "type-on" to if (u.domain in TECH) 0.6f else 0.25f, "slam" to 0.35f, "spread" to 0.35f)
                else -> listOf("blur-in" to 1f, "mask-rise" to 0.8f, "type-on" to 0.3f)
            }
            return recipeRng.weighted(options.map { (r, w) -> r to w * taste.recipe(r) * if (usedRecipes.lastOrNull() == r) 0.3f else 1f })
        }

        private fun camera(read: LineRead): String? = when {
            read.energy >= 0.8f -> showRng.pick(listOf("push-in", null))
            read.energy <= 0.35f -> showRng.pick(listOf("still", null))
            else -> null
        }

        private fun intoTakeover(): List<Pair<String, Float>> = when (mood) {
            "calm", "luxury", "inspiring" -> listOf("leak" to 1f, "zoom" to 0.8f, "whip" to 0.3f)
            "serious" -> listOf("zoom" to 1f, "whip" to 0.5f, "leak" to 0.3f)
            else -> listOf("whip" to 1f, "flash" to 0.7f, "zoom" to 0.6f, "leak" to 0.25f)
        }.map { (t, w) -> t to w * taste.transition(t) }

        private fun outOfTakeover(): List<Pair<String, Float>> = listOf("whip" to 1f, "zoom" to 0.5f, "cut" to 0.6f).map { (t, w) -> t to w * taste.transition(t) }

        private fun chooseLook(): String {
            u.brief.look.takeIf { it in LOOKS }?.let { return it }
            val base = when (mood) {
                "calm", "luxury", "inspiring" -> listOf("lumen" to 1f, "paper" to 0.45f, "noir" to 0.35f)
                "serious" -> listOf("paper" to 1f, "noir" to 0.8f, "lumen" to 0.4f)
                "playful" -> listOf("paper" to 1f, "noir" to 0.7f, "lumen" to 0.5f)
                else -> listOf("noir" to 1f, "paper" to 0.45f, "lumen" to 0.35f)
            }
            return lookRng.weighted(base.map { (l, w) -> l to w * taste.look(l) })
        }

        private fun chooseMusic(): MusicMood? {
            if (!taste.music || u.brief.music == "none") return null
            MusicMood.fromId(u.brief.music)?.let { return it }
            val options = when (mood) {
                "energetic", "urgent" -> listOf(MusicMood.Energetic to 1f, MusicMood.Uplifting to 0.6f)
                "calm" -> listOf(MusicMood.Chill to 1f, MusicMood.Cinematic to 0.4f)
                "luxury", "inspiring" -> listOf(MusicMood.Cinematic to 1f, MusicMood.Chill to 0.6f, MusicMood.Uplifting to 0.4f)
                "serious" -> listOf(MusicMood.Corporate to 1f, MusicMood.Cinematic to 0.6f, MusicMood.Tense to 0.3f)
                "playful" -> listOf(MusicMood.Uplifting to 1f, MusicMood.Energetic to 0.5f)
                else -> listOf(MusicMood.Uplifting to 1f, MusicMood.Corporate to 0.6f, MusicMood.Energetic to 0.5f)
            }
            return lookRng.weighted(options)
        }

        private fun markOptions(look: String) = when (look) {
            "lumen" -> listOf("ink" to 1f, "underline" to 0.6f, "block" to 0.3f)
            "paper" -> listOf("block" to 1f, "underline" to 0.7f, "circle" to 0.5f)
            else -> listOf("block" to 1f, "ink" to 0.5f, "underline" to 0.4f, "circle" to 0.3f)
        }

        // ------------------------------------------------------------ word helpers

        /** Where [quote] is said inside [line] (word indices), if it is. */
        private fun locate(quote: String, line: Lines.Line): IntRange? {
            val q = quote.split(' ').map(NumberWords::normalize).filter { it.isNotEmpty() }
            if (q.isEmpty()) return null
            fun same(a: String, b: String) = a == b || (a.length > 2 && b.startsWith(a)) || (b.length > 2 && a.startsWith(b))
            for (i in line.first..line.last) {
                val hits = q.indices.count { j -> norm.getOrNull(i + j)?.let { same(it.trimEnd('،', ',', '.'), q[j]) } == true }
                if (hits * 2 > q.size) return i..minOf(line.last, i + q.size - 1)
            }
            return null
        }

        /** The most stressed content word of the line, with a neighbour for context. */
        private fun strongestPhrase(line: Lines.Line): String? {
            val peak = line.range.filter { Words.isContent(texts[it]) }.maxByOrNull { Words.weight(words[it]) } ?: return null
            val b = minOf(line.last, peak + 1)
            return (peak..b).joinToString(" ") { texts[it].trimEnd('.', '،', ',') }
        }

        private fun focus(line: Lines.Line): Int = line.range.filter { Words.isContent(texts[it]) }.maxByOrNull { Words.weight(words[it]) } ?: line.first

        private fun emphasisIn(title: String): List<String> {
            val parts = title.split(' ').filter { it.isNotEmpty() }
            if (parts.size < 2) return emptyList()
            return listOf(parts.maxByOrNull { p -> norm.indexOfFirst { it == NumberWords.normalize(p) }.let { i -> if (i >= 0) words[i].emphasis else 0f } + p.length * 0.01f }!!)
        }

        private fun sense(line: Lines.Line): Quantities.Sense = senses[lines.indexOf(line).coerceAtLeast(0)]
        private fun startOf(i: Int) = words[i.coerceIn(0, words.lastIndex)].range.startMs / 1000f
        private fun endOf(i: Int) = words[i.coerceIn(0, words.lastIndex)].range.endMs / 1000f

        private fun entityVisual(line: Lines.Line): String? = u.entities.firstOrNull { it.at in line.range && it.visual.isNotBlank() }?.visual
        private fun entityAt(line: Lines.Line): Int? = u.entities.firstOrNull { it.at in line.range && it.visual.isNotBlank() }?.at
        private fun entityVisualFor(item: String): String? = u.entities.firstOrNull { it.name.equals(item, ignoreCase = true) && it.visual.isNotBlank() }?.visual
    }

    private companion object {
        val LOOKS = setOf("noir", "paper", "lumen")
        val TECH = setOf("tech", "software", "ai", "crypto", "gaming")

        /**
         * Shows that work as full-frame graphics, by how much they gain from the whole frame. A
         * voucher, stats and a countdown are the piece's big moments; a progress bar sits over the
         * speaker at the top.
         */
        val TAKEOVER_SHOWS = mapOf(
            "voucher" to 1.1f, "network" to 1f, "stats" to 1f, "meter" to 0.95f, "countdown" to 0.95f, "chart" to 0.9f, "objects" to 0.8f, "list" to 0.75f,
            "terminal" to 0.6f, "counter" to 0.45f, "headline" to 0.25f,
        )

        /** Shows drawn from the numbers' meaning ([Quantities]). */
        val RICH_SHOWS = setOf("voucher", "stats", "countdown", "progress")

        /** Readings the numbers may upgrade to a rich show. */
        val UPGRADABLE = setOf("headline", "counter", "none")
        val TEXT_RECIPES = setOf("slam", "mask-rise", "type-on", "blur-in", "flip", "spread", "stack", "glitch")
        val ALTERNATE_TEXT = mapOf(
            "slam" to "flip", "mask-rise" to "flip", "type-on" to "mask-rise", "blur-in" to "mask-rise",
            "flip" to "mask-rise", "spread" to "slam", "stack" to "slam", "glitch" to "slam",
        )

        /** Beats that keep the screen alive on their own while up: the call to action, names landing one by one. */
        val SELF_PACED = setOf("comment", "logos", "list", "objects")
        val PICTURED = setOf("object", "concept", "place", "product")

        /** Light verbs and fillers: said with stress, but nothing to pop on screen. */
        val LIGHT_WORDS = setOf(
            "کردم", "کردن", "کردیم", "کرد", "کنید", "بکنید", "بکنی", "بکنیم", "کنی", "کنم", "بکن", "شد", "شده", "شدن", "گذاشتم", "گذاشت", "دادن", "داد",
            "گرفتن", "گرفت", "نبود", "باشید", "باشین", "نیست", "بریم", "برو", "اومده", "رفتیم", "یعنی", "الان", "دیگه", "همون", "همونجا", "اینجوری", "اونجا",
            "really", "just", "actually", "going", "gonna", "doing",
        )
        val DM_WORDS = listOf("دایرکت", "دایرک", "dm", "direct", "inbox")
        val CHECK_VERBS = setOf("کن", "بکن", "کنید", "بکنید", "کنین", "بکنین", "check", "it")
        val VOUCHER_WORDS = setOf("ووچر", "وچر", "voucher", "هدیه", "جایزه", "بونوس", "bonus", "gift")
        val LESS = setOf("کمتر", "زیر", "under", "less")
        val DEADLINES = mapOf(
            "کمپین" to "کمپین", "جشنواره" to "جشنواره", "تخفیف" to "تخفیف", "حراج" to "حراج", "ثبتنام" to "ثبت‌نام", "مهلت" to "مهلت",
            "مسابقه" to "مسابقه", "چالش" to "چالش", "پیشنهاد" to "پیشنهاد", "campaign" to "campaign", "sale" to "sale", "offer" to "offer", "deal" to "deal",
        )
        const val MAX_STATS = 3
        const val CTA_REPRISE_S = 7f

        // Rhythm.
        const val RHYTHM_GAP = 2.5f
        const val MIN_RHYTHM_GAP = 1.8f
        const val MAX_RHYTHM_GAP = 4.5f
        const val MIN_LEAD = 0.8f
        const val MIN_TAIL = 0.4f
        const val MIN_SHOT = 1.0f
        const val FILL_HOLD = 1.6f
        const val STAMP_SPACING = 8f
        const val MAX_FILLS = 400
        const val SHORT_WORD = 3
        const val MIN_POP = 3
        const val TYPICAL_TEXT = 12
        const val LINGER = 0.55f
        const val MAX_HOLD = 7f

        fun round1(v: Float) = kotlin.math.round(v * 10f) / 10f
        const val MIN_TAKEOVER_S = 1.4f
        const val CTA_TAIL = 0.45f
        const val EARLIEST_TAKEOVER_MS = 3500L
        const val MIN_NODES = 7
        const val PICTURE_CHANCE = 0.7f
        const val MAX_NODES = 9

        val GROWTH = setOf("رشد", "سود", "افزایش", "بیشتر", "up", "growth", "more", "increase")
        val DECLINE = setOf("کاهش", "ریزش", "سقوط", "کمتر", "ضرر", "down", "drop", "less", "decrease", "loss")
        val CTA_LABELS = mapOf(
            "comment" to "کامنت کن", "follow" to "فالو کن", "save" to "ذخیره کن", "share" to "بفرست برای دوستت", "link" to "لینک در بیو", "dm" to "دایرکت بده",
        )
        val NETWORK_DEFAULTS = mapOf(
            "software" to listOf("src", "api", "ui", "db", "auth", "tests", "docs", "config", "utils"),
            "tech" to listOf("api", "ui", "db", "cloud", "auth", "data", "docs"),
            "ai" to listOf("model", "data", "prompt", "tools", "memory", "agent"),
        )

        fun moodEnergy(mood: String) = when (mood) {
            "energetic", "urgent" -> 0.85f
            "playful", "confident" -> 0.65f
            "inspiring", "serious" -> 0.5f
            else -> 0.35f
        }

        fun String.isAscii() = isNotBlank() && all { it.code < 128 }

        /** Looks like something typed in a shell: lower-case ASCII starting with a tool or verb. */
        fun String.isCommand() = isAscii() && ' ' in this && split(' ').first().let { it == it.lowercase() && it.length in 2..12 }

        /** Names worth a logo tile: Latin-script names ("Claude Code"), not Persian common words. */
        fun String.isLogoName() = any { it in 'A'..'Z' || it in 'a'..'z' }

        fun slug(topic: String) = topic.lowercase().split(' ', '-').filter { it.isNotBlank() && it.all(Char::isLetterOrDigit) }.take(2).joinToString("-").ifEmpty { "it" }
    }
}
