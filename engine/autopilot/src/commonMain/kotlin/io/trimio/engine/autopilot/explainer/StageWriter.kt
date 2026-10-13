package io.trimio.engine.autopilot.explainer

import io.trimio.engine.autopilot.Concepts
import io.trimio.engine.autopilot.Enumerations
import io.trimio.engine.autopilot.Phrases
import io.trimio.engine.autopilot.Quantities
import io.trimio.engine.autopilot.Rng
import io.trimio.engine.motion.explainer.Beat
import io.trimio.engine.motion.explainer.BeatContent
import io.trimio.engine.motion.explainer.Item
import io.trimio.engine.motion.explainer.Primitive
import io.trimio.engine.motion.explainer.StageOp

/** What the whole film remembers across chapters: the pictures it used, the subjects it named. */
internal class FilmMemory {
    /** Main pictures in the order they were put on stage. */
    val mains = mutableListOf<Primitive>()

    /** The named subjects of the chapters so far («Haiku», «Sonnet»…), for a ranking that compares them. */
    val subjects = mutableListOf<String>()
}

/**
 * One chapter's stage, written clause by clause by the rules. The stage persists and builds: a
 * new idea brings a new picture (morphing or swapping the main one in its place, never a hard
 * cut), and a clause that goes on about the same thing works on what is there — ticks the tiles,
 * drains the meter, lights the best path, badges the tallest bar, adds a tag to the card.
 * Every beat lands on the word that says it.
 */
