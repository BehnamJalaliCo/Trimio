package io.trimio.engine.autopilot

/**
 * Concrete things people talk about, in Persian and English, with the English query that finds
 * their picture in the visual vocabulary (emoji-style packs: "egg", "garlic", "pill", "house").
 * The rules path has no model to say what a line shows; this small lexicon lets it picture the
 * eggs of a recipe, the pill of a medical tip or the window of an apartment tour. It also tells
 * the piece's field from its words, so a cooking video is filed as food, not "general".
 */
object Concepts {

    /** The picture query for [word] (as said, any Persian suffix), or null when it is not a known thing. */
    fun visualOf(word: String): String? {
        val w = Phrases.key(word).replace(" ", "")
        if (w.length < 2) return null
        THINGS[w]?.let { return it }
        // Plurals and suffixes: «تخم‌مرغ‌ها», «سبزیش», "eggs".
        return THINGS.entries.firstOrNull { (k, _) -> k.length >= MIN_STEM && w.startsWith(k) && w.removePrefix(k) in SUFFIXES }?.value
    }

    /** The field of a piece, from its words and its brief: the one most of its words belong to, or "general". */
    fun domainOf(texts: List<String>, prompt: String): String {
        val words = (texts + prompt.split(' ', '\n', '،', ',', '.')).map { Phrases.key(it).replace(" ", "") }.filter { it.length >= 2 }
        val scores = DOMAINS.mapValues { (_, keys) -> words.count { w -> keys.any { k -> w == k || (k.length >= MIN_STEM && w.startsWith(k) && w.removePrefix(k) in SUFFIXES) } } }
        val best = scores.maxByOrNull { it.value } ?: return "general"
        return if (best.value >= MIN_DOMAIN_HITS) best.key else "general"
    }

    private val SUFFIXES = setOf("", "ها", "های", "هاتون", "ش", "شو", "رو", "و", "ی", "ای", "مون", "تون", "s", "es")

