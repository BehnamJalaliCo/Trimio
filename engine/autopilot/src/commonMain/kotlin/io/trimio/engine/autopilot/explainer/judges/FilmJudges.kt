package io.trimio.engine.autopilot.explainer.judges

import io.trimio.engine.autopilot.Finding
import io.trimio.engine.autopilot.RulesUnderstander
import io.trimio.engine.autopilot.Severity
import io.trimio.engine.autopilot.Verdict
import io.trimio.engine.autopilot.explainer.ArtDirector
import io.trimio.engine.autopilot.explainer.Chapters
import io.trimio.engine.autopilot.explainer.Clause
import io.trimio.engine.autopilot.explainer.Families
import io.trimio.engine.autopilot.explainer.FilmCheck
import io.trimio.engine.autopilot.explainer.Metaphors
import io.trimio.engine.autopilot.explainer.Narration
import io.trimio.engine.autopilot.explainer.RulesDirector
import io.trimio.engine.autopilot.explainer.judges.FilmJury.Companion.finding
import io.trimio.engine.autopilot.explainer.judges.FilmJury.Companion.verdict
import io.trimio.engine.motion.explainer.Beat
import io.trimio.engine.motion.explainer.Chapter
import io.trimio.engine.motion.explainer.ExplainerFilm
import io.trimio.engine.motion.explainer.FilmGrammar
import io.trimio.engine.motion.explainer.PaletteBook
import io.trimio.engine.motion.explainer.Primitive
import io.trimio.engine.motion.explainer.StageOp

/** Shared readings of a film for the judges. */
internal object Films {
    /** The clause a beat illustrates: the one whose words it carries, else the one it falls in. */
    fun clauseOf(n: Narration, b: Beat): Clause? =
        n.clauses.firstOrNull { it.text == b.line } ?: n.clauses.lastOrNull { it.start <= b.at + EDGE } ?: n.clauses.firstOrNull()

    /** The film's chapters as clause ranges (what the rules would rewrite one of). */
    fun plans(n: Narration, f: ExplainerFilm): List<Chapters.Plan> {
        val firsts = f.chapters.mapIndexed { i, ch -> if (i == 0) 0 else n.clauses.indexOfFirst { it.start >= ch.start - EDGE }.let { if (it < 0) n.clauses.size else it } }
        return f.chapters.indices.map { i ->
            val from = firsts[i].coerceAtMost(n.clauses.size - 1).coerceAtLeast(0)
            val to = ((firsts.getOrNull(i + 1) ?: n.clauses.size) - 1).coerceAtLeast(from)
            Chapters.Plan(from..to, f.chapters[i].title, null)
        }
    }

    fun creates(b: Beat) = b.op in FilmCheck.CREATES && b.primitive != null

    fun keys(s: String): List<String> = s.split(' ').map(Metaphors::bare).filter { it.isNotEmpty() }

    const val EDGE = 0.3
}

/**
 * Each picture says its sentence. A picture a sentence's idea calls for fits ([Metaphors]); a
 * picture whose words come from its sentence (or its chapter) is grounded; a picture that is
 * neither while the sentence plainly says something else is wrong. A picture that only prints its
 * long sentence is no metaphor, and a sentence with no picture at all is a hole.
 */
class MetaphorJudge : FilmJudge {
    override val name = "metaphor"
    override val dimension = "each picture says its sentence"

    override fun score(c: FilmJury.Context): Verdict {
        val n = c.narration
        val f = mutableListOf<Finding>()
        // The film's own chapter names count as said (an agenda of the subjects to come is grounded).
        val titles = c.film.chapters.flatMap { Films.keys(it.title) }
        for (ch in c.film.chapters) for (b in ch.beats.filter(Films::creates)) f += relevance(n, ch, b, titles)
        val shown = c.film.chapters.flatMap { it.beats }
        val holes = n.clauses.filter { cl -> shown.none { it.line == cl.text || it.at in cl.start - Films.EDGE..cl.end } }
        f += holes.take(MAX_HOLES).map { finding(it.start, Severity.Minor, "no picture for «${it.text.take(SHORT)}»", "give the sentence a beat on its stage") }
        return verdict(this, f)
    }

