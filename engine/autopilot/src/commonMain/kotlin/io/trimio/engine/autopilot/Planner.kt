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
 * - over the speaker no stretch goes without a visual event ([Rhythm]);
 * - the call to action is the clearest thing on the last screen.
 */
class Planner(private val taste: Taste = Taste(), private val brief: String = "") {

    data class Plan(val score: Score, val music: MusicMood?, val choices: Choices, val notes: List<String>)

    /**
     * A cold open ([ColdOpen]): the hook line's words play first, for [length] seconds, before the
     * piece starts from its beginning. [line] is the hook line, [words] the words heard in it.
     */
    data class Opening(val length: Float, val line: Int, val words: IntRange)

    /**
     * [words] are on the output clock (after silence cuts, after the [opening] when there is one);
     * [footage] is false for audio-only pieces, which are all graphics.
     */
    fun plan(words: List<Word>, lines: List<Lines.Line>, u: Understanding, seed: Long, footage: Boolean = true, opening: Opening? = null): Plan =
        Session(words, lines, u, seed, footage, opening?.takeIf { it.line in lines.indices }).run()

    private inner class Session(
        val words: List<Word>,
        val lines: List<Lines.Line>,
        val u: Understanding,
        val seed: Long,
        val footage: Boolean,
        val opening: Opening?,
    ) {
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
        val dialogueRng = root.fork(11)

        /** Reported speech shown as a chat thread, by the line it starts on. */
        val threads = mutableMapOf<Int, Dialogue.Thread>()
        val dialogue = Dialogue(words, lines, texts.count { w -> w.any { it in '\u0600'..'\u06FF' } } * 2 > texts.size)

        /** Each line's numbers with their units and cues. */
        val senses = Quantities.senses(texts, lines)

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
        val shownLogos = mutableSetOf<String>()

        /** Rich graphics already shown (a voucher said twice is shown once). */
        val shownRich = mutableSetOf<String>()

        /** Picture queries already on screen. */
        val usedVisuals = mutableSetOf<String>()
        var reads: List<LineRead> = emptyList()
        val rtl = texts.count { w -> w.any { it in '\u0600'..'\u06FF' } } * 2 > texts.size

        /** Graphics drawn from what the numbers mean. */
        val figures = Figures(texts, norm, rtl) { quote, line -> locate(quote, line) }

        /** The first seconds: the payoff first, or the cold open's figure. */
        val hooks by lazy { Hooks(lines, texts, u, senses, figures, rtl, hookRng, recipeRng, taste, mark, usedRecipes, notes) }

        /** The ask: the comment field with the keyword as said, the DM step, the field again at the end. */
        val ask = CallToAction(words, texts, norm, lines, u, brief, rtl) { quote, line -> locate(quote, line) }

        fun run(): Plan {
            if (words.isEmpty() || lines.isEmpty()) {
                return Plan(Score(look = look, captions = CaptionScore(show = false)), music, Choices(seed, look, music?.id, emptyList(), emptyList()), notes)
            }
            // The cold open shows the hook line's figure: the line itself, said later, shows something else.
            opening?.let { o -> hooks.openingKey(o)?.let { shownRich += it } }
            reads = threaded(settle(lines.indices.map { k -> refine(k, u.lines.getOrNull(k) ?: LineRead(show = "none")) }))
            val takeovers = chooseTakeovers(reads)
            val scenes = cut(takeovers)
            polish(scenes)
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

        /**
         * Reported speech («یکی اومده تو دایرکت می‌گه …», "someone asked me …") and the speaker's
         * answer become one chat thread on the line it starts; the lines it runs over are its scene.
         * Never over the call to action or a line with a figure of its own.
         */
        private fun threaded(reads: List<LineRead>): List<LineRead> {
            val found = dialogue.find(
                allowed = { j -> reads[j].show in THREAD_LINES },
                hinted = { j -> u.lines.getOrNull(j)?.let { it.role in DIALOGUE_ROLES || it.show == "message" } == true },
            )
            found.forEach { threads[it.line] = it }
            if (found.isNotEmpty()) notes += "dialogue: " + found.joinToString { "lines ${it.line}–${it.lastLine} (${it.bubbles.size} bubbles)" }
            return reads.mapIndexed { k, r -> if (k in threads) r.copy(show = "message") else r }
        }

        /** The edit cut into scenes: one per line (lines a running beat holds join its scene), after the cold open. */
        private fun cut(takeovers: Set<Int>): MutableList<SceneScore> {
            val scenes = mutableListOf<SceneScore>()
            opening?.let { scenes += hooks.openingScene(it, reads) }
            var previousTakeover = false
            // A beat that runs on past its line (a list of names, a held data graphic) keeps its scene until it is done.
            var coveredUntil = -1
            for ((k, line) in lines.withIndex()) {
                val read = reads[k]
                val covered = line.first <= coveredUntil && scenes.isNotEmpty()
                // A line merged into a running beat can bring two full-frame scenes together: never.
                val takeover = k in takeovers && !covered && !previousTakeover
                val beats = mutableListOf<BeatScore>()
                // With a cold open the hook has been seen: the body starts clean.
                if (k == 0 && opening == null) payoffLine?.takeIf { it > 0 && u.brief.payoffFirst }?.let { hooks.hook(it, reads) }?.let { beats += it }
                // A line inside a running beat adds nothing of its own (only the call to action).
                beats += beatsFor(k, line, read, takeover, hasHook = beats.isNotEmpty()).filter { !covered || it.recipe == "comment" || it in ask.beats }
                coveredUntil = maxOf(coveredUntil, beats.maxOfOrNull { it.until ?: it.at ?: -1 } ?: -1, heldUntil(k, beats, takeovers))
                if (covered) {
                    scenes[scenes.lastIndex] = scenes.last().let { it.copy(beats = it.beats + beats) }
                    continue
                }
                val sc = scene(k, line, read, takeover, previousTakeover, beats)
                // The hook line heard again after the cold open: a punch-in, not the same figure twice.
                scenes += if (opening?.line == k && !takeover) sc.copy(camera = "push-in") else sc
                previousTakeover = takeover
            }
            return scenes
        }

        /** Over the speaker, no stretch without a visual event; no text treatment three times running. */
        private fun polish(scenes: MutableList<SceneScore>) {
            // A graphic a cut would leave on screen for less than a glance is dropped, not flashed.
            val rhythm = Rhythm(words, lines, reads, u, taste, fillRng, energy, usedRecipes, usedVisuals, ask.keyword, ::locate)
            // A data graphic that cannot be read in full (a full-frame scene or the ask comes right after) is dropped.
            val least = { b: BeatScore -> if (b.recipe in PLAIN_DATA && b.time == null) DATA_HOLD else 0f }
            rhythm.prune(scenes, least)
            if (footage) rhythm.keepAlive(scenes)
            rhythm.vary(scenes)
            rhythm.prune(scenes, least)
            notes += rhythm.notes
        }

        /**
         * The last word a held beat (a data graphic, the hook, the DM step) still covers: its scene runs on
         * instead of a cut ending it early. It stops before a line with a graphic of its own (a
         * figure, names, a full-frame scene, the call to action), which keeps its own scene.
         */
        private fun heldUntil(k: Int, beats: List<BeatScore>, takeovers: Set<Int>): Int {
            val end = beats.filter { it.recipe in DATA_RECIPES || it.time != null || (it.hold != null && it.recipe != "comment") }
                .mapNotNull { b -> b.hold?.let { h -> (b.time ?: startOf(b.at ?: lines[k].first)) + h } }.maxOrNull() ?: return -1
            var last = -1
            for (j in k + 1..lines.lastIndex) {
                // A line with a graphic of its own keeps its own scene.
                val own = reads[j].show !in setOf("none", "headline") || j in takeovers
                // The compiler starts a scene a little before its first word: cover until the beat is done.
                if (own || startOf(lines[j].first) - SCENE_LEAD >= end) break
                last = lines[j].last
            }
            return last
        }

        /**
         * Each figure once, in its richest form: a number a voucher, stats board, countdown or
         * progress bar shows (said earlier or later) gets no counter, meter or chart of its own.
         */
        private fun settle(reads: List<LineRead>): List<LineRead> {
            val shown = reads.indices.filter { reads[it].show in RICH_SHOWS }.flatMap { k -> figures.figureOf(senses[k], reads[k].show) } +
                listOfNotNull(opening?.let { hooks.openingFigure(it) })
            val values = shown.map { it.value }.toSet()
            val at = shown.map { it.at }.toSet()
            return reads.mapIndexed { k, r ->
                val q = figures.counted(senses[k]).takeIf { r.show in NUMBER_SHOWS }
                if (q != null && (q.at in at || q.value in values)) r.copy(show = plainShow(lines[k], r)) else r
            }
        }

        /** Data graphics stay long enough to be read: 3.2 s, the payoff voucher 4 s (their scene runs on, see [heldUntil]). */
        private fun lasting(k: Int, b: BeatScore): BeatScore {
            val at = b.at
            if (b.recipe !in DATA_RECIPES || b.time != null || at == null) return b
            val spoken = (b.until?.let { endOf(it) + LINGER } ?: startOf(at)) - startOf(at)
            val least = if (b.recipe == "voucher" && k == payoffLine) PAYOFF_HOLD else DATA_HOLD
            return b.copy(hold = maxOf(spoken, least, b.hold ?: 0f))
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
            return when {
                // A list of names said in the line is always shown as logos, whatever was asked.
                brands.size >= 2 -> "logos"
                else -> numbersSay(k, line, read, bigNumber) ?: when {
                    !grounded(line, read, number != null, brands.isNotEmpty()) -> plainShow(line, read)
                    read.show in setOf("counter", "meter") && number == null -> "headline"
                    read.show == "logos" && brands.isEmpty() && read.items.isEmpty() -> "headline"
                    read.show == "comment" && u.cta == null -> "headline"
                    plain && bigNumber -> if (showRng.chance(0.75f)) "counter" else read.show
                    else -> read.show
                }
            }
        }

        /** What the line's numbers decide about its show, or null when they leave it to the reading. */
        private fun numbersSay(k: Int, line: Lines.Line, read: LineRead, bigNumber: Boolean): String? {
            val sense = senses[k]
            val rich = sense.richShow()
            return when {
                // Views and comments are a stats board or a counter, never a made-up trend.
                read.show in setOf("chart", "meter") && sense.social.isNotEmpty() -> rich ?: "counter"
                // A voucher, stats, countdown or progress needs its numbers in the line.
                read.show in RICH_SHOWS && !sense.supports(read.show) -> rich ?: if (bigNumber) "counter" else plainShow(line, read)
                // When the numbers clearly say more than a headline or a bare counter, show what they say.
                rich != null && read.show in UPGRADABLE -> rich
                else -> null
            }
        }

        private fun plainShow(line: Lines.Line, read: LineRead) = if (read.title.isNotBlank() && locate(read.title, line) != null) "headline" else "none"

        /**
         * A rich graphic is shown once: the same voucher or quota said again becomes a stamp of
         * what changed («پر شد») or a plain headline, so the piece moves forward.
         */
        private fun once(k: Int, read: LineRead): LineRead {
            val s = senses[k]
            val key = figures.keyOf(read.show, s) ?: return read
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
            line.range.filter { texts[it].trimEnd('،', ',', '.').let { w -> w.isNotEmpty() && w.first().isUpperCase() && !RulesUnderstander.isCommonWord(w) } }
                .map { texts[it].trimEnd('،', ',', '.') }.filter { n -> u.entities.none { e -> n in e.name } }

        // ------------------------------------------------------------ structure

        /** Which lines become full-frame graphics: the strongest candidates, never adjacent, within budget. */
        private fun chooseTakeovers(reads: List<LineRead>): Set<Int> {
            if (!footage) return lines.indices.toSet()
            val total = duration(0, lines.lastIndex)
            // The share of full-frame graphics varies by seed (a calmer or a busier cut of the same piece).
            val budget = total * taste.takeover * takeoverRng.range(0.6f, 1.35f)
            // Not in the first seconds: the viewer first meets the speaker (and the hook).
            // A thread takes the full frame only when it is long (three bubbles or more).
            val candidates = reads.indices.filter { k -> k > 0 && k < lines.lastIndex && reads[k].show in TAKEOVER_SHOWS && words[lines[k].first].range.startMs >= EARLIEST_TAKEOVER_MS &&
                (threads[k]?.bubbles?.size ?: STAGED_THREAD) >= STAGED_THREAD &&
                // The ask keeps the speaker on screen while it plays out (the DM step after the keyword).
                reads[k - 1].show != "comment" }
                // The piece's big figures first; the rest by weight, varied by seed.
                .sortedByDescending { k ->
                    TAKEOVER_SHOWS.getValue(reads[k].show) * takeoverRng.range(0.5f, 1.5f) + (if (reads[k].show in BIG_MOMENTS) BIG_FIRST else 0f) + (if (k == payoffLine) 1f else 0f)
                }
            val chosen = mutableSetOf<Int>()
            var used = 0f
            for (k in candidates) {
                // A figure holds its scene for as long as it needs (see [lasting]), however briefly it is said.
                val d = when {
                    k in threads -> duration(k, threads.getValue(k).lastLine)
                    reads[k].show in RICH_SHOWS -> maxOf(duration(k, k), DATA_HOLD)
                    else -> duration(k, k)
                }
                // Two full frames with only a breath of speaker between them read as one: keep them apart
                // (a figure's scene runs on over the lines it holds, see [heldUntil]).
                val neighbour = chosen.any { c ->
                    val (a, b) = if (c < k) c to k else k to c
                    reach(a) + 1 >= b || (b - a == 2 && duration(a + 1, a + 1) < SPEAKER_BETWEEN_S)
                }
                if (neighbour || used + d > budget || d < MIN_TAKEOVER_S) continue
                chosen += k
                used += d
            }
            return chosen
        }

        /** The last line a full-frame figure on line [k] would hold its scene over. */
        private fun reach(k: Int): Int {
            threads[k]?.let { return it.lastLine }
            if (reads[k].show !in RICH_SHOWS) return k
            val end = startOf(lines[k].first) + maxOf(duration(k, k), DATA_HOLD)
            return (k + 1..lines.lastIndex).takeWhile { j -> startOf(lines[j].first) - SCENE_LEAD < end }.lastOrNull() ?: k
        }

        private fun duration(a: Int, b: Int) = (words[lines[b].last].range.endMs - words[lines[a].first].range.startMs) / 1000f

        // ------------------------------------------------------------ beats

        private fun beatsFor(k: Int, line: Lines.Line, read: LineRead, takeover: Boolean, hasHook: Boolean): List<BeatScore> {
            val e = (read.energy * 0.6f + energy * 0.4f).coerceIn(0.2f, 1f)
            val primary = primary(line, read, e, takeover)
            val out = mutableListOf<BeatScore>()
            // The hook owns the first seconds: line 0's own graphic waits unless it is the CTA.
            val waits = hasHook && k == 0 && read.show !in setOf("comment", "lower-third", "message")
            if (primary != null && !waits) out += primary
            if (!(hasHook && k == 0)) extra(line, read, takeover, primary != null, out.size)?.let { out += it }
            ask.follow(k, line, read, out)
            usedRecipes += out.map { it.recipe }
            return out.map { lasting(k, it) }
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
            "comment" -> if (ask.placed) null else ask.comment(line, read)
            "lower-third" -> lowerThird(line, read)
            "stamp" -> stamp(line, read, e)
            "voucher" -> voucher(line, read, e, takeover)
            "stats" -> stats(line, read, e, takeover)
            "countdown" -> countdown(line, read, e, takeover)
            "progress" -> progress(line, read, e, takeover)
            "message" -> message(line, read, e, takeover)
            "headline" -> headline(line, read, e, takeover)
            else -> null
        }

        /** A chat thread over the lines its story runs; the answer bubble and the header vary by seed. */
        private fun message(line: Lines.Line, read: LineRead, e: Float, takeover: Boolean): BeatScore? {
            val t = threads[lines.indexOf(line)] ?: return headline(line, read, e, takeover)
            val reply = t.reply != null && dialogueRng.chance(REPLY_CHANCE)
            return dialogue.beat(t, reply, dialogueRng.pick(dialogue.labels(t)), e, if (takeover) "center" else "top")
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
            val titled = takeover && hasPrimary && read.title.isNotBlank() && read.show !in setOf("headline", "stamp", "message") && read.show !in RICH_SHOWS
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
            val q = figures.counted(sense(line)) ?: return headline(line, read, e, takeover)
            val at = q.at
            val percent = q.kind == Quantities.Kind.Percent
            val growth = (at until minOf(line.last + 1, at + q.count + 3)).any { norm[it] in GROWTH }
            return BeatScore(
                recipe = "counter", at = at, until = minOf(line.last, at + q.count), value = q.value.toFloat(), decimals = q.decimals,
                suffix = if (percent) "٪" else "", prefix = if ((percent && growth) || q.opened) "+" else "",
                label = figures.unitLabel(q) ?: read.title.ifBlank { null }, energy = maxOf(e, 0.7f), place = if (takeover) "center" else "top",
            )
        }

        private fun place(takeover: Boolean) = if (takeover) "center" else "top"
        private fun voucher(line: Lines.Line, read: LineRead, e: Float, takeover: Boolean) = figures.voucher(line, sense(line), read, e, place(takeover)) ?: counter(line, read, e, takeover)
        private fun stats(line: Lines.Line, read: LineRead, e: Float, takeover: Boolean) = figures.stats(line, sense(line), read, e, place(takeover)) ?: counter(line, read, e, takeover)
        private fun countdown(line: Lines.Line, read: LineRead, e: Float, takeover: Boolean) = figures.countdown(line, sense(line), e, place(takeover)) ?: counter(line, read, e, takeover)
        private fun progress(line: Lines.Line, read: LineRead, e: Float, takeover: Boolean) = figures.progress(line, sense(line), read, e, place(takeover)) ?: counter(line, read, e, takeover)

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

        private fun lowerThird(line: Lines.Line, read: LineRead): BeatScore? {
            val person = u.entities.firstOrNull { it.kind == "person" && it.at in line.range }?.name
            val name = person ?: read.title.ifBlank { return null }
            return BeatScore(recipe = "lower-third", at = line.first, text = name, label = read.title.takeIf { person != null && it.isNotBlank() }, place = "lower")
        }

        private fun stamp(line: Lines.Line, read: LineRead, e: Float): BeatScore? =
            read.title.ifBlank { null }?.let { BeatScore(recipe = "stamp", at = locate(it, line)?.first ?: focus(line), text = it, energy = maxOf(e, 0.75f), place = "top") }

        /** A sticker of the most concrete thing the line names, if the vocabulary has one. */
        private fun pictureFor(line: Lines.Line, read: LineRead): BeatScore? {
            val entity = u.entities.firstOrNull { it.at in line.range && Rhythm.isPicture(it.visual) && it.kind in Rhythm.PICTURED && it.visual !in usedVisuals }
            if (entity != null) return BeatScore(recipe = "object", at = entity.at, visual = entity.visual, hold = 1.8f, place = "top").also { usedVisuals += entity.visual }
            // The line's own picture, when it names something concrete (a short noun phrase).
            val query = read.visual.substringBefore(',').trim().takeIf { q -> q.split(' ').size in 1..3 && Rhythm.isPicture(q) && q !in usedVisuals } ?: return null
            usedVisuals += query
            return BeatScore(recipe = "object", at = focus(line), visual = query, hold = 1.8f, place = "top", label = read.title.ifBlank { null })
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
            "terminal" to 0.6f, "message" to 0.6f, "counter" to 0.45f, "headline" to 0.25f,
        )

        /** Lines a chat thread may run over: none with a figure, names or the ask of their own. */
        val THREAD_LINES = setOf("none", "headline", "stamp", "object", "objects", "lower-third", "list", "message")
        val DIALOGUE_ROLES = setOf("dialogue", "quote")
        const val REPLY_CHANCE = 0.75f
        const val STAGED_THREAD = 3

        /** Shows drawn from the numbers' meaning ([Quantities]). */
        val RICH_SHOWS = setOf("voucher", "stats", "countdown", "progress")

        /** Shows the full frame goes to first: the piece's big figures. */
        val BIG_MOMENTS = setOf("voucher", "stats", "countdown")
        const val BIG_FIRST = 10f
        const val SPEAKER_BETWEEN_S = 2f

        /** Graphics that show data: they stay long enough to be read. */
        val DATA_RECIPES = setOf("counter", "voucher", "stats", "countdown", "progress", "chart", "meter", "network", "terminal", "list", "objects", "logos")
        val NUMBER_SHOWS = setOf("counter", "meter", "chart")

        /** Data graphics with nothing else to say: shown in full or not at all. */
        val PLAIN_DATA = setOf("counter", "meter", "chart", "network", "terminal")
        const val DATA_HOLD = 3.2f

        const val PAYOFF_HOLD = 4f
        const val LINGER = 0.55f

        /** How early the compiler starts a scene before its first word. */
        const val SCENE_LEAD = 0.12f

        /** Readings the numbers may upgrade to a rich show. */
        val UPGRADABLE = setOf("headline", "counter", "none")

        const val MIN_TAKEOVER_S = 1.4f
        const val EARLIEST_TAKEOVER_MS = 3500L
        const val MIN_NODES = 7
        const val PICTURE_CHANCE = 0.7f
        const val MAX_NODES = 9

        val GROWTH = setOf("رشد", "سود", "افزایش", "بیشتر", "up", "growth", "more", "increase")
        val DECLINE = setOf("کاهش", "ریزش", "سقوط", "کمتر", "ضرر", "down", "drop", "less", "decrease", "loss")
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
