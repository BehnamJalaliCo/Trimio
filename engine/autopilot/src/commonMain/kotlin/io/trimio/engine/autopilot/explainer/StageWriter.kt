package io.trimio.engine.autopilot.explainer

import io.trimio.engine.autopilot.Asks
import io.trimio.engine.autopilot.Enumerations
import io.trimio.engine.autopilot.Lines
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

    /** Whether the opening has shown what the film will walk through. */
    var agenda = false
}

/**
 * One chapter's stage, written clause by clause by the rules. The stage persists and builds: a
 * new idea brings a new picture (morphing or swapping the main one in its place, never a hard
 * cut), and a clause that goes on about the same thing works on what is there — ticks the tiles,
 * drains the meter, lights the best path, badges the tallest bar, adds a tag to the card. A small
 * companion (a clock, a check, a warning) may join the main picture. Every beat lands on the word
 * that says it.
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
    private val visuals: Visuals = Visuals.Lexicon,
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
    private val lines = n.clauses.map { Lines.Line(it.first, it.last) }
    private val lists = Enumerations.lists(n.texts, lines)
    private val ask = Asks.read(n.texts, lines)?.first

    fun write(): List<Beat> {
        plan.subject?.let { s -> if (memory.subjects.none { Phrases.key(it) == Phrases.key(s) }) memory.subjects += s }
        for (k in plan.clauses) clause(n.clauses[k])
        return beats()
    }

    // ---- one clause --------------------------------------------------------------------------

    private fun clause(c: Clause) {
        val found = Metaphors.find(c.words.map { n.keys[it] }, c.first, c.read?.gist.orEmpty())
        val main = live.firstOrNull { it.main }
        // The ideas that make a picture first, the small signs (a clock, a check) after.
        val strong = found.filter { it.strength >= STRONG }.sortedBy { if (it.idea in COMPANION_IDEAS) 1 else 0 }
        val counted = quantity(c)?.takeIf { q -> strong.firstOrNull()?.let { it.idea in FIGURES || it.strength < CLEAR } ?: true }
        when {
            c.index == ask -> cta(c)
            index == 0 && agenda(c) -> Unit
            step(c) -> Unit
            counted != null && figure(c) -> companion(c, found, Idea.Figure)
            main != null && goOn(main, c, found) -> companion(c, found, main.idea)
            traits(c) -> Unit
            else -> {
                val used = strong.firstOrNull { idea(it, c, found) }
                when {
                    used != null -> companion(c, found, used.idea)
                    figure(c) || listed(c) || picture(c) -> Unit
                    else -> plain(c, main)
                }
            }
        }
    }

    /** The clause's first number with a meaning (a unit, a percent, ten or more), when it has one. */
    private fun quantity(c: Clause): Quantities.Quantity? = Quantities.sense(c.words.map { n.texts[it] }, c.first).quantities
        .firstOrNull { it.value > 0 && (it.unit.isNotEmpty() || it.kind == Quantities.Kind.Percent || it.value >= TEN) }

    /** The opening chapter shows, once, what the film will walk through: the names of the chapters to come. */
    private fun agenda(c: Clause): Boolean {
        val coming = plans.drop(1).mapNotNull { it.subject }.distinct()
        if (memory.agenda || coming.size < 2) return false
        val turns = c.words.any { n.keys[it] == n.category || Metaphors.find(listOf(n.keys[it])).any { f -> f.idea == Idea.Compare } }
        if (!turns && c.index != plan.clauses.last) return false
        memory.agenda = true
        add(Primitive.Chips, c, c.first, BeatContent(items = coming.take(MAX_ITEMS).map { Item(it) }), "the subjects the film will walk through, previewed as chips", null, main = true)
        return true
    }

    /** The ask at the end («فالو کنید», "follow for more"): a badge with the action said. */
    private fun cta(c: Clause) {
        val at = c.words.firstOrNull { i -> ASKS.any { Metaphors.bare(n.texts[i]).startsWith(it) } } ?: c.first
        add(Primitive.Badge, c, at, BeatContent(text = Said.from(n, c, at, 2), visual = "bell"), "the call to action as a badge to tap", null, main = true)
    }

    /** A step counted off («Step two, grind the beans», «حرکت دوم»): one more step on the chapter's steps, the last one ticked. */
    private fun step(c: Clause): Boolean {
        val head = c.words.take(HEAD).firstOrNull { i -> Metaphors.bare(n.texts[i]) in STEP_NOUNS && i < c.last && Metaphors.bare(n.texts[i + 1]) in COUNTERS } ?: return false
        val counted = head..(head + 1)
        val after = counted.last + 1
        val label = (if (after <= c.last) Said.from(n, c, after, 3) else "").ifBlank { counted.joinToString(" ") { Phrases.clean(n.texts[it]) } }
        val steps = live.firstOrNull { it.main && it.primitive in STEPS }
        if (steps == null) {
            add(choose(Idea.Steps), c, counted.first, BeatContent(items = listOf(Item(label))), "each step joins the steps as it is said", Idea.Steps, main = true)
        } else {
            if (steps.checked < steps.items) {
                move(counted.first, StageOp.Check, steps, BeatContent(picks = (steps.checked until steps.items).toList()), "the step before is done", c)
                steps.checked = steps.items
            }
            steps.items++
            steps.uses++
            move(counted.first, StageOp.Build, steps, BeatContent(items = listOf(Item(label))), "the next step builds in", c)
        }
        return true
    }

    // ---- going on with what is on stage --------------------------------------------------------

    /** Works on the main picture when the clause goes on about the same thing; false when it says something new. */
    private fun goOn(main: El, c: Clause, found: List<Found>): Boolean {
        if (main.uses >= MAX_USES) return false
        val follows = FOLLOWS.getOrDefault(main.primitive, emptySet())
        val ideas = found.filter { it.strength >= WEAK }.map { it.idea }.toSet()
        val fresh = found.firstOrNull { it.strength >= STRONG && it.idea != main.idea && it.idea !in follows && it.idea !in COMPANION_IDEAS }
        if (fresh != null) return false
        val at = found.firstOrNull { it.idea in follows }?.at ?: mid(c)
        return when (main.primitive) {
            Primitive.Bars -> bars(main, c, at, ideas)
            else -> workOn(main, c, at, ideas, found)
        }
    }

    /** Bars go on: the last is the lowest, the tallest gets its badge, or one more grows in. */
    private fun bars(main: El, c: Clause, at: Int, ideas: Set<Idea>): Boolean = when {
        Idea.Decline in ideas -> pick(main, c, at, listOf(main.items - 1), "", "the last bar is the lowest")
        Idea.Rank in ideas -> pick(main, c, at, listOf(main.items - 1), superlative(c), "the tallest gets the badge")
        Idea.Grow in ideas || Idea.Accumulate in ideas -> taller(main, c, at)
        else -> false
    }

    private fun workOn(main: El, c: Clause, at: Int, ideas: Set<Idea>, found: List<Found>): Boolean {
        return when (main.primitive) {
            Primitive.TaskTiles, Primitive.Checklist -> tick(main, c, at)
            Primitive.Meter, Primitive.Ring -> (ideas.any { it in DRAINS } || main.uses == 0) && drain(main, c, at, ideas)
            Primitive.Branches, Primitive.Flow -> main.checked == 0 && pick(main, c, at, listOf(0), Said.around(n, c, best(c) ?: at, 2), "the best path lights up")
            Primitive.Blocks -> blocks(main, c, at)
            Primitive.Dots -> if (Idea.Many in ideas) more(main, c, at) else Idea.Portion in ideas && blocks(main, c, at)
            Primitive.Ladder -> (Idea.Rank in ideas || main.checked == 0) && pick(main, c, at, listOf(main.items - 1), superlative(c), "the top rung lights up")
            Primitive.Profile -> tag(main, c)
            Primitive.Tangle -> (Idea.Success in ideas) && solved(c, found.first { it.idea == Idea.Success }.at)
            Primitive.IconTiles, Primitive.Chips, Primitive.Steps -> highlight(main, c, at)
            else -> false
        }
    }

    /** Bars that go on growing: one more, taller. */
    private fun taller(e: El, c: Clause, at: Int): Boolean {
        if (e.items >= MAX_ITEMS) return false
        e.items++
        e.uses++
        move(at, StageOp.Build, e, BeatContent(items = listOf(Item("", value = 1.0))), "one more bar, taller: it keeps growing", c)
        return true
    }

    /** More dots light up: even more of them. */
    private fun more(e: El, c: Clause, at: Int): Boolean {
        e.uses++
        move(at, StageOp.Check, e, BeatContent(picks = (0 until DOTS * 2 / 3).toList()), "more and more of the dots light up", c)
        return true
    }

    private fun tick(e: El, c: Clause, at: Int): Boolean {
        if (e.checked >= e.items) return false
        val picks = (e.checked until e.items).toList()
        e.checked = e.items
        e.uses++
        move(at, StageOp.Check, e, BeatContent(picks = picks), "each item ticked as it gets done", c)
        return true
    }

    private fun drain(e: El, c: Clause, at: Int, ideas: Set<Idea>): Boolean {
        val now = e.value ?: 1.0
        val fast = e.fast || Idea.Fast in ideas || c.words.any { Metaphors.bare(n.texts[it]) in FAST_WORDS }
        val next = when {
            Idea.Grow in ideas -> minOf(1.0, now + GROW_STEP)
            fast -> minOf(now - SLOW_STEP, FAST_TO)
            else -> now - SLOW_STEP
        }.coerceIn(MIN_VALUE, 1.0)
        e.value = next
        e.uses++
        move(at, StageOp.Fill, e, BeatContent(value = round2(next), unit = "%"), if (fast) "the meter drops fast" else "the meter drains, slowly", c)
        return true
    }

    private fun pick(e: El, c: Clause, at: Int, picks: List<Int>, caption: String, why: String): Boolean {
        e.checked++
        e.uses++
        val op = if (FilmCheck.fits(StageOp.Check, e.primitive) && e.primitive != Primitive.Ladder) StageOp.Check else StageOp.Highlight
        move(at, op, e, BeatContent(caption = caption, picks = picks), why, c)
        return true
    }

    /** Blocks: the first few take one colour (the hard part), then the rest the other. */
    private fun blocks(e: El, c: Clause, at: Int): Boolean {
        if (e.checked >= 2) return false
        val first = minOf(BLOCK_FEW, e.items)
        val picks = if (e.checked == 0) (0 until first).toList() else (first until e.items).toList()
        val caption = Said.around(n, c, best(c) ?: c.words.firstOrNull { Phrases.nounish(n.texts[it]) } ?: at, 3)
        e.checked++
        e.uses++
        move(at, if (e.checked == 1) StageOp.Check else StageOp.Highlight, e, BeatContent(caption = caption, picks = picks), "one share of the blocks takes its own colour", c)
        return true
    }

    /** A card gets a tag: the words that describe the persona («تازه‌وارد شرکت»), once. */
    private fun tag(e: El, c: Clause): Boolean {
        if (e.items >= 2) return false
        val noun = c.words.firstOrNull { Phrases.nounish(n.texts[it]) && !Said.role(n.texts[it]) } ?: return false
        val tag = Said.from(n, c, noun, 2).takeIf { it.isNotBlank() } ?: return false
        e.items++
        e.uses++
        move(noun, StageOp.Build, e, BeatContent(items = listOf(Item(tag))), "a tag on the card says what kind of persona", c)
        return true
    }

    /** The tangle is solved: a check beside it. */
    private fun solved(c: Clause, at: Int): Boolean {
        if (live.any { it.primitive == Primitive.Mark }) return false
        add(Primitive.Mark, c, at, BeatContent(text = Said.from(n, c, at, 2), visual = "check mark"), "the tangle gets its check: it handles this well", Idea.Success, main = false)
        return true
    }

    private fun highlight(e: El, c: Clause, at: Int): Boolean {
        e.uses++
        move(at, StageOp.Highlight, e, BeatContent(picks = listOf((e.uses - 1).coerceIn(0, maxOf(0, e.items - 1)))), "the point lands on what is already there", c)
        return true
    }

    /** A small picture beside the main one when the clause also says something it draws («دیرتر» → a clock). */
    private fun companion(c: Clause, found: List<Found>, used: Idea?) {
        val f = found.firstOrNull { it.strength >= STRONG && it.idea in COMPANION_IDEAS && it.idea != used } ?: return
        if (live.any { !it.main && it.idea == f.idea }) return
        if (used in setOf(Idea.Slow, Idea.Fast) && f.idea in setOf(Idea.Slow, Idea.Fast)) return
        val (primitive, visual) = when (f.idea) {
            Idea.Slow, Idea.Fast -> Primitive.Clock to (if (f.idea == Idea.Fast) "stopwatch" else "hourglass")
            Idea.Warning -> Primitive.Badge to "warning"
            Idea.Success -> Primitive.Mark to "check mark"
            else -> Primitive.Icon to "light bulb"
        }
        add(primitive, c, f.at, BeatContent(text = Said.from(n, c, f.at, 2), visual = visual), "a small sign beside the picture: ${f.idea.name.lowercase()}", f.idea, main = false)
    }

    // ---- new pictures --------------------------------------------------------------------------

    /** Qualities said one after another: tiles that build in as each is said. */
    private fun traits(c: Clause): Boolean {
        val said = c.words.mapNotNull { i -> Metaphors.trait(n.texts[i])?.let { i to it } }.distinctBy { it.second }
        if (said.size < 2) return false
        val primitive = choose(Idea.Traits)
        val (first, visual) = said.first()
        val e = add(primitive, c, first, BeatContent(items = listOf(Item(quality(c, first), visual = visual))), "each quality is a tile that builds in as it is said", Idea.Traits, main = true)
        for ((i, v) in said.drop(1).take(MAX_ITEMS - 1)) {
            e.items++
            move(i, StageOp.Build, e, BeatContent(items = listOf(Item(quality(c, i), visual = v))), "another quality joins the row", c)
        }
        return true
    }

    /** A quality as said: the word, with the noun it qualifies when one follows it («قدرت استدلال»). */
    private fun quality(c: Clause, i: Int): String {
        val w = Phrases.clean(n.texts[i])
        val next = (i + 1).takeIf { it <= c.last && n.texts[i].trimEnd().last() !in ",،" && Phrases.nounish(n.texts[it]) && Metaphors.trait(n.texts[it]) == null }
        return if (next != null) w + " " + Phrases.clean(n.texts[next]) else w
    }

    /** Puts the picture of [f] on stage; false when the clause does not give it what it needs. */
    @Suppress("CyclomaticComplexMethod")
    private fun idea(f: Found, c: Clause, found: List<Found>): Boolean {
        val at = f.at
        val phrase = label(c, at)
        return when (f.idea) {
            Idea.Persona, Idea.Analogy -> persona(c, at)
            Idea.Routine -> routine(c, at, phrase)
            Idea.Drain -> meter(c, at, found)
            Idea.Decline -> decline(c, at, phrase)
            Idea.Grow, Idea.Accumulate -> phrase.isNotBlank() && add(choose(f.idea), c, at, BeatContent(text = phrase, items = rising(BARS)), "it grows: bars rise", f.idea, main = true).let { true }
            Idea.Uses -> add(choose(Idea.Uses), c, at, BeatContent(text = phrase, items = Said.examples(n.understanding.domain, n.persian)),
                "most everyday work, drawn as the kinds of work", f.idea, main = true).let { true }
            Idea.Path -> path(c, at)
            Idea.Think -> phrase.isNotBlank() && add(choose(Idea.Think), c, at, BeatContent(text = phrase, items = List(3) { Item("") }), "thinking it through before acting",
                f.idea, main = true).let { true }
            Idea.Tangle -> add(Primitive.Tangle, c, at, BeatContent(text = phrase, visual = "tangle"), "deep, complex problems: a tangle", f.idea, main = true).let { true }
            Idea.Rank, Idea.Levels -> rank(c, at, f.idea)
            Idea.Portion -> portion(c, at)
            Idea.Slow, Idea.Fast -> add(Primitive.Clock, c, at, BeatContent(text = Said.from(n, c, at, 2)), "time: a clock", f.idea, main = false).let { true }
            Idea.Compare, Idea.Balance, Idea.Change -> sides(c, at, f.idea)
            Idea.Steps, Idea.Connect, Idea.Filter -> process(c, at, f.idea)
            Idea.Cycle -> phrase.isNotBlank() && add(Primitive.Cycle, c, at, BeatContent(text = Said.title(n, c, 3).ifBlank { phrase }, items = List(CYCLE) { Item("") }),
                "it comes round again: a cycle", f.idea, main = true).let { true }
            Idea.Question -> phrase.isNotBlank() && add(choose(Idea.Question), c, at, BeatContent(text = phrase), "the question the chapter answers", f.idea, main = false).let { true }
            Idea.Warning -> add(choose(Idea.Warning), c, at, BeatContent(text = phrase.ifBlank { Said.from(n, c, at, 2) }, visual = "warning"), "a caution, marked", f.idea, main = false).let { true }
            Idea.Goal, Idea.Insight -> phrase.isNotBlank() && add(choose(f.idea), c, at, BeatContent(text = phrase,
                visual = if (f.idea == Idea.Goal) "direct hit" else "light bulb"), "the point as a symbol", f.idea, main = false).let { true }
            Idea.Success -> add(choose(Idea.Success), c, at, BeatContent(text = Said.from(n, c, at, 2), visual = "check mark"), "it works: a check", f.idea, main = false).let { true }
            Idea.Many -> add(
                Primitive.Dots, c, at, BeatContent(text = phrase, value = DOTS.toDouble(), picks = (0 until DOTS / 3).toList()), "so many: a field of dots", f.idea, main = true,
            ).let { true }
            Idea.Talk -> add(Primitive.Chat, c, at, BeatContent(text = phrase, items = listOf(Item(Said.title(n, c, 3))), visual = "speech balloon"),
                "asking and answering: a chat", f.idea, main = true).let { true }
            Idea.Place -> add(Primitive.Map, c, at, BeatContent(text = phrase, visual = "world map"), "where it goes: a map", f.idea, main = true).let { true }
            Idea.History -> history(c, at, phrase)
            Idea.Quote -> Said.title(n, c).isNotBlank() && add(Primitive.Quote, c, at, BeatContent(text = Said.title(n, c)), "someone's words, quoted", f.idea, main = false).let { true }
            Idea.Money -> figure(c) || phrase.isNotBlank() && choose(Idea.Money).let { p -> add(p, c, at, BeatContent(text = phrase, visual = "coin"), "money: coins stack up",
                f.idea, main = p != Primitive.Icon).let { true } }
            Idea.Traits, Idea.Figure -> false
        }
    }

    /** A label for the picture of a cue at [at]: the noun phrase around it, else the clause's own phrase. */
    private fun label(c: Clause, at: Int): String {
        val around = Said.around(n, c, at)
        return if (Phrases.nounish(n.texts[at]) && around.isNotBlank()) around else Said.title(n, c).ifBlank { around }
    }

    private fun routine(c: Clause, at: Int, phrase: String): Boolean {
        val e = add(choose(Idea.Routine), c, at, BeatContent(text = phrase, items = List(TILES) { Item("", visual = "task") }), "light repetitive work: a row of task tiles", Idea.Routine, main = true)
        // Said on to the end of the clause («… رو بهش بده»): the tiles are ticked there.
        if (c.last - at >= 2) tick(e, c, c.last)
        return true
    }

    /** "Like an intern": a persona card; "like a clock", "like a notebook": the thing it is like. */
    private fun persona(c: Clause, at: Int): Boolean {
        val like = Metaphors.bare(n.texts[at]) in LIKE
        val roleAt = if (like) (at + 1..minOf(c.last, at + LIKE_REACH)).firstOrNull { Said.role(n.texts[it]) || visuals.of(n.texts[it], c.text) != null } else at
        if (roleAt == null) return false
        val caption = if (like) n.texts.subList(at, roleAt).joinToString(" ") { Phrases.clean(it) } + "…" else ""
        if (!Said.role(n.texts[roleAt])) return analogy(c, at, roleAt, caption)
        val e = add(Primitive.Profile, c, at, BeatContent(text = Phrases.clean(n.texts[roleAt]), caption = caption, visual = "person"), "a persona: a profile card", Idea.Persona, main = true)
        // The words that describe the persona become its tag, when they are said.
        val tagAt = (roleAt + 1..c.last).firstOrNull { Phrases.nounish(n.texts[it]) } ?: return true
        val tag = Said.from(n, c, tagAt, 2).takeIf { it.isNotBlank() } ?: return true
        e.items++
        move(tagAt, StageOp.Build, e, BeatContent(items = listOf(Item(tag))), "a tag says what kind of persona", c)
        return true
    }

    private fun analogy(c: Clause, at: Int, thing: Int, caption: String): Boolean {
        val visual = visuals.of(n.texts[thing], c.text) ?: return false
        val clock = visual.contains("clock") || visual.contains("watch")
        add(if (clock) Primitive.Clock else Primitive.Icon, c, at, BeatContent(text = Phrases.clean(n.texts[thing]), caption = caption, visual = visual),
            "an analogy: the thing it is like", Idea.Analogy, main = false)
        return true
    }

    private fun meter(c: Clause, at: Int, found: List<Found>): Boolean {
        val fast = found.any { it.idea == Idea.Fast } || c.words.any { Metaphors.bare(n.texts[it]) in FAST_WORDS }
        val noun = c.words.firstOrNull { Metaphors.bare(n.texts[it]).let { b -> METER_NOUNS.any { s -> b.startsWith(s) } } }
        val label = (if (noun != null) Said.around(n, c, noun, 3) else Said.title(n, c, 3)).ifBlank { return false }
        val start = if (fast) FAST_FROM else 1.0
        val e = add(choose(Idea.Drain), c, noun ?: at, BeatContent(text = label, value = start, unit = "%"), "a limit: a meter that drains", Idea.Drain, main = true)
        e.value = start
        e.fast = fast
        val verb = c.words.firstOrNull { it > (noun ?: at) + 1 && Metaphors.bare(n.texts[it]) in DRAIN_VERBS } ?: return true
        drain(e, c, verb, if (fast) setOf(Idea.Fast) else emptySet())
        return true
    }

    private fun decline(c: Clause, at: Int, cue: String): Boolean {
        val head = c.words.firstOrNull { Phrases.nounish(n.texts[it]) && Metaphors.bare(n.texts[it]) !in Said.ADVERBS }
        val phrase = head?.let { Phrases.clean(n.texts[it]) } ?: cue
        if (phrase.isBlank()) return false
        val p = choose(Idea.Decline)
        if (p == Primitive.Meter) {
            val e = add(p, c, at, BeatContent(text = phrase, value = 1.0, unit = "%"), "less and less: a meter that drains", Idea.Decline, main = true)
            e.value = 1.0
            drain(e, c, minOf(c.last, at + 1), setOf(Idea.Fast))
        } else {
            add(p, c, at, BeatContent(text = phrase, items = rising(BARS).reversed()), "less and less: bars that fall", Idea.Decline, main = true)
        }
        return true
    }

    private fun path(c: Clause, at: Int): Boolean {
        val e = add(choose(Idea.Path), c, at, BeatContent(text = goal(), items = List(3) { Item("") }), "finding the best path: branches that converge on the task", Idea.Path, main = true)
        val best = best(c)?.takeIf { it > at } ?: (if (c.last > at + 2) c.last else null) ?: return true
        pick(e, c, best, listOf(0), best(c)?.let { Said.around(n, c, it, 2) }.orEmpty(), "the best path lights up")
        return true
    }

    /** The target a path or a thought converges on: the word for a task, in the film's language. */
    private fun goal(): String = if (n.persian) "هدف" else "goal"

    private fun rank(c: Clause, at: Int, idea: Idea): Boolean {
        val names = memory.subjects.takeLast(MAX_ITEMS)
        val items = if (names.size >= 2) names.mapIndexed { i, s -> Item(s, value = round2((i + 1.0) / names.size)) } else rising(BARS)
        val e = add(choose(idea), c, at, BeatContent(items = items), "the strongest: bars by tier, the last one tallest", idea, main = true)
        val sup = best(c) ?: at
        pick(e, c, maxOf(sup, at), listOf(items.lastIndex), superlative(c), "the tallest gets the badge")
        return true
    }

    private fun portion(c: Clause, at: Int): Boolean {
        val focus = best(c) ?: c.words.firstOrNull { it > at && Phrases.nounish(n.texts[it]) } ?: return false
        val e = add(choose(Idea.Portion), c, at, BeatContent(items = List(BLOCKS) { Item("") }), "only part of the work: blocks in two colours", Idea.Portion, main = true)
        blocks(e, c, maxOf(focus, at))
        return true
    }

    private fun sides(c: Clause, at: Int, idea: Idea): Boolean {
        val names = Chapters.subjectsOf(n, c)
        val items = when {
            names.size >= 2 -> names.take(2).map { Item(it) }
            idea == Idea.Change && index > 0 -> listOf(Item(plans[index - 1].title), Item(plan.title))
            else -> return false
        }
        add(choose(idea), c, at, BeatContent(text = label(c, at), items = items), "two sides set against each other", idea, main = true)
        return true
    }

    private fun process(c: Clause, at: Int, idea: Idea): Boolean {
        val list = lists[c.index]?.items?.map { r -> Item(r.joinToString(" ") { Phrases.clean(n.texts[it]) }) }.orEmpty()
        if (list.size < 2) return false
        add(choose(idea), c, at, BeatContent(items = list.take(MAX_ITEMS)), "one thing leads to the next", idea, main = true)
        return true
    }

    private fun history(c: Clause, at: Int, phrase: String): Boolean {
        val years = years(c)
        val items = years.ifEmpty { listOf(Item(phrase)) }.filter { it.label.isNotBlank() }
        if (items.isEmpty()) return false
        add(Primitive.Timeline, c, at, BeatContent(items = items), "when it happened: a timeline", Idea.History, main = true)
        return true
    }

    /** Numbers said: a year joins the timeline; a percent fills the meter or rings; a count is a stat. */
    private fun figure(c: Clause): Boolean {
        val q = Quantities.sense(c.words.map { n.texts[it] }, c.first).quantities.firstOrNull { it.value > 0 } ?: return false
        val main = live.firstOrNull { it.main }
        val year = q.year || (q.decimals == 0 && q.value in YEARS && q.unit.isEmpty())
        if (year) return timeline(c, q, main)
        val percent = q.kind == Quantities.Kind.Percent
        if (percent && main != null && main.primitive in setOf(Primitive.Meter, Primitive.Ring)) {
            main.value = q.value / PERCENT
            main.uses++
            move(q.at, StageOp.Fill, main, BeatContent(value = round2(q.value / PERCENT), unit = "%"), "the meter moves to the figure said", c)
            return true
        }
        val noun = c.words.firstOrNull { it !in q.at..q.last && Phrases.nounish(n.texts[it]) && Metaphors.bare(n.texts[it]) !in Said.ADVERBS && Metaphors.bare(n.texts[it]) !in Said.FILLERS }
        val label = noun?.let { Said.around(n, c, it, 2) }?.takeIf { it.isNotBlank() && it != q.unit } ?: Said.title(n, c, 3)
        val value = if (percent) q.value / PERCENT else q.value
        add(if (percent) Primitive.Ring else Primitive.Stat, c, q.at, BeatContent(text = label, value = value, unit = if (percent) "%" else q.unit), "the figure itself, counted up",
            Idea.Figure, main = percent)
        return true
    }

    private fun timeline(c: Clause, q: Quantities.Quantity, main: El?): Boolean {
        val label = Quantities.digits(q.value, n.persian)
        if (main != null && main.primitive == Primitive.Timeline) {
            main.items++
            main.uses++
            move(q.at, StageOp.Build, main, BeatContent(items = listOf(Item(label))), "the next date on the timeline", c)
        } else {
            add(Primitive.Timeline, c, q.at, BeatContent(items = listOf(Item(label))), "a date: a timeline", Idea.History, main = true)
        }
        return true
    }

    /** A list said out loud: its items as tiles (with pictures when they are things) or chips. */
    private fun listed(c: Clause): Boolean {
        val said = lists[c.index] ?: return false
        val items = said.items.take(MAX_ITEMS).map { r ->
            Item(r.joinToString(" ") { Phrases.clean(n.texts[it]) }, visual = r.firstNotNullOfOrNull { Said.visualOf(n.texts[it]) }.orEmpty())
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
        val i = c.words.firstOrNull { Phrases.nounish(n.texts[it]) && visuals.of(n.texts[it], c.text) != null }
        if (i != null) {
            add(Primitive.Icon, c, i, BeatContent(text = Phrases.clean(n.texts[i]), visual = visuals.of(n.texts[i], c.text).orEmpty()), "the thing itself, as a picture", null, main = false)
            return true
        }
        val whole = visuals.forSentence(c.text, n.persian) ?: return false
        val title = Said.title(n, c, 2).ifBlank { return false }
        add(Primitive.Icon, c, c.first, BeatContent(text = title, visual = whole), "a picture of what the sentence says", null, main = false)
        return true
    }

    /** Nothing new to draw: the point lands on what is there, else a short phrase of what is said. */
    private fun plain(c: Clause, main: El?) {
        if (main != null && main.uses < MAX_USES) {
            highlight(main, c, mid(c))
            return
        }
        val subject = plan.subject?.let(Phrases::key)
        val title = Said.title(n, c, 3).split(' ').filter { Phrases.key(it) != subject }.joinToString(" ").ifBlank { naming(c) ?: return }
        val primitive = if (' ' in title) Primitive.Kicker else Primitive.BigWord
        add(primitive, c, c.first, BeatContent(text = title), "the line's key phrase, set as type", null, main = false)
    }

    /** A clause that names the chapter's subject («مدل اول اسمش Haiku هست»): the words around the name, without it («مدل اول»). */
    private fun naming(c: Clause): String? {
        val subject = plan.subject?.let(Phrases::key) ?: return null
        val words = c.words.map { n.texts[it] }
        if (words.none { Phrases.key(it) == subject }) return null
        return words.takeWhile { Phrases.key(it) != subject }.filter { Phrases.nounish(it) || Metaphors.bare(it) in COUNTERS }.take(2).joinToString(" ") { Phrases.clean(it) }.ifBlank { null }
    }

    // ---- the stage's bookkeeping -----------------------------------------------------------------

    /** Puts a new picture on stage: a main one morphs or swaps the current main in place; a companion joins. */
    private fun add(primitive: Primitive, c: Clause, at: Int, content: BeatContent, why: String, idea: Idea?, main: Boolean): El {
        val current = live.firstOrNull { it.main }
        if (main && current != null) {
            val op = if (Families.morphs(current.primitive, primitive)) StageOp.Morph else StageOp.Replace
            val e = El(current.id, primitive, true, content.items.size, idea)
            live[live.indexOf(current)] = e
            moveAt(time(at), op, e, primitive, content, why, c)
            memory.mains += primitive
            return e
        }
        // Never two of the same small picture: the one there takes the new words in place.
        live.firstOrNull { !main && !it.main && it.primitive == primitive }?.let { twin ->
            moveAt(time(at), StageOp.Replace, twin, primitive, content, why, c)
            return twin
        }
        while (live.size >= MAX_LIVE) {
            val old = live.firstOrNull { !it.main } ?: live.first()
            live.remove(old)
            moveAt(time(at) - CLEAR_LEAD, StageOp.Clear, old, null, BeatContent(), "makes room", c)
        }
        val e = El("c${index + 1}-${Drafts.serial(primitive)}-${++made}", primitive, main, content.items.size, idea)
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

    /** Beats in time order, each lasting until the next and long enough to read. */
    private fun beats(): List<Beat> {
        val sorted = moves.withIndex().sortedWith(compareBy({ it.value.at }, { it.index })).map { it.value }
        val placed = mutableListOf<Move>()
        var t = start + HEADING
        for (m in sorted) {
            val at = maxOf(m.at, t).coerceAtMost(end - MIN_GAP)
            placed += Move(at, m.op, m.element, m.primitive, m.content, m.metaphor, m.line)
            t = at + when (m.op) {
                StageOp.Clear, StageOp.Dim -> TOGETHER
                StageOp.Add, StageOp.Replace, StageOp.Morph -> readable(m.content)
                else -> MIN_GAP
            }
        }
        return placed.mapIndexed { i, m ->
            val until = placed.getOrNull(i + 1)?.at?.coerceAtLeast(m.at + TOGETHER) ?: end
            Beat(round2(m.at), round2(minOf(until, end)), m.line, m.op, m.element, m.primitive, m.content, m.metaphor)
        }
    }

    /** Seconds a new picture needs before the next beat: more words, more time. */
    private fun readable(c: BeatContent): Double {
        val chars = c.text.length + c.caption.length + c.items.sumOf { it.label.length }
        return (READ_BASE + READ_PER_CHAR * chars).coerceIn(MIN_GAP, READ_MAX)
    }

    private fun time(word: Int): Double = maxOf(start, n.startOf(word) - LEAD)

    private fun mid(c: Clause): Int = c.first + (c.last - c.first) / 2

    /** Where «بهترین»/"best"/«سخت‌ترین» is said in [c]. */
    private fun best(c: Clause): Int? = c.words.firstOrNull { Metaphors.bare(n.texts[it]).let { b -> b.endsWith("ترین") || b == "best" || b == "hardest" || b == "strongest" } }

    private fun superlative(c: Clause): String = best(c)?.let { Phrases.clean(n.texts[it]) }.orEmpty()

    private fun years(c: Clause): List<Item> = Quantities.sense(c.words.map { n.texts[it] }, c.first).quantities
        .filter { it.year || (it.decimals == 0 && it.value in YEARS) }.map { Item(Quantities.digits(it.value, n.persian)) }

    private fun rising(count: Int): List<Item> = List(count) { i -> Item("", value = round2((i + 1.0) / count)) }

    companion object {
        const val MAX_LIVE = 3
        const val MAX_ITEMS = 6
        private const val STRONG = 0.95f
        /** An idea this strong wins over a number said in the same clause. */
        private const val CLEAR = 1.3f
        private const val TEN = 10.0
        private const val HEAD = 3
        private const val LIKE_REACH = 3
        private val FIGURES = setOf(Idea.Money, Idea.Accumulate, Idea.Grow, Idea.Figure, Idea.Many, Idea.Cycle, Idea.Decline)
        private val STEP_NOUNS = setOf("step", "number", "tip", "rule", "مرحله", "مرحلهی", "قدم", "گام", "حرکت", "نکته", "قانون", "روش")
        private const val WEAK = 0.6f
        private const val MAX_USES = 3
        private const val TILES = 5
        private const val BARS = 4
        private const val CYCLE = 4
        private const val BLOCKS = 10
        private const val BLOCK_FEW = 3
        private const val DOTS = 24
        private const val PERCENT = 100.0
        private const val SLOW_STEP = 0.025
        private const val FAST_FROM = 0.99
        private const val FAST_TO = 0.33
        private const val GROW_STEP = 0.2
        private const val MIN_VALUE = 0.05
        private const val LEAD = 0.12
        private const val CLEAR_LEAD = 0.05
        private const val HEADING = 0.5
        const val MIN_GAP = 0.6
        private const val READ_BASE = 0.6
        private const val READ_PER_CHAR = 0.03
        private const val READ_MAX = 1.4
        private const val TOGETHER = 0.05
        private const val AVOID = 0.15f
        private val YEARS = 1000.0..2100.0

        private val TEXT_HEAVY = setOf(Primitive.BigWord, Primitive.Quote)
        private val STEPS = setOf(Primitive.Steps, Primitive.Flow, Primitive.Timeline, Primitive.Checklist)
        private val DRAINS = setOf(Idea.Drain, Idea.Fast, Idea.Slow, Idea.Grow, Idea.Decline)
        private val COMPANION_IDEAS = setOf(Idea.Slow, Idea.Fast, Idea.Warning, Idea.Success, Idea.Insight)
        private val LIKE = setOf("مثل", "like", "همچون", "انگار")
        private val COUNTERS = setOf(
            "one", "two", "three", "four", "five", "six", "first", "second", "third", "fourth", "fifth", "1", "2", "3", "4", "5",
            "اول", "دوم", "سوم", "چهارم", "پنجم", "ششم", "یک", "یکم",
        )
        private val ASKS = listOf("فالو", "follow", "subscribe", "سابسکرایب", "کامنت", "comment", "لایک", "like", "share", "شیر", "save", "سیو", "ذخیره")
        private val FAST_WORDS = setOf("زود", "زودتر", "سریع", "سریعتر", "fast", "faster", "quickly", "soon")
        private val DRAIN_VERBS = setOf("کم", "کمتر", "تموم", "تمام", "drains", "drain", "runs", "drops", "falls", "empties")
        private val METER_NOUNS = listOf("محدودیت", "مصرف", "سهمیه", "باتری", "بودجه", "limit", "usage", "quota", "battery", "budget")

        /** What may follow each main picture on its own stage (the ideas that work on it). */
        private val FOLLOWS: Map<Primitive, Set<Idea>> = mapOf(
            Primitive.TaskTiles to setOf(Idea.Routine, Idea.Success, Idea.Steps),
            Primitive.Checklist to setOf(Idea.Routine, Idea.Success, Idea.Steps),
            Primitive.Steps to setOf(Idea.Steps, Idea.Success),
            Primitive.Meter to DRAINS,
            Primitive.Ring to DRAINS,
            Primitive.Branches to setOf(Idea.Path, Idea.Think, Idea.Success),
            Primitive.Flow to setOf(Idea.Path, Idea.Think, Idea.Success),
            Primitive.Blocks to setOf(Idea.Portion, Idea.Rank),
            Primitive.Dots to setOf(Idea.Portion, Idea.Many),
            Primitive.Bars to setOf(Idea.Rank, Idea.Grow, Idea.Decline, Idea.Accumulate),
            Primitive.Ladder to setOf(Idea.Rank, Idea.Levels),
            Primitive.Tangle to setOf(Idea.Success, Idea.Tangle),
            Primitive.IconTiles to setOf(Idea.Uses, Idea.Traits),
            Primitive.Chips to setOf(Idea.Uses, Idea.Compare),
            Primitive.Profile to setOf(Idea.Persona),
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