    private fun relevance(n: Narration, ch: Chapter, b: Beat, titles: List<String>): List<Finding> {
        val p = b.primitive ?: return emptyList()
        val clause = Films.clauseOf(n, b) ?: return emptyList()
        val keys = clause.words.map { n.keys[it] }
        val found = Metaphors.find(keys, clause.first, clause.read?.gist.orEmpty())
        val counted = p in FIGURES && io.trimio.engine.autopilot.Quantities.sense(clause.words.map { n.texts[it] }, clause.first).quantities.isNotEmpty()
        val fits = counted || found.any { it.strength >= WEAK && it.idea.pictures.any { (q, _) -> q == p } }
        val words = (listOf(b.content.text, b.content.caption) + b.content.items.map { it.label }).flatMap(Films::keys).filter { it.length >= MIN_STEM }
        val near = (n.clauses.filter { kotlin.math.abs(it.index - clause.index) <= 1 }.flatMap { it.words.map { i -> n.keys[i] } } + Films.keys(ch.title) + titles).map { it.take(STEM) }.toSet()
        val grounded = words.any { it.take(STEM) in near } || p in NEUTRAL
        val strong = found.firstOrNull { it.strength >= STRONG }
        val out = mutableListOf<Finding>()
        when {
            fits || (grounded && strong == null) -> Unit
            strong != null && !grounded -> out += finding(b.at, Severity.Major, "${picture(p)} for «${clause.text.take(SHORT)}», which says ${strong.idea.name.lowercase()}",
                "draw the idea: ${strong.idea.pictures.joinToString("/") { picture(it.first) }}")
            strong != null -> out += finding(b.at, Severity.Minor, "${picture(p)} only loosely fits «${clause.text.take(SHORT)}»", "a picture of ${strong.idea.name.lowercase()}")
            else -> out += finding(b.at, Severity.Minor, "${picture(p)} has nothing to do with «${clause.text.take(SHORT)}»", "a picture of what the sentence says")
        }
        val printed = Films.keys(b.content.text)
        if (p !in SAID_ON_PURPOSE && printed.size >= PRINTED_WORDS && printed.count { it in keys } * 10 >= printed.size * PRINTED_SHARE) {
            out += finding(b.at, Severity.Minor, "the picture prints the sentence «${b.content.text}»", "a metaphor, not the words")
        }
        return out
    }

    /** Chapters with a wrong picture or a hole, written again by the rules. */
    override fun revise(c: FilmJury.Context, verdict: Verdict): ExplainerFilm? {
        val bad = verdict.findings.filter { it.severity.cost > 0f }.map { it.at.toDouble() }
        if (bad.isEmpty() || news(c.film)) return null
        val plans = Films.plans(c.narration, c.film)
        val rules = RulesDirector()
        val chapters = c.film.chapters.mapIndexed { i, ch ->
            if (bad.any { it >= ch.start - Films.EDGE && it < ch.end }) rules.chapter(c.narration, c.film.art.seed, plans, i, c.film) else ch
        }
        return c.check.repair(c.film.copy(chapters = chapters)).takeIf { it != c.film }
    }

    private companion object {
        const val WEAK = 0.6f
        const val STRONG = 1.1f
        const val STEM = 4
        const val MIN_STEM = 3
        const val SHORT = 40
        const val MAX_HOLES = 4
        const val PRINTED_WORDS = 5
        const val PRINTED_SHARE = 7
        /** Pictures that carry the sentence's own words by design (a news headline, a note of terms). */
        val SAID_ON_PURPOSE = setOf(Primitive.Headline, Primitive.Annotation, Primitive.Bubble, Primitive.Quote)
        val FIGURES = setOf(Primitive.Stat, Primitive.Ring, Primitive.Meter, Primitive.Bars, Primitive.Dots, Primitive.Timeline)
        val NEUTRAL = setOf(Primitive.Headline, Primitive.Hero, Primitive.Bubble, Primitive.Annotation, Primitive.Calendar, Primitive.Stars, Primitive.Kicker, Primitive.BigWord,
            Primitive.Heading, Primitive.Note, Primitive.Stat, Primitive.Badge, Primitive.Quote)
    }
}

