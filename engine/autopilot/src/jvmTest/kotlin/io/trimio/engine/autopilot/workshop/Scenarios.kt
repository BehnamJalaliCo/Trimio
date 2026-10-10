package io.trimio.engine.autopilot.workshop

import io.trimio.engine.autopilot.Cta

/**
 * The workshop's videos, written the way people talk to a phone camera: colloquial Persian with
 * fillers and repeats, English names inside Persian, numbers, years, prices, deadlines, quotes
 * and every kind of ask (comment a word, DM, follow, save, link in bio, share, none). The marks
 * are [ScriptReader]'s: `{clean|heard}` a mistake the recogniser makes here, `~` a filler, `*`
 * a stressed word. Ids and order are stable: the golden archive keys on them.
 */
internal object Scenarios {
    private val MALE = Delivery(f0 = 118f, syllable = 0.155f)
    private val FEMALE = Delivery(f0 = 208f, syllable = 0.15f)
    private val CALM_MALE = Delivery(f0 = 110f, syllable = 0.18f)
    private val CALM_FEMALE = Delivery(f0 = 198f, syllable = 0.175f)

    private class Spec(
        val id: String,
        val language: String,
        val domain: String,
        val prompt: String,
        val script: String,
        val ask: Cta?,
        val delivery: Delivery,
        val footage: Boolean = true,
        /** Chance a longer Persian word gets a sound-alike letter swap. */
        val noise: Float = 0.06f,
    )

    val all: List<Scenario> by lazy { SPECS.map(::build) }

    private fun build(s: Spec): Scenario {
        val tokens = ScriptReader.tokens(s.script)
        val seed = s.id.fold(17L) { h, c -> h * 131 + c.code }
        val timeline = Performance.time(tokens, s.delivery, Dice(seed))
        val heard = Recogniser(seed xor 0x5EED, s.language, s.noise).hear(tokens, timeline)
        return Scenario(
            s.id, s.language, s.domain, s.prompt.trimIndent().trim(), heard.words, heard.truth, s.ask, s.footage, heard.errors, timeline.lengthMs / 1000f,
        ) { SyntheticVoice.render(timeline, s.delivery, seed) }
    }

