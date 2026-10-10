package io.trimio.engine.autopilot

import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PersianSpellingTest {
    private val spelling = runBlocking { PersianSpelling.load() }

    /** The crypto-campaign benchmark exactly as whisper large-v3 wrote it (synthetic text, no personal data). */
    private val heard = (
        "یکی اومده تو دایرک می‌گه بابا فلان سررافی بود، همون اولش می‌گفتیم می‌رفتیم سبتنام می‌کردیم، اسمش رو تو همون کلیپت به ما می‌گفتی، " +
            "ما می‌رفتیم سبتنام می‌کردیم، دیگه نیازی نبود، نمی‌دونم کامنت بذاریم و بریم دایرکت. نه عزیزم من اینجوری نیست، این کمپینو مخصوص پیج " +
            "من فعال کردن برای هزار نفر اول. الان هزار نفر اول توی دوازده ساعت دیروز تموم شد، دیروز یعنی من این رو پوست کردم، یعنی پوست گذاشتم، " +
            "هزار نفر اول درجا پر شد، یعنی توی کمتر از دوازده ساعت پنجا هزار بازید خورد و پنج هزار کامنت. و الان دو هزار تا دیگه ظرفیت باز شده و " +
            "چلا هشت ساتم بیشتر نمونده از کمپینشون. سی سد و پنجاه تتر سرمایه اولیه برای هر کارورد در نظر گرفتن در قالب ووچر که میتونید همه تون " +
            "دریافت بکنید. کلمه تتر رو برام کامنت بکن، بعد برو دایرکتتو چک کن، همونجا همه توضیحاتو دادن برای دریافت سی سد و پنجاه تتر که خیلی " +
            "راحت دریافت بکنی و ترک بکنی و سودتو برداشت بکنی. موفق باشید."
        ).split(' ')

    @Test
    fun benchmarkTranscriptLosesItsSpellingMistakes() {
        val fixes = spelling.correct(heard)
        val expected = mapOf(
            "دایرک" to "دایرکت", "سررافی" to "صرافی", "سبتنام" to "ثبت‌نام", "پنجا" to "پنجاه", "بازید" to "بازدید", "چلا" to "چهل و",
            "ساتم" to "ساعتم", "سی" to "سیصد", "سد" to "", "میتونید" to "می‌تونید", "همه" to "همه‌تون", "تون" to "",
        )
        for ((i, fix) in fixes) {
            val core = heard[i].trimEnd('،', '.')
            assertEquals(expected[core], fix.trimEnd('،', '.'), "«${heard[i]}» was changed to «$fix»")
        }
        val text = heard.indices.mapNotNull { i -> (fixes[i] ?: heard[i]).takeIf { it.isNotEmpty() } }.joinToString(" ")
        for (right in listOf("دایرکت می‌گه", "فلان صرافی بود،", "می‌رفتیم ثبت‌نام می‌کردیم", "پنجاه هزار بازدید", "چهل و هشت ساعتم", "سیصد و پنجاه تتر", "که می‌تونید همه‌تون")) {
            assertTrue(right in text, "missing «$right» in $text")
        }
        // Each listed slip, every time it occurs (سبتنام and سی سد twice).
        assertEquals(15, fixes.size, "$fixes")
    }

    @Test
    fun correctColloquialWordsStayAsTheyAre() {
        val words = (
            "اومده می‌گه بابا همون اولش می‌گفتیم نمی‌دونم بذاریم بریم اینجوری تموم دیگه کلیپت کمپینو دایرکتتو توضیحاتو سودتو همونجا کمپینشون " +
                "بیخیال چیکار واسه آره خب عزیزم داداش رفیق باحاله خوشگله چیه کجاست اونجا اینو بهت بهش ازش باهاش دلم می‌خوام نمی‌شه بخورم ببینم " +
                "ووچر تتر کامنت پیج دایرکت ترید لایک فالو استوری آپلود دانلود گوشیمو خونه‌مون رفیقام بچه‌ها حواست مواظب"
            ).split(' ')
        assertTrue(words.size >= 30)
        // One word at a time, so no word gets support from the others.
        val changed = words.filter { spelling.correct(listOf(it)).isNotEmpty() }
        assertTrue(changed.isEmpty(), "correct words changed: $changed")
        assertTrue(spelling.correct(words).isEmpty(), "${spelling.correct(words)}")
    }

    @Test
    fun earlierBenchmarkSlips() {
        // «روزمرد» is no word, «روزمره» is one slip away: fixed. «نسب», «تیه» and «کود» are words of their own
        // (lineage, a rare spelling, fertiliser): only the brief or the director can say «نصب», «تهیه», «کد».
        assertEquals(mapOf(1 to "روزمره"), spelling.correct("زندگی روزمرد ما".split(' ')))
        assertEquals(mapOf(7 to "می‌کنه"), spelling.correct("بعد از نسب یه مپ کامل تیه میکنه".split(' ')))
        assertTrue(spelling.correct("کود رو کپی کن".split(' ')).isEmpty())
    }

    @Test
    fun latinDigitsAndPunctuationAreKept() {
        val words = listOf("Claude", "Code", "۳۵۰", "350", "سررافی؟", "«سبتنام»")
        assertEquals(mapOf(4 to "صرافی؟", 5 to "«ثبت‌نام»"), spelling.correct(words))
    }

    @Test
    fun briefSpellingsKeepPriority() = runBlocking {
        val words = listOf("دایرک", "سی", "سد", "سررافی")
        // The brief already fixed «سد» (say, to a brand): the join must not swallow it.
        val fixes = Proofreader.fromLexicon(words, brief = "", fixed = mapOf(2 to "صد"))
        assertEquals(mapOf(3 to "صرافی"), fixes)
        // A word spelled in the brief is evidence for a slip one letter away.
        assertEquals("دایرکت", Proofreader.fromLexicon(listOf("برو", "دایرک"), brief = "بگو برو دایرکت")[1])
    }

    @Test
    fun joinedWordsBecomeOneWordSpanningBoth() = runBlocking {
        val texts = listOf("سی", "سد", "و", "پنجاه", "تتر.")
        val transcript = Transcript(Language.Persian, texts.mapIndexed { k, w -> Word(w, TimeRange(k * 400L, k * 400L + 300), language = Language.Persian) })
        val fixed = transcript.withSpelling(Proofreader.fromLexicon(texts, brief = ""))
        assertEquals(listOf("سیصد", "و", "پنجاه", "تتر."), fixed.words.map { it.text })
        assertEquals(TimeRange(0, 700), fixed.words[0].range)
    }

    @Test
    fun directorFixesOfUnknownWordsAreConfirmedByTheList() {
        // «کارورد» is no word; the director's «کاربر» is two slips away and a common word.
        assertEquals(mapOf(1 to "کاربر"), Proofreader.align(listOf("هر", "کارورد", "در"), 0, "هر کاربر در"))
        // A colloquial word is never formalised: «تموم» is a word.
        assertTrue(Proofreader.align(listOf("همه", "تموم", "شد"), 0, "همه تمام شد").isEmpty())
    }

    /**
     * Precision on real text (not run by default): TRIMIO_SPELLING_EVAL=<Leipzig *-sentences.txt>[,…] prints every
     * change the pass makes to sentences that are (mostly) spelled right; a reviewer judges each one.
     */
    @Test
    fun precisionOnCorpus() {
        val paths = System.getenv("TRIMIO_SPELLING_EVAL")?.split(',') ?: return
        for (path in paths) {
            val lines = File(path).readLines().map { it.substringAfter('\t') }.shuffled(kotlin.random.Random(1)).take(SAMPLE)
            var words = 0
            val changes = mutableMapOf<String, Int>()
            for (line in lines) {
                val ws = line.split(' ').filter { it.isNotBlank() }
                words += ws.size
                spelling.correct(ws).forEach { (i, w) -> if (w.isNotEmpty()) changes.merge("${ws[i]} → $w" + if (spelling.correct(ws)[i + 1] == "") " [+${ws[i + 1]}]" else "", 1, Int::plus) }
            }
            println("$path: ${lines.size} sentences, $words words, ${changes.values.sum()} changes")
            changes.entries.sortedByDescending { it.value }.forEach { println("  ${it.value} ${it.key}") }
        }
    }

    private companion object {
        const val SAMPLE = 5000
    }
}