/**
 * The stage builds: elements come once and are worked on (ticked, filled, morphed, dimmed), a new
 * picture swaps or morphs the old one in place, never a clear and a fresh start inside a chapter,
 * never two of the same picture side by side, and the stage itself is sound (references,
 * crowding, ops that suit their pictures).
 */
class ContinuityJudge : FilmJudge {
    override val name = "continuity"
    override val dimension = "the stage builds, no hard cuts"

    override fun score(c: FilmJury.Context): Verdict {
        val f = mutableListOf<Finding>()
        var reuse = 0
        var total = 0
        for (ch in c.film.chapters) {
            val beats = ch.beats
            total += beats.size
            val reused = beats.count { it.op in REUSE }
            reuse += reused
            if (beats.size >= MIN_BEATS && reused == 0) f += finding(ch.start, Severity.Major, "«${ch.title}»: the stage never builds", "tick, fill, morph or highlight what is there")
            beats.indices.filter { hardCut(beats, it) }.forEach { k ->
                f += finding(beats[k].at, Severity.Minor, "a hard cut: ${beats[k].element} cleared and ${beats[k + 1].element} added", "morph or replace it in place")
            }
            val adds = beats.filter { it.op == StageOp.Add && it.primitive != null && it.primitive !in COMPANIONS }
            adds.zipWithNext().filter { (a, b) -> a.primitive == b.primitive }.forEach { (_, b) ->
                f += finding(b.at, Severity.Minor, "a second ${picture(b.primitive!!)} beside the first", "build on the one already there")
            }
        }
        if (total >= MIN_FILM && reuse * RATIO_DEN < total) f += finding(0.0, Severity.Minor, "few beats work on what is on stage ($reuse of $total)", "more ticks, fills, morphs")
        f += c.check.problems(c.film).filter { it.kind in STAGE }.take(MAX_STAGE).map { finding(it.at, Severity.Major, it.detail, "fix the stage") }
        return verdict(this, f)
    }

    /** Beat [k] clears an element and the next adds one drawn alike, at once: a cut where a morph would do. */
    private fun hardCut(beats: List<Beat>, k: Int): Boolean {
        val b = beats[k]
        val next = beats.getOrNull(k + 1) ?: return false
        if (b.op != StageOp.Clear || next.op != StageOp.Add || next.at - b.at >= CUT) return false
        val old = primitiveOf(beats, k, b.element) ?: return false
        return next.primitive?.let { Families.morphs(old, it) } == true
    }

    private fun primitiveOf(beats: List<Beat>, k: Int, id: String): Primitive? = beats.take(k).lastOrNull { it.element == id && it.primitive != null }?.primitive

    override fun revise(c: FilmJury.Context, verdict: Verdict): ExplainerFilm? {
        val chapters = c.film.chapters.map { ch -> builds(merged(inPlace(ch))) }
        return c.check.repair(c.film.copy(chapters = chapters)).takeIf { it != c.film }
    }

    /** A clear followed by an add: the add replaces (or morphs) the cleared element in place. */
    private fun inPlace(ch: Chapter): Chapter {
        val out = mutableListOf<Beat>()
        val renamed = mutableMapOf<String, String>()
        var k = 0
        while (k < ch.beats.size) {
            val b = ch.beats[k]
            val next = ch.beats.getOrNull(k + 1)
            val cut = next != null && next.op == StageOp.Add && next.at - b.at < CUT
            if (b.op == StageOp.Clear && cut) {
                val old = primitiveOf(ch.beats, k, b.element)
                val op = if (old != null && next!!.primitive != null && Families.morphs(old, next.primitive!!)) StageOp.Morph else StageOp.Replace
                out += next!!.copy(op = op, element = b.element, at = b.at)
                renamed[next.element] = b.element
                k += 2
            } else {
                out += b.copy(element = renamed[b.element] ?: b.element)
                k++
            }
        }
        return ch.copy(beats = out)
    }

