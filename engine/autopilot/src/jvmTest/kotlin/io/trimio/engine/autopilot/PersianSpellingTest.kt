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
            "ساتم" to "ساعت هم", "سی" to "سیصد", "سد" to "", "میتونید" to "می‌تونید", "همه" to "همه‌تون", "تون" to "",
            // Given away by their neighbours (see confusionsNeedTheirContext).
            "پوست" to "پست", "کارورد" to "کاربر", "ترک" to "ترید",
        )
        for ((i, fix) in fixes) {
            val core = heard[i].trimEnd('،', '.')
            assertEquals(expected[core], fix.trimEnd('،', '.'), "«${heard[i]}» was changed to «$fix»")
        }
        val text = heard.indices.mapNotNull { i -> (fixes[i] ?: heard[i]).takeIf { it.isNotEmpty() } }.joinToString(" ")
        for (right in listOf(
            "دایرکت می‌گه", "فلان صرافی بود،", "می‌رفتیم ثبت‌نام می‌کردیم", "پنجاه هزار بازدید", "چهل و هشت ساعت هم", "سیصد و پنجاه تتر", "که می‌تونید همه‌تون",
            "این رو پست کردم، یعنی پست گذاشتم،", "برای هر کاربر در", "و ترید بکنی",
        )) {
            assertTrue(right in text, "missing «$right» in $text")
        }
        // Each listed slip, every time it occurs (سبتنام, سی سد and پوست twice).
        assertEquals(19, fixes.size, "$fixes")
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
        // «روزمرد» is no word, «روزمره» is one slip away: fixed. «تیه» before «می‌کنه» is «تهیه» (a confusion
        // settled by the next word). «نسب» and «کود» are words of their own (lineage, fertiliser): only the brief
        // or the director can say «نصب», «کد».
        assertEquals(mapOf(1 to "روزمره"), spelling.correct("زندگی روزمرد ما".split(' ')))
        assertEquals(mapOf(6 to "تهیه", 7 to "می‌کنه"), spelling.correct("بعد از نسب یه مپ کامل تیه میکنه".split(' ')))
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
    fun severalWordFixesBecomeSeveralWords() = runBlocking {
        val texts = listOf("و", "چلا", "هشت", "ساتم", "بیشتر", "نمونده.", "ساعت")
        val transcript = Transcript(Language.Persian, texts.mapIndexed { k, w -> Word(w, TimeRange(k * 500L, k * 500L + 400), language = Language.Persian) })
        val fixes = Proofreader.fromLexicon(texts, brief = "")
        assertEquals(mapOf(1 to "چهل و", 3 to "ساعت هم"), fixes)
        val fixed = transcript.withSpelling(fixes)
        assertEquals(listOf("و", "چهل", "و", "هشت", "ساعت", "هم", "بیشتر", "نمونده.", "ساعت"), fixed.words.map { it.text })
        assertTrue(fixed.words.none { w -> w.text.any { it.isWhitespace() } || w.text.isEmpty() })
        // «چلا» (500–900 ms) shared by letters: «چهل» 3/4, «و» 1/4; the words stay in order and inside the heard word.
        assertEquals(TimeRange(500, 800), fixed.words[1].range)
        assertEquals(TimeRange(800, 900), fixed.words[2].range)
        assertEquals(TimeRange(1500, 1900), TimeRange(fixed.words[4].range.startMs, fixed.words[5].range.endMs))
        // «ساعتم» stays «ساعتم» where no number counts it.
        assertEquals("ساعتم", spelling.correct(listOf("این", "ساتم", "ساعت"))[1])
    }

    @Test
    fun alignLeavesSeveralWordFixesWhole() {
        // The director's «چهل هشت» must not shrink «چهل و» to «چهل».
        assertTrue(Proofreader.align(listOf("چهل و", "هشت"), 0, "چهل هشت").isEmpty())
    }

    @Test
    fun briefEzafeIsTheSameWord() {
        assertTrue(Proofreader.fromBrief(listOf("سرمایه", "اولیه"), "۳۵۰ تتر سرمایهٔ اولیه").isEmpty())
        assertTrue(Proofreader.fromBrief(listOf("خانه"), "خانۀ ما").isEmpty())
        // A real slip still takes the brief's word, without the ezafe mark.
        assertEquals(mapOf(0 to "سرمایه"), Proofreader.fromBrief(listOf("سرمایح"), "سرمایهٔ اولیه"))
    }

    @Test
    fun confusionsNeedTheirContext() {
        fun fixed(text: String) = text.split(' ').let { ws -> spelling.correct(ws).let { f -> ws.indices.joinToString(" ") { f[it] ?: ws[it] } } }
        // «پوست گذاشتم» can only be a post; «پوست کردم» is peeling unless the talk is about posts.
        assertEquals("دیروز پست گذاشتم", fixed("دیروز پوست گذاشتم"))
        assertEquals("پرتقال رو پوست کردم", fixed("پرتقال رو پوست کردم"))
        assertEquals("تو پیج پست کردم و کامنت بذارید", fixed("تو پیج پوست کردم و کامنت بذارید"))
        assertEquals("پوست صورتم خشک شده", fixed("پوست صورتم خشک شده"))
        // «ترک کردن» is trading only in a talk about crypto, and never with an object («بازار رو ترک کنی»).
        assertEquals("ترک کردم", fixed("ترک کردم"))
        assertEquals("با تتر و صرافی راحت ترید بکنی", fixed("با تتر و صرافی راحت ترک بکنی"))
        assertEquals("با تتر و صرافی بازار رو ترک کنی", fixed("با تتر و صرافی بازار رو ترک کنی"))
        // «تیه» is never done or made; alone it stays.
        assertEquals("نقشه رو تهیه می‌کنه", fixed("نقشه رو تیه می‌کنه"))
        assertEquals("تیه", fixed("تیه"))
    }

    @Test
    fun silentHeBeforeAClitic() {
        assertEquals(mapOf(1 to "روزمره‌ت"), spelling.correct(listOf("کارهای", "روزمرت", "سریع‌تر")))
        assertTrue(spelling.correct(listOf("دستت", "درد", "نکنه")).isEmpty())
    }

    @Test
    fun briefCompoundsJoinTheirHeardHalves() {
        // Video 1: «صرف جوی» heard for the brief's «صرفه‌جویی»; the halves become one word.
        val fixes = Proofreader.fromBrief(listOf("توکن", "صرف", "جوی", "بشه."), "۹۰٪ صرفه‌جویی در توکن")
        assertEquals(mapOf(1 to "صرفه‌جویی", 2 to ""), fixes)
        assertTrue(1 !in Proofreader.fromBrief(listOf("صرف", "شد"), "۹۰٪ صرفه‌جویی در توکن"), "no compound without its second half")
    }

    @Test
    fun directorRespellingsThatAreWorse() {
        assertTrue(spelling.worse("می‌گه", "میگه"), "half-space removed")
        assertTrue(spelling.worse("دایرکتتو", "دایرکتت"), "clitic cut off")
        assertTrue(spelling.worse("ووچر", "وچر"), "letter deleted into no word")
        assertTrue(spelling.worse("می‌کردیم،", "میکردیم"), "punctuation does not hide it")
        assertTrue(!spelling.worse("پوست", "پست"), "deleted into a more common word")
        assertTrue(!spelling.worse("زمینن", "زمین"), "doubled last letter of no word")
        assertTrue(!spelling.worse("سبتنام", "ثبت‌نام"))
        assertTrue(!spelling.worse("میکنه", "می‌کنه"))
        assertTrue(!spelling.worse("کارورد", "کاربر"))
    }

    /**
     * Whole runs (not run by default): TRIMIO_SPELLING_RUNS=<transcript.json>:<prompt.txt>[,…] prints what
     * [Proofreader.fromBrief] and [Proofreader.fromLexicon] make of a recorded transcript ([{"w": …}, …]).
     */
    @Test
    fun recordedRuns() = runBlocking {
        val runs = System.getenv("TRIMIO_SPELLING_RUNS")?.split(',') ?: return@runBlocking
        for (run in runs) {
            val (json, prompt) = run.split(':')
            val words = kotlinx.serialization.json.Json.parseToJsonElement(File(json).readText()).let { it as kotlinx.serialization.json.JsonArray }
                .map { (it as kotlinx.serialization.json.JsonObject)["w"].toString().trim('"') }
            val brief = File(prompt).readText()
            val spelled = Proofreader.fromBrief(words, brief)
            val lexical = Proofreader.fromLexicon(words, brief, spelled)
            val all = spelled + lexical
            println("== $json")
            spelled.forEach { (i, w) -> println("  brief: ${words[i]} → $w") }
            lexical.forEach { (i, w) -> println("  lexicon: ${words[i]} → ${w.ifEmpty { "(joined)" }}") }
            println("  " + words.indices.mapNotNull { i -> (all[i] ?: words[i]).takeIf { it.isNotEmpty() } }.joinToString(" "))
        }
    }

    @Test
    fun mergedWordsSplitAtAKnownPair() {
        assertEquals(mapOf(1 to "که دیگه"), spelling.correct(listOf("گفت", "کهدیگه", "نمیام")))
        assertEquals(mapOf(1 to "همه چیزم"), spelling.correct(listOf("من", "همهچیذم", "رو")))
        assertEquals(mapOf(1 to "که امروز"), spelling.correct(listOf("فرصتی", "کهامروض", "داریم")))
        assertTrue(spelling.correct(listOf("کتابخونه", "همیشه", "شلوغه")).isEmpty())
    }

    @Test
    fun splitPrefixesJoin() {
        assertEquals(mapOf(1 to "ناامید", 2 to ""), spelling.correct(listOf("واقعاً", "نا", "امید", "بودم")))
        assertEquals(mapOf(0 to "بی‌ترتیب", 1 to ""), spelling.correct(listOf("بی", "ترتیب", "می‌ریزم")))
        assertEquals(mapOf(1 to "بی‌ترتیب"), spelling.correct(listOf("کارامو", "بیترتیب", "می‌ریزم")))
        // «هم کار» is "work too", not «همکار».
        assertTrue(spelling.correct(listOf("ما", "هم", "کار", "می‌کنیم")).isEmpty())
    }

    @Test
    fun placeNamesJoinAfterAStreet() {
        assertEquals(mapOf(1 to "ولیعصره،", 2 to ""), spelling.correct(listOf("خیابون", "ولی", "عصره،", "بالاتر")))
        assertTrue(spelling.correct(listOf("ولی", "عصر", "که", "شد")).isEmpty(), "but in the evening")
    }

    @Test
    fun numbersHeardAsOtherWords() {
        assertEquals("صد", spelling.correct("سد و بیست و پنج متره".split(' '))[0])
        assertEquals("شصت", spelling.correct("شست درصد".split(' '))[0])
        assertEquals("دوازده", spelling.correct("دوازه ساعت".split(' '))[0])
        assertTrue(spelling.correct("پشت سد کرج".split(' ')).isEmpty())
        assertTrue(spelling.correct("انگشت شست".split(' ')).isEmpty())
    }

    @Test
    fun briefLatinTargetsAreNamesNotEverydayWords() {
        assertTrue(Proofreader.fromBrief(listOf("I", "made", "25", "more"), "make it engaging", spelling).isEmpty())
        val news = "News explainer, serious tone. Show the numbers with charts and counters, dark look, no music."
        assertTrue(Proofreader.fromBrief(listOf("you", "need", "to", "watch", "Chat"), news, spelling).isEmpty())
        val coach = "Customer testimonial for Coach Maya's program. Warm and honest, calm music, phrase captions."
        assertEquals(mapOf(0 to "Coach", 1 to "Maya's"), Proofreader.fromBrief(listOf("coach", "Mya's", "group", "Walk", "back"), coach, spelling))
        val tools = "Punchy reel. Show the logos of Notion, ChatGPT, Claude and Google Calendar as I name them, word-by-word captions."
        assertEquals(
            mapOf(0 to "Notion.", 1 to "ChatGPT", 2 to "", 4 to "Claude،"),
            Proofreader.fromBrief(listOf("Nation.", "Chat", "GPT", "یا", "Cloud،"), tools, spelling),
        )
        assertEquals(mapOf(1 to "Google Calendar", 2 to ""), Proofreader.fromBrief(listOf("تو", "گوگل", "کلندر", "و"), tools, spelling))
    }

    @Test
    fun brandsSaidInPersianTakeTheBriefsSpelling() {
        assertEquals(mapOf(1 to "Nobitex"), Proofreader.fromBrief(listOf("صرافی", "نوبیتکس", "برای"), "ریلز برای کمپین Nobitex، سبک نوآر", spelling))
        assertEquals(mapOf(1 to "Lumina"), Proofreader.fromBrief(listOf("سرم", "لومینا", "که"), "ریلز فروش برای لانچ سرم Lumina؛ پرانرژی", spelling))
        assertEquals(
            mapOf(1 to "Nike", 3 to "Adidas"),
            Proofreader.fromBrief(listOf("کتونی‌های", "نایک", "و", "آدیداس", "پنجاه"), "تخفیف Nike و Adidas", spelling),
        )
        assertEquals(mapOf(0 to "Galaxy S24 Ultra", 1 to "", 2 to "", 3 to ""), Proofreader.fromBrief(listOf("گلکسی", "اس", "۲۴", "اولترا"), "مقایسهٔ Galaxy S24 Ultra و iPhone 15 Pro Max", spelling))
        // Not named in the brief, or a common Persian word: stays Persian.
        assertTrue(Proofreader.fromBrief(listOf("کتونی‌های", "نایک"), "یه ریلز جذاب بساز", spelling).isEmpty())
        assertTrue(Proofreader.fromBrief(listOf("آدم", "نیک", "و", "خوب"), "تخفیف Nike", spelling).isEmpty())
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

    @Test
    fun splittingAWordThatOverlapsTheNextKeepsTheWordsInOrder() {
        // Whisper's «ساتم» runs to 2.0 s while «بیشتر» already starts at 1.2 s: the fix «ساعت هم» splits it.
        val words = listOf(Word("چلا", TimeRange(0, 600), language = Language.Persian), Word("ساتم", TimeRange(600, 2000), language = Language.Persian), Word("بیشتر", TimeRange(1200, 2400), language = Language.Persian))
        val fixed = Transcript(Language.Persian, words).withSpelling(mapOf(1 to "ساعت هم")).words
        assertEquals(listOf("چلا", "ساعت", "هم", "بیشتر"), fixed.map { it.text })
        assertTrue(fixed.zipWithNext().all { (a, b) -> a.range.startMs <= b.range.startMs })
    }

    private companion object {
        const val SAMPLE = 5000
    }
}
