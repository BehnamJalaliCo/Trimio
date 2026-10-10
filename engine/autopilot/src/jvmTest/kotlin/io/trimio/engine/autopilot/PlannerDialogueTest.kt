package io.trimio.engine.autopilot

import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.motion.score.BeatScore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** Reported speech becomes one chat thread: the words as said, the reply as the speaker's bubble. */
class PlannerDialogueTest {

    private fun words(text: String, language: Language): List<Word> {
        var t = 300L
        return text.split(' ').filter { it.isNotEmpty() }.map { w ->
            Word(w, TimeRange(t, t + 320), language = language, emphasis = if (w.length > 5) 0.6f else 0.2f).also { t += if (w.last() in ".?؟") 700 else 380 }
        }
    }

    private fun threads(text: String, language: Language, prompt: String = ""): List<Pair<Planner.Plan, BeatScore>> {
        val words = words(text, language)
        val lines = Lines.split(words)
        val u = RulesUnderstander.understand(Transcript(language, words), prompt, lines)
        return (1L..8L).map { seed ->
            val plan = Planner().plan(words, lines, u, seed)
            val beats = plan.score.scenes.flatMap { it.beats }
            plan to assertNotNull(beats.singleOrNull { it.recipe == "message" }, "one thread: ${beats.map { it.recipe }}")
        }
    }

    @Test
    fun aPersianQuestionAndItsAnswer() {
        val text = "یکی تو کامنت‌ها نوشته که این اپ واقعا رایگانه یا باید پول بدیم؟ گفتم نه کاملا رایگانه، فقط کافیه ثبت‌نام کنی. " +
            "بعدش بریم سراغ آموزش اصلی که خیلی مهمه و همه باید ببینن."
        val all = threads(text, Language.Persian)
        for ((plan, m) in all) {
            assertEquals("این اپ واقعا رایگانه یا باید پول بدیم؟", m.items.first(), "the question as said")
            m.items.drop(1).forEach { assertTrue(it.startsWith(">"), "only the answer is outgoing: ${m.items}") }
            assertTrue(m.hold!! > 3f, "held until the answer is said")
            // The thread is the graphic for its lines: nothing lands on top of it while it is up.
            val until = m.until!!
            val onTop = plan.score.scenes.flatMap { it.beats }.filter { b -> b !== m && b.at != null && b.at!! in m.at!!..until }
            assertTrue(onTop.isEmpty(), "nothing on top of the thread: $onTop")
        }
        assertTrue(all.any { (_, m) -> m.items.last() == ">نه کاملا رایگانه" }, "the answer, as said: ${all.map { it.second.items }}")
        assertTrue(all.any { (_, m) -> m.items.size == 1 }, "some seeds keep the answer spoken only")
        assertTrue(all.map { it.second.label }.toSet().size >= 2, "the header varies by seed")
    }

    @Test
    fun anEnglishDmAndTheReply() {
        val text = "Someone DM'd me yesterday and asked, why didn't you just tell us the name of the exchange in the video? " +
            "I told him, it doesn't work like that. The campaign is only for the first thousand people on my page."
        val all = threads(text, Language.English)
        for ((_, m) in all) {
            assertEquals("why didn't you tell us the name", m.items.first(), "the question, shortened to what tells")
            assertTrue(m.label == null || m.label in setOf("a follower", "DM"), "${m.label}")
        }
        assertTrue(all.any { (_, m) -> m.items.last() == ">it doesn't work like that" }, "the reply: ${all.map { it.second.items }}")
    }

    @Test
    fun noThreadWithoutReportedSpeech() {
        val text = "این اپ واقعا رایگانه و فقط کافیه ثبت‌نام کنی. بعدش بریم سراغ آموزش اصلی که خیلی مهمه و همه باید ببینن. کلمه «اپ» رو برام کامنت کن."
        val words = words(text, Language.Persian)
        val lines = Lines.split(words)
        val u = RulesUnderstander.understand(Transcript(Language.Persian, words), "", lines)
        val beats = Planner().plan(words, lines, u, 1L).score.scenes.flatMap { it.beats }
        assertTrue(beats.none { it.recipe == "message" }, "a call to action is never a thread: ${beats.map { it.recipe }}")
    }
}
