package io.trimio.engine.retrieval

import io.trimio.core.model.text.TextKey
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CorporaTest {
    @Test
    fun bundledCorporaLoadAndAreWellFormed() = runTest {
        val b = Corpora.bundled()
        assertEquals(36, b.primitives.size)
        assertEquals(27, b.families.size)
        assertTrue(b.sounds.count { it.kind == Corpora.MUSIC } == 6 && b.sounds.count { it.kind == Corpora.SFX } == 10)
        assertTrue(b.glossary.size >= 300, "glossary has ${b.glossary.size} terms")
        assertEquals(setOf("crypto", "forex", "finance", "medical", "tech", "ai", "social"), b.glossary.terms.map { it.domain }.toSet())
        assertTrue(b.exemplars.size >= 100)
        // Every document has bilingual text, and ids are unique per corpus.
        for (d in b.primitives + b.families + b.sounds) assertTrue(d.text.isNotBlank() && d.fa.isNotBlank(), d.id)
        for (list in listOf(b.primitives, b.families, b.sounds, b.glossary.docs(), b.exemplars.map { it.doc() })) {
            assertEquals(list.size, list.map { it.id }.toSet().size)
        }
        // Glossary notes are short enough for an on-screen definition; canonical spellings are not variants of another term.
        assertTrue(b.glossary.terms.all { it.note.length <= 60 })
    }

    @Test
    fun glossaryFindsTermsBySpellingAndNeverCorrectsSynonyms() = runTest {
        val g = Corpora.bundled().glossary
        val found = g.find("قیمت بیت کوین و اتریم امروز با RSI").map { it.term.term }
        assertTrue("بیت‌کوین" in found && "اتریوم" in found && "آر اس آی" in found, found.toString())
        // A misspelling is corrected; an alias (synonym, English name) is found but never "corrected".
        assertEquals("اتریوم", g.spell("اتریم"))
        assertEquals("بیت‌کوین", g.spell("بیتکوین"))
        assertNull(g.spell("bitcoin"))
        assertNull(g.spell("ابر"))
        // A one-word Persian synonym is not "found" by spelling (it is an everyday word).
        assertTrue(g.find("ابر سیاه").none { it.term.term == "فضای ابری" })
        // The recogniser prompt stays short and in the topic.
        val prompt = g.asrPrompt("crypto")
        assertTrue(prompt.length <= 220 && "تتر" in prompt)
    }

    @Test
    fun creatorsOwnGlossaryWins() = runTest {
        val memory = UserMemory(MemoryStore.InMemory())
        memory.remember(MemoryEntry(MemoryKind.Brand, "ترایمیو", "اپ ساخت ویدیو"))
        memory.remember(MemoryEntry(MemoryKind.Correction, "تریمیو", "ترایمیو"))
        memory.remember(MemoryEntry(MemoryKind.Banned, "ارزان"))
        memory.remember(MemoryEntry(MemoryKind.Banned, "ارزان"))
        val own = memory.glossary()
        assertEquals("ترایمیو", own.spell("تریمیو"))
        assertEquals(setOf(TextKey.of("ارزان")), memory.banned())
        assertEquals(3, memory.entries().size)
        val merged = own + Corpora.bundled().glossary
        assertEquals("ترایمیو", merged.find("با ترایمیو بساز").single().term.term)
        // It persists through the store.
        val store = MemoryStore.InMemory()
        UserMemory(store).remember(MemoryEntry(MemoryKind.Tone, "آرام و بدون اصطلاح عامیانه", pinned = true))
        assertEquals(1, UserMemory(store).pinned().size)
        memory.forget(MemoryKind.Banned, "ارزان")
        assertTrue(memory.banned().isEmpty())
    }

    @Test
    fun tasteMemoryKeepsTheBestAndNewest() = runTest {
        val store = MemoryStore.InMemory()
        val taste = TasteMemory(store, capacity = 3)
        taste.record(listOf(Exemplar("a", "یک", score = 9.5f), Exemplar("b", "دو", score = 7f)))
        assertEquals(listOf("a"), taste.all().map { it.id })
        taste.record((1..4).map { Exemplar("n$it", "خط $it", score = 9f + it / 10f) })
        val kept = TasteMemory(store, capacity = 3).all()
        assertEquals(3, kept.size)
        assertTrue(kept.all { it.source == "taste" })
        assertTrue("n4" in kept.map { it.id })
    }

    @Test
    fun retrievalAnswersByWordsWithoutModels() = runTest {
        val pictures = listOf(
            Corpora.Picture("noto:1f680", "rocket", listOf("launch", "space"), listOf("موشک", "پرتاب")),
            Corpora.Picture("noto:1f4c8", "chart increasing", listOf("growth", "profit"), listOf("نمودار", "رشد", "سود")),
        )
        val r = Retrieval.open(pictures)
        assertTrue(!r.semantic)
        assertEquals("noto:1f680", r.visuals("موشک بالا رفت").hits.first().doc.id)
        val meter = r.visuals("سهمیه آروم‌آروم کم میشه", kinds = Corpora.STRUCTURES).hits
        assertTrue(meter.isEmpty() || meter.all { it.doc.kind in Corpora.STRUCTURES })
        assertEquals("music:tense", r.sounds("ریسک و هشدار و سقوط بازار", Corpora.MUSIC).hits.first().doc.id)
        val examples = r.examples("مدل مثل کارآموزه", 2)
        assertTrue(examples.isNotEmpty())
        val terms = r.terms("قیمت تتر و بیت‌کوین")
        assertTrue(terms.map { it.term }.containsAll(listOf("تتر", "بیت‌کوین")))
        val hints = r.hints(listOf("قیمت تتر امروز بالا رفت"), "crypto")
        assertTrue(hints.startsWith("Terms") && "تتر" in hints && "Examples" in hints, hints)
    }
}