    /** A second add of the same picture becomes more of the first (built in, or highlighted). */
    private fun merged(ch: Chapter): Chapter {
        var last: Beat? = null
        val renamed = mutableMapOf<String, String>()
        val beats = ch.beats.map { b ->
            val prev = last
            val same = prev != null && b.op == StageOp.Add && b.primitive == prev.primitive && main(b)
            val out = when {
                same && FilmCheck.fits(StageOp.Build, b.primitive!!) && b.content.items.isNotEmpty() -> b.copy(op = StageOp.Build, element = prev!!.element, primitive = null)
                same -> b.copy(op = StageOp.Highlight, element = prev!!.element, primitive = null)
                else -> b.copy(element = renamed[b.element] ?: b.element)
            }
            if (same) renamed[b.element] = prev!!.element
            if (b.op == StageOp.Add && b.primitive !in COMPANIONS && !same) last = b
            out
        }
        return ch.copy(beats = beats)
    }

    private fun main(b: Beat) = b.primitive !in COMPANIONS

    /** A chapter that never builds gets a highlight on its main picture when its last sentence is said. */
    private fun builds(ch: Chapter): Chapter {
        if (ch.beats.size < MIN_BEATS || ch.beats.any { it.op in REUSE }) return ch
        val main = ch.beats.firstOrNull { it.op == StageOp.Add && it.primitive !in COMPANIONS } ?: return ch
        val last = ch.beats.last()
        val at = (last.at + ch.end) / 2
        if (ch.end - at < MIN_TAIL) return ch
        return ch.copy(beats = ch.beats + Beat(at, ch.end, last.line, StageOp.Highlight, main.element, metaphor = "the point lands on what is already there"))
    }

    private companion object {
        val REUSE = setOf(StageOp.Build, StageOp.Check, StageOp.Fill, StageOp.Highlight, StageOp.Dim, StageOp.Morph, StageOp.Replace)
        val COMPANIONS = setOf(Primitive.Kicker, Primitive.Note, Primitive.Clock, Primitive.Mark, Primitive.Badge, Primitive.Icon, Primitive.BigWord, Primitive.Quote, Primitive.Heading)
        val STAGE = setOf(FilmCheck.Kind.Reference, FilmCheck.Kind.Crowded, FilmCheck.Kind.Op)
        const val MIN_BEATS = 3
        const val MIN_FILM = 6
        const val RATIO_DEN = 5
        const val CUT = 1.5
        const val MAX_STAGE = 6
        const val MIN_TAIL = 0.6
    }
}

/**
 * The whole vocabulary, not one device: enough different pictures for the film's length, never
 * the same picture three times running, chapters that do not open alike, and a look that is not
 * the look of a film already made of the same narration.
 */
class VarietyJudge : FilmJudge {
    override val name = "variety"
    override val dimension = "a mix of pictures and a look of its own"

    override fun score(c: FilmJury.Context): Verdict {
        val f = mutableListOf<Finding>()
        val made = made(c.film)
        val want = if (news(c.film)) minOf(NEWS_WANT, made.size) else minOf(WANT_MAX, WANT_BASE + c.film.chapters.size, (made.size * SHARE).toInt())
        val distinct = made.map { it.second.primitive }.distinct().size
        if (distinct < want) f += finding(0.0, if (want - distinct >= 3) Severity.Major else Severity.Minor, "$distinct kinds of picture for ${made.size} pictures", "draw on more of the vocabulary")
        runs(made).forEach { (at, p) -> f += finding(at, Severity.Major, "${picture(p)} three times in a row", "another picture of the same idea") }
        val openings = c.film.chapters.map { ch -> ch.beats.firstOrNull(Films::creates)?.primitive }
        if (!news(c.film)) openings.windowed(3).filter { w -> w[0] != null && w.all { it == w[0] } }.forEach { f += finding(0.0, Severity.Minor,
            "three chapters open with ${picture(it[0]!!)}", "open one differently") }
        val art = c.film.art
        for (o in c.others) {
            when {
                o.palette == art.palette && o.layout == art.layout -> f += finding(0.0, Severity.Major, "the same look as an earlier film (${art.palette}, ${art.layout})",
                    "another palette and layout")
                o.palette == art.palette -> f += finding(0.0, Severity.Minor, "the same palette as an earlier film (${art.palette})", "another palette")
            }
        }
        return verdict(this, f)
    }