    private val THINGS: Map<String, String> = HashMap<String, String>().apply {
        fun add(query: String, vararg keys: String) = keys.forEach { k -> put(k.replace("\u200c", ""), query) }
        // Food.
        add("egg", "تخممرغ", "تخم", "egg", "eggs")
        add("herb", "سبزی", "سبزیجات", "جعفری", "parsley", "herbs", "herb")
        add("leafy green", "کاهو", "اسفناج", "spinach", "lettuce")
        add("bread", "آرد", "نان", "نون", "flour", "bread")
        add("salt", "نمک", "salt")
        add("hot pepper", "فلفل", "chili", "pepper")
        add("garlic", "garlic")
        add("onion", "پیاز", "onion")
        add("tomato", "گوجه", "tomato")
        add("potato", "سیبزمینی", "potato")
        add("lemon", "لیمو", "lemon")
        add("butter", "کره", "butter")
        add("cheese wedge", "پنیر", "پارمزان", "cheese", "parmesan")
        add("spaghetti", "پاستا", "ماکارونی", "spaghetti", "pasta", "noodles")
        add("cooking", "تابه", "ماهیتابه", "pan", "skillet", "frying")
        add("droplet", "روغن", "oil")
        add("hot beverage", "قهوه", "چای", "coffee", "espresso", "tea")
        add("shortcake", "دسر", "کیک", "cake", "dessert")
        add("chestnut", "گردو", "walnut", "walnuts", "nuts")
        add("cherries", "زرشک", "berries", "cherries")
        add("candy", "پشمک", "شیرینی", "باقلوا", "candy", "sweets")
        add("spoon", "قاشق", "spoon", "tablespoon", "tablespoons")
        add("fork and knife with plate", "غذا", "food", "dinner", "lunch", "meal")
        add("meat on bone", "گوشت", "meat", "beef")
        // Body and health.
        add("pill", "قرص", "کپسول", "pill", "pills", "tablet")
        add("syringe", "آمپول", "واکسن", "syringe", "injection", "vaccine")
        add("test tube", "آزمایش", "test", "lab")
        add("stethoscope", "دکتر", "پزشک", "doctor", "physician")
        add("sun", "آفتاب", "خورشید", "sun", "sunlight")
        add("bone", "استخون", "استخوان", "bone", "bones")
        add("tired face", "خستگی", "خسته", "fatigue", "tired")
        add("hair pick", "مو", "ریزش", "hair")
        add("brain", "مغز", "brain")
        add("anatomical heart", "قلب", "heart")
        add("tooth", "دندون", "دندان", "tooth", "teeth")
        add("sleeping face", "sleep", "sleeping")
        add("person running", "دویدن", "run", "running", "runner", "10k")
        add("footprints", "قدم", "steps", "walk")
        add("person lifting weights", "تمرین", "ورزش", "باشگاه", "workout", "gym", "exercise", "lift")
        add("bicycle", "دوچرخه", "bicycle", "bike")
        add("leg", "پا", "leg", "legs")
        add("flexed biceps", "عضله", "شکم", "muscle", "abs", "core")
        add("stopwatch", "ثانیه", "تایمر", "seconds", "timer")
        // Places and travel.
        add("house", "خونه", "خانه", "house", "home")
        add("office building", "برج", "آپارتمان", "ساختمان", "apartment", "building", "tower")
        add("window", "پنجره", "window")
        add("mountain", "کوه", "mountain", "mountains")
        add("kitchen knife", "آشپزخونه", "آشپزخانه", "kitchen")
        add("bed", "اتاقخواب", "خواب", "bed", "bedroom")
        add("automobile", "ماشین", "پارکینگ", "car", "parking")
        add("person swimming", "استخر", "pool", "swimming")
        add("desert", "کویر", "بیابان", "desert")
        add("deciduous tree", "باغ", "درخت", "garden", "park", "tree")
        add("classical building", "تاریخی", "مسجد", "museum", "historic")
        add("wind face", "بادگیر", "باد", "wind")
        add("cityscape", "شهر", "city", "town")
        add("airplane", "پرواز", "هواپیما", "سفر", "flight", "plane", "trip", "travel")
        add("hotel", "هتل", "hotel")
        add("door", "door")
        add("convenience store", "مغازه", "فروشگاه", "قنادی", "shop", "store")
        add("metro", "مترو", "subway", "metro", "train")
        add("cloud with rain", "بارون", "باران", "rain")
        // Money and work.
        add("money bag", "پول", "سرمایه", "money", "cash", "capital", "savings")
        add("credit card", "کارت", "card")
        add("bank", "بانک", "bank")
        add("chart increasing", "تورم", "رشد", "inflation", "growth")
        add("briefcase", "شغل", "job", "career")
        add("receipt", "فاکتور", "receipt", "bill")
        add("wrapped gift", "هدیه", "جایزه", "gift", "prize")
        add("shopping bags", "خرید", "shopping")
        add("label", "تخفیف", "قیمت", "discount", "sale", "price")
        // Time.
        add("calendar", "تقویم", "هفته", "calendar", "week", "deadline")
        add("alarm clock", "ساعت", "clock", "hour", "hours")
        // Devices and tools.
        add("mobile phone", "گوشی", "موبایل", "phone", "smartphone")
        add("camera", "دوربین", "camera")
        add("battery", "باتری", "شارژ", "battery")
        add("headphone", "هدفون", "هندزفری", "earbuds", "headphones")
        add("laptop", "لپتاپ", "کامپیوتر", "laptop", "computer")
        add("keyboard", "کیبورد", "keyboard", "shortcut", "shortcuts")
        add("robot", "ربات", "bot", "robot", "ai")
        add("hammer and wrench", "ابزار", "تعمیر", "tools", "repair")
        add("spiral notepad", "لیست", "یادداشت", "list", "notes", "sheet")
        add("books", "کتاب", "درس", "book", "books", "lesson")
        add("graduation cap", "دانشگاه", "تحصیل", "university", "degree")
        add("pencil", "رایتینگ", "نوشتن", "writing", "essay")
        add("microphone", "پادکست", "میکروفون", "podcast", "microphone")
        add("speech balloon", "مکالمه", "speaking", "conversation")
        add("trophy", "نمره", "قهرمان", "score", "trophy", "winner")
        add("globe with meridians", "دنیا", "جهان", "world", "global")
        add("bottle with popping cork", "افتتاحیه", "جشن", "opening", "party")
        add("lotion bottle", "سرم", "کرم", "serum", "cream", "skincare")
        add("running shoe", "کتونی", "کفش", "sneakers", "shoes")
        add("light bulb", "ایده", "idea", "tip")
    }

