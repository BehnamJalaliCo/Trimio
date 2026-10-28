package io.trimio.engine.production

import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.assets.BeatGrid
import io.trimio.engine.assets.MusicMood
import io.trimio.engine.autopilot.Autopilot
import io.trimio.engine.autopilot.Workbench
import io.trimio.engine.motion.recipe.Sfx
import io.trimio.engine.motion.recipe.SfxKind
import io.trimio.engine.motion.score.AutoDirector
import io.trimio.engine.motion.score.BeatScore
import io.trimio.engine.motion.score.EditPlan
import io.trimio.engine.motion.score.NumberWords
import io.trimio.engine.motion.score.Score
import io.trimio.engine.motion.style.StyleBook
import kotlin.math.abs

/**
 * Applies the creator's [EditOverrides] to the director's production: the words (their text and
 * emphasis), the cut (pauses, tics, deleted words, cuts on the beat), the score (style, energy,
 * caption density, elements on and off, added elements) and the sound (music, its level, the
 * effects, the creator's hits). Deterministic: the same production and overrides always give the
 * same edit, so undo is applying an earlier state again.
 *
 * Every result goes through the quality gates as a draft does ([Workbench.gate]: on-screen
 * spelling, the critic) and the jury scores it ([Workbench.judge]); the creator's own text is
 * respected (the spelling gate warns, it does not rewrite).
 */
object Retouch {
    /**
     * The furthest [Cuts.snap] nudges a cut onto a beat (seconds). The editor's own snap — the one
     * [apply] runs whenever the music's grid is known — opens the window to [BeatGrid.halfBeat]:
     * a cut lands on a beat, not merely near one.
     */
    const val BEAT_SNAP = 0.08f
    const val MIN_MUSIC_DB = -32f
    const val MAX_MUSIC_DB = -6f

    /** The bed rises this much between words, above its level under them (the director's mix: -20 / -12). */
    private const val GAP_LIFT_DB = 8f

    /** A beat without an energy of its own moves at this (the compiler's middle). */
    const val DEFAULT_ENERGY = 0.6f

    /** A word's tail is never cut into by a beat snap: it keeps this much after it. */
    private const val WORD_TAIL_S = 0.03f

    /**
     * The edit with the overrides applied, and what the gates said about it. [mixed] is false when
     * only the picture was made ([apply] with `sound = false`): the soundtrack and the panel are
     * still the director's then.
     */
    class Result(val production: Autopilot.Production, val warnings: List<String>, val notes: List<String>, val mixed: Boolean = true)

    /** True when [base] can be changed in the editor (it kept its [Workbench], or its film's bench). */
    fun editable(base: Autopilot.Production): Boolean = base.bench != null || base.filmBench != null

    /**
     * [base] with [o] applied. The picture (words, cut, score, gates, compile) takes milliseconds;
     * the [sound] (mastering the mix, the jury hearing it) takes seconds on a long edit, so the
     * editor shows the picture first and asks for the sound after.
     */
    fun apply(base: Autopilot.Production, o: EditOverrides, sound: Boolean = true): Result {
        if (o.isEmpty) return Result(base, emptyList(), emptyList())
        base.filmBench?.let { return film(base, it, o, sound) }
        val bench = base.bench ?: error("this edit was made without its workbench and cannot be changed")
        val words = words(bench, o)
        val mood = mood(base, o)
        var edit = Cuts.of(base.edit, bench, o)
        var arrangement = bench.arrange(mood, edit, words)
        val onGrid = snapped(edit, arrangement?.grid, words.words, o.beatCuts)
        if (onGrid !== edit) {
            edit = onGrid
            arrangement = bench.arrange(mood, edit, words)
        }
        val planned = score(base, bench, o, words)
        val score = if (mood == null) planned.copy(bpm = null, beatOffset = 0f) else bench.gridded(planned, arrangement)
        val gated = bench.gate(score, words, edit, own = o.texts.map { it.text }, refused = refused(base, o))
        val compiled = gated.compiled.copy(sfx = cues(gated.compiled.sfx, o, words, edit))
        val notes = listOf("editor: ${summary(o)}") + gated.notes + gated.warnings.map { "spelling (creator): «$it» is not in the lexicon (kept)" }
        val picture = base.copy(
            compiled = compiled, score = gated.score, plan = base.plan.copy(music = mood), edit = edit,
            report = base.report + notes, source = Autopilot.Compiled(bench.compiler, gated.input),
        )
        if (!sound) return Result(picture, gated.warnings, gated.notes, mixed = false)
        val levels = o.musicDb?.let { db -> bench.levels.copy(musicUnderDb = db, musicGapDb = db + GAP_LIFT_DB) } ?: bench.levels
        val mix = bench.master(compiled, edit, words, mood, arrangement, levels)
        val panel = bench.judge(gated.copy(compiled = compiled), edit, words, mood, arrangement, mix, levels)
        return Result(picture.copy(soundtrack = mix, panel = panel ?: base.panel), gated.warnings, gated.notes)
    }