    /** Every picture put on stage, in order: (time, beat). */
    private fun made(f: ExplainerFilm): List<Pair<Double, Beat>> = f.chapters.flatMap { ch -> ch.beats.filter(Films::creates).map { it.at to it } }

    private fun runs(made: List<Pair<Double, Beat>>): List<Pair<Double, Primitive>> =
        made.windowed(3).filter { w -> w.all { it.second.primitive == w[0].second.primitive } }.map { it[1].first to it[1].second.primitive!! }.distinctBy { it.first }

    override fun revise(c: FilmJury.Context, verdict: Verdict): ExplainerFilm? {
        var film = c.film
        // The middle of a run of three takes another picture of the same idea.
        val middles = runs(made(film)).map { it.first }.toSet()
        if (middles.isNotEmpty()) {
            film = film.copy(chapters = film.chapters.map { ch ->
                ch.copy(beats = ch.beats.map { b -> if (Films.creates(b) && b.at in middles) b.copy(primitive = Metaphors.alternatives(b.primitive!!).firstOrNull() ?: b.primitive) else b })
            })
        }
        // A look already used: the next seed's look that differs.
        if (c.others.any { it.palette == film.art.palette }) {
            val used = c.others.map { it.palette }.toSet()
            val next = (1..LOOKS).asSequence().map { k -> ArtDirector.choose(c.narration, film.art.seed + k * LOOK_STRIDE) }.firstOrNull { it.palette !in used }
            if (next != null) film = film.copy(art = next.copy(seed = film.art.seed))
        }
        return c.check.repair(film).takeIf { it != c.film }
    }

    private companion object {
        const val WANT_BASE = 2
        const val WANT_MAX = 7
        const val SHARE = 0.7
        const val NEWS_WANT = 4
        const val LOOKS = 24
        const val LOOK_STRIDE = 7919L
    }
}

/**
 * Every beat on screen long enough to read (more words, more time), on its own words (not long
 * before its sentence, not long after), chapters long enough to land, and the clock sound.
 */
class TimingJudge : FilmJudge {
    override val name = "timing"
    override val dimension = "readable, on the words"

    override fun score(c: FilmJury.Context): Verdict {
        val n = c.narration
        val f = mutableListOf<Finding>()
        for (ch in c.film.chapters) {
            if (ch.end - ch.start < (if (news(c.film)) MIN_SCENE else MIN_CHAPTER)) f += finding(ch.start, Severity.Minor, "chapter «${ch.title}» lasts ${fmt(ch.end - ch.start)}s",
                "join it to a neighbour")
            for (b in ch.beats) {
                // A news scene's pieces stay up together (the headline under the rising hero): only the stagger is judged there.
                val need = if (news(c.film)) MIN_STEP else need(b)
                if (b.until - b.at < need * SHORT && b.op != StageOp.Clear && b.op != StageOp.Dim) {
                    f += finding(b.at, Severity.Minor, "${b.op.name.lowercase()} ${b.element} on screen ${fmt(b.until - b.at)}s", "give it ${fmt(need)}s")
                }
                val clause = Films.clauseOf(n, b) ?: continue
                if (b.at < clause.start - EARLY) f += finding(b.at, Severity.Minor, "${b.element} ${fmt(clause.start - b.at)}s before its words", "land it on its words")
                if (b.at > clause.end + LATE) f += finding(b.at, Severity.Minor, "${b.element} ${fmt(b.at - clause.end)}s after its words", "land it on its words")
            }
        }
        f += c.check.problems(c.film).filter { it.kind == FilmCheck.Kind.Time }.take(MAX_TIME).map { finding(it.at, Severity.Major, it.detail, "times inside the narration, in order") }
        return verdict(this, f.take(MAX_FINDINGS))
    }

