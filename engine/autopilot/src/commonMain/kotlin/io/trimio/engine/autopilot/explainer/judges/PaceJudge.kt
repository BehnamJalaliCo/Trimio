package io.trimio.engine.autopilot.explainer.judges

import io.trimio.engine.autopilot.Finding
import io.trimio.engine.autopilot.Severity
import io.trimio.engine.autopilot.Verdict
import io.trimio.engine.autopilot.explainer.Illustrations
import io.trimio.engine.autopilot.explainer.IllustrationRequest
import io.trimio.engine.autopilot.explainer.Narration
import io.trimio.engine.autopilot.explainer.NewsWriter
import io.trimio.engine.autopilot.explainer.judges.FilmJury.Companion.finding
import io.trimio.engine.autopilot.explainer.judges.FilmJury.Companion.verdict
import io.trimio.engine.motion.explainer.Beat
import io.trimio.engine.motion.explainer.BeatContent
import io.trimio.engine.motion.explainer.Chapter
import io.trimio.engine.motion.explainer.ExplainerFilm
import io.trimio.engine.motion.explainer.Primitive
import io.trimio.engine.motion.explainer.StageOp

/**
 * The illustrated news film's pace and truth: a new visual idea every one or two seconds, nothing
 * still for more than about two and a half, every scene with a headline that says its sentence (its
 * words come from it) and a hero illustration of who or what the sentence is about, and no more than
 * one device per scene. A diagram film is calm by design: this judge passes it.
 */
class PaceJudge : FilmJudge {
    override val name = "pace"
    override val dimension = "a new idea every second or two, the headline and the picture true to the sentence"

    override fun score(c: FilmJury.Context): Verdict {
        if (!news(c.film)) return verdict(this, emptyList())
        val f = mutableListOf<Finding>()
        for (ch in c.film.chapters) f += scene(c.narration, ch)
        return verdict(this, f.take(MAX_FINDINGS))
    }

    private fun scene(n: Narration, ch: Chapter): List<Finding> {
        val out = mutableListOf<Finding>()
        val times = (listOf(ch.start) + ch.beats.map { it.at } + ch.end).sorted()
        val still = times.zipWithNext().maxOfOrNull { (a, b) -> b - a } ?: 0.0
        if (still > STATIC) out += finding(ch.start, Severity.Major, "«${ch.title}»: nothing moves for ${fmt(still)}s", "a new visual idea every 1–2 s")
        else if (still > IDEA) out += finding(ch.start, Severity.Minor, "«${ch.title}»: ${fmt(still)}s without a new idea", "a new visual idea every 1–2 s")
        val headline = ch.beats.firstOrNull { it.primitive == Primitive.Headline }
        val line = ch.beats.firstOrNull()?.line.orEmpty()
        val said = Films.keys(line).map { it.take(STEM) }.toSet()
        val shown = headline?.content?.let { Films.keys(it.kicker + " " + it.text + " " + it.punch) }.orEmpty().filter { it.length >= MIN_STEM }
        when {
            headline == null -> out += finding(ch.start, Severity.Major, "«${ch.title}» has no headline", "a headline from its sentence")
            shown.none { it.take(STEM) in said } -> out += finding(headline.at, Severity.Major, "the headline «${headline.content.text}» does not say «${line.take(SHORT)}»",
                "the sentence's own words")
        }
        val hero = ch.beats.firstOrNull { it.primitive == Primitive.Hero }
        val request = hero?.let { IllustrationRequest.parse(it.content.illustration) }
        val spoken = n.words.indices.filter { n.startOf(it) >= ch.start - EDGE && n.startOf(it) < ch.end }
        val expected = if (spoken.isEmpty()) null else Illustrations.request(n, spoken.first()..spoken.last(), "")
        when {
            request == null -> out += finding(ch.start, Severity.Major, "«${ch.title}» has no hero illustration", "draw who or what the sentence is about")
            expected != null && !matches(request, expected) -> out += finding(hero.at, Severity.Minor,
                "the illustration «${request.subject}» is not what «${line.take(SHORT)}» is about (${expected.subject})", "illustrate ${expected.subject}")
        }
        val devices = ch.beats.count { it.op == StageOp.Add && it.primitive in DEVICES }
        // One device, or a note of terms with its countdown (the reference's pair).
        if (devices > 2) out += finding(ch.start, Severity.Minor, "«${ch.title}» has $devices devices", "one device per scene")
        return out
    }

    private fun matches(a: IllustrationRequest, b: IllustrationRequest): Boolean {
        val words = (a.query).lowercase().split(' ', ',').filter { it.length > 2 }.toSet()
        return b.query.lowercase().split(' ', ',').any { it.length > 2 && it in words }
    }

    /** Scenes that fail are written again by the rules; a still stretch gets a highlight in its middle. */
    override fun revise(c: FilmJury.Context, verdict: Verdict): ExplainerFilm? {
        if (!news(c.film)) return null
        val bad = verdict.findings.filter { it.severity.cost > 0f }.map { it.at.toDouble() }
        val writer = NewsWriter()
        val rules = writer.film(c.narration, c.film.art.seed)
        val chapters = c.film.chapters.map { ch ->
            val failing = bad.any { it >= ch.start - EDGE && it < ch.end }
            val fresh = rules.chapters.minByOrNull { kotlin.math.abs(it.start - ch.start) }?.takeIf { kotlin.math.abs(it.start - ch.start) < EDGE * 4 }
            when {
                !failing -> ch
                fresh != null && (ch.beats.none { it.primitive == Primitive.Headline } || ch.beats.none { it.primitive == Primitive.Hero }) -> fresh.copy(start = ch.start, end = ch.end)
                else -> livened(ch)
            }
        }
        return c.check.repair(c.film.copy(chapters = chapters)).takeIf { it != c.film }
    }

    /** A scene's longest still stretch gets a pulse on its headline (or hero) halfway through. */
    private fun livened(ch: Chapter): Chapter {
        val times = (listOf(ch.start) + ch.beats.map { it.at } + ch.end).sorted()
        val (a, b) = times.zipWithNext().maxByOrNull { (x, y) -> y - x } ?: return ch
        if (b - a <= IDEA) return ch
        val target = ch.beats.firstOrNull { it.primitive == Primitive.Hero } ?: ch.beats.firstOrNull { it.op == StageOp.Add } ?: return ch
        val mid = (a + b) / 2
        val line = ch.beats.first().line
        return ch.copy(beats = (ch.beats + Beat(mid, b, line, StageOp.Highlight, target.element, content = BeatContent(), metaphor = "a pulse keeps the scene alive")).sortedBy { it.at })
    }

    private fun fmt(v: Double) = (kotlin.math.round(v * 10) / 10).toString()

    private companion object {
        const val STATIC = 2.5
        const val IDEA = 2.0
        const val STEM = 4
        const val MIN_STEM = 3
        const val SHORT = 40
        const val EDGE = 0.3
        const val MAX_FINDINGS = 12
        val DEVICES = setOf(Primitive.Bubble, Primitive.Annotation, Primitive.Calendar, Primitive.Stat, Primitive.Chips, Primitive.Meter, Primitive.Clock)
    }
}
