package io.trimio.engine.autopilot

import io.trimio.core.model.transcript.Word
import io.trimio.engine.motion.score.BeatScore
import io.trimio.engine.motion.score.SceneScore
import io.trimio.engine.motion.score.Score
import io.trimio.engine.motion.score.StoryMap
import io.trimio.engine.motion.score.Words

/**
 * The editorial critic («Critic تدوینی»): the questions a supervising editor asks of a cut — is
 * the hook surprising, which word carries the message, where does the tension sit — answered as
 * numbers on the shape a virality panel reads: hook, flow, value, trend. Everything here is
 * rule-based and on-device, over the transcript and the timeline alone; no model, no cloud.
 * [fresh] is the from-scratch pass: it judges the story map without the rules' draft in hand, so a
 * larger local model (or this panel) never anchors on a mediocre first reading.
 */
internal object EditCritic {

    /** The four numbers of the virality panel, each 0–10. */
    data class Virality(val hook: Float, val flow: Float, val value: Float, val trend: Float) {
        val total: Float get() = (hook + flow + value + trend) / 4f

        fun summary(): String = "hook ${one(hook)} flow ${one(flow)} value ${one(value)} trend ${one(trend)}"
    }

    /** The critic's read of one edit: the panel, the three questions, and what it would fix. */
    data class Report(
        val virality: Virality,
        /** «پیام کدام کلمه؟» — the word the piece is about, when one word carries it. */
        val messageWord: String?,
        /** «کشش کجاست؟» — where the tension lands, in seconds. */
        val tensionAt: Float?,
        /** «هوک غافلگیرکننده؟» — the opening gives away less than it promises. */
        val surprisingHook: Boolean,
        val findings: List<String>,
    )

    /** Judges [score] over [words], reading the story [map] when there is one. */
    fun review(score: Score, words: List<Word>, map: StoryMap? = null): Report {
        val graphics = score.scenes.flatMap { it.beats }.filter { it.recipe != CAPTIONS }
        val lands = graphics.map { land(it, score, words) }.sorted()
        val payoff = map?.beatOf("payoff")
        val tensionAt = map?.beatOf("turn")?.let { start(words, it.from) } ?: lands.getOrNull(lands.size / 2)
        val messageWord = message(payoff?.claim, graphics, words)
        val surprisingHook = lands.firstOrNull()?.let { it <= HOOK_BY } == true && payoff != null &&
            map.beats.firstOrNull()?.kind == "setup"
        val findings = mutableListOf<String>()
        if (lands.isEmpty()) findings += "no graphic at all — the edit is only speech"
        if (payoff == null) findings += "no payoff in the story map — the piece promises nothing"
        if (messageWord == null) findings += "no single word carries the message — the claim is diffuse"
        lands.firstOrNull { it > HOOK_BY }?.let { findings += "the first strike lands at ${one(it)}s — too late for the thumb" }
        return Report(
            virality = virality(lands, graphics, map, messageWord),
            messageWord = messageWord,
            tensionAt = tensionAt,
            surprisingHook = surprisingHook,
            findings = findings,
        )
    }

    /**
     * The from-scratch pass: [map] judged as if nothing before it existed. A rule draft that
     * anchored a later reading is simply not part of the input — the de-anchoring the bigger local
     * model gets when it is available, and this panel's standing behaviour otherwise.
     */
    fun fresh(map: StoryMap, words: List<Word>): StoryMap {
        val said = words.map { it.text }
        val beats = map.beats.filter { b -> b.claim.isNotBlank() && (b.from..maxOf(b.from, b.until)).any { said.getOrNull(it)?.let(::Words.isContent) == true } }
        return StoryMap(beats = beats, objects = map.objects, banned = map.banned)
    }

    private fun virality(lands: List<Float>, graphics: List<BeatScore>, map: StoryMap?, messageWord: String?): Virality {
        val hook = when {
            lands.isEmpty() -> 0f
            lands.first() <= HOOK_BY -> 9f
            lands.first() <= LATE_HOOK -> 6f
            else -> 3f
        }.let { if (map?.beatOf("payoff") != null) it else it * 0.6f }
        val flow = flow(lands, graphics)
        val value = when {
            messageWord != null && graphics.any { g -> g.text?.contains(messageWord) == true || g.label?.contains(messageWord) == true } -> 9f
            messageWord != null -> 6f
            else -> 3f
        }
        val trend = when {
            map?.beatOf("callback") != null -> 8f
            map?.beatOf("turn") != null -> 6f
            else -> 4f
        }
        return Virality(hook.coerceIn(0f, 10f), flow.coerceIn(0f, 10f), value.coerceIn(0f, 10f), trend.coerceIn(0f, 10f))
    }

    /** Flow: the tension rises into the payoff and releases after it — not a flat line. */
    private fun flow(lands: List<Float>, graphics: List<BeatScore>): Float {
        if (lands.size < 2) return 4f
        val steps = lands.zipWithNext { a, b -> b - a }
        val even = steps.maxOrNull()!! - steps.minOrNull()!!
        val energies = graphics.map { it.energy ?: 0.6f }
        val rises = energies.zipWithNext().count { (a, b) -> b > a }
        return 5f + (if (even < FLAT_GAP) 2f else 0f) + rises * 0.6f
    }

    /** The word the piece is about: the payoff claim's heaviest content word. */
    private fun message(claim: String?, graphics: List<BeatScore>, words: List<Word>): String? {
        val candidates = claim?.split(' ').orEmpty().filter { Words.isContent(it) }
        val said = words.map { it.text }
        return candidates.maxByOrNull { w -> said.count { it == w } * 2 + w.length }
            ?: graphics.mapNotNull { it.label ?: it.text }.flatMap { it.split(' ') }.filter { Words.isContent(it) }.maxByOrNull { it.length }
    }

    private fun land(b: BeatScore, score: Score, words: List<Word>): Float {
        b.time?.let { return it }
        b.at?.let { return start(words, it) }
        return score.scenes.firstOrNull()?.from?.let { start(words, it) } ?: 0f
    }

    private fun start(words: List<Word>, i: Int) = words.getOrNull(i)?.range?.startMs?.div(1000f) ?: 0f

    private fun one(v: Float) = (kotlin.math.round(v * 10f) / 10f).toString()

    private const val CAPTIONS = "pop-captions"
    private const val HOOK_BY = 1.5f
    private const val LATE_HOOK = 3f
    private const val FLAT_GAP = 2.5f
}