    private val DOMAINS: Map<String, List<String>> = mapOf(
        "food" to listOf(
            "آشپزی", "کوکو", "سبزی", "تخممرغ", "قهوه", "کافه", "دسر", "غذا", "تابه", "قاشق", "مواد", "دستور", "پاستا", "cook", "recipe", "pasta",
            "garlic", "butter", "kitchen", "ingredients", "spaghetti", "coffee", "parmesan", "dinner",
        ),
        "medical" to listOf("دکتر", "پزشک", "ویتامین", "قرص", "آزمایش", "بیماری", "علامت", "درمان", "دوز", "متخصص", "doctor", "symptom", "dose", "vitamin"),
        "fitness" to listOf(
            "تمرین", "حرکت", "پلانک", "کرانچ", "ست", "شکم", "ورزش", "باشگاه", "استراحت", "workout", "run", "kilos", "steps", "coach", "gym", "exercise",
            "program", "lift",
        ),
        "travel" to listOf("سفر", "یزد", "شهر", "کوچه", "باغ", "ولاگ", "هتل", "بادگیر", "کویر", "میدون", "travel", "trip", "flight", "hotel", "vlog"),
        "real-estate" to listOf("خونه", "واحد", "متراژ", "متر", "آپارتمان", "برج", "پارکینگ", "انباری", "خواب", "لابی", "پذیرایی", "apartment", "rent", "property"),
        "education" to listOf(
            "آموزش", "زبان", "کلاس", "آیلتس", "امتحان", "اشتباه", "نمره", "آموزشگاه", "مکالمه", "رایتینگ", "learn", "lesson", "shortcut", "shortcuts",
            "excel", "course", "student", "formula", "column",
        ),
        "tech" to listOf("گوشی", "دوربین", "باتری", "زوم", "iphone", "galaxy", "samsung", "apple", "phone", "battery", "earbuds", "noise", "sound"),
        "software" to listOf("notion", "chatgpt", "claude", "ابزار", "calendar", "prompt", "پرامپت", "app", "code", "برنامهریزی"),
        "ai" to listOf("openai", "chatgpt", "model", "bot", "هوش", "gpt", "stanford"),
        "crypto" to listOf("صرافی", "کریپتو", "ترید", "بیتکوین", "تتر", "nobitex", "crypto", "bitcoin", "exchange", "trading", "کارمزد"),
        "finance" to listOf("پسانداز", "حقوق", "درآمد", "سرمایه", "پول", "بدهی", "خرج", "savings", "salary", "income", "invest"),
        "news" to listOf("گزارش", "آمار", "تورم", "اقتصاد", "نقدینگی", "کارشناس", "report", "news", "stories", "fines", "revenue"),
        "beauty" to listOf("سرم", "پوست", "لک", "رضایت", "skin", "serum", "makeup", "lumina"),
        "fashion" to listOf("کتونی", "لباس", "سایز", "nike", "adidas", "sneakers", "fashion"),
        "lifestyle" to listOf("انگیزه", "داستان", "زندگی", "شکست", "مامانم", "بدهیا", "story", "life"),
    )

    private const val MIN_STEM = 3
    private const val MIN_DOMAIN_HITS = 3
}
