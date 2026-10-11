package io.trimio.engine.autopilot

import io.trimio.core.model.transcript.Word
import io.trimio.engine.autopilot.AssetReading.Role
import io.trimio.engine.autopilot.AssetReading.Where
import io.trimio.engine.motion.recipe.Recipes
import io.trimio.engine.motion.score.BeatScore
import io.trimio.engine.motion.score.NumberWords
import io.trimio.engine.motion.score.SceneScore
import io.trimio.engine.motion.score.Score
import io.trimio.engine.motion.score.Words

/**
 * Puts the creator's own media and the brief's glossary into a planned score.
 *
 * Media: each attached asset ([AssetReading]) goes to the line that talks about it — the brief's
 * cue words first («وقتی گفتم سرم»), else the line whose words overlap its keywords most (same
 * word, the same word with a suffix, the same name in the other script by its consonants, or the
 * same concept), once, and never two assets on one line. One that matches nothing goes where the
 * brief says (the start, the end), a logo to the call to action, anything else to its most relevant
 * line. Its form follows what it is: a logo the brand-mark tile over the speaker; a product photo a
 * premium card and a screenshot a device or browser frame, on a stage of their own for that line;
 * a clip a full-frame cut-in that keeps the voice, or a picture-in-picture card over the speaker.
 * Over the speaker, graphics go above the head (the compiler keeps them off the face).
 *
 * Glossary: a site said aloud gets the address-bar card spelled as the brief writes it, and when
 * the brief asks for terms to be explained («توضیح بده») a term it defines gets a lower third.
 */
internal class UserMedia(private val assets: List<UserAsset>, private val readings: List<AssetReading>, brief: String) {

    private val glossary = Glossary.read(brief)
    private val byId = assets.associateBy { it.id }

    /** One asset's moment: its line, the word it lands on, the recipe and why. */
    data class Placement(val reading: AssetReading, val line: Int, val at: Int, val recipe: String, val why: String)

    private class Hit(val at: Int, val score: Float, val why: String)

    /** [score] with every asset placed and the glossary shown; [words] on the output clock, [lines] over them. */
    fun apply(score: Score, words: List<Word>, lines: List<Lines.Line>, seed: Long, log: (String) -> Unit = {}): Score {
        if (words.isEmpty() || lines.isEmpty()) return score
        var s = score
        // Later moments first: splitting a scene never moves the word indices of an earlier one.
        for (p in place(words, lines, score).sortedByDescending { it.at }) {
            s = put(s, p, words, lines, seed)
            log("media: ${p.reading.id} (${p.reading.role.name.lowercase()}) → ${p.recipe} on line ${p.line} at «${words[p.at].text}» — ${p.why}")
        }
        s = sites(s, words, lines, log)
        return definitions(s, words, lines, log)
    }

    /** Where each asset goes and in what form. */
    fun place(words: List<Word>, lines: List<Lines.Line>, score: Score): List<Placement> {
        val norm = words.map { key(it.text) }
        val hits = readings.flatMap { r -> lines.indices.mapNotNull { k -> hit(r, lines[k], norm)?.let { Triple(r, k, it) } } }
        val out = mutableListOf<Placement>()
        val used = mutableSetOf<Int>()
        for ((r, k, h) in hits.filter { it.third.score >= MATCH }.sortedWith(compareByDescending<Triple<AssetReading, Int, Hit>> { it.third.score }.thenBy { it.second })) {
            if (out.any { it.reading.id == r.id } || k in used) continue
            out += Placement(r, k, h.at, recipe(r, lines[k], words), h.why)
            used += k
        }
        val cta = ctaLine(score, lines)
        for (r in readings.filter { r -> out.none { it.reading.id == r.id } }) {
            val (k, why) = fallback(r, lines, cta, used, hits.filter { it.first.id == r.id })
            out += Placement(r, k, lines[k].first, recipe(r, lines[k], words), why)
            used += k
        }
        return out
    }

    // ------------------------------------------------------------------ matching

    private fun hit(r: AssetReading, line: Lines.Line, norm: List<String>): Hit? {
        r.cue.firstNotNullOfOrNull { c -> find(c, line, norm)?.let { Hit(it, CUE, "the brief: «$c»") } }?.let { return it }
        val keys = r.keywords.flatMap { it.split(' ') }.map(::key).filter { it.length >= 2 && Words.isContent(it) }.distinct()
        var total = 0f
        var best = 0f
        var at = -1
        for (i in line.range) {
            if (!Words.isContent(norm[i])) continue
            val s = keys.maxOfOrNull { similar(it, norm[i]) } ?: 0f
            total += s
            if (s > best) {
                best = s
                at = i
            }
        }
        return if (at < 0) null else Hit(at, total, "says «${norm[at]}»")
    }

