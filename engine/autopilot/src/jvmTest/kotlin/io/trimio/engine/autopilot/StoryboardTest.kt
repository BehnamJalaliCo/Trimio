package io.trimio.engine.autopilot

import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Word
import io.trimio.engine.motion.score.BeatScore
import io.trimio.engine.motion.score.SceneScore
import io.trimio.engine.motion.score.Score
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StoryboardTest {
    private val said = "رفتم یه دونه کانال دیدم، ده تا دونه در روز کلن سیگنال می‌ده، " +
        "نه تاش استاب می‌خوره، یه دونه‌ش سود داره، " +
        "اونم کردن تو بوق و کرنا که آقا ما داریم سود می‌دیم، ورودیشم دو میلیون و هفتصد هزار تومنه"
    private val words = said.split(' ').mapIndexed { i, w -> Word(w, TimeRange(i * 400L, i * 400L + 350), language = Language.Persian) }
    private val texts = words.map { it.text }
    private fun at(w: String) = texts.indexOfFirst { it.startsWith(w) }

    @Test
    fun aCountBrokenDownIsOneGridThatTurnsRedAndGreen() {
        val grid = Storyboard.tiles(texts).single().beat
        assertEquals("board-tiles", grid.recipe)
        assertEquals(10f, grid.value)
        assertEquals(at("ده"), grid.at)
        assertTrue("سیگنال" in grid.text!!, grid.text)
        assertEquals(listOf("loss:9", "gain:1"), grid.items.map { it.substringBeforeLast(':') })
        // Each state lands on its own number, as it is said.
        assertEquals(listOf(at("نه"), (at("نه") until texts.size).first { texts[it] == "یه" }), grid.itemAt)
    }

    @Test
    fun theSpeakerIsNeverReplacedAndEveryLineIsACard() {
        val score = Score(
            scenes = listOf(
                SceneScore(from = 0, beats = listOf(BeatScore(recipe = "slam", text = "یه کانال سیگنال", at = 0, energy = 0.6f))),
                SceneScore(from = at("ده"), bg = "aurora", inset = "corner", transition = "whip", beats = listOf(BeatScore(recipe = "counter", at = at("ده"), value = 10f))),
                SceneScore(
                    from = at("اونم"),
                    beats = listOf(
                        BeatScore(recipe = "stack", text = "ما سود می‌دیم", at = at("اونم"), energy = 0.9f),
                        BeatScore(recipe = "price-tag", at = texts.indexOf("دو"), value = 2_700_000f, suffix = "تومان"),
                    ),
                ),
            ),
        )
        val board = Storyboard.board(score, words)
        assertTrue(board.scenes.all { it.bg == null && it.inset == null })
        val beats = board.scenes.flatMap { it.beats }
        // The strongest title stays a title; the other becomes a pill.
        assertEquals(1, beats.count { it.recipe in setOf("slam", "stack") })
        assertTrue(beats.any { it.recipe == "stack" })
        assertTrue(beats.any { it.recipe == "board-pill" && it.text == "یه کانال سیگنال" }, beats.joinToString("\n") { "${it.recipe} ${it.at} ${it.text}" })
        // The counter on the grid's words is the grid now.
        assertFalse(beats.any { it.recipe == "counter" })
        assertTrue(beats.any { it.recipe == "board-tiles" })
        // Their entry fee reads red.
        assertEquals("loss", beats.single { it.recipe == "board-price" }.mark)
        assertTrue(beats.all { it.place == "top" })
        assertEquals("meaning", board.captions.mark)
        assertEquals(3, board.captions.maxWords)
    }

    @Test
    fun aBriefCanStillAskForFullFrameGraphics() {
        assertTrue(Storyboard.wanted("یه ادیت حرفه‌ای بزن", footage = true))
        assertFalse(Storyboard.wanted("گرافیک‌ها تمام صفحه باشن", footage = true))
        assertFalse(Storyboard.wanted("", footage = false))
    }

    @Test
    fun theirPriceAgainstOursIsOneVersusCard() {
        val talk = "ورودیشم دو میلیون و هفتصد هزار تومنه، ما این همه شفاف‌سازی کردیم، کلن پنجاه تتر ورودی مونه، اصلا به ما پولی نمی‌دی، ورودیمون رایگانه."
        val heard = talk.split(' ').mapIndexed { i, w -> Word(w, TimeRange(i * 400L, i * 400L + 350), language = Language.Persian) }
        val found = BoardDevices.versus(heard.map { it.text }, StoryCut.clauses(heard, Lines.split(heard)))!!.beat
        assertEquals("board-versus", found.recipe)
        assertEquals("gain:ما|۵۰ تتر|ورودی", found.items[0])
        assertTrue(found.items[1].startsWith("loss:اونا|۲٬۷۰۰٬۰۰۰"), found.items[1])
        assertTrue(found.items.drop(2).isNotEmpty(), "the gains said after it grow the card: ${found.items}")
    }

    @Test
    fun everyCardOfTheStoryboardStaysACardWhenTranslatedAgain() {
        val told = Storyboard.board(Score(scenes = listOf(SceneScore(from = 0))), words)
        for (recipe in listOf("board-tiles", "board-pill", "board-quote", "board-price", "board-versus", "board-steps", "board-chips", "board-chat")) {
            val card = BeatScore(recipe = recipe, at = 1, text = "یه کانال سیگنال", items = listOf("gain:ما|۵۰ تتر", "loss:اونا|۱۰۰ تتر"))
            assertEquals(recipe, Storyboard.translated(told, card)?.recipe, recipe)
        }
    }
}