internal class StageWriter(
    private val n: Narration,
    private val plans: List<Chapters.Plan>,
    private val index: Int,
    private val rng: Rng,
    private val memory: FilmMemory,
    /** The beginning and end of the chapter on the narration's clock. */
    private val start: Double,
    private val end: Double,
) {
    private class El(val id: String, var primitive: Primitive, val main: Boolean, var items: Int, val idea: Idea?) {
        var checked = 0
        var value: Double? = null
        var uses = 0
        var fast = false
    }

    private class Move(val at: Double, val op: StageOp, val element: String, val primitive: Primitive?, val content: BeatContent, val metaphor: String, val line: String)

    private val plan = plans[index]
    private val moves = mutableListOf<Move>()
    private val live = mutableListOf<El>()
    private var made = 0
    private val lists = Enumerations.lists(n.texts, n.clauses.map { io.trimio.engine.autopilot.Lines.Line(it.first, it.last) })
    private val ask = io.trimio.engine.autopilot.Asks.read(n.texts, n.clauses.map { io.trimio.engine.autopilot.Lines.Line(it.first, it.last) })?.first

    fun write(): List<Beat> {
        plan.subject?.let { s -> if (memory.subjects.none { Phrases.key(it) == Phrases.key(s) }) memory.subjects += s }
        for (k in plan.clauses) clause(n.clauses[k])
        return beats()
    }

    // ---- one clause --------------------------------------------------------------------------

    private fun clause(c: Clause) {
        val found = Metaphors.find(c.words.map { n.keys[it] }, c.first, c.read?.gist.orEmpty())
        val strong = found.firstOrNull { it.strength >= STRONG }
        val main = live.firstOrNull { it.main }
        when {
            c.index == ask -> cta(c)
            index == 0 && agenda(c) -> Unit
            main != null && goOn(main, c, found) -> Unit
            traits(c) -> Unit
            strong != null -> idea(strong, c, found)
            figure(c) -> Unit
            listed(c) -> Unit
            picture(c) -> Unit
            else -> plain(c, main)
        }
    }

    /** The opening chapter shows what the film will walk through: the names of the chapters to come. */
    private fun agenda(c: Clause): Boolean {
        val coming = plans.drop(1).mapNotNull { it.subject }.distinct()
        if (coming.size < 2 || live.any { it.primitive == Primitive.Chips }) return false
        val names = Chapters.subjectsOf(n, c)
        val mentions = c.words.any { n.keys[it] == n.category || Metaphors.find(listOf(n.keys[it])).any { f -> f.idea == Idea.Compare } }
        if (names.size < 2 && !mentions && c.index != plan.clauses.last) return false
        add(Primitive.Chips, c, c.first, BeatContent(items = coming.take(MAX_ITEMS).map { Item(it) }), "the subjects the film will walk through, previewed as chips", null, main = true)
        return true
    }

    /** The ask at the end («فالو کنید», "follow for more"): a badge with the action said. */
    private fun cta(c: Clause) {
        val at = c.words.firstOrNull { i -> ASKS.any { Metaphors.bare(n.texts[i]).startsWith(it) } } ?: c.first
        add(Primitive.Badge, c, at, BeatContent(text = Said.from(n, c, at, 2), visual = "bell"), "the call to action as a badge to tap", null, main = true)
    }

    // ---- going on with what is on stage --------------------------------------------------------

    /** Works on the main picture when the clause goes on about the same thing; false when it says something new. */
    private fun goOn(main: El, c: Clause, found: List<Found>): Boolean {
        if (main.uses >= MAX_USES) return false
        val ideas = found.filter { it.strength >= WEAK }.map { it.idea }.toSet()
        val fresh = found.firstOrNull { it.strength >= STRONG && it.idea != main.idea && it.idea !in FOLLOWS.getOrDefault(main.primitive, emptySet()) }
        if (fresh != null && fresh.strength >= STRONG + 0.2f) return false
        val at = found.firstOrNull { it.idea in FOLLOWS.getOrDefault(main.primitive, emptySet()) }?.at ?: mid(c)
        return when (main.primitive) {
            Primitive.TaskTiles, Primitive.Checklist, Primitive.Steps, Primitive.Timeline -> tick(main, c, at, (main.items - main.checked).coerceAtLeast(0))
            Primitive.Meter, Primitive.Ring -> (ideas.any { it in DRAINS } || fresh == null) && drain(main, c, at, ideas)
            Primitive.Branches -> (main.checked == 0) && pick(main, c, at, listOf(0), Said.around(n, c, best(c) ?: at, 2), "the best path lights up")
            Primitive.Blocks -> blocks(main, c, at)
            Primitive.Bars, Primitive.Ladder -> (Idea.Rank in ideas || main.checked == 0) && pick(main, c, at, listOf(main.items - 1), superlative(c), "the tallest gets the badge")
            Primitive.Profile -> fresh == null && tag(main, c)
            Primitive.Tangle -> (Idea.Success in ideas) && solved(c, found.first { it.idea == Idea.Success }.at)
            Primitive.IconTiles, Primitive.Chips -> fresh == null && highlight(main, c, at)
            else -> false
        }
    }

    private fun tick(e: El, c: Clause, at: Int, left: Int): Boolean {
        if (left <= 0) return false
        val picks = (e.checked until e.items).toList()
        e.checked = e.items
        e.uses++
        move(at, StageOp.Check, e, BeatContent(picks = picks), "each item ticked as it gets done", c)
        return true
    }

    private fun drain(e: El, c: Clause, at: Int, ideas: Set<Idea>): Boolean {
        val now = e.value ?: 1.0
        val fast = e.fast || Idea.Fast in ideas || c.words.any { Metaphors.bare(n.texts[it]) in FAST_WORDS }
        val next = if (Idea.Grow in ideas) minOf(1.0, now + GROW_STEP) else maxOf(0.05, now - if (fast) FAST_STEP else SLOW_STEP)
        e.value = next
        e.uses++
        move(at, StageOp.Fill, e, BeatContent(value = round2(next), unit = "%"), if (fast) "the meter drops fast" else "the meter drains, slowly", c)
        return true
    }

    private fun pick(e: El, c: Clause, at: Int, picks: List<Int>, caption: String, why: String): Boolean {
        e.checked++
        e.uses++
        val op = if (e.primitive == Primitive.Branches) StageOp.Check else StageOp.Highlight
        move(at, op, e, BeatContent(caption = caption, picks = picks), why, c)
        return true
    }

    /** Blocks: the first few take one colour (the hard part), then the rest the other. */
    private fun blocks(e: El, c: Clause, at: Int): Boolean {
        if (e.checked >= 2) return false
        val first = minOf(BLOCK_FEW, e.items)
        val picks = if (e.checked == 0) (0 until first).toList() else (first until e.items).toList()
        val caption = Said.around(n, c, best(c) ?: at, 3)
        e.checked++
        e.uses++
        move(at, if (e.checked == 1) StageOp.Check else StageOp.Highlight, e, BeatContent(caption = caption, picks = picks), "one share of the blocks takes its own colour", c)
        return true
    }

    /** A card gets a tag: the words that describe the persona («تازه‌وارد شرکت»). */
    private fun tag(e: El, c: Clause): Boolean {
        val noun = c.words.firstOrNull { Phrases.nounish(n.texts[it]) } ?: return false
        val tag = Said.around(n, c, noun, 3).takeIf { it.isNotBlank() } ?: return false
        e.items++
        e.uses++
        move(noun, StageOp.Build, e, BeatContent(items = listOf(Item(tag))), "a tag on the card says what kind of persona", c)
        return true
    }

    /** The tangle is solved: a check beside it. */
    private fun solved(c: Clause, at: Int): Boolean {
        add(Primitive.Mark, c, at, BeatContent(text = Said.from(n, c, at, 2), visual = "check mark"), "the tangle gets its check: it handles this well", Idea.Success, main = false)
        return true
    }

    private fun highlight(e: El, c: Clause, at: Int): Boolean {
        e.uses++
        val caption = Said.title(n, c, 3)
        move(at, StageOp.Highlight, e, BeatContent(caption = caption, picks = listOf(minOf(e.uses - 1, maxOf(0, e.items - 1)))), "the point lands on what is already there", c)
        return true
    }

    // ---- new pictures --------------------------------------------------------------------------

    /** Qualities said one after another: tiles that build in as each is said. */
    private fun traits(c: Clause): Boolean {
        val said = c.words.mapNotNull { i -> Metaphors.trait(n.texts[i])?.let { i to it } }.distinctBy { it.second }
        if (said.size < 2) return false
        val (first, visual) = said.first()
        val primitive = choose(Idea.Traits)
        val e = add(primitive, c, first, BeatContent(items = listOf(Item(Said.around(n, c, first, 2), visual = visual))), "each quality is a tile that builds in as it is said", Idea.Traits, main = true)
        for ((i, v) in said.drop(1).take(MAX_ITEMS - 1)) {
            e.items++
            move(i, StageOp.Build, e, BeatContent(items = listOf(Item(Said.around(n, c, i, 2), visual = v))), "another quality joins the row", c)
        }
        return true
    }

    @Suppress("CyclomaticComplexMethod")
    private fun idea(f: Found, c: Clause, found: List<Found>) {
        val at = f.at
        val phrase = Said.around(n, c, at)
        when (f.idea) {
            Idea.Persona -> persona(c, at)
            Idea.Routine -> add(choose(Idea.Routine), c, at, BeatContent(text = phrase, items = List(TILES) { Item("", visual = "task") }), "light repetitive work: a row of task tiles", f.idea, main = true)
            Idea.Drain, Idea.Fast -> meter(c, at, found)
            Idea.Grow, Idea.Accumulate -> add(choose(f.idea), c, at, BeatContent(text = phrase, items = rising(TILES - 1)), "it grows: bars rise", f.idea, main = true)
            Idea.Uses -> add(choose(Idea.Uses), c, at, BeatContent(text = phrase, items = Said.examples(n.understanding.domain, n.persian)), "most everyday work, drawn as the kinds of work", f.idea, main = true)
            Idea.Path -> path(c, at)
            Idea.Think -> add(choose(Idea.Think), c, at, BeatContent(text = phrase, items = List(3) { Item("") }), "thinking it through before acting", f.idea, main = true)
            Idea.Tangle -> add(Primitive.Tangle, c, at, BeatContent(text = phrase, visual = "tangle"), "deep, complex problems: a tangle", f.idea, main = true)
            Idea.Rank, Idea.Levels -> rank(c, at, f.idea)
            Idea.Portion -> portion(c, at)
            Idea.Slow -> add(Primitive.Clock, c, at, BeatContent(text = Said.from(n, c, at)), "it takes longer: a clock", f.idea, main = false)
            Idea.Compare, Idea.Balance, Idea.Change -> sides(c, at, f.idea)
            Idea.Steps, Idea.Cycle, Idea.Connect, Idea.Filter -> process(c, at, f.idea)
            Idea.Question -> add(choose(Idea.Question), c, at, BeatContent(text = phrase), "the question the chapter answers", f.idea, main = false)
            Idea.Warning -> add(choose(Idea.Warning), c, at, BeatContent(text = phrase, visual = "warning"), "a caution, marked", f.idea, main = false)
            Idea.Goal, Idea.Insight -> add(choose(f.idea), c, at, BeatContent(text = phrase, visual = if (f.idea == Idea.Goal) "direct hit" else "light bulb"), "the point as a symbol", f.idea, main = false)
            Idea.Success -> add(choose(Idea.Success), c, at, BeatContent(text = Said.from(n, c, at, 2), visual = "check mark"), "it works: a check", f.idea, main = false)
            Idea.Many -> add(Primitive.Dots, c, at, BeatContent(text = phrase, value = DOTS.toDouble(), picks = (0 until DOTS / 3).toList()), "many people: a field of dots", f.idea, main = true)
            Idea.Talk -> add(Primitive.Chat, c, at, BeatContent(text = phrase, visual = "speech balloon"), "asking and answering: a chat", f.idea, main = true)
            Idea.Place -> add(Primitive.Map, c, at, BeatContent(text = phrase, visual = "world map"), "where it happens: a map", f.idea, main = true)
            Idea.History -> add(Primitive.Timeline, c, at, BeatContent(text = phrase, items = years(c)), "when it happened: a timeline", f.idea, main = true)
            Idea.Quote -> add(Primitive.Quote, c, at, BeatContent(text = Said.title(n, c)), "someone's words, quoted", f.idea, main = false)
            Idea.Money -> if (!figure(c)) add(choose(Idea.Money), c, at, BeatContent(text = phrase, visual = "coin"), "money: coins stack up", f.idea, main = true)
            Idea.Traits, Idea.Figure -> if (!figure(c)) plain(c, live.firstOrNull { it.main })
        }
    }

    private fun persona(c: Clause, at: Int) {
        val like = Metaphors.bare(n.texts[at]) in LIKE
        val roleAt = if (like) (at + 1..c.last).firstOrNull { Phrases.nounish(n.texts[it]) } ?: at else at
        val role = Phrases.clean(n.texts[roleAt])
        val caption = if (like) n.texts.subList(at, roleAt).joinToString(" ") { Phrases.clean(it) } + "…" else ""
        val e = add(Primitive.Profile, c, at, BeatContent(text = role, caption = caption, visual = "person"), "a persona: a profile card", Idea.Persona, main = true)
        // The words that describe the persona become its tag, when they are said.
        val tagAt = (roleAt + 1..c.last).firstOrNull { Phrases.nounish(n.texts[it]) } ?: return
        val tag = Said.around(n, c, tagAt, 3).takeIf { it.isNotBlank() && it != role } ?: return
        e.items++
        move(tagAt, StageOp.Build, e, BeatContent(items = listOf(Item(tag))), "a tag says what kind of persona", c)
    }

    private fun meter(c: Clause, at: Int, found: List<Found>) {
        val fast = found.any { it.idea == Idea.Fast } || c.words.any { Metaphors.bare(n.texts[it]) in FAST_WORDS }
        val label = c.words.firstOrNull { Metaphors.bare(n.texts[it]).let { b -> METER_NOUNS.any { s -> b.startsWith(s) } } }?.let { Said.around(n, c, it, 3) } ?: Said.around(n, c, at, 3)
        val e = add(choose(Idea.Drain), c, at, BeatContent(text = label, value = 1.0, unit = "%"), "a limit: a meter that drains", Idea.Drain, main = true)
        e.value = 1.0
        e.fast = fast
        val verb = c.words.firstOrNull { it > at && Metaphors.bare(n.texts[it]) in DRAIN_VERBS } ?: return
        drain(e, c, verb, if (fast) setOf(Idea.Fast) else emptySet())
    }

    private fun path(c: Clause, at: Int) {
        val goal = Said.title(n, c, 2).ifBlank { plan.title }
        val e = add(choose(Idea.Path), c, at, BeatContent(text = goal, items = List(3) { Item("") }), "finding the best path: branches that converge on the task", Idea.Path, main = true)
        val best = best(c)?.takeIf { it > at } ?: return
        pick(e, c, best, listOf(0), Said.around(n, c, best, 2), "the best path lights up")
    }

    private fun rank(c: Clause, at: Int, idea: Idea) {
        val names = memory.subjects.takeLast(MAX_ITEMS)
        val items = if (names.size >= 2) names.mapIndexed { i, s -> Item(s, value = round2((i + 1.0) / names.size)) } else rising(BARS)
        val e = add(choose(idea), c, at, BeatContent(items = items), "the strongest: bars by tier, the last one tallest", idea, main = true)
        val sup = c.words.firstOrNull { Metaphors.bare(n.texts[it]).endsWith("ترین") || Metaphors.bare(n.texts[it]).endsWith("est") } ?: at
        pick(e, c, maxOf(sup, at), listOf(items.lastIndex), superlative(c), "the tallest gets the badge")
    }

    private fun portion(c: Clause, at: Int) {
        val e = add(choose(Idea.Portion), c, at, BeatContent(text = Said.around(n, c, best(c) ?: at, 3), items = List(BLOCKS) { Item("") }), "only part of the work: blocks in two colours", Idea.Portion, main = true)
        blocks(e, c, minOf(c.last, at + 1))
    }

    private fun sides(c: Clause, at: Int, idea: Idea) {
        val names = Chapters.subjectsOf(n, c)
        val items = if (names.size >= 2) names.take(2).map { Item(it) } else listOf(Item(plans.getOrNull(index - 1)?.title ?: Said.title(n, c, 2)), Item(plan.title))
        add(choose(idea), c, at, BeatContent(text = Said.around(n, c, at, 3), items = items), "two sides set against each other", idea, main = true)
    }

    private fun process(c: Clause, at: Int, idea: Idea) {
        val list = lists[c.index]?.items?.map { r -> Item(r.joinToString(" ") { Phrases.clean(n.texts[it]) }) }
        val items = list ?: listOf(Item(Said.around(n, c, at, 3)))
        add(choose(idea), c, at, BeatContent(items = items.take(MAX_ITEMS)), "one thing leads to the next", idea, main = true)
    }

    /** Numbers said: a stat, a ring for a percent, a meter when one is on stage. */
    private fun figure(c: Clause): Boolean {
        val q = Quantities.sense(c.words.map { n.texts[it] }, c.first).quantities.firstOrNull { !it.year && it.value > 0 } ?: return false
        val main = live.firstOrNull { it.main }
        val percent = q.kind == Quantities.Kind.Percent
        if (percent && main != null && main.primitive in setOf(Primitive.Meter, Primitive.Ring)) {
            main.value = q.value / PERCENT
            main.uses++
            move(q.at, StageOp.Fill, main, BeatContent(value = round2(q.value / PERCENT), unit = "%"), "the meter moves to the figure said", c)
            return true
        }
        val primitive = if (percent) Primitive.Ring else Primitive.Stat
        val value = if (percent) q.value / PERCENT else q.value
        add(primitive, c, q.at, BeatContent(text = Said.around(n, c, q.last.coerceAtMost(c.last), 3), value = value, unit = if (percent) "%" else q.unit), "the figure itself, counted up", Idea.Figure, main = percent)
        return true
    }

    /** A list said out loud: its items as tiles (with pictures when they are things) or chips. */
    private fun listed(c: Clause): Boolean {
        val said = lists[c.index] ?: return false
        val items = said.items.take(MAX_ITEMS).map { r ->
            Item(r.joinToString(" ") { Phrases.clean(n.texts[it]) }, visual = r.firstNotNullOfOrNull { Concepts.visualOf(n.texts[it]) }.orEmpty())
        }
        val primitive = if (items.count { it.visual.isNotEmpty() } * 2 >= items.size) Primitive.IconTiles else Primitive.Chips
        val e = add(primitive, c, said.items.first().first, BeatContent(items = items.take(1)), "the list as it is said", Idea.Uses, main = true)
        for ((k, r) in said.items.withIndex().drop(1).take(MAX_ITEMS - 1)) {
            e.items++
            move(r.first, StageOp.Build, e, BeatContent(items = listOf(items[k])), "the next item builds in", c)
        }
        return true
    }

    /** A concrete thing said: its picture with its name. */
    private fun picture(c: Clause): Boolean {
        val i = c.words.firstOrNull { Concepts.visualOf(n.texts[it]) != null && Phrases.nounish(n.texts[it]) } ?: return false
        add(Primitive.Icon, c, i, BeatContent(text = Said.around(n, c, i, 2), visual = Concepts.visualOf(n.texts[i]).orEmpty()), "the thing itself, as a picture", null, main = false)
        return true
    }

    /** Nothing new to draw: the point lands on what is there, else a short phrase of what is said. */
    private fun plain(c: Clause, main: El?) {
        if (main != null && main.uses < MAX_USES) {
            highlight(main, c, mid(c))
            return
        }
        val title = Said.title(n, c, 3).ifBlank { return }
        val primitive = if (' ' in title) Primitive.Kicker else Primitive.BigWord
        add(primitive, c, c.first, BeatContent(text = title), "the line's key phrase, set as type", null, main = false)
    }

    // ---- the stage's bookkeeping -----------------------------------------------------------------

    /** Puts a new picture on stage: a main one morphs or swaps the current main in place; a companion joins. */
    private fun add(primitive: Primitive, c: Clause, at: Int, content: BeatContent, why: String, idea: Idea?, main: Boolean): El {
        val current = live.firstOrNull { it.main }
        if (main && current != null) {
            val op = if (Families.morphs(current.primitive, primitive)) StageOp.Morph else StageOp.Replace
            current.primitive = primitive
            val e = El(current.id, primitive, true, content.items.size, idea)
            live[live.indexOf(current)] = e
            moveAt(time(at), op, e, primitive, content, why, c)
            memory.mains += primitive
            return e
        }
        while (live.size >= MAX_LIVE) {
            val old = live.firstOrNull { !it.main } ?: live.first()
            live.remove(old)
            moveAt(time(at) - CLEAR_LEAD, StageOp.Clear, old, null, BeatContent(), "makes room", c)
        }
        val e = El("c${index + 1}-${primitive.name.lowercase()}-${++made}", primitive, main, content.items.size, idea)
        if (primitive in TEXT_HEAVY) current?.let { moveAt(time(at) - CLEAR_LEAD, StageOp.Dim, it, null, BeatContent(), "steps back for the words", c) }
        live += e
        if (main) memory.mains += primitive
        moveAt(time(at), StageOp.Add, e, primitive, content, why, c)
        return e
    }

    private fun move(word: Int, op: StageOp, e: El, content: BeatContent, why: String, c: Clause) = moveAt(time(word), op, e, null, content, why, c)

    private fun moveAt(at: Double, op: StageOp, e: El, primitive: Primitive?, content: BeatContent, why: String, c: Clause) {
        moves += Move(at, op, e.id, primitive, content, why, c.text)
    }

    /** The picture for [idea] this seed draws; a main picture used twice in a row lets another one of the idea take a turn. */
    private fun choose(idea: Idea): Primitive {
        val recent = memory.mains.takeLast(2)
        val options = idea.pictures.map { (p, w) -> p to if (recent.size == 2 && recent.all { it == p }) w * AVOID else w }
        return rng.weighted(options)
    }

    /** Beats in time order, each lasting until the next, readable (never closer than [MIN_GAP]). */
    private fun beats(): List<Beat> {
        val sorted = moves.sortedBy { it.at }
        val placed = mutableListOf<Move>()
        var t = start + HEADING
        for (m in sorted) {
            val at = maxOf(m.at, t).coerceAtMost(end - MIN_GAP)
            placed += Move(at, m.op, m.element, m.primitive, m.content, m.metaphor, m.line)
            t = at + if (m.op == StageOp.Clear || m.op == StageOp.Dim) TOGETHER else MIN_GAP
        }
        return placed.mapIndexed { i, m ->
            val until = placed.getOrNull(i + 1)?.at?.coerceAtLeast(m.at + TOGETHER) ?: end
            Beat(round2(m.at), round2(minOf(until, end)), m.line, m.op, m.element, m.primitive, m.content, m.metaphor)
        }
    }

    private fun time(word: Int): Double = maxOf(start, n.startOf(word) - LEAD)

    private fun mid(c: Clause): Int = c.first + (c.last - c.first) / 2

    /** Where «بهترین»/"best"/«سخت‌ترین» is said in [c]. */
    private fun best(c: Clause): Int? = c.words.firstOrNull { Metaphors.bare(n.texts[it]).let { b -> b.endsWith("ترین") || b == "best" || b == "hardest" || b.endsWith("est") } }

    private fun superlative(c: Clause): String = best(c)?.let { Phrases.clean(n.texts[it]) }.orEmpty()

    private fun years(c: Clause): List<Item> =
        Quantities.sense(c.words.map { n.texts[it] }, c.first).quantities.filter { it.year }.map { Item(Quantities.digits(it.value, n.persian)) }

    private fun rising(count: Int): List<Item> = List(count) { i -> Item("", value = round2((i + 1.0) / count)) }

    companion object {
        const val MAX_LIVE = 3
        const val MAX_ITEMS = 6
        private const val STRONG = 0.95f
        private const val WEAK = 0.6f
        private const val MAX_USES = 3
        private const val TILES = 5
        private const val BARS = 4
        private const val BLOCKS = 10
        private const val BLOCK_FEW = 3
        private const val DOTS = 24
        private const val PERCENT = 100.0
        private const val SLOW_STEP = 0.025
        private const val FAST_STEP = 0.33
        private const val GROW_STEP = 0.2
        private const val LEAD = 0.12
        private const val CLEAR_LEAD = 0.05
        private const val HEADING = 0.5
        const val MIN_GAP = 0.6
        private const val TOGETHER = 0.05
        private const val AVOID = 0.15f

        private val TEXT_HEAVY = setOf(Primitive.BigWord, Primitive.Quote)
        private val DRAINS = setOf(Idea.Drain, Idea.Fast, Idea.Slow, Idea.Grow)
        private val LIKE = setOf("مثل", "like", "همچون", "انگار")
        private val ASKS = listOf("فالو", "follow", "subscribe", "سابسکرایب", "کامنت", "comment", "لایک", "like", "share", "شیر", "save", "سیو", "ذخیره")
        private val FAST_WORDS = setOf("زود", "زودتر", "سریع", "سریعتر", "fast", "faster", "quickly", "soon")
        private val DRAIN_VERBS = setOf("کم", "کمتر", "تموم", "تمام", "میکنه", "میشه", "drains", "drain", "runs", "drops", "goes", "falls")
        private val METER_NOUNS = listOf("محدودیت", "مصرف", "سهمیه", "باتری", "بودجه", "limit", "usage", "quota", "battery", "budget")

        /** What may follow each main picture on its own stage (the ideas that work on it). */
        private val FOLLOWS: Map<Primitive, Set<Idea>> = mapOf(
            Primitive.TaskTiles to setOf(Idea.Routine, Idea.Success, Idea.Steps),
            Primitive.Checklist to setOf(Idea.Routine, Idea.Success, Idea.Steps),
            Primitive.Steps to setOf(Idea.Steps, Idea.Success),
            Primitive.Meter to DRAINS,
            Primitive.Ring to DRAINS,
            Primitive.Branches to setOf(Idea.Path, Idea.Think, Idea.Success),
            Primitive.Blocks to setOf(Idea.Portion, Idea.Rank),
            Primitive.Bars to setOf(Idea.Rank, Idea.Grow),
            Primitive.Ladder to setOf(Idea.Rank, Idea.Levels),
            Primitive.Tangle to setOf(Idea.Success, Idea.Tangle),
            Primitive.IconTiles to setOf(Idea.Uses, Idea.Traits),
            Primitive.Chips to setOf(Idea.Uses, Idea.Compare),
        )

        fun round2(v: Double): Double = kotlin.math.round(v * 100.0) / 100.0
    }
}

/** Which primitives are drawn alike (items in a row, a quantity, words, a drawn shape): a morph between them reads as one thing changing. */
object Families {
    val ITEMS = setOf(
        Primitive.Chips, Primitive.IconTiles, Primitive.TaskTiles, Primitive.Checklist, Primitive.Steps, Primitive.Timeline, Primitive.Blocks,
        Primitive.Dots, Primitive.Grid, Primitive.Flow, Primitive.Cycle, Primitive.Funnel, Primitive.Ladder, Primitive.Stack,
    )
    val VALUES = setOf(Primitive.Meter, Primitive.Bars, Primitive.Ring, Primitive.Stat, Primitive.Scale, Primitive.Clock)
    val WORDS = setOf(Primitive.Heading, Primitive.Kicker, Primitive.BigWord, Primitive.Note, Primitive.Quote, Primitive.Badge)
    val SHAPES = setOf(Primitive.Tangle, Primitive.Branches, Primitive.Thinking, Primitive.Mark, Primitive.Icon, Primitive.Profile, Primitive.Chat, Primitive.Map)

    fun morphs(a: Primitive, b: Primitive): Boolean = a != b && listOf(ITEMS, VALUES, WORDS, SHAPES).any { a in it && b in it }
}