    /** Where [cue] (one to three words) is said in [line]. */
    private fun find(cue: String, line: Lines.Line, norm: List<String>): Int? {
        val c = cue.split(' ').map(::key).filter { it.isNotEmpty() }
        if (c.isEmpty()) return null
        return line.range.firstOrNull { i -> i + c.size - 1 <= line.last && c.indices.all { j -> similar(c[j], norm[i + j]) >= CLOSE } }
    }

    /** No line matched: where the brief says, a logo at the call to action, else the most relevant free line. */
    private fun fallback(r: AssetReading, lines: List<Lines.Line>, cta: Int, used: Set<Int>, hits: List<Triple<AssetReading, Int, Hit>>): Pair<Int, String> = when {
        r.where == Where.Start -> 0 to "the brief: at the start"
        r.where == Where.End -> cta to "the brief: at the end"
        r.role == Role.Logo -> cta to "a logo signs the call to action"
        else -> hits.filter { it.second !in used }.maxByOrNull { it.third.score }?.let { it.second to "closest line" }
            ?: lines.indices.filter { it !in used && it != cta && it != 0 }.minByOrNull { kotlin.math.abs(it - lines.size / 2) }?.let { it to "the middle of the piece" }
            ?: (lines.size / 2 to "the middle of the piece")
    }

    private fun recipe(r: AssetReading, line: Lines.Line, words: List<Word>): String {
        val video = byId[r.id]?.kind == UserAsset.Kind.Video
        return when {
            r.role == Role.Logo -> "logo-mark"
            video && (r.inset || r.role == Role.Screenshot) -> "pip"
            // A cut-in when the line gives it time (picture-in-picture when it cannot have a stage, see [put]).
            video -> if (seconds(words, line.first, line.last) < MIN_CUT_IN) "pip" else "b-roll"
            r.role == Role.Screenshot -> "screenshot"
            else -> "product"
        }
    }

    // ------------------------------------------------------------------ placing

    private fun put(s: Score, p: Placement, words: List<Word>, lines: List<Lines.Line>, seed: Long): Score {
        val asset = byId[p.reading.id] ?: return s
        val beat = beatFor(p, asset, words, lines[p.line])
        if (p.recipe !in STAGED) return overlay(s, p.at, lines[p.line], beat)
        return stage(s, p, beat, words, lines, seed) ?: overlay(s, p.at, lines[p.line], if (p.recipe == "b-roll") beat.copy(recipe = "pip") else beat)
    }

    private fun beatFor(p: Placement, asset: UserAsset, words: List<Word>, line: Lines.Line): BeatScore {
        val said = seconds(words, p.at, line.last) + LINGER
        val clip = asset.duration ?: said
        val base = BeatScore(recipe = p.recipe, at = p.at, media = asset.id, aspect = asset.aspect)
        return when (p.recipe) {
            "logo-mark" -> base.copy(label = p.reading.brand.ifBlank { null }, mark = if (p.reading.light) "dark" else null, hold = LOGO_HOLD)
            "product" -> base.copy(label = label(words, p.at, line), hold = maxOf(CARD_HOLD, said))
            "screenshot" -> base.copy(label = glossary.sites.firstOrNull(), hold = maxOf(CARD_HOLD, said))
            "b-roll" -> base.copy(hold = minOf(clip, said).coerceAtLeast(MIN_CUT_IN))
            else -> base.copy(hold = minOf(clip, maxOf(PIP_HOLD, said)))
        }
    }

    /** The thing named in the speaker's words, from where the asset lands: up to three content words. */
    private fun label(words: List<Word>, at: Int, line: Lines.Line): String? {
        val from = (at..line.last).firstOrNull { Words.isContent(words[it].text) } ?: return null
        return (from..minOf(line.last, from + 2)).takeWhile { it == from || Words.isContent(words[it].text) }
            .joinToString(" ") { words[it].text.trimEnd('.', '،', ',', '!', '?', '؟', ':') }.ifBlank { null }
    }

    /** Over the speaker (or the stage already there): the line's own text treatments make room, the asset goes above the head. */
    private fun overlay(s: Score, at: Int, line: Lines.Line, beat: BeatScore): Score {
        val k = sceneOf(s, at) ?: return s
        val sc = s.scenes[k]
        val kept = sc.beats.filterNot { b -> b.at in line.range && b.recipe in REPLACEABLE }
        val place = if (sc.bg in STAGES && kept.none { it.at in line.range }) "center" else "top"
        return s.withScene(k, sc.copy(beats = kept + beat.copy(place = place)))
    }