    /** Seconds a beat needs: more for more words. */
    private fun need(b: Beat): Double {
        val chars = b.content.text.length + b.content.caption.length + b.content.items.sumOf { it.label.length }
        return if (Films.creates(b)) (BASE + PER_CHAR * chars).coerceAtMost(MAX_NEED) else MIN_STEP
    }

    /** Beats too short to read push the next one later (within the chapter); then the clock is repaired. */
    override fun revise(c: FilmJury.Context, verdict: Verdict): ExplainerFilm? {
        val chapters = c.film.chapters.map { ch ->
            // A beat far from its words goes back to them.
            val beats = ch.beats.map { b ->
                val clause = Films.clauseOf(c.narration, b)
                val off = clause != null && (b.at < clause.start - EARLY || b.at > clause.end + LATE)
                if (off) b.copy(at = (clause!!.start - LEAD).coerceIn(ch.start, ch.end - MIN_STEP)) else b
            }.sortedBy { it.at }.toMutableList()
            for (k in 0 until beats.size - 1) {
                val b = beats[k]
                val want = b.at + need(b) * SHORT
                if (beats[k + 1].at < want && want < ch.end - MIN_STEP) beats[k + 1] = beats[k + 1].copy(at = want)
            }
            ch.copy(beats = beats)
        }
        return c.check.repair(c.film.copy(chapters = chapters)).takeIf { it != c.film }
    }

    private fun fmt(v: Double) = (kotlin.math.round(v * 10) / 10).toString()

    private companion object {
        const val BASE = 0.55
        const val PER_CHAR = 0.03
        const val MAX_NEED = 1.4
        const val MIN_STEP = 0.35
        const val SHORT = 0.7
        const val EARLY = 0.8
        const val LEAD = 0.12
        const val LATE = 1.2
        const val MIN_CHAPTER = 2.5
        const val MIN_SCENE = 1.5
        const val MAX_TIME = 6
        const val MAX_FINDINGS = 12
    }
}

/**
 * The words on screen: a Persian film writes Persian (names said in Latin excepted) with Persian
 * digits, every word heard, briefed or known to the lexicon, no unsaid company, nothing too long.
 */
class ScriptJudge : FilmJudge {
    override val name = "spelling"
    override val dimension = "words on screen"

    override fun score(c: FilmJury.Context): Verdict {
        val problems = c.check.problems(c.film)
        val f = problems.filter { it.kind in MAJOR }.distinctBy { it.detail }.take(MAX).map { finding(it.at, Severity.Major, it.detail, "the spoken spelling, Persian script, or leave it out") } +
            problems.filter { it.kind in MINOR }.distinctBy { it.detail }.take(MAX).map { finding(it.at, Severity.Minor, it.detail, "shorter, and in words") }
        val digits = if (c.narration.persian) c.film.captions.filter { s -> s.text.any { it in '0'..'9' } }.take(MAX).map { finding(it.start, Severity.Minor,
            "Latin digits in caption «${it.text}»", "Persian digits") } else emptyList()
        return verdict(this, f + digits)
    }

    override fun revise(c: FilmJury.Context, verdict: Verdict): ExplainerFilm? {
        val repaired = c.check.repair(c.film)
        val captions = if (c.narration.persian) repaired.captions.map { it.copy(text = io.trimio.core.model.text.Numerals.toPersian(it.text)) } else repaired.captions
        return repaired.copy(captions = captions).takeIf { it != c.film }
    }