    /**
     * An explainer film with the creator's palette ([EditOverrides.style] naming a film palette), music, its level and the
     * whooshes; the film itself (its chapters and beats) is the director's.
     */
    private fun film(base: Autopilot.Production, bench: io.trimio.engine.autopilot.explainer.FilmBench, o: EditOverrides, sound: Boolean): Result {
        val mood = mood(base, o)
        val notes = listOf("editor: ${summary(o)}")
        val picture = bench.production(o.style, mood, base.report + notes, base.panel, sound = base.soundtrack)
        if (!sound) return Result(picture, emptyList(), notes, mixed = false)
        return Result(bench.production(o.style, mood, base.report + notes, base.panel, musicDb = o.musicDb, effects = o.effects ?: true), emptyList(), notes)
    }

    // ------------------------------------------------------------------ words

    /**
     * The transcript as the creator left it: their text (a span's first word carries it and runs
     * to the span's end; the others go blank), cut words blank, and their emphasis: an emphasised
     * word at full stress with its neighbours below the caption's highlight, a plain one at none.
     */
    internal fun words(bench: Workbench, o: EditOverrides): Transcript {
        val source = bench.transcript.words
        val out = source.toMutableList()
        o.emphasis.forEach { (i, on) ->
            if (i !in out.indices) return@forEach
            if (on) {
                for (j in (i - NEIGHBOURS)..(i + NEIGHBOURS)) {
                    if (j in out.indices && j != i && o.emphasis[j] != true) out[j] = out[j].copy(emphasis = minOf(out[j].emphasis, QUIET))
                }
            }
            out[i] = out[i].copy(emphasis = if (on) 1f else 0f)
        }
        for (r in o.texts) {
            val from = r.from.coerceIn(source.indices)
            val to = r.to.coerceIn(from, source.lastIndex)
            out[from] = out[from].copy(text = r.text, range = TimeRange(source[from].range.startMs, source[to].range.endMs))
            for (j in from + 1..to) out[j] = out[j].copy(text = "")
        }
        for (i in Cuts.removed(bench, o)) if (i in out.indices) out[i] = out[i].copy(text = "")
        return Transcript(bench.transcript.language, out)
    }

    private fun mood(base: Autopilot.Production, o: EditOverrides): MusicMood? = when (val id = o.music) {
        null -> base.plan.music
        EditOverrides.NO_MUSIC -> null
        else -> MusicMood.fromId(id) ?: base.plan.music
    }

    /** The director's beats the creator turned off, by what they show (the critic may offer the same one again). */
    private fun refused(base: Autopilot.Production, o: EditOverrides): (BeatScore) -> Boolean {
        val off = base.score.scenes.flatMapIndexed { k, s -> s.beats.filterIndexed { j, _ -> o.elements[key(k, j)] == false } }
        if (off.isEmpty()) return { false }
        return { b -> off.any { it.recipe == b.recipe && it.text == b.text && it.at == b.at && it.value == b.value } }
    }

