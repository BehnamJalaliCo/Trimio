package io.trimio.engine.autopilot

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The owner's real 2.5-minute Persian benchmark: every word it put on screen misspelled, every
 * correct term a fix broke, and every graphic text that did not read on its own.
 */
class ScreenWordsTest {
    private val lexicon = runBlocking { PersianSpelling.load() }

    private fun fixed(sentence: String): String {
        val words = sentence.split(' ')
        val fixes = lexicon.correct(words)
        return words.indices.joinToString(" ") { fixes[it] ?: words[it] }.split(' ').filter { it.isNotEmpty() }.joinToString(" ")
    }

    @Test
    fun protectedTermsAreNeverSplitOrChanged() {
        val tokens = fixed("باعث میشه 90 درصد توی مصرف توکن صرف جوی بشه.").split(' ')
        assertTrue("توکن" in tokens && "کن" !in tokens, tokens.joinToString(" "))
        val opus = fixed("اینجاست که اوپس وارد مسئله میشه. اوپس فوقلاده باهوشه").split(' ')
        assertTrue(opus.count { it == "اوپس" } == 2 && "او" !in opus && "پس" !in opus, opus.joinToString(" "))
        for (term in listOf("توکن", "اوپس", "ریپو", "پرامپت", "تسک", "کامنت", "دایرکت", "فالو", "تتر", "ووچر", "ترید", "کمپین", "اپلیکیشن")) {
            assertTrue(Loanwords.protected(term), term)
            assertNull(lexicon.correct(listOf("این", term, "رو"))[1], "«$term» is left as it is")
        }
    }

    @Test
    fun aValidWordIsNeverSplitInTwo() {
        assertNull(lexicon.correct(listOf("اوپس", "فوق‌العاده", "باهوشه"))[0])
        assertNull(lexicon.correct(listOf("مصرف", "توکن"))[1])
    }

    @Test
    fun theBenchmarkMisspellingsArePutRight() {
        val cases = mapOf(
            "این مودل مثل کاراموز تازه وارد شرکته" to listOf("مدل", "کارآموز"),
            "مودل فیبل قبیل‌ترین مودل کلاد هستش" to listOf("قوی‌ترین"),
            "دیگتر جوابتونو میده" to listOf("دیرتر"),
            "من تو پیجم در مورد حوشه مسئولی زیاد پست میذارم" to listOf("هوش", "مصنوعی"),
            "اوپس فوقلاده باهوشه، قدرت استدالش بسیار بالاست" to listOf("فوق‌العاده", "استدلالش"),
            "یا چچی پیتیت بدی برات نسب بکنه، بعد از نسب یه مپ کامل" to listOf("نصب"),
            "به کلادکود، کودکس یا اوپنکود" to listOf("کلاد", "کد،", "کدکس", "اوپن‌کد"),
            "سختترین‌ها و دوشوارترین‌ها" to listOf("سخت‌ترین‌ها", "دشوارترین‌ها"),
            "برو توی چتچی پیتی بزن، نه تاش استاب می‌خوره" to listOf("چت‌جی‌پی‌تی", "استاپ"),
            "از کل پروژه تیه میکنه" to listOf("تهیه"),
            "توی مصرف توکن صرف جوی بشه" to listOf("صرفه‌جویی"),
            "کافیه که این ریپور رو به کلاد بدی" to listOf("ریپو"),
        )
        for ((heard, wanted) in cases) {
            val out = fixed(heard).split(' ')
            for (w in wanted) assertTrue(w in out, "«$heard» → «${out.joinToString(" ")}» has «$w»")
        }
        val after = fixed("یا چچی پیتیت بدی برات نسب بکنه، بعد از نسب یه مپ کامل").split(' ')
        assertFalse("نسب" in after, "both «نسب» are «نصب»: ${after.joinToString(" ")}")
    }

    @Test
    fun onlyWordsTermsAndNamesAreShown() {
        val screen = ScreenWords(lexicon, ScreenWords.namesIn("این مدل فیبل قوی‌ترین مدل کلاد هستش".split(' ')) { ScreenWords(lexicon).shows(it) })
        for (ok in listOf("توکن", "می‌کنه", "مصرفتونو", "سخت‌ترین‌ها", "دایرکتتو", "کارآموز", "فیبل", "اوپس", "هوش", "مصنوعی", "۹۰")) assertTrue(screen.shows(ok), ok)
        for (bad in listOf("کاراموز", "پیتیت", "تذکیر", "مودل", "قبیل‌ترین", "دیگتر", "حوشه", "فوقلاده", "استدالش")) assertFalse(screen.shows(bad), bad)
        assertEquals("کارآموز", screen.respelled("کاراموز"))
        assertEquals("مدل", screen.respelled("مودل"))
    }

