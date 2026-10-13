package io.trimio.engine.autopilot.judges

import io.trimio.core.model.text.Numerals
import io.trimio.engine.autopilot.Finding
import io.trimio.engine.autopilot.Judge
import io.trimio.engine.autopilot.Jury
import io.trimio.engine.autopilot.PersianSpelling
import io.trimio.engine.autopilot.ScreenSpelling
import io.trimio.engine.autopilot.ScreenWords
import io.trimio.engine.autopilot.Severity
import io.trimio.engine.autopilot.Verdict
import io.trimio.engine.motion.CounterNode
import io.trimio.engine.motion.Group
import io.trimio.engine.motion.Node
import io.trimio.engine.motion.score.BeatScore
import io.trimio.engine.motion.score.Score

/**
 * The proofreader. Every Persian word a graphic shows is one the viewer heard, the brief spelled
 * or the lexicon knows ([ScreenSpelling], the last gate before drawing); «می/نمی» and plural
 * «ها/های» take the half-space where the lexicon writes them so («می‌تونید», not «میتونید»),
 * with no stray or doubled half-spaces; numbers in Persian text use Persian digits and
 * separators («۵۰٬۰۰۰», not «50,000»), and counters roll in the piece's script. Captions are what
 * was heard: their half-spaces are checked, their words are the transcript's. Latin words are
 * English or names the piece knows, sanely cased ([EnglishWords]).
 */
class SpellingJudge : Judge {
    override val name = "spelling"
    override val dimension = "words on screen"

    override fun score(c: Jury.Context): Verdict {
        val f = mutableListOf<Finding>()
        val lex = c.lexicon
        if (lex == null) {
            f += Bench.finding(0f, Severity.Note, "no lexicon: unknown-word check skipped", "-")
        } else {
            // Every word read on screen — graphics, captions, the cold open's, the end card — is a word, a term or a name.
            val words = ScreenWords(lex, names(c))
            val onScreen = shown(c).map { (t, s) -> Triple(t, s, "") } + Bench.captions(c).map { Triple(it.at, it.text, "caption ") }
            f += onScreen.flatMap { (t, s, where) -> words.unknown(s).map { w -> Bench.finding(t, Severity.Major, "${where}«$w» is no word", "the right spelling, or another phrase") } }
                .distinctBy { it.what }.capped(MAX_WORDS)
        }
        val graphics = shown(c)
        f += graphics.flatMap { (t, s) -> halfSpaces(s, lex).map { (w, fix) -> Bench.finding(t, Severity.Minor, "«$w» on screen", "write «$fix»") } }.distinctBy { it.what }.capped()
        val captions = Bench.captions(c).map { it.at to it.text }
        f += captions.flatMap { (t, s) -> halfSpaces(s, lex).map { (w, fix) -> Bench.finding(t, Severity.Minor, "caption «$w»", "spell the transcript «$fix»") } }.distinctBy { it.what }.capped()
        f += english(c, graphics, captions)
        f += Script(c).foreign().map { (p, w) -> Bench.finding(p.at, Severity.Major, "English «$w» on ${p.recipe} in a Persian video", "Persian words, or a name the brief gives in Latin") }
            .distinctBy { it.what }.capped(MAX_WORDS)
        f += digits(graphics + captions)
        f += counters(c)
        return Verdict.of(this, f)
    }

    /** Latin words: unknown ones on graphics are major, in captions (what was heard) minor; casing on both. */
    private fun english(c: Jury.Context, graphics: List<Pair<Float, String>>, captions: List<Pair<Float, String>>): List<Finding> {
        val en = EnglishWords(c)
        if (!en.active) return emptyList()
        fun scan(texts: List<Pair<Float, String>>, severity: Severity, where: String) = texts.flatMap { (t, s) ->
            s.split(' ').mapNotNull { en.check(it) }.map { (what, fix) -> Bench.finding(t, if ("cased" in what) Severity.Minor else severity, "$where$what", fix) }
        }.distinctBy { it.what }.capped(MAX_WORDS)
        // Captions are what was heard, rare words included («al dente», «cloves»): only their casing is judged
        // here; recognition slips in captions are measured against the truth in the golden archive.
        return scan(graphics, Severity.Major, "") + scan(captions, Severity.Minor, "caption: ").filter { "cased" in it.what }
    }

    private fun screen(c: Jury.Context, lex: PersianSpelling) = ScreenSpelling(lex, (c.prelude + c.words).map { it.text }, c.prompt)

    /** Names the understanding, the brief and the speech itself give ([ScreenWords.namesIn]). */
    private fun names(c: Jury.Context): List<String> {
        val lex = c.lexicon ?: return emptyList()
        val heard = (c.prelude + c.words).map { it.text }
        return c.understanding.entities.map { it.name } + c.prompt.split(' ', '\n', '،', ',') + ScreenWords.namesIn(heard) { ScreenWords(lex).shows(it) }
    }

    /** Every text a graphic shows, with when: the compiled text and the score's labels and items. */
    private fun shown(c: Jury.Context): List<Pair<Float, String>> {
        val starts = Bench.sceneStarts(c.score, c.words)
        val fromScore = c.score.scenes.indices.flatMap { k ->
            c.score.scenes[k].beats.flatMap { b -> Bench.shownTexts(b).map { Bench.beatStart(b, k, c.score, c.words, starts) to it } }
        }
        return (Bench.graphics(c).map { it.at to it.text } + fromScore).filter { it.second.isNotBlank() }
    }