    /** The edit's cues without the accents' effects when they are off, and the creator's hits on their words. */
    private fun cues(sfx: List<Sfx>, o: EditOverrides, words: Transcript, edit: EditPlan): List<Sfx> {
        val kept = if (o.effects == false) emptyList() else sfx
        val hits = o.hits.filter { it in words.words.indices && words.words[it].text.isNotBlank() }
            .map { Sfx(edit.toOutput(words.words[it].range.startMs / MS), SfxKind.Hit, HIT_GAIN) }
        return (kept + hits).sortedBy { it.at }
    }

    // ------------------------------------------------------------------------------ beat snap

    /**
     * The edit with its cuts on the music's grid: the real beat-snap — on whenever a grid is known
     * ([beatCuts] only turns it off), in a [BeatGrid.halfBeat] window. An animation's landing snaps
     * the same way at compile time ([BeatGrid.snapLanding]).
     */
    internal fun snapped(edit: EditPlan, grid: BeatGrid?, words: List<Word>, beatCuts: Boolean?): EditPlan =
        if (beatCuts == false || grid == null) edit else snapCuts(edit, grid, words)

    /**
     * Every cut between two body pieces moved onto the nearest beat of [grid]: the window is
     * [BeatGrid.halfBeat] — a cut lands on a beat, not merely near one — and the outgoing piece
     * runs on into the pause it was cut from, never onto a word in the gap (a deleted word stays
     * deleted) and never into its own last word. The pieces after it move with it.
     */
    internal fun snapCuts(edit: EditPlan, grid: BeatGrid, words: List<Word>): EditPlan {
        val body = edit.segments
        if (body.size < 2) return edit
        val window = grid.halfBeat
        val out = mutableListOf<EditPlan.Segment>()
        var at = body.first().outStart
        for ((k, s) in body.withIndex()) {
            var end = s.sourceEnd
            val next = body.getOrNull(k + 1)
            if (next != null) {
                val t = at + s.length
                val d = grid.nearestBeat(t.toDouble()) - t
                val lastWord = words.filter { it.range.startMs / MS in s.sourceStart..<s.sourceEnd }.maxOfOrNull { it.range.endMs / MS } ?: s.sourceStart
                val blocked = words.filter { it.range.startMs / MS in s.sourceEnd..<next.sourceStart }.minOfOrNull { it.range.startMs / MS } ?: next.sourceStart
                val longest = maxOf(s.sourceEnd, blocked)
                val shortest = minOf(s.sourceEnd, lastWord + WORD_TAIL_S)
                if (abs(d) <= window) end = (s.sourceEnd + d.toFloat()).coerceIn(shortest, longest)
            }
            out += EditPlan.Segment(s.sourceStart, end, at)
            at += end - s.sourceStart
        }
        val plan = EditPlan(out, edit.opening)
        return edit.outro?.let { o -> EditPlan(out, edit.opening, o.copy(sourceStart = out.last().sourceEnd)) } ?: plan
    }

    // ------------------------------------------------------------------ score

    /** The director's score with the creator's elements, style, energy, captions and words. */
    internal fun score(base: Autopilot.Production, bench: Workbench, o: EditOverrides, words: Transcript): Score {
        val director = base.score
        val texts = bench.transcript.words.map { it.text }
        val scenes = director.scenes.mapIndexed { k, s ->
            s.copy(beats = s.beats.filterIndexed { j, _ -> o.elements[key(k, j)] != false })
        }.toMutableList()
        fun add(word: Int, beat: BeatScore) {
            if (scenes.isEmpty()) return
            val k = sceneOf(director, word, words.words)
            scenes[k] = scenes[k].copy(beats = scenes[k].beats + beat)
        }
        suggestions(base, bench).forEachIndexed { i, s -> if (o.elements["d$i"] == true) add(s.at ?: 0, s) }
        o.added.forEachIndexed { i, a -> if (o.elements["a$i"] != false) add(a.from, added(a)) }
        val style = o.style?.let(StyleBook::of)?.takeIf { it.id != director.look }
        var score = director.copy(
            scenes = scenes,
            look = style?.id ?: director.look,
            accent = if (style != null) style.accents.firstOrNull() else director.accent,
            captions = director.captions.copy(
                maxWords = o.captions?.maxWords ?: director.captions.maxWords,
                place = style?.captions?.place ?: director.captions.place,
            ),
        )
        o.energy?.let { score = energised(score, it) }
        return uncut(worded(score, texts, o), texts, o.deleted)
    }

