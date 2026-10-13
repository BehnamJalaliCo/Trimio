package io.trimio.engine.autopilot.explainer

import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Word
import io.trimio.engine.autopilot.PersianSpelling
import kotlinx.coroutines.runBlocking

/**
 * Synthetic narrations for the screenwriter's tests: plain scripts timed like speech (about 2.6
 * words a second, a breath after commas and a pause after sentences), in Persian and English, on
 * the subjects explainers are made of.
 */
internal object Narrations {
    val lexicon: PersianSpelling by lazy { runBlocking { PersianSpelling.load() } }

    fun words(script: String, language: Language): List<Word> {
        var t = 0.3
        return script.split(Regex("\\s+")).filter { it.isNotBlank() }.map { w ->
            val length = 0.22 + 0.035 * w.length
            val word = Word(w, TimeRange((t * 1000).toLong(), ((t + length) * 1000).toLong()), language = language)
            t += length + when (w.last()) {
                '.', '?', '؟', '!' -> 0.55
                ',', '،', ':' -> 0.25
                else -> 0.08
            }
            word
        }
    }

    fun of(script: String, persian: Boolean, prompt: String = ""): Narration =
        Narration.of(words(script, if (persian) Language.Persian else Language.English), prompt, lexicon = lexicon)

    /** AI model tiers, said the way a creator says it (Latin names inside Persian speech). */
    val FA_TECH = """
        شاید هر روز از هوش مصنوعی استفاده کنی، ولی فرق مدل‌هاش رو ندونی.
        مدل اول اسمش Haiku هست. مثل یه کارآموز تازه‌وارد شرکته.
        کارهای سبک و تکراری رو بهش بده. محدودیت مصرفت خیلی آروم کم میشه.
        مدل دوم اسمش Sonnet هست. به درد اکثر کارهای روزمره می‌خوره.
        اول با خودش فکر می‌کنه که بهترین راه انجامش چیه. ولی توی مسائل عمیق و پیچیده گیر می‌کنه.
        اینجاست که Opus وارد میشه. Opus باهوش، خلاق و دقیقه.
        دیرتر جواب میده و محدودیت مصرفت زودتر تموم میشه. ولی توی کارهای پیچیده خیلی خوب جواب میده.
        در نهایت Fable قوی‌ترین مدل هست. فقط برای سخت‌ترین قسمت کار ازش استفاده کن. بقیه کار رو بده به Opus.
    """.trimIndent()

    val FA_HEALTH = """
        خواب خوب راز سلامتیه. اول، هر شب یه ساعت ثابت بخواب. بدنت مثل یه ساعت عادت می‌کنه.
        دوم، یک ساعت قبل از خواب گوشی رو کنار بذار. نور صفحه مغزت رو بیدار نگه می‌داره.
        سوم، قهوه رو بعد از ظهر کم کن. کافئین تا شش ساعت توی بدنت می‌مونه.
        اگه این سه تا عادت رو داشته باشی، انرژیت هر روز بیشتر میشه.
    """.trimIndent()

    val FA_FINANCE = """
        تورم یعنی پولت هر سال ارزش کمتری داره. فرض کن صد میلیون تومن پس‌انداز داری.
        اگه تورم چهل درصد باشه، بعد از یه سال قدرت خریدت خیلی کم میشه.
        برای همین باید پولت رو جایی بذاری که رشد کنه. پول کم کم روی هم جمع میشه.
        ولی مراقب ریسک باش. همه پولت رو یه جا سرمایه‌گذاری نکن.
    """.trimIndent()

    val EN_FINANCE = """
        Most people think saving money is boring. But here is the secret. First, pay yourself before you pay anything else.
        Put ten percent of your income aside every month. Then, let compound interest do the work.
        Your savings grow slowly at first, then faster and faster. After twenty years, the same habit can be worth three times more.
        Finally, avoid the biggest mistake: never touch the savings for small purchases. Follow for more money tips.
    """.trimIndent()

    val EN_HISTORY = """
        In the fifteenth century, books were copied by hand. A single book could take a monk a whole year.
        Then, around 1450, Gutenberg built the printing press. Suddenly, hundreds of copies could be made in weeks.
        Ideas spread across Europe faster than ever before. Within fifty years, millions of books were printed.
        The printing press changed how the world learns.
    """.trimIndent()

    val EN_HOWTO = """
        Here is how to make a great pour over coffee. Step one, heat the water to ninety four degrees.
        Step two, grind the beans medium fine. Step three, pour a little water and wait thirty seconds.
        This is called the bloom. Step four, pour the rest slowly in circles.
        The whole brew should take about three minutes. Too fast, and it tastes sour. Too slow, and it tastes bitter.
    """.trimIndent()

    val EN_TECH = """
        Every app you use talks to a server. First, your phone sends a request. Then the server thinks about it and finds the answer.
        But a busy server gets stuck when millions of people ask at once. That is where caching comes in.
        A cache is like a small notebook next to the chef. The most common answers are kept close, so the reply is instant.
        The result: faster apps and smaller bills.
    """.trimIndent()

    val ALL = listOf(
        "fa-tech" to (FA_TECH to true), "fa-health" to (FA_HEALTH to true), "fa-finance" to (FA_FINANCE to true),
        "en-finance" to (EN_FINANCE to false), "en-history" to (EN_HISTORY to false), "en-howto" to (EN_HOWTO to false), "en-tech" to (EN_TECH to false),
    )
}
