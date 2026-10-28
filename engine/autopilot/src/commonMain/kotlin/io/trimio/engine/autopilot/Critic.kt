package io.trimio.engine.autopilot

import io.trimio.core.model.transcript.Word
import io.trimio.engine.motion.score.BeatScore
import io.trimio.engine.motion.score.Compiler
import io.trimio.engine.motion.score.SceneScore
import io.trimio.engine.motion.score.Score
import io.trimio.engine.motion.score.StoryMap
import io.trimio.engine.motion.score.Words

/**
 * Watches the compiled edit the way a supervising editor would and sends it back with fixes:
 * a hook must land in the first 1.5 s, no stretch sits still longer than the energy there allows
 * ([EnergyCurve.gap], never inside the calm a big strike earns), no more than two graphics share
 * the screen, a text treatment is not used three times running, and the call to action holds the
 * last screen. Each pass returns the issues it found and a revised score; the autopilot recompiles
 * until the critic is satisfied (or two passes are spent). What the self-critique loop grades
 * ([ContactSheet], [EditCritic]) comes back as [Review.findings] — reported, never a veto.
 */
class Critic(private val maxStill: Float = 3.5f, private val hookBy: Float = 1.5f) {

    data class Issue(val at: Float, val kind: String, val detail: String)

    data class Review(val issues: List<Issue>, val revised: Score?, val findings: List<String> = emptyList()) {
        val clean: Boolean get() = issues.isEmpty()
    }

    // Five checks in the order an editor makes them, each with its fix.
    @Suppress("CyclomaticComplexMethod")
    fun review(score: Score, out: Compiler.Output, words: List<Word>, lines: List<Lines.Line>, cta: Cta?, u: Understanding? = null, map: StoryMap? = null): Review {
        val issues = mutableListOf<Issue>()
        var scenes = score.scenes
        val graphics = out.beats.filter { it.recipe != CAPTIONS }
        val duration = out.composition.duration

        // 1. The hook: something besides captions within the first 1.5 s.
        if (graphics.none { it.at <= hookBy } && lines.isNotEmpty()) {
            issues += Issue(0f, "hook", "nothing lands in the first ${hookBy}s")
            val line = lines.first()
            phrase(line, words)?.let { p -> Storyboard.translated(score, BeatScore(recipe = "slam", text = p, energy = 0.9f, place = "top"))?.let { scenes = scenes.addBeat(0, it) } }
        }

        // 2. Stillness: stretches with no graphic on screen and no cut; long ones get a punch-in cut.
        // (A graphic on screen is alive — its own choreography is the change. The energy curve sizes
        // the stretch ([EnergyCurve.gap]: a hot stretch breathes shorter), and the calm a big strike
        // earns is never called dead air ([EnergyCurve.calm]).)
        val impulses = EnergyCurve.impulses(score, words)
        val calm = EnergyCurve.calm(score, words)
        val changes = (graphics.flatMap { listOf(it.at, it.out) } + sceneStarts(score, words) + 0f + duration).sorted()
        val quiet = changes.zipWithNext().filter { (a, b) -> deadAir(a, b, impulses, calm) && graphics.none { g -> g.at < b - 0.05f && g.out > a + 0.05f } }
        for ((a, b) in quiet) {
            val mid = (a + b) / 2f
            // A cut on the speaker, never inside a stage (it would cut its graphic before it lands) nor a chat thread still
            // being told: the word nearest the middle of the stretch that is free.
            fun free(w: Int): Boolean {
                val host = scenes.lastOrNull { (it.from ?: 0) <= w } ?: return true
                return host.bg == null && host.beats.none { it.recipe == THREAD && (it.until ?: -1) >= w }
            }
            val word = words.indices.filter { i -> words[i].range.startMs / 1000f in (a + EDGE)..(b - EDGE) }
                .sortedBy { i -> kotlin.math.abs(words[i].range.startMs / 1000f - mid) }
                .firstOrNull { w -> w > 0 && scenes.none { it.from == w } && free(w) } ?: continue
            issues += Issue(a, "still", "${kotlin.math.round((b - a) * 10f) / 10f}s without a visual change")
            // A new scene over footage alternates the punch-in: the cheapest, most natural change.
            scenes = (scenes + SceneScore(from = word)).sortedBy { it.from ?: 0 }
        }

        // 3. Clutter: at most two graphics at once.
        val crowded = graphics.filter { g -> graphics.count { o -> o !== g && o.at < g.out - 0.1f && g.at < o.out - 0.1f } >= 2 }
        if (crowded.isNotEmpty()) issues += Issue(crowded.first().at, "clutter", "${crowded.size} graphics overlap three deep")

        // 4. Repetition of a text treatment three times in a row.
        val text = graphics.filter { it.recipe in TEXT_RECIPES }.map { it.recipe }
        text.windowed(3).firstOrNull { it.toSet().size == 1 }?.let { r ->
            issues += Issue(0f, "repeat", "${r.first()} three times running")
            var seen = 0
            scenes = scenes.map { sc ->
                sc.copy(beats = sc.beats.map { b -> if (b.recipe == r.first() && ++seen % 3 == 0) b.copy(recipe = ALTERNATE.getValue(b.recipe)) else b })
            }
        }

        // 5. The call to action owns the end.
        val ctaShown = graphics.any { it.recipe == "comment" && it.out >= duration - 1.5f }
        if (cta != null && cta.keyword.isNotBlank() && !ctaShown) {
            issues += Issue(duration, "cta", "the call to action is not on the last screen")
            val last = scenes.lastIndex
            // The word as the planner placed it (what the speaker said), not the model's spelling of it.
            val keyword = scenes.flatMap { it.beats }.lastOrNull { it.recipe == "comment" }?.text ?: cta.keyword
            if (last >= 0 && scenes[last].beats.none { it.recipe == "comment" }) {
                scenes = scenes.addBeat(last, BeatScore(recipe = "comment", text = keyword, hold = 4f, label = "کامنت کن", place = "top"))
            }
        }

        // 6. What the self-critique loop grades: the contact sheet's six numbers and the editorial
        // critic's three questions — reported to the editor, never gating (the panel is young).
        val sheet = ContactSheet.grade(score, words, u)
        val editorial = EditCritic.review(score, words, map)
        val findings = listOf("contact sheet: ${sheet.summary()}") + sheet.findings.map { "contact sheet: $it" } +
            listOf("edit critic: ${editorial.virality.summary()}") + editorial.findings.map { "edit critic: $it" }

        val changed = scenes != score.scenes
        return Review(issues, if (changed) score.copy(scenes = scenes) else null, findings)
    }