    private companion object {
        val MAJOR = setOf(FilmCheck.Kind.Script, FilmCheck.Kind.Spelling, FilmCheck.Kind.Brand)
        val MINOR = setOf(FilmCheck.Kind.Long, FilmCheck.Kind.Empty, FilmCheck.Kind.Heading, FilmCheck.Kind.Value)
        const val MAX = 8
    }
}

/**
 * What the creator asked for, and what the film is for: the brief's look (dark or light), no
 * captions when asked, the narration's own language, a subject per chapter (not one long chapter
 * nor a chapter per sentence), chapters that do not share an accent with their neighbour.
 */
class FilmBriefJudge : FilmJudge {
    override val name = "brief"
    override val dimension = "the brief and the film's job"

    override fun score(c: FilmJury.Context): Verdict {
        val f = mutableListOf<Finding>()
        val brief = RulesUnderstander.brief(c.narration.prompt)
        val film = c.film
        if (brief.captions == "none" && film.captions.isNotEmpty()) f += finding(0.0, Severity.Major, "captions although the brief asks for none", "no captions")
        val dark = PaletteBook.named(film.art.palette).dark
        if (brief.look == "noir" && !dark) f += finding(0.0, Severity.Major, "a light palette for a dark brief", "a dark palette")
        if (brief.look in setOf("paper", "lumen") && dark) f += finding(0.0, Severity.Major, "a dark palette for a light brief", "a light palette")
        if (film.language != c.narration.language) f += finding(0.0, Severity.Critical, "the film is in ${film.language}, the narration in ${c.narration.language}", "the narration's language")
        val least = (c.narration.duration / SECONDS_PER_SUBJECT).toInt()
        if (film.chapters.size < least) f += finding(0.0, Severity.Minor, "${film.chapters.size} chapters for ${c.narration.duration.toInt()}s", "a chapter per subject")
        film.chapters.filter { it.end - it.start > LONGEST }.forEach { f += finding(it.start, Severity.Minor, "«${it.title}» runs ${(it.end - it.start).toInt()}s",
            "split it where the subject turns") }
        c.check.problems(film).filter { it.kind == FilmCheck.Kind.Accent }.forEach { f += finding(it.at, Severity.Minor, it.detail, "another accent") }
        return verdict(this, f)
    }

    override fun revise(c: FilmJury.Context, verdict: Verdict): ExplainerFilm? {
        val brief = RulesUnderstander.brief(c.narration.prompt)
        var film = c.film.copy(language = c.narration.language)
        if (brief.captions == "none") film = film.copy(captions = emptyList(), art = film.art.copy(captionStyle = ArtDirector.NO_CAPTIONS))
        val want = when (brief.look) {
            "noir" -> ArtDirector.DARK
            "paper", "lumen" -> ArtDirector.LIGHT
            else -> null
        }
        if (want != null && want.isNotEmpty() && film.art.palette !in want) film = film.copy(art = film.art.copy(palette = want[(film.art.seed.mod(want.size.toLong())).toInt()]))
        // Too few chapters: the rules' own cut, when it finds more subjects.
        val least = (c.narration.duration / SECONDS_PER_SUBJECT).toInt()
        if (film.chapters.size < least && !news(film)) {
            val plans = Chapters.find(c.narration)
            if (plans.size > film.chapters.size) film = RulesDirector().diagram(c.narration, film.art.seed, plans).copy(art = film.art, captions = film.captions)
        }
        return c.check.repair(film).takeIf { it != c.film }
    }

    private companion object {
        const val SECONDS_PER_SUBJECT = 30.0
        const val LONGEST = 32.0
    }
}

/** Whether [f] is an illustrated news film (fast scenes, not a building stage). */
internal fun news(f: ExplainerFilm) = FilmGrammar.named(f.art.grammar) == FilmGrammar.IllustratedNews

/** A picture's name as films write it ("task-tiles"). */
internal fun picture(p: Primitive): String = io.trimio.engine.autopilot.explainer.Drafts.serial(p)