    /**
     * Words the creator cut are gone from the graphics that quote them, as from the sound and the
     * captions: a graphic's text without them (its own occurrence: the one around the word it lands
     * on, else the first), and a graphic left with nothing to say goes. Text that does not quote
     * the transcript word for word is left as written.
     */
    internal fun uncut(score: Score, said: List<String>, deleted: Set<Int>): Score {
        if (deleted.isEmpty()) return score
        val heard = said.map(::norm)
        fun cut(text: String?, at: Int?): String? {
            if (text == null) return null
            val tokens = text.split(' ').filter { it.isNotBlank() }
            val wanted = tokens.map(::norm)
            if (wanted.isEmpty() || wanted.size > heard.size) return text
            val starts = (0..heard.size - wanted.size).filter { s -> wanted.indices.all { j -> heard[s + j] == wanted[j] } }
            val s = starts.firstOrNull { at != null && at in it until it + wanted.size } ?: starts.firstOrNull() ?: return text
            return tokens.filterIndexed { j, _ -> s + j !in deleted }.joinToString(" ")
        }
        return score.copy(
            scenes = score.scenes.map { scene ->
                scene.copy(
                    beats = scene.beats.mapNotNull { b ->
                        val text = cut(b.text, b.at)
                        if (b.text != null && text.isNullOrEmpty()) return@mapNotNull null
                        val left = text.orEmpty().split(' ').map(::norm).toSet()
                        b.copy(text = text, emphasis = if (text == b.text) b.emphasis else b.emphasis.filter { norm(it) in left })
                    },
                )
            },
        )
    }

    /** Every beat moved by the same amount, so the piece's mean energy is [target] and its contrasts stay. */
    internal fun energised(score: Score, target: Float): Score {
        val beats = score.scenes.flatMap { it.beats }
        if (beats.isEmpty()) return score
        val shift = target - meanEnergy(score)
        return score.copy(scenes = score.scenes.map { s -> s.copy(beats = s.beats.map { b -> b.copy(energy = ((b.energy ?: DEFAULT_ENERGY) + shift).coerceIn(MIN_ENERGY, 1f)) }) })
    }

    fun meanEnergy(score: Score): Float {
        val beats = score.scenes.flatMap { it.beats }
        return if (beats.isEmpty()) DEFAULT_ENERGY else beats.map { it.energy ?: DEFAULT_ENERGY }.average().toFloat()
    }

    /** The creator's text and emphasis in the graphics that quote those words. */
    private fun worded(score: Score, said: List<String>, o: EditOverrides): Score {
        if (o.texts.isEmpty() && o.emphasis.isEmpty()) return score
        val swaps = o.texts.map { r -> said.subList(r.from.coerceIn(said.indices), (r.to + 1).coerceIn(r.from + 1, said.size)) to r.text }
        val marks = o.emphasis.filterKeys { it in said.indices }.map { (i, on) -> norm(o.texts.firstOrNull { i == it.from }?.text ?: said[i]) to on }
        fun text(s: String?) = s?.let { swaps.fold(it) { acc, (from, to) -> replaceSpan(acc, from, to) } }
        return score.copy(
            scenes = score.scenes.map { scene ->
                scene.copy(
                    beats = scene.beats.map { b ->
                        val t = text(b.text)
                        val tokens = t.orEmpty().split(' ').filter { it.isNotBlank() }
                        val marked = marks.fold(b.emphasis.map { e -> text(e) ?: e }) { acc, (n, on) ->
                            val token = tokens.firstOrNull { norm(it) == n }
                            when {
                                token == null -> acc
                                on -> if (acc.none { norm(it) == n }) acc + token else acc
                                else -> acc.filter { norm(it) != n }
                            }
                        }
                        b.copy(text = t, label = text(b.label), items = b.items.map { text(it) ?: it }, emphasis = marked)
                    },
                )
            },
        )
    }