    /** Whether [a, b] is dead air: longer than the curve allows there, and not the calm before a strike. */
    private fun deadAir(a: Float, b: Float, impulses: List<EnergyCurve.Impulse>, calm: List<ClosedFloatingPointRange<Float>>): Boolean {
        val mid = (a + b) / 2f
        if (EnergyCurve.inCalm(mid, calm)) return false
        val e = impulses.minByOrNull { kotlin.math.abs(it.at - mid) }?.energy ?: MID_ENERGY
        return b - a > minOf(maxStill, EnergyCurve.gap(e))
    }

    private fun List<SceneScore>.addBeat(k: Int, beat: BeatScore) = mapIndexed { i, s -> if (i == k) s.copy(beats = listOf(beat) + s.beats) else s }

    private fun sceneStarts(score: Score, words: List<Word>) = score.scenes.mapNotNull { s -> s.time ?: s.from?.let { words.getOrNull(it)?.range?.startMs?.div(1000f) } }

    private fun phrase(line: Lines.Line, words: List<Word>): String? {
        val peak = line.range.filter { Words.isContent(words[it].text) }.maxByOrNull { Words.weight(words[it]) } ?: return null
        return (peak..minOf(line.last, peak + 1)).joinToString(" ") { words[it].text.trimEnd('.', '،', ',') }
    }

    private companion object {
        const val CAPTIONS = "pop-captions"
        const val THREAD = "message"

        /** A cut keeps this far from either end of the still stretch it breaks. */
        const val EDGE = 0.8f

        /** The energy where nothing was said about one: the ordinary gap. */
        const val MID_ENERGY = 0.6f
        val TEXT_RECIPES = setOf("slam", "mask-rise", "type-on", "blur-in", "flip", "spread", "stack", "glitch")
        val ALTERNATE = mapOf(
            "slam" to "stack", "mask-rise" to "flip", "type-on" to "mask-rise", "blur-in" to "mask-rise",
            "flip" to "mask-rise", "spread" to "slam", "stack" to "slam", "glitch" to "slam",
        )
    }
}