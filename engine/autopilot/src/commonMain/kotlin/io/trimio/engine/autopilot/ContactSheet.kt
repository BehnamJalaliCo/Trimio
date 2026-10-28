package io.trimio.engine.autopilot

import io.trimio.core.model.transcript.Word
import io.trimio.engine.motion.score.BeatScore
import io.trimio.engine.motion.score.Score

/**
 * Contact-sheet QA: the self-critique loop's scoring pass. Six questions, each 0–10, answerable
 * from the score and the transcript alone so they can gate every edit automatically: is the hook
 * there, does it read on a phone, does the motion hold up, is there variety, is it on-brand, does
 * it land with the voice. [Sheet.total] is what a loop asserts against; [findings] says what to fix.
 */
internal object ContactSheet {

    /** One criterion's grade and why. */
    data class Grade(val name: String, val score: Float, val note: String)

    /** The six grades of the contact sheet, in the order the eye reads them. */
    data class Sheet(
        val hook: Grade,
        val legibility: Grade,
        val motion: Grade,
        val variety: Grade,
        val brand: Grade,
        val sync: Grade,
    ) {
        val grades: List<Grade> get() = listOf(hook, legibility, motion, variety, brand, sync)
        val total: Float get() = grades.sumOf { it.score.toDouble() }.toFloat() / grades.size
        val min: Float get() = grades.minOf { it.score }
        val findings: List<String> get() = grades.filter { it.score < PASS }.map { "${it.name}: ${it.note}" }

        fun summary(): String = grades.joinToString(" ") { "${it.name} ${one(it.score)}" } + " — total ${one(total)}"

        companion object {
            const val PASS = 8f
        }
    }

    /** Grades [score] over [words] (and [u], when a reading is in hand). */
    fun grade(score: Score, words: List<Word>, u: Understanding? = null): Sheet {
        val beats = score.scenes.flatMap { it.beats }
        val graphics = beats.filter { it.recipe != CAPTIONS }
        val lands = graphics.mapNotNull { b -> b.time ?: b.at?.let { words.getOrNull(it)?.range?.startMs?.div(1000f) } }.sorted()
        val texts = graphics.flatMap { listOfNotNull(it.text, it.label) + it.items }
        return Sheet(
            hook = hook(lands, graphics),
            legibility = legibility(texts, score),
            motion = motion(graphics),
            variety = variety(graphics, score),
            brand = brand(score, u),
            sync = sync(graphics, words),
        )
    }

    private fun hook(lands: List<Float>, graphics: List<BeatScore>) = when {
        graphics.isEmpty() || lands.isEmpty() -> Grade("hook", 0f, "nothing lands — no hook at all")
        lands.first() <= HOOK_BY -> Grade("hook", 10f, "the first strike is up by ${one(lands.first())}s")
        lands.first() <= LATE_HOOK -> Grade("hook", 6f, "the hook waits until ${one(lands.first())}s")
        else -> Grade("hook", 2f, "the hook is ${one(lands.first())}s in — the thumb is gone")
    }

    /** Mobile readability: no card shows more words than a phone reads in one glance. */
    private fun legibility(texts: List<String>, score: Score): Grade {
        val longest = texts.maxOfOrNull { it.split(' ').size } ?: 0
        val maxWords = score.captions.maxWords
        return when {
            longest <= GLANCE_WORDS && maxWords <= PHONE_WORDS -> Grade("legibility", 10f, "every card fits a phone glance")
            longest <= GLANCE_WORDS + 2 -> Grade("legibility", 7f, "one card runs to $longest words")
            else -> Grade("legibility", 4f, "a card shows $longest words — too long for a phone")
        }
    }

    /** Motion quality: nothing flashes, nothing sits still. */
    private fun motion(graphics: List<BeatScore>): Grade {
        val short = graphics.count { (it.hold ?: MIN_HOLD) < FLASH }
        return when {
            graphics.isEmpty() -> Grade("motion", 2f, "no motion to judge")
            short == 0 -> Grade("motion", 10f, "every graphic holds long enough to read")
            short == 1 -> Grade("motion", 7f, "one graphic flashes by")
            else -> Grade("motion", 4f, "$short graphics flash by")
        }
    }

    /** Variety: the edit does not show the same trick all the way through. */
    private fun variety(graphics: List<BeatScore>, score: Score): Grade {
        val recipes = graphics.map { it.recipe }.distinct().size
        val cameras = score.scenes.mapNotNull { it.camera }.distinct().size
        val spread = recipes + cameras
        return when {
            spread >= RICH -> Grade("variety", 10f, "$recipes treatments and $cameras camera moves")
            spread >= MODEST -> Grade("variety", 7f, "a narrow vocabulary: $recipes treatments")
            else -> Grade("variety", 4f, "one trick all the way through")
        }
    }

    /** On-brand: the edit keeps one look and the maker's voice. */
    private fun brand(score: Score, u: Understanding?): Grade {
        val look = score.look
        val mood = u?.mood
        return when {
            look in LOOKS && mood == null -> Grade("brand", 8f, "the look «$look» holds throughout")
            look in LOOKS -> Grade("brand", 10f, "the look «$look» holds, reading «$mood»")
            else -> Grade("brand", 4f, "the look «$look» is not one this brand wears")
        }
    }

    /** Audio sync: every graphic lands on words, not between them. */
    private fun sync(graphics: List<BeatScore>, words: List<Word>): Grade {
        val anchored = graphics.count { it.at != null || it.time != null }
        val drift = graphics.filter { it.at != null && it.at !in words.indices }.size
        return when {
            drift > 0 -> Grade("sync", 4f, "$drift graphics point outside the transcript")
            graphics.isEmpty() -> Grade("sync", 7f, "no graphics to sync")
            anchored == graphics.size -> Grade("sync", 10f, "every graphic lands on its words")
            else -> Grade("sync", 7f, "${graphics.size - anchored} graphics float free of the voice")
        }
    }

    private fun one(v: Float) = (kotlin.math.round(v * 10f) / 10f).toString()

    private const val CAPTIONS = "pop-captions"
    private const val HOOK_BY = 1.5f
    private const val LATE_HOOK = 3f
    private const val GLANCE_WORDS = 5
    private const val PHONE_WORDS = 5
    private const val MIN_HOLD = 1.5f
    private const val FLASH = 0.8f
    private const val RICH = 5
    private const val MODEST = 3
    private val LOOKS = setOf("noir", "paper", "lumen")
}
