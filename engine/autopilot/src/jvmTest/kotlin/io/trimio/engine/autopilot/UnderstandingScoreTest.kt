package io.trimio.engine.autopilot

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UnderstandingScoreTest {

    private val gold = Understanding(
        domain = "software",
        entities = listOf(Entity(13, 14, "Claude Code", "app"), Entity(15, 15, "Codex", "app"), Entity(17, 17, "OpenCode", "app"), Entity(19, 19, "ChatGPT", "app")),
        lines = listOf("headline", "none", "logos", "terminal", "network", "headline", "meter", "comment").map { LineRead(show = it) },
        hook = Hook(6, "۹۰٪ توکن کمتر"),
        cta = Cta("comment", "کد"),
    )

    @Test
    fun aReadingScoresPerfectAgainstItself() {
        val s = UnderstandingScore.of(gold, gold)
        assertEquals(100, s.total)
        assertEquals(1f, s.showExact)
        assertEquals("show=1.00 (exact 1.00) hook=1 cta=1 entities=1.00 domain=1 total=100", s.summary())
    }

    @Test
    fun equivalentShowsAgreeButAreNotExact() {
        // stamp ~ headline and counter ~ meter render as the same kind of beat; list is not a network.
        val shows = listOf("stamp", "none", "logos", "terminal", "list", "headline", "counter", "comment")
        val s = UnderstandingScore.of(gold.copy(lines = shows.map { LineRead(show = it) }), gold)
        assertEquals(7f / 8, s.show)
        assertEquals(5f / 8, s.showExact)
    }

    @Test
    fun keywordsAndNamesCompareAsAViewerTypesThem() {
        val candidate = gold.copy(
            domain = "ai",
            cta = Cta("comment", "«كد»"),
            entities = listOf(Entity(13, 13, "Claude", "app"), Entity(19, 19, "chatgpt", "app"), Entity(3, 3, "Git", "app")),
        )
        val s = UnderstandingScore.of(candidate, gold)
        assertTrue(s.ctaKeyword, "quotes and the Arabic kaf do not change the keyword")
        assertTrue(s.domain, "ai and software pick the same look")
        assertEquals(0.5f, s.entityRecall)
        assertEquals("۹۰ درصد".let(UnderstandingScore::normalise), "90درصد")
    }

    @Test
    fun missingDecisionsCost() {
        val candidate = gold.copy(lines = gold.lines.take(4), hook = Hook(0), cta = Cta("follow"), entities = emptyList(), domain = "food")
        val s = UnderstandingScore.of(candidate, gold)
        assertFalse(s.hook)
        assertFalse(s.ctaKeyword)
        assertFalse(s.domain)
        assertEquals(0.5f, s.show, "lines the model never read count against it")
        assertEquals(20, s.total)
    }
}
