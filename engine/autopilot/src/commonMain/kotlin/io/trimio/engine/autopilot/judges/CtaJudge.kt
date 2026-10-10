package io.trimio.engine.autopilot.judges

import io.trimio.core.model.transcript.Word
import io.trimio.engine.autopilot.Finding
import io.trimio.engine.autopilot.Judge
import io.trimio.engine.autopilot.Jury
import io.trimio.engine.autopilot.Severity
import io.trimio.engine.autopilot.Verdict
import io.trimio.engine.motion.score.BeatScore
import io.trimio.engine.motion.score.Compiler
import io.trimio.engine.motion.score.EditPlan
import io.trimio.engine.motion.score.NumberWords
import io.trimio.engine.motion.score.SceneScore
import io.trimio.engine.motion.score.Score

/**
 * The ending. When the speaker asks for something, the call to action is the clearest thing on
 * the last screen: the comment field is up for at least [minShow] seconds and holds to the very
 * end, nothing else shares the last [endWindow] seconds or lands after the ask (the second step
 * excepted), it types the keyword as the speaker said it, and when the speaker then sends viewers
 * to their DMs («بعد برو دایرکتتو چک کن») the second-step stamp says so.
 */
class CtaJudge(
    private val minShow: Float = 3.5f,
    private val endWindow: Float = 1.5f,
    private val endTolerance: Float = 0.15f,
) : Judge {
    override val name = "cta"
    override val dimension = "call to action and ending"

    override fun score(c: Jury.Context): Verdict {
        val graphics = Bench.graphics(c)
        val comment = graphics.filter { it.recipe == Bench.COMMENT }.maxByOrNull { it.out }
        if (comment == null && !asked(c)) return Verdict.of(this, listOf(Bench.finding(0f, Severity.Note, "no call to action in the speech", "-")))
        if (comment == null) return Verdict.of(this, listOfNotNull(missing(c, graphics)))
        val f = mutableListOf<Finding>()
        if (comment.out < c.duration - endTolerance) {
            f += Bench.finding(comment.out, Severity.Major, "the call to action leaves at ${Bench.r1(comment.out)}s of ${Bench.r1(c.duration)}s", "hold it to the end")
        }
        if (comment.out - comment.at < minShow) {
            f += Bench.finding(comment.at, Severity.Minor, "the call to action is up ${Bench.r1(comment.out - comment.at)}s", "at least $minShow s: an end hold after the last word")
        }
        rivals(c, graphics, comment).forEach { g -> f += Bench.finding(g.at, Severity.Major, "${g.recipe} shares the last screen with the call to action", "nothing new after the ask") }
        val end = c.duration - endWindow
        graphics.firstOrNull { it !== comment && it.recipe != Bench.STAMP && it.out > end && Bench.area(it) > Bench.area(comment) }?.let {
            f += Bench.finding(it.at, Severity.Minor, "${it.recipe} is larger than the call to action at the end", "the ask is the biggest thing on the last screen")
        }
        val keyword = spokenKeyword(c)
        when {
            comment.text.isBlank() -> f += Bench.finding(comment.at, Severity.Major, "the comment field has no keyword", "type the keyword the speaker said")
            said(c).none { it == norm(comment.text) } -> {
                val heard = keyword?.let { " («$it»)" }.orEmpty()
                f += Bench.finding(comment.at, Severity.Major, "«${comment.text}» is not the word the speaker said$heard", "type the spoken keyword")
            }
        }
        if (dmWord(c.words, comment.at) != null && graphics.none { it.recipe == Bench.STAMP && it.at >= comment.at - 0.1f }) {
            f += Bench.finding(comment.at, Severity.Major, "the speaker sends viewers to their DMs; the second step is not shown", "a stamp of the DM step under the field")
        }
        return Verdict.of(this, f)
    }

    /**
     * No comment field: with a keyword said, the ask failed (critical); an ask without one («نظرتونو
     * کامنت کنید», «فالو کن») needs its words on screen from the ask to the end (a stamp will do).
     */
    private fun missing(c: Jury.Context, graphics: List<Compiler.Placed>): Finding? {
        if (spokenKeyword(c) != null) return Bench.finding(c.duration, Severity.Critical, "the call to action never appears", "the comment field with the keyword on the last screen")
        val i = askWord(c) ?: return null
        val from = Bench.start(c.words, c.lines.firstOrNull { i in it.range }?.first ?: i) - 0.2f
        val shown = graphics.any { it.recipe in ASK_SHOWS && it.at >= from && it.out >= c.duration - endTolerance }
        val what = "the speaker asks («${c.words[i].text}») but nothing on screen says so"
        return if (shown) null else Bench.finding(Bench.start(c.words, i), Severity.Major, what, "the ask's words on the last screen")
    }

    /** The last word of the second half that asks for something (comment, follow, save, link, DM). */
    private fun askWord(c: Jury.Context): Int? = c.words.indices.lastOrNull { i -> i * 2 >= c.words.size && ASKS.any { norm(c.words[i].text).startsWith(it) } }

    /** Graphics other than the second step that land after the ask, or are still up in the last seconds. */
    private fun rivals(c: Jury.Context, graphics: List<Compiler.Placed>, comment: Compiler.Placed) = graphics.filter { g ->
        g !== comment && g.recipe != Bench.STAMP && g.recipe != Bench.COMMENT && (g.at > comment.at + 0.05f || g.out > c.duration - endWindow + 0.1f)
    }

    /** The speech asks for something: the understanding read a call to action, or someone is told to comment. */
    private fun asked(c: Jury.Context) = c.understanding.cta?.let { it.keyword.isNotBlank() || it.action.isNotBlank() } == true

    private fun said(c: Jury.Context) = (c.prelude + c.words).map { norm(it.text) }

    /** The keyword as said: the model's when it is said, else the word after «کلمه» in the second half. */
    private fun spokenKeyword(c: Jury.Context): String? {
        val asked = c.understanding.cta?.keyword?.trim('«', '»', '"', ' ')?.takeIf { k -> k.isNotBlank() && norm(k) in said(c) }
        val texts = c.words.map { it.text }
        val marked = texts.indices.lastOrNull { i -> i + 1 < texts.size && i * 2 >= texts.size && norm(texts[i]) in MARKERS }?.let { texts[it + 1].trim { ch -> !ch.isLetterOrDigit() } }
        return asked ?: marked
    }

    /** The first DM word said after [after] seconds, with its index. */
    private fun dmWord(words: List<Word>, after: Float): Int? = words.indices.firstOrNull { i ->
        words[i].range.startMs / 1000f >= after - 0.2f && DM.any { norm(words[i].text).startsWith(it) }
    }

    override fun revise(c: Jury.Context, verdict: Verdict): Score? {
        val graphics = Bench.graphics(c)
        val comment = graphics.filter { it.recipe == Bench.COMMENT }.maxByOrNull { it.out }
        var score = c.score
        val keyword = spokenKeyword(c)
        if (comment == null) return keyword?.let { ask(c, it) } ?: stampAsk(c)
        val at = Bench.locate(score, c.words, comment) ?: return null
        score = hold(c, score, at, comment, keyword)
        // The ask's scene runs to the end: scenes after it fold into it, and rivals landing after it go.
        val k = at.first
        val after = score.scenes.drop(k + 1).flatMap { it.beats }
        score = score.copy(scenes = score.scenes.take(k + 1).mapIndexed { i, s -> if (i == k) s.copy(beats = s.beats + after) else s })
        for (g in rivals(c, graphics, comment)) {
            val r = Bench.locate(score, c.words, g) ?: continue
            // Landing after the ask: it goes; up from before: it leaves as the ask lands.
            score = Bench.mapBeat(score, r) { b -> if (g.at > comment.at) null else b.copy(hold = maxOf(MIN_LEFT, comment.at - g.at)) }
        }
        dmWord(c.words, comment.at)?.takeIf { graphics.none { it.recipe == Bench.STAMP && it.at >= comment.at - 0.1f } }?.let { i ->
            val text = (i..minOf(i + 1, c.words.lastIndex)).joinToString(" ") { c.words[it].text.trimEnd('.', '،', ',') }
            score = Bench.addBeat(score, minOf(k, score.scenes.lastIndex), BeatScore(recipe = Bench.STAMP, at = i, text = text, hold = STAMP_HOLD, energy = 0.8f, place = "lower"))
        }
        return score.takeIf { it != c.score }
    }

    /**
     * An ask said too late to be read: the card already holds to the end, so the edit holds past
     * the last word (picture and music, [EditPlan.withOutro]) until it has been up [minShow].
     */
    override fun outro(c: Jury.Context, verdict: Verdict): Float? {
        val card = Bench.graphics(c).filter { it.recipe in ASK_SHOWS && it.out >= c.duration - endTolerance }.maxByOrNull { it.out - it.at } ?: return null
        val shown = card.out - card.at
        if (shown >= minShow) return null
        val held = c.edit?.outro?.length ?: 0f
        return (held + minShow - shown + OUTRO_MARGIN).coerceAtMost(EditPlan.MAX_OUTRO).takeIf { it > held }
    }

    /**
     * The field held to the end, typing the spoken keyword; when it would be up too briefly it
     * lands as the ask's line begins instead of on the keyword (its scene starting with it).
     */
    private fun hold(c: Jury.Context, score: Score, at: Pair<Int, Int>, comment: Compiler.Placed, keyword: String?): Score {
        val line = score.scenes[at.first].beats[at.second].at?.let { i -> c.lines.firstOrNull { i in it.range } }
        val early = line?.first?.takeIf { comment.out - comment.at < minShow && Bench.start(c.words, it) < comment.at - 0.2f }
        val start = early?.let { Bench.start(c.words, it) } ?: comment.at
        val moved = if (early == null) score else score.copy(scenes = score.scenes.mapIndexed { k, sc -> if (k == at.first && k > 0 && (sc.from ?: 0) > early) sc.copy(from = early) else sc })
        return Bench.mapBeat(moved, at) { b ->
            b.copy(
                at = early ?: b.at, time = if (early != null) null else b.time, hold = maxOf(b.hold ?: 0f, c.duration - start + 1f),
                text = if (keyword != null && norm(b.text.orEmpty()) !in said(c)) keyword else b.text,
            )
        }
    }

    /**
     * The comment field where the keyword is said, in a scene of its own from the start of the
     * ask's line that runs to the end (what came after it goes; the DM step is added next round).
     */
    private fun ask(c: Jury.Context, word: String): Score? {
        val at = c.words.indices.lastOrNull { norm(c.words[it].text) == norm(word) } ?: return null
        val from = c.lines.firstOrNull { at in it.range }?.first ?: at
        val start = Bench.start(c.words, at)
        val beat = BeatScore(recipe = Bench.COMMENT, at = at, text = word, hold = c.duration - start + 1f, label = LABEL, place = "top")
        val t = Bench.start(c.words, from)
        val starts = Bench.sceneStarts(c.score, c.words)
        val before = c.score.scenes.filterIndexed { k, _ -> k == 0 || starts[k] < t - 0.01f }
        return c.score.copy(scenes = before + SceneScore(from = from, beats = listOf(beat)))
    }

    /** An ask without a keyword: its own words, stamped from the ask to the end. */
    private fun stampAsk(c: Jury.Context): Score? {
        val i = askWord(c) ?: return null
        val k = c.score.scenes.lastIndex.takeIf { it >= 0 } ?: return null
        val text = (i..minOf(i + 1, c.words.lastIndex)).joinToString(" ") { c.words[it].text.trimEnd('.', '،', ',', '!', '؟', '?') }
        val beat = BeatScore(recipe = Bench.STAMP, at = i, text = text, hold = c.duration - Bench.start(c.words, i) + 1f, energy = 0.7f, place = "top")
        return Bench.addBeat(c.score, k, beat)
    }

    private companion object {
        const val LABEL = "کامنت کن"
        const val STAMP_HOLD = 1.8f
        const val MIN_LEFT = 1f
        const val OUTRO_MARGIN = 0.2f
        val MARKERS = setOf("کلمه", "کلمهی", "کلمهٔ", "کلمۀ", "word", "keyword")
        val DM = listOf("دایرکت", "دایرک", "dm", "direct", "inbox")
        val ASKS = listOf("کامنت", "comment", "بنویس", "فالو", "follow", "سیو", "ذخیره", "save", "share", "لینک", "link", "دایرکت", "subscribe")
        val ASK_SHOWS = setOf(Bench.COMMENT, "endcard", Bench.STAMP, "lower-third")

        fun norm(s: String) = NumberWords.normalize(s).lowercase().trim { !it.isLetterOrDigit() }.replace("‌", "")
    }
}