    @Test
    fun graphicTextsReadOnTheirOwn() {
        val fragments = listOf(
            "درد اکثر کارای", "باید استفاده", "زیاد استفاده", "اکثرشو", "همینجوری سخت", "پروژه تهیه", "دیرتر جوابتونو", "وارد مسئله", "تازه", "خودش فکر",
            "مورد هوش مصنوعی", "سخت‌ترین",
            // The owner's 155 s video (the 1.0.14 crash): what the rules first put on screen from it.
            "اونم", "شرایطو", "کلمه کانالو", "کانال‌ها تبلیغات", "مثلا خوبه", "چجوری سود", "کامیونیتی‌هایی", "بالاتر", "ورودیمونه رایگان",
            "خوبه", "چجوریه واقعیتشون چجوریه", "چت‌جی‌پی‌تی آقا سودشون",
        )
        for (bad in fragments) {
            assertNotNull(Fragments.fault(bad), bad)
        }
        for (good in listOf("کارهای پیچیده", "کارای پیچیده", "محدودیت مصرف", "سرمایه اولیه", "هوش مصنوعی", "قوی‌ترین مدل کلاد", "کارآموز تازه‌وارد", "صرافی")) {
            assertNull(Fragments.fault(good), good)
        }
        val said = "بکنید و سودتو برداشت بکنید. موفق باشید.".split(' ')
        assertFalse(Fragments.quotable(listOf("ترید") + said, 1..said.size), "a quote starting on «بکنید» mid-sentence")
        val whole = "این مدل مثل کارآموز تازه‌وارد شرکته.".split(' ')
        assertTrue(Fragments.quotable(listOf("بدید.") + whole, 1..whole.size), "a whole sentence")
    }

    /**
     * The owner's 1/10 reel (1.0.29): the director's own headlines were refused as "not said" or as starting on the verb
     * «ده» (ten), while lone words cut from the speech («سایتمو», «کانال‌های», «پولی») went on screen.
     */
    @Test
    fun theDirectorsHeadlinesShowWhenTheySayWhatWasSaid() {
        for (bad in listOf("سایتمو", "کانال‌های")) assertNotNull(Fragments.fault(bad), bad)
        for (good in listOf("ده سیگنال در روز", "تمام سیگنال‌ها در CoinPro.com", "۵۰ تتر ورودی")) assertNull(Fragments.fault(good), good)

        fun line(s: String) = s.split(' ')
        val brief = line("اسم سایت ما CoinPro.com هست.")
        val grounded = mapOf(
            "ده سیگنال در روز" to "ده تا دونه در روز کلن سیگنال می‌ده،",
            "۵۰ تتر ورودی" to "می‌تونی بری، کلن پنجاه تتر ورودی مونه،",
            "ورود با پنجاه دلار" to "پنجاه دولار کف ورودیمونه رایگان هستیم",
            "تمام سیگنال‌ها در CoinPro.com" to "CoinPro.com می‌تونی بری، تمام سیگنال همونو گذاشتیم،",
            "شارژ حساب خودت" to "اونم تازه حساب خودتو شارج می‌کنی،",
            // «تومن» is how «تومان» is said.
            "ورودی ۲,۷۰۰,۰۰۰ تومان" to "راضی، ورودیشم دو میلیون و هفتصد هزار تومنه،",
        )
        for ((title, said) in grounded) assertTrue(Headlines.grounded(title, line(said), emptyList(), brief), title)
        val invented = mapOf(
            "ورود با اطمینان" to "راضی، ورودیشم دو میلیون و هفتصد هزار تومنه،",
            "شفافیت بی‌نظیر ما" to "سفید شفاف‌تر از پیج ما و سایت ما اصلا نداریم",
            "بدون سر و صدا سود" to "داره، اونم کردن تو بوق و کرنا که آقا ما داریم",
            "۲۰ سیگنال در روز" to "ده تا دونه در روز کلن سیگنال می‌ده،",
        )
        for ((title, said) in invented) assertFalse(Headlines.grounded(title, line(said), emptyList(), brief), title)

        assertTrue(Headlines.fits("ده سیگنال در روز") && Headlines.fits("CoinPro.com"))
        assertFalse(Headlines.fits("پولی") || Headlines.fits("شفافیت"))
        assertEquals("ورود با ۵۰ دلار", Headlines.written("ورود با پنجاه دلار", persian = true))
        assertEquals("۱۰ سیگنال در روز", Headlines.written("ده سیگنال در روز", persian = true))
    }
}