    /**
     * A stage for the line (a scene of its own, cut back to the speaker after it) holding only the
     * asset. A figure said later in the line keeps its own screen: the stage ends where it lands
     * (when that still leaves the asset its time). Null when the line opens the piece, or holds the
     * ask or a figure the asset cannot make room for.
     */
    private fun stage(s: Score, p: Placement, beat: BeatScore, words: List<Word>, lines: List<Lines.Line>, seed: Long): Score? {
        val line = lines[p.line]
        val first = line.first
        val keep = s.scenes.flatMap { it.beats }.filter { it.recipe in KEEP }
        val natural = if (beat.recipe == "b-roll") clipEnd(words, p.at, line, beat.hold ?: 0f) else line.last + 1
        // A figure later in the line closes the stage early, if the asset is still up long enough.
        val figure = keep.mapNotNull { it.at }.filter { it in (p.at + 1) until natural }.minOrNull()
            ?.takeIf { start(words, it) - start(words, p.at) >= (Recipes.named(beat.recipe)?.minHold ?: MIN_CUT_IN) }
        val end = figure ?: natural
        val held = keep.any { b ->
            val at = b.at ?: return@any false
            at in first until end || (at < first && start(words, at) + (b.hold ?: 0f) > start(words, first) - HELD_SLACK)
        }
        if (p.line == 0 || held || sceneOf(s, first) == null) return null
        var t = splitAt(s, first)
        if (end <= words.lastIndex) t = splitAt(t, end)
        val k = t.scenes.indexOfFirst { it.from == first }.takeIf { it >= 0 } ?: return null
        val into = if (beat.recipe == "b-roll") "cut" else Rng(seed * PRIME + p.at).pick(listOf("zoom", "whip", "flash"))
        val hold = figure?.let { minOf(beat.hold ?: 0f, start(words, it) - start(words, p.at)) } ?: beat.hold
        t = t.withScene(k, t.scenes[k].copy(bg = BG.getValue(beat.recipe), camera = null, transition = into, beats = listOf(beat.copy(place = "center", hold = hold))))
        t.scenes.getOrNull(k + 1)?.takeIf { it.from == end && it.transition == null }?.let { t = t.withScene(k + 1, it.copy(transition = if (beat.recipe == "b-roll") "cut" else "whip")) }
        return t
    }

    /** The first word after a clip of [length] seconds landing at [at] runs out (the line's end at most). */
    private fun clipEnd(words: List<Word>, at: Int, line: Lines.Line, length: Float): Int {
        val until = start(words, at) + length
        return ((at + 1)..line.last).firstOrNull { start(words, it) >= until - CLIP_SLACK } ?: (line.last + 1)
    }

    /** [s] with a scene starting exactly at word [i]: the scene around it is cut in two, its beats by where they land. */
    private fun splitAt(s: Score, i: Int): Score {
        val k = s.scenes.indexOfLast { it.from != null && it.from!! <= i }
        if (k < 0 || s.scenes[k].from == i) return s
        val sc = s.scenes[k]
        val (moved, kept) = sc.beats.partition { b -> (b.at ?: -1) >= i }
        val tail = SceneScore(from = i, bg = sc.bg, camera = sc.camera, beats = moved)
        return s.copy(scenes = s.scenes.take(k) + sc.copy(beats = kept) + tail + s.scenes.drop(k + 1))
    }

    // ------------------------------------------------------------------ glossary

    /** The address-bar card where a site is first said, spelled as the brief writes it. */
    private fun sites(score: Score, words: List<Word>, lines: List<Lines.Line>, log: (String) -> Unit): Score {
        var s = score
        val done = mutableSetOf<String>()
        for (i in words.indices) {
            val domain = glossary.site(words[i].text)?.takeIf { done.add(it.lowercase()) } ?: continue
            val line = lines.firstOrNull { i in it.range } ?: continue
            val k = sceneOf(s, i) ?: continue
            val sc = s.scenes[k]
            val kept = sc.beats.filterNot { b -> b.at in line.range && b.recipe in TEXT }
            val beat = BeatScore("url", at = i, text = domain, brand = domain.substringBefore('.'), place = "top", hold = URL_HOLD)
            s = s.withScene(k, sc.copy(beats = kept + beat))
            log("glossary: the site $domain at «${words[i].text}»")
        }
        return s
    }