    /** Words missing or misusing a half-space in [s], with their right spelling. */
    private fun halfSpaces(s: String, lex: PersianSpelling?): List<Pair<String, String>> {
        val tokens = s.split(' ').filter { it.isNotEmpty() }
        return tokens.indices.mapNotNull { i ->
            val core = tokens[i].trim { !it.isLetterOrDigit() && it != ZW }
            if (!Bench.persian(core)) return@mapNotNull null
            val next = tokens.getOrNull(i + 1)?.trim { !it.isLetterOrDigit() }
            when {
                ZWNJ + ZWNJ in core -> core to core.replace(ZWNJ + ZWNJ, ZWNJ)
                core.startsWith(ZWNJ) || core.endsWith(ZWNJ) -> core to core.trim(ZW)
                core in PREFIXES && next != null && Bench.persian(next) -> "$core $next" to core + ZWNJ + next
                lex != null -> joined(core, lex)?.let { core to it }
                else -> null
            }
        }
    }

    /** «میتونید» → «می‌تونید», «قابلیتها» → «قابلیت‌ها», when the lexicon writes them so. */
    private fun joined(core: String, lex: PersianSpelling): String? {
        if (ZWNJ in core) return null
        val prefix = PREFIXES.firstOrNull { core.startsWith(it) && core.length - it.length >= 2 }
        if (prefix != null) {
            val zw = prefix + ZWNJ + core.substring(prefix.length)
            // As the lexicon's own rule: a verb prefix the list writes with a half-space takes one.
            return zw.takeIf { lex.count(it) > 0 }
        }
        val suffix = PLURALS.firstOrNull { core.endsWith(it) && core.length - it.length >= 2 } ?: return null
        val zw = core.dropLast(suffix.length) + ZWNJ + suffix
        return zw.takeIf { lex.count(it) >= MIN_COUNT && lex.count(core) * RATIO < lex.count(it) }
    }

    private fun loneLatinDigits(s: String): Boolean {
        val tokens = s.split(' ')
        fun latinWord(i: Int) = tokens.getOrNull(i)?.any { it in 'A'..'Z' || it in 'a'..'z' } == true
        return tokens.indices.any { i -> tokens[i].any { it in '0'..'9' } && !latinWord(i - 1) && !latinWord(i + 1) && !latinWord(i) }
    }

    private fun digits(texts: List<Pair<Float, String>>): List<Finding> {
        val persian = texts.filter { Bench.persian(it.second) }
        // A model number beside a Latin name keeps Latin digits («iPhone 15»); others read in Persian.
        val latin = persian.filter { (_, s) -> loneLatinDigits(s) }
        val out = mutableListOf<Finding>()
        latin.firstOrNull()?.let { (t, s) -> out += Bench.finding(t, Severity.Minor, "Latin digits in Persian text «$s»", "Persian digits («${Numerals.toPersian(s)}»)") }
        texts.firstOrNull { SEPARATOR.containsMatchIn(it.second) }?.let { (t, s) ->
            out += Bench.finding(t, Severity.Minor, "Latin separators in Persian digits «$s»", "٬ for thousands, ٫ for decimals")
        }
        return out
    }

    private fun counters(c: Jury.Context): List<Finding> {
        val rtl = c.words.count { Bench.persian(it.text) } * 2 > c.words.size
        val wrong = counterNodes(c.compiled.composition.root).filter { it.persianDigits != rtl }
        return wrong.take(1).map { Bench.finding(it.start, Severity.Minor, "a counter rolls in ${if (rtl) "Latin" else "Persian"} digits", "the piece's own digits") }
    }

    private fun counterNodes(n: Node): List<CounterNode> = when (n) {
        is Group -> n.children.flatMap { counterNodes(it) }
        is CounterNode -> listOf(n)
        else -> emptyList()
    }

    override fun revise(c: Jury.Context, verdict: Verdict): Score? {
        val lex = c.lexicon
        val checked = lex?.let { screen(c, it).check(c.score) {} } ?: c.score
        val en = EnglishWords(c).takeIf { it.active }
        fun fix(s: String): String {
            var out = en?.fix(s) ?: s
            halfSpaces(s, lex).forEach { (w, right) -> out = out.replace(w, right) }
            return if (Bench.persian(out)) Numerals.toPersian(out) else out
        }
        fun BeatScore.fixed() = copy(text = text?.let(::fix), label = label?.let(::fix), items = items.map(::fix), emphasis = emphasis.map(::fix))
        var score = checked.copy(scenes = checked.scenes.map { s -> s.copy(beats = s.beats.map { it.fixed() }) })
        // English on a Persian piece: the words go, or the graphic takes a plain Persian form, or it goes.
        val script = Script(c)
        for (p in script.foreign().map { it.first }.distinct()) {
            val at = Bench.locate(score, c.words, p) ?: continue
            score = Bench.mapBeat(score, at) { b -> script.persian(b) }
        }
        return score.takeIf { it != c.score }
    }

    private companion object {
        const val ZWNJ = "‌"
        const val ZW = '\u200C'
        const val MAX_WORDS = 4
        const val RATIO = 2
        const val MIN_COUNT = 5
        val PREFIXES = listOf("نمی", "می")
        val PLURALS = listOf("های", "ها")
        val SEPARATOR = Regex("[۰-۹][,.][۰-۹]")
    }
}
