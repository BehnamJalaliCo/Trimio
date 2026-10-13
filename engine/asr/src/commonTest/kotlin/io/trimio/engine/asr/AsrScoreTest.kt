package io.trimio.engine.asr

import kotlin.test.Test
import kotlin.test.assertEquals

class AsrScoreTest {
    @Test
    fun persianFormsHalfSpacesAndMarksDoNotCountAsErrors() {
        val s = AsrScore.of("این پروژه را نصب می‌کنه.", "اين پروژه را نصب ميکنه")
        assertEquals(0f, s.wer)
    }

    @Test
    fun countsEditsAndGlossaryTerms() {
        val gold = "این ریپو رو به Claude Code یا ChatGPT بدی"
        val hyp = "این ریپو به کلاد کد یا ChatGPT بدی برات"
        val s = AsrScore.of(gold, hyp, glossary = listOf("Claude Code", "ChatGPT", "ریپو", "Codex"))
        // رو deleted; Claude Code → کلاد کد (2 substitutions); برات inserted.
        assertEquals(9, s.words)
        assertEquals(2, s.substitutions)
        assertEquals(1, s.deletions)
        assertEquals(1, s.insertions)
        assertEquals(3, s.terms)
        assertEquals(2, s.termsRight)
        assertEquals(listOf("Claude Code"), s.missedTerms)
    }
}