    /**
     * A lower third with the brief's meaning of a term where it is first said, when the brief asks for
     * explanations: the meaning in the piece's language is the line (it sets the reading direction), the term its tag.
     */
    private fun definitions(score: Score, words: List<Word>, lines: List<Lines.Line>, log: (String) -> Unit): Score {
        if (!glossary.explain) return score
        var s = score
        for ((term, meaning) in glossary.terms) {
            if (meaning == null) continue
            val t = term.split(' ').map(::key)
            val i = words.indices.firstOrNull { i -> t.indices.all { j -> words.getOrNull(i + j)?.let { key(it.text) } == t[j] } } ?: continue
            val k = sceneOf(s, i) ?: continue
            if (lines.none { i in it.range }) continue
            s = s.withScene(k, s.scenes[k].let { it.copy(beats = it.beats + BeatScore("lower-third", at = i, text = meaning, label = term, hold = TERM_HOLD)) })
            log("glossary: $term explained «$meaning»")
        }
        return s
    }

    // ------------------------------------------------------------------ helpers

    private fun ctaLine(score: Score, lines: List<Lines.Line>): Int {
        val at = score.scenes.firstNotNullOfOrNull { sc -> sc.beats.firstOrNull { it.recipe in ASKS }?.let { b -> b.at ?: sc.from } }
        return at?.let { a -> lines.indexOfFirst { a in it.range } }?.takeIf { it >= 0 } ?: lines.lastIndex
    }

    private fun sceneOf(s: Score, i: Int): Int? = s.scenes.indexOfLast { (it.from ?: 0) <= i }.takeIf { it >= 0 }

    private fun Score.withScene(k: Int, scene: SceneScore) = copy(scenes = scenes.mapIndexed { i, sc -> if (i == k) scene else sc })

    companion object {
        private const val CUE = 10f
        private const val MATCH = 2f
        private const val CLOSE = 1.5f
        private const val LINGER = 0.3f
        private const val LOGO_HOLD = 2.6f
        private const val CARD_HOLD = 2.6f
        private const val PIP_HOLD = 2.2f
        private const val URL_HOLD = 2.8f
        private const val TERM_HOLD = 3.2f
        private const val MIN_CUT_IN = 1.2f
        private const val CLIP_SLACK = 0.15f
        private const val HELD_SLACK = 0.3f
        private const val PRIME = 7919L
        private const val MIN_STEM = 4
        private const val MAX_SUFFIX = 3

        private val STAGED = setOf("product", "screenshot", "b-roll")
        private val BG = mapOf("product" to "aurora", "screenshot" to "grid", "b-roll" to "plain")
        private val STAGES = setOf("aurora", "grid", "plain")
        private val ASKS = setOf("comment", "endcard")
        private val TEXT = setOf("slam", "mask-rise", "type-on", "blur-in", "flip", "spread", "stack", "glitch")

        /** What a line keeps whatever is placed on it: the ask, a conversation, a figure that must be read. */
        private val KEEP = ASKS + setOf("message", "voucher", "countdown", "stats", "progress", "counter", "meter", "chart", "bars", "ticker", "terminal", "network")

        /** What makes room for an asset on its line. */
        private val REPLACEABLE = TEXT + setOf("object", "objects", "icon", "stamp", "chips", "list", "logos", "lower-third")

        fun key(w: String) = NumberWords.normalize(w).trim { !it.isLetterOrDigit() }

        private fun latin(w: String) = w.any { it in 'a'..'z' }

        /** How alike a keyword and a said word are: the same, the same with a suffix, the same name across scripts, the same thing. */
        fun similar(a: String, b: String): Float {
            if (a.isEmpty() || b.isEmpty()) return 0f
            val stem = minOf(a.length, b.length) >= MIN_STEM && (b.startsWith(a) || a.startsWith(b)) && kotlin.math.abs(a.length - b.length) <= MAX_SUFFIX
            val sa = if (latin(a)) BriefNames.latinSkeleton(a) else BriefNames.persianSkeleton(a)
            val sb = if (latin(b)) BriefNames.latinSkeleton(b) else BriefNames.persianSkeleton(b)
            val across = latin(a) != latin(b) && sa.length >= 2 && sa == sb && minOf(a.length, b.length) >= 3
            // A recogniser's slip in a Latin word ("dashbord"): the same consonants, long enough to be sure.
            val slip = latin(a) && latin(b) && sa == sb && sa.length >= MIN_STEM
            val concept = Concepts.visualOf(a)?.let { it == Concepts.visualOf(b) } == true
            return when {
                a == b || across -> 2f
                stem || slip || concept -> 1.5f
                else -> 0f
            }
        }

        private fun start(words: List<Word>, i: Int) = words[i.coerceIn(0, words.lastIndex)].range.startMs / 1000f

        private fun seconds(words: List<Word>, a: Int, b: Int) = (words[b.coerceIn(0, words.lastIndex)].range.endMs - words[a.coerceIn(0, words.lastIndex)].range.startMs) / 1000f
    }
}