    private val SPECS = listOf(
        Spec(
            "fa-tutorial-english", "fa", "education", "یه ریلز جذاب بساز",
            "سلام سلام! ~اِم امروز می‌خوام سه تا اشتباه رو بهتون بگم که {تقریباً|تقریبا} همهٔ ما ایرانی‌ها تو {مکالمهٔ|مکالمه} انگلیسی انجامش می‌دیم. " +
                "اشتباه اول: می‌گیم I am agree. نه! agree خودش فعله، باید بگی I agree. اشتباه دوم، ~ااا می‌گیم explain me. نه عزیزم، explain to me، " +
                "یا اصلاً بگو can you explain it? اشتباه سوم که خیلی خیلی رایجه: I have 25 years old. سن رو با be می‌گن، I am 25. " +
                "حالا اینا فقط سه تاش بود. من یه لیست {سی‌تایی|سی تایی} از این {اشتباه‌ها|اشتباها} درست کردم. کلمهٔ لیست رو برام کامنت کن تا فایلش رو برات بفرستم.",
            Cta("comment", "لیست"), FEMALE,
        ),
        Spec(
            "fa-launch-serum", "fa", "beauty", "ریلز فروش برای لانچ سرم Lumina؛ پرانرژی، تخفیف چهل درصد و مهلت تا جمعه رو برجسته کن، سبک روشن و تمیز.",
            "خب خب خب، بالاخره رسید! سرم ویتامین سی {Lumina|لومینا} که این همه منتظرش بودید، از امروز {رسماً|رسما} لانچ شد. " +
                "این سرم فقط تو چهار هفته لک‌ها رو کم‌رنگ می‌کنه. اینو من نمی‌گم، تست بالینیش می‌گه، {نود و دو|۹۲} درصد رضایت. " +
                "حالا خبر خوب: به مناسبت لانچ چهل درصد تخفیف گذاشتیم، یعنی به جای {ششصد و پنجاه|ششصد و پنجا} هزار تومن، سیصد و نود هزار تومن. " +
                "ولی فقط تا جمعه شب! لینک خرید تو {بایوئه،|بایوعه،} دیر بجنبی تموم شده ها.",
            Cta("link"), FEMALE,
        ),
        Spec(
            "fa-cooking-kuku", "fa", "food", "یه ویدیو آشپزی گرم و صمیمی بساز، مواد لازم رو لیست کن، موسیقی آرام.",
            "سلام به همگی! امروز می‌خوایم یه کوکو سبزی درست کنیم که ~ااا خوب پف کنه و وسطش خام نمونه، چون می‌دونم خیلی‌هاتون این مشکل رو دارید. " +
                "مواد لازم: نیم کیلو سبزی کوکو، پنج تا تخم‌مرغ، یه قاشق آرد، یه کم زرشک و گردو، نمک و فلفل و زردچوبه. " +
                "نکتهٔ اول، سبزی رو {حتماً حتماً|حتما حتما} خشک کنید، اگه آب داشته باشه کوکو وا می‌ره. " +
                "نکتهٔ دوم، یه قاشق چای‌خوری بیکینگ پودر بریزید، این راز پف کردنشه. تخم‌مرغ‌ها رو جدا هم بزنید، بعد سبزی رو اضافه کنید. " +
                "تابه رو با روغن زیاد داغ کنید، مواد رو بریزید، درش رو بذارید، با حرارت کم، بیست دقیقه. بعد برش بزنید، برگردونید، ده دقیقه طرف دیگه. " +
                "ببینید چه رنگی گرفته! اگه به کارت اومد سیوش کن که گمش نکنی.",
            Cta("save"), CALM_FEMALE,
        ),
        Spec(
            "fa-medical-vitamin-d", "fa", "medical",
            """
            ویدیو آموزشی پزشکی. لحن جدی و قابل‌اعتماد، سبک روشن و مینیمال.
            «۸۰ درصد» رو با گرافیک عددی نشون بده و سه علامت رو لیست کن.
            بدون موسیقی. کپشن کامل و بدون غلط.
            """,
            "من دکتر نیلوفر احمدی هستم، متخصص داخلی. بذارید یه چیزی رو رک بگم: طبق مطالعات، نزدیک هشتاد درصد ایرانی‌ها کمبود {ویتامین دی|ویتامین D} دارن، " +
                "حتی کسایی که فکر می‌کنن زیاد آفتاب می‌خورن. سه تا علامتش رو بگم: خستگی {مزمن،|مضمن،} دردای استخونی و ریزش مو. " +
                "ولی خواهش می‌کنم {خودسرانه|خودصرانه} قرص پنجاه هزار واحدی نخورید! اول یه آزمایش ساده بدید. اگه سطحش زیر سی بود، با پزشکتون دوز رو تنظیم کنید. " +
                "معمولاً روزی هزار تا دو هزار واحد کافیه. برای نکته‌های بیشتر فالو کنید.",
            Cta("follow"), CALM_FEMALE,
        ),
        Spec(
            "fa-real-estate", "fa", "real-estate", "سبک لوکس و آرام برای معرفی یه آپارتمان؛ متراژ و قیمت رو شیک نشون بده.",
            "اگه دنبال یه خونهٔ دنج و {نورگیر|نور گیر} تو غرب تهرانید، این واحد رو از دست ندید. ~اِم اینجا {سعادت‌آباده،|سعادت آباده،} برج تازه‌سازه، ما الان طبقهٔ دوازدهمیم. " +
                "صد و بیست و پنج متره، سه تا خواب داره، یکیش مستر. پذیرایی رو ببینید، کل این دیوار پنجرهٔ {قدیه،|غدیه،} ویو کامل به کوه. " +
                "آشپزخونه‌اش اپنه با کابینت‌های {های‌گلاس.|هایگلاس.} یه پارکینگ و انباری هم داره. لابی و نگهبانی بیست و چهار ساعته، استخر و سونا هم تو طبقهٔ منفی یکه. " +
                "قیمتش متری صد و ده میلیونه، که برای این منطقه واقعاً منصفانه‌ست. برای بازدید حضوری، کلمهٔ بازدید رو برام دایرکت بفرستید.",
            Cta("dm", "بازدید"), CALM_MALE,
        ),
        Spec(
            "fa-fitness-abs", "fa", "fitness", "پرانرژی و تند؛ شمارش حرکت‌ها رو نشون بده",
            "سه تا حرکت، ده دقیقه، هر روز! اگه می‌خوای شکمت صاف بشه، این تمرین رو تو خونه انجام بده. حرکت اول، پلانک: سی ثانیه نگه دار، کمرت صاف باشه. " +
                "حرکت دوم، کرانچ دوچرخه، بیست تا، آروم و {کنترل‌شده.|کنترل شده.} حرکت سوم، بالا آوردن پا، {پونزده|۱۵} تا. بین هر ست سی ثانیه استراحت، {کلاً|کلا} سه ست. " +
                "چهار هفته انجامش بدی، خودت فرقش رو می‌بینی. برنامهٔ کامل چهار هفته‌ای رو می‌خوای؟ کلمهٔ برنامه رو کامنت کن.",
            Cta("comment", "برنامه"), MALE,
        ),
        Spec(
            "fa-travel-yazd", "fa", "travel", "یه ولاگ سفر به یزد بساز.",
            "خب رسیدیم یزد! ~ااا الان ساعت {شیش|شش} صبحه، هوا هنوز خنکه و اومدیم تو بافت تاریخی یه دوری بزنیم تا شلوغ نشده. " +
                "این کوچه‌ها رو ببینید، همه‌اش {کاه‌گله،|کاهگله،} دیوارا اینقدر بلندن که ظهرم سایه‌ست. اونجا رو ببینید، اون بادگیره. به یزد می‌گن شهر بادگیرها. " +
                "بلندترین بادگیر دنیا هم همین‌جاست، تو باغ {دولت‌آباد،|دولت آباد،} {سی و سه|سی سه} متر ارتفاع داره. ورودی باغ برای ما ایرانیا پنجاه هزار تومنه، که واقعاً ارزشش رو داره. " +
                "حالا یه چیزی بگم، ~اِم اگه یزد اومدید {حتماً|حتما} باقلوا و پشمک بخرید، ولی نه از بازار، از قنادی‌های قدیمی دور میدون {امیرچخماق.|امیر چخماق.} " +
                "شبم بیاید همین میدون، چراغاش که روشن می‌شه یه چیز دیگه‌ست. ما سه شب اینجاییم، فردا می‌ریم کویر {خرانق،|خرانغ،} اونجا رو هم براتون می‌ذارم. " +
                "اگه می‌خواید قسمت بعدی رو از دست ندید، فالو کنید.",
            Cta("follow"), MALE,
        ),
        Spec(
            "fa-motivation-story", "fa", "lifestyle", "داستان انگیزشی، کپشن کلمه به کلمه، حس الهام‌بخش و آرام.",
            "پنج سال پیش، تو یه روز سرد {آذر،|آزر،} من همه چیزم رو از دست دادم. کارگاهی که با بابام راه انداخته بودیم {ورشکست|ورشکصت} شد. " +
                "صد و هشتاد میلیون بدهی داشتم و ~ااا راستش رو بخواید، دیگه نمی‌دونستم باید چیکار کنم. یه شب نشسته بودم تو ماشین، جلوی خونه، روم نمی‌شد برم تو. " +
                "مامانم زنگ زد. گفت پسرم چرا نمیای بالا؟ گفتم مامان، من شکست خوردم. یه جمله گفت که {هیچ‌وقت|هیچوقت} یادم نمی‌ره. " +
                "گفت: شکست وقتیه که دیگه بلند نشی، تو فقط زمین خوردی. ~اِم فرداش رفتم یه کار ساده پیدا کردم، {شاگرد|شاگرت} یه مغازهٔ تعمیر موبایل. " +
                "روزی دوازده ساعت کار می‌کردم، شبا آموزش می‌دیدم. یک سال طول کشید تا اولین تعمیر جدی رو خودم انجام بدم. " +
                "دو سال بعد، یه {غرفهٔ|غرفه} کوچیک تو {پاساژ|پاساج} گرفتم. الان... الان سه تا شعبه داریم و بیست و دو نفر با ما کار می‌کنن. " +
                "بدهیا؟ همه‌اش رو تا قرون آخر دادم. نمی‌گم راحت بود، نه، خیلی شبا گریه کردم. ولی اون جملهٔ مامانم هر بار که کم آوردم تو گوشم بود. " +
                "اگه تو هم الان زمین خوردی، بدون که هنوز تموم نشده. اگه این حرف به دردت خورد، بفرستش برای کسی که امروز لازمش داره.",
            Cta("share"), CALM_MALE,
        ),
        Spec(
            "fa-tech-review-phones", "fa", "tech",
            """
            ریویوی مقایسه‌ای Galaxy S24 Ultra و iPhone 15 Pro Max.
            لوگوی Samsung و Apple رو بیار، مشخصات (زوم ۱۰۰ برابر، باتری ۵۰۰۰ میلی‌آمپر، قیمت‌ها) رو با عدد نشون بده، سبک تیره و تکنولوژیک.
            آخرش «کلمهٔ گوشی رو کامنت کن» واضح باشه.
            """,
            "{Galaxy S24 Ultra|گلکسی اس ۲۴ اولترا} یا iPhone 15 Pro Max؟ ~ااا من دو هفته با هر دوتاش زندگی کردم، بذارید بی‌تعارف بگم. " +
                "دوربین: تو عکس روز {تقریباً|تقریبا} مساوین، ولی زوم صد برابر Samsung واقعاً {دیوونه‌کننده‌ست.|دیوونه کنندست.} شب، iPhone رنگ‌ها رو طبیعی‌تر درمیاره. " +
                "باتری: Galaxy با پنج هزار میلی‌آمپر راحت یه روز و نیم کار کرد، ولی iPhone آخر شب به بیست درصد می‌رسید. " +
                "نرم‌افزار؟ اگه تو اکوسیستم Apple هستی، با Mac و {AirPods،|Air Pods،} اصلاً فکرشم نکن، iPhone بخر. " +
                "قیمت؟ تو بازار ایران الان Galaxy حدود {هفتاد و پنج|هفتادوپنج} میلیونه و {iPhone|آیفون} {نود و دو|۹۲} میلیون. " +
                "خلاصه، عکاس و گیمری؟ Samsung. راحتی و اکوسیستم؟ Apple. مقایسهٔ کامل با جدول رو می‌خوای؟ کلمهٔ گوشی رو کامنت کن.",
            Cta("comment", "گوشی"), MALE,
        ),
        Spec(
            "fa-crypto-bitcoin", "fa", "crypto",
            """
            ریلز تبلیغاتی برای کمپین Nobitex، مخاطب تازه‌واردهای کریپتو.
            هوک: «۱۰۰ تتر هدیه» از ثانیهٔ اول با کارت ووچر، نه مقدمه.
            فوریت: «فقط ۵۰۰ ظرفیت مونده» با نوار ظرفیت و «کمتر از ۷۲ ساعت» با شمارش معکوس.
            اسم صرافی رو با لوگو بیار. مکث‌ها رو کوتاه کن، ریتم تند، کپشن کلمه‌به‌کلمه بدون غلط املایی.
            آخر ویدیو «کلمهٔ بیت رو کامنت کن» واضح‌ترین چیز صفحه باشه. سبک نوآر.
            """,
            "{بیت‌کوین|بیتکوین} دوباره رفت بالای صد هزار دلار و همه دارن می‌پرسن الان بخرم یا دیره؟ ~اِم ببین، من بهت نمی‌گم چی بخری، ولی یه فرصت دارم که نباید از دستش بدی. " +
                "{صرافی|سرافی} {Nobitex|نوبیتکس} برای کاربرای جدید یه کمپین گذاشته: با {ثبت‌نام|سبتنام} و {احراز|اهراز} هویت، صد تتر هدیهٔ ترید می‌گیری. " +
                "تا الان سه هزار نفر گرفتنش و فقط {پونصد|پانصد} تا ظرفیت مونده. مهلتشم تا یکشنبه ساعت دوازده شبه، یعنی کمتر از {هفتاد و دو|هفتادو دو} ساعت. " +
                "کارمزد معاملاتم تا آخر ماه {صفره.|سفره.} کلمهٔ بیت رو کامنت کن، لینک ثبت‌نام و آموزش {قدم‌به‌قدم|قدم به قدم} رو برات دایرکت می‌کنم.",
            Cta("comment", "بیت"), MALE,
        ),
        Spec(
            "fa-podcast-quote", "fa", "finance", "یه تیکه از پادکستم؛ نقل‌قول استاد رو برجسته کن، سبک نوآر، ریتم آرام.",
            "ببین، من بیست و شیش سالم بود که رفتم پیش استادم، آقای {رحیمی.|رهیمی.} گفتم استاد، من سه ساله دارم کار می‌کنم و هیچی {پس‌انداز|پسانداز} ندارم، کجای کارم اشتباهه؟ " +
                "یه نگاهی بهم کرد، گفت: مشکلت {درآمد|در آمد} نیست، مشکلت اینه که پولت قبل از اینکه برسه خرج شده. ~ااا من اون موقع نفهمیدم یعنی چی. " +
                "بعد برام توضیح داد که آدما {معمولاً|معمولا} اول خرج می‌کنن، بعد هر چی موند پس‌انداز می‌کنن، و خب معمولاً هیچی نمی‌مونه. گفت برعکسش کن. " +
                "روزی که حقوق می‌گیری، اول بیست درصدش رو بذار کنار، انگار اصلاً نبوده. من از همون روز این کار رو کردم. " +
                "بعد از پنج سال، همون بیست درصد شد سرمایهٔ اولین کار خودم. قسمت کامل این پادکست تو {یوتیوبه،|یوتوبه،} لینکش تو بایوئه.",
            Cta("link"), CALM_MALE, footage = false,
        ),
        Spec(
            "fa-testimonial-ielts", "fa", "education", "ویدیو رضایت هنرجو برای آموزشگاه زبان پارسیان، صادقانه و گرم، نمره‌ها رو نشون بده.",
            "من سارا هستم. سه ماه پیش که اومدم آموزشگاه {پارسیان،|پارصیان،} {آیلتسم|آیلتس م} پنج و نیم بود و ~اِم واقعاً {ناامید|نا امید} بودم، چون دو بار امتحان داده بودم. " +
                "اینجا اولین کاری که کردن یه {تعیین|تعین} سطح دقیق بود، فهمیدیم مشکل اصلیم رایتینگه. هفته‌ای سه جلسه کلاس داشتم، با {تصحیح|تصحیه} رایتینگ هر روز. " +
                "امتحان آخرم... هفت گرفتم! رایتینگم شد {شیش|شش} و نیم. اگه شما هم هدفتون مهاجرت یا ادامه تحصیله، کلمهٔ مشاوره رو کامنت کنید تا باهاتون تماس بگیرن.",
            Cta("comment", "مشاوره"), FEMALE,
        ),
        Spec(
            "fa-news-inflation", "fa", "news", "سبک خبری و جدی؛ درصدها و سال‌ها رو با نمودار نشون بده، بدون موسیقی.",
            "تورم ایران به کجا رسید؟ بیاید با عدد ببینیم. طبق گزارش مرکز آمار، تورم {نقطه‌به‌نقطه|نقطه به نقطه} تو سال {هزار و چهارصد و یک|۱۴۰۱} به پنجاه و دو درصد رسید، " +
                "که بالاترین رقم از سال هزار و سیصد و هفتاد و چهار بود. تو سال هزار و چهارصد و دو این عدد اومد پایین، حدود {سی و نه|سی نه} درصد. " +
                "ولی ~ااا نکته اینجاست: تورم {خوراکی‌ها|خوراکیها} هنوز بالای چهل و پنج درصده. یعنی سفرهٔ مردم از میانگین سریع‌تر کوچیک شده. " +
                "یه مقایسه: سال نود و هفت، یه کیلو گوشت قرمز حدود هفتاد هزار تومن بود، الان بالای ششصد هزار تومنه، یعنی نزدیک نه برابر. " +
                "{کارشناسا|کارشناس ها} می‌گن اگه {نقدینگی|نغدینگی} همین‌طوری رشد کنه، کاهش پایدار تورم بعیده. نظر شما چیه؟ تو کامنتا بنویسید.",
            Cta("comment"), CALM_MALE,
        ),
        Spec(
            "fa-mixed-ai-tools", "fa", "software",
            "Punchy reel for my productivity page. Show the logos of Notion, ChatGPT, Claude and Google Calendar as I name them, word-by-word captions, no music.",
            "من هر یکشنبه شب با سه تا ابزار کل هفته‌م رو {برنامه‌ریزی|برنامه ریزی} می‌کنم، و این زندگیمو عوض کرد. اول، همه کارامو {بی‌ترتیب|بیترتیب} می‌ریزم تو {Notion.|Nation.} " +
                "دوم، این لیست رو می‌دم به {ChatGPT|Chat GPT} یا {Claude،|Cloud،} می‌گم بر اساس اولویت و انرژی من برام بچینش. " +
                "سوم، خروجی رو می‌برم تو {Google Calendar|گوگل کلندر} و برای هر کار یه بلاک زمانی می‌ذارم. " +
                "این کار {کلاً|کلا} بیست دقیقه وقت می‌بره، ولی هفته‌ای حداقل پنج ساعت برام وقت آزاد می‌کنه. پرامپتی که استفاده می‌کنم رو می‌خوای؟ کلمهٔ ابزار رو کامنت کن.",
            Cta("comment", "ابزار"), FEMALE,
        ),
        Spec(
            "fa-flash-sale-15s", "fa", "fashion", "یه ریلز جذاب بساز",
            "فقط امروز! همهٔ {کتونی‌های|کتونیهای} {Nike|نایک} و {Adidas|آدیداس} پنجاه درصد تخفیف. موجودی محدوده، سایز چهل و دو و چهل و سه داره تموم می‌شه. " +
                "همین الان دایرکت بده، کلمهٔ کتونی رو بفرست تا لینک خرید رو برات بفرستیم.",
            Cta("dm", "کتونی"), MALE,
        ),
        Spec(
            "fa-cafe-no-captions", "fa", "food", "بدون زیرنویس؛ حس گرم و آرام برای افتتاحیهٔ کافه.",
            "کافه‌مون بالاخره باز شد! ~اِم سه سال رویاش رو داشتیم. قهوه‌ها رو خودمون {رُست|رست} می‌کنیم، دسرها هم همه خونگیه. " +
                "این هفته به افتخار افتتاحیه، قهوهٔ اولتون مهمون ما. آدرسمون خیابون {ولیعصره،|ولی عصره،} بالاتر از پارک {ساعی.|ساهی.} منتظرتونیم.",
            null, CALM_FEMALE,
        ),
        Spec(
            "en-tutorial-excel", "en", "education", "make it engaging",
            "Okay, three Excel shortcuts that'll save you, like, an hour every week. Number one: Control plus E. It's called {Flash Fill.|flash fill.} " +
                "Type one example and Excel fills the whole column for you. Number two, ~um, Control plus T turns your range into a proper table, with filters, in one second. " +
                "Number three, and this one's my favorite: Alt plus equals. It sums the column instantly. No more typing {SUM|some} formulas. " +
                "I made a cheat sheet with 25 more. Comment the word SHEET and I'll send it to you.",
            Cta("comment", "SHEET"), FEMALE,
        ),
        Spec(
            "en-launch-earbuds", "en", "tech",
            """
            Launch reel for Nova Buds Pro. Hook: the ${'$'}79 launch price in the first second.
            Show the 40 hours of battery as a number, the Friday deadline as a countdown and the 5,000 units of the first batch as a progress bar.
            Clean light look, upbeat music, phrase captions. End on "link in bio".
            """,
            "Meet {Nova Buds Pro.|Nova Bots Pro.} We spent two years building these, and honestly, I think they're the best earbuds under \$100. " +
                "Active noise cancelling that actually works on the subway. 40 hours of battery with the case. They're {IPX5,|IPX 5,} so rain and sweat, no problem. " +
                "And the sound? Tuned by the same team that built studio monitors. Launch price is \$79, but only until Friday midnight, then it goes back to \$99. " +
                "We made just 5,000 units in the first batch. Link's in the bio. Go.",
            Cta("link"), MALE,
        ),
        Spec(
            "en-cooking-pasta", "en", "food", "یه ریلز آشپزی برای پیج انگلیسی‌زبانم بساز، گرم و آرام.",
            "This is the {15-minute|fifteen minute} garlic butter pasta I make when I'm too tired to cook. Get your water boiling and salt it like the sea. " +
                "While the spaghetti cooks, melt three tablespoons of butter in a pan, low heat. Add six cloves of garlic, sliced thin, and a pinch of {chili|chilly} flakes. " +
                "Don't let the garlic brown, it gets bitter. Okay, pasta's {al dente.|all dente.} Before you drain it, save one cup of the pasta water. That's the secret. " +
                "Toss the pasta in the butter, splash in the water, and keep stirring till it's {glossy.|glassy.} Handful of parmesan, lots of black pepper, parsley. Done. " +
                "15 minutes, five ingredients. Save this for the next lazy night.",
            Cta("save"), CALM_FEMALE,
        ),
        Spec(
            "en-testimonial-coach", "en", "fitness", "Customer testimonial for Coach Maya's program. Warm and honest, calm music, phrase captions.",
            "Six months ago I couldn't run for two minutes without stopping. I'd tried every app, every diet, nothing stuck. " +
                "A friend dragged me to {Coach Maya's|coach Mya's} group program, and, ~uh, honestly I almost quit after week one. But she never made it about punishment. " +
                "It was small stuff. Walk 10,000 steps. Sleep seven hours. Lift three times a week. Six months later, I'm down 12 kilos, and last Sunday I ran my first {10K.|10 K.} " +
                "Under an hour. If you're where I was, just start. Maya's next group starts on March 1st, and she's only taking 30 people.",
            null, CALM_FEMALE,
        ),
        Spec(
            "en-news-ai", "en", "ai", "News explainer, serious tone. Show the numbers with charts and counters, dark look, no music.",
            "Three AI stories from this week you actually need to know. First, {OpenAI|Open AI} said {ChatGPT|Chat GPT} now has 800 million weekly users. " +
                "That's double what it had in February. Second, a {Stanford|Stamford} report found that the cost of running a {GPT-3.5|GPT 3.5} level model dropped more than 280 times " +
                "between 2022 and 2024. 280 times. That's why AI is suddenly in every app on your phone. " +
                "Third, and this is the one I'd watch, the EU AI Act's first rules kicked in, so companies in Europe now have to tell you when you're talking to a bot. " +
                "Fines go up to 7% of global revenue. So: cheaper models, way more users, and finally some rules. Follow for the weekly roundup every Friday.",
            Cta("follow"), MALE,
        ),
    )
}