    /** [s] with the run of tokens saying [span] replaced by [with]; untouched when it does not say it. */
    internal fun replaceSpan(s: String, span: List<String>, with: String): String {
        val tokens = s.split(' ')
        val wanted = span.map(::norm).filter { it.isNotEmpty() }
        if (wanted.isEmpty()) return s
        for (i in 0..tokens.size - wanted.size) {
            if (wanted.indices.all { j -> norm(tokens[i + j]) == wanted[j] }) {
                return (tokens.subList(0, i) + with + tokens.subList(i + wanted.size, tokens.size)).joinToString(" ")
            }
        }
        return s
    }

    private fun norm(s: String) = NumberWords.normalize(s)

    private fun added(a: AddedElement): BeatScore = when (a.kind) {
        ElementKind.Headline -> BeatScore(recipe = a.kind.recipe, at = a.from, until = a.to, text = a.text, place = "top", energy = DEFAULT_ENERGY)
        ElementKind.Impact -> BeatScore(recipe = a.kind.recipe, at = a.from, until = a.to, text = a.text, place = "center", energy = IMPACT_ENERGY)
        ElementKind.Counter -> {
            val n = NumberWords.findAll(a.text.split(' ')).firstOrNull()
            BeatScore(
                recipe = a.kind.recipe, at = a.from, until = a.to, place = "center", energy = IMPACT_ENERGY,
                value = n?.value?.toFloat() ?: 0f, decimals = n?.decimals, suffix = if (n?.percent == true) "٪" else "",
            )
        }
    }

    /** The director's beat [j] of scene [k]. */
    fun key(k: Int, j: Int) = "b$k.$j"

    /** The scene a word falls in: the last one starting at or before it. */
    fun sceneOf(score: Score, word: Int, words: List<Word>): Int {
        val at = words.getOrNull(word)?.range?.startMs?.div(MS) ?: 0f
        return score.scenes.indexOfLast { s ->
            when {
                s.from != null -> s.from!! <= word
                s.time != null -> s.time!! <= at
                else -> true
            }
        }.coerceAtLeast(0)
    }

    /**
     * The director's further suggestions: what the rules would put on each line the edit leaves
     * without a graphic (a number's counter, a headline, an icon), in order. Off until the creator
     * turns one on (`d<n>`).
     */
    fun suggestions(base: Autopilot.Production, bench: Workbench): List<BeatScore> {
        val words = bench.transcript.words
        if (words.isEmpty()) return emptyList()
        val norm = words.map { norm(it.text) }
        val covered = base.score.scenes.flatMap { s -> s.beats.mapNotNull { it.at } + listOfNotNull(s.from) }.toSet()
        val notes = mutableListOf<String>()
        return bench.lines.filter { l -> l.range.none { it in covered } }
            .flatMap { l -> AutoDirector.beats(words, norm, l.range, first = false, notes) }
            .filter { it.recipe != "comment" && it.at != null }
            .take(MAX_SUGGESTIONS)
    }

    private fun summary(o: EditOverrides): String = buildList {
        if (o.texts.isNotEmpty()) add("${o.texts.size} texts")
        if (o.emphasis.isNotEmpty()) add("${o.emphasis.size} emphasis")
        if (o.deleted.isNotEmpty()) add("${o.deleted.size} words cut")
        if (o.hits.isNotEmpty()) add("${o.hits.size} hits")
        o.style?.let { add("style $it") }
        o.energy?.let { add("energy $it") }
        o.captions?.let { add("captions ${it.name.lowercase()}") }
        o.music?.let { add("music $it") }
        o.musicDb?.let { add("music $it dB") }
        o.effects?.let { add("effects $it") }
        if (o.elements.isNotEmpty() || o.added.isNotEmpty()) add("${o.elements.size} element switches, ${o.added.size} added")
        o.silences?.let { add("silences $it") }
        o.fillers?.let { add("fillers $it") }
        o.beatCuts?.let { add("beat cuts $it") }
    }.joinToString(", ")

    private const val MS = 1000f
    private const val NEIGHBOURS = 3
    private const val QUIET = 0.5f
    private const val MIN_ENERGY = 0.05f
    private const val IMPACT_ENERGY = 0.9f
    private const val HIT_GAIN = 1f
    private const val MAX_SUGGESTIONS = 12
}
