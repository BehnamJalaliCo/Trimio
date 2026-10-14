package io.trimio.engine.illustration.library

import io.trimio.core.model.text.TextKey

/**
 * Word matching for Persian and English requests: folded terms ([TextKey.terms]) compared with
 * keywords by equality, a Persian suffix («کاربران» ~ «کاربر», «بانکی» ~ «بانک») or an English one
 * ("users" ~ "user"), or a long shared prefix.
 */
object Words {
    fun terms(text: String): List<String> = TextKey.terms(text).filter { it.length > 1 && it !in STOP }

    /** Whole words only (a half-space word stays one word: «لپ‌تاپ» is not «لپ» and «تاپ»), for counting what a picture shows. */
    fun words(text: String): List<String> = TextKey.words(text).filter { it.length > 1 && it !in STOP }

    /** 1 for the same word, 0.8 for the same stem, 0 otherwise. */
    fun match(term: String, keyword: String): Float {
        if (term == keyword) return 1f
        if (keyword.length < MIN_STEM || term.length < MIN_STEM) return 0f
        val (long, short) = if (term.length >= keyword.length) term to keyword else keyword to term
        if (!long.startsWith(short)) return 0f
        val rest = long.substring(short.length)
        return if (rest in SUFFIXES || (short.length >= PREFIX_MATCH && rest.length <= 2)) STEM else 0f
    }

    /** The best match of [term] among [keywords]. */
    fun best(term: String, keywords: Collection<String>): Float = keywords.maxOfOrNull { match(term, it) } ?: 0f

    /** True when any term of [text] matches one of [keywords]. */
    fun any(text: String, keywords: Collection<String>): Boolean = terms(text).any { best(it, keywords) > 0f }

    private const val MIN_STEM = 3
    private const val PREFIX_MATCH = 5
    private const val STEM = 0.8f

    private val SUFFIXES = setOf(
        "s", "es", "ed", "ing", "er", "ers", "ly",
        "ها", "های", "هایی", "ان", "یان", "ی", "ای", "یی", "ش", "اش", "م", "ت", "مان", "شان", "تان", "تر", "ترین", "ه", "ا", "اشون", "شون",
    )

    private val STOP = setOf(
        "a", "an", "the", "of", "in", "on", "at", "to", "for", "with", "and", "or", "his", "her", "their", "its", "is", "are", "be", "by",
        "from", "this", "that", "some", "very", "full", "front",
        "را", "از", "به", "در", "با", "که", "این", "آن", "و", "یا", "هم", "تا", "برای", "روی", "جلوی", "یک", "یه", "است", "هست", "بود", "شد",
        "می", "خود", "اش", "ها", "های", "کنار", "پیش", "حال", "پر",
    )
}

/** Picture words for people: who, how they feel, what they do (English and Persian). */
object PeopleWords {
    val PERSON = set(
        "person man woman people user users customer customers employee worker manager boss student doctor nurse girl boy guy lady programmer",
        "businessman businesswoman expert experts",
        "investor trader buyer seller owner citizen crowd team family colleague client someone everyone he she they kid child",
        "آدم شخص فرد مرد زن خانم آقا مردم کاربر کاربران مشتری مشتریان کارمند کارگر مدیر رئیس دانشجو دانش‌آموز دکتر پزشک پرستار",
        "دختر پسر سرمایه‌گذار سرمایه‌گذاران معامله‌گر تریدر خریدار فروشنده صاحب شهروند جمعیت تیم خانواده همکار ارباب‌رجوع کسی همه بچه",
        "نفر نفرات کاربرا مشتریا آدما کارمندا مردا زنا",
    )
    val WOMAN = set("woman women lady girl mother nurse she her wife businesswoman", "زن زنان زنا خانم خانما دختر دخترا مادر پرستار همسر")
    val MAN = set("man men guy boy father he him husband businessman", "مرد مردان مردا آقا آقایان پسر پسرا پدر شوهر")
    val OLD = set("old elderly grandfather grandmother retired senior pensioner", "پیر پیرمرد پیرزن مسن بازنشسته سالمند پدربزرگ مادربزرگ")
    val DOCTOR = set("doctor physician nurse surgeon medic dentist", "دکتر پزشک پرستار جراح دندانپزشک")
    val GROUP = set(
        "people users customers crowd team group investors employees citizens family everyone colleagues clients buyers they experts",
        "مردم کاربران مشتریان جمعیت تیم گروه سرمایه‌گذاران کارمندان شهروندان خانواده همه همکاران خریداران آنها",
        "کاربرا مشتریا آدما کارمندا مردان زنان نفرات پزشکان",
    )
    val TWO = set("two couple pair both", "دو دونفر زوج هردو")
    val THREE = set("three several few", "سه چند چندنفر")

    /** Face pieces by feeling (first match wins). */
    val FACES: List<Pair<Set<String>, List<String>>> = listOf(
        set("worried anxious nervous concerned stressed upset confused uneasy stuck", "نگران مضطرب استرس پریشان ناراحت سردرگم دلواپس گیر نگرانی") to
            listOf("Concerned", "ConcernedFear"),
        set("scared afraid fear panic terrified", "ترس ترسیده وحشت هراس ترسید") to listOf("Fear", "ConcernedFear"),
        set("angry furious mad annoyed complain", "عصبانی خشمگین شاکی اعتراض معترض") to listOf("Angry", "Rage"),
        set("surprised shocked amazed wow unexpected", "تعجب شوکه شگفت‌زده متعجب حیرت غافلگیر") to listOf("Awe"),
        set("tired exhausted bored sleepy", "خسته کسل بی‌حوصله خواب") to listOf("Tired", "EyesClosed"),
        set("happy glad joy success profit win smile excited celebrate", "خوشحال شاد موفق سود برنده لبخند هیجان جشن خوشحالی") to
            listOf("SmileBig", "Smile", "SmileTeeth", "LoveGrin"),
        set("thinking wondering doubt suspicious unsure skeptical", "فکر شک تردید مشکوک مردد") to listOf("Suspicious", "Serious", "Solemn"),
        set("calm relaxed peaceful", "آرام آسوده راحت") to listOf("Calm"),
        set("explain explaining talk talking say says speak teach present", "توضیح صحبت حرف میگوید میگه گفت آموزش ارائه") to listOf("Explaining"),
        set("serious focused determined", "جدی متمرکز مصمم") to listOf("Serious", "Driven"),
    )

    /** Pose pieces by action (first match wins); bust poses first, then full-body ones. */
    val POSES: List<Pair<Set<String>, List<String>>> = listOf(
        set("phone mobile smartphone app application scroll texting screen notification", "گوشی موبایل تلفن اپ اپلیکیشن برنامه پیامک اعلان صفحه") to
            listOf("bust/Device"),
        set("coffee tea drink cup", "قهوه چای نوشیدن فنجان") to listOf("bust/Coffee"),
        set("paper document report letter contract bill reading read notice form receipt", "کاغذ سند گزارش نامه قرارداد قبض خواندن برگه اطلاعیه فرم رسید") to
            listOf("bust/Paper"),
        set("wheelchair disabled", "ویلچر معلول") to listOf("sitting/Wheelchair"),
        set("bike bicycle cycling", "دوچرخه") to listOf("sitting/Bike"),
        set("game gaming play", "بازی گیم") to listOf("bust/Gaming"),
        set("walk walking go leave commute moving", "راه قدم رفتن میرود ترک") to listOf("standing/WalkingBW", "standing/WalkingWB"),
        set("sit sitting rest seated", "نشسته نشستن استراحت نشست") to listOf("sitting/MediumBW", "sitting/CrossedLegs", "sitting/HandsBackWB"),
        set("point pointing show idea", "اشاره نشان ایده") to listOf("bust/PointingUp", "standing/PointingFingerBW"),
        set("wait waiting refuse confident crossed", "منتظر انتظار امتناع مطمئن") to listOf("bust/ArmsCrossed", "standing/CrossedArmsBW"),
        set("explain explaining talk present teach", "توضیح ارائه آموزش صحبت") to listOf("bust/Explaining"),
    )

    val BUST_DEFAULT = listOf(
        "bust/Shirt", "bust/Sweater", "bust/Hoodie", "bust/Turtleneck", "bust/ButtonShirt", "bust/PoloSweater", "bust/PocketShirt",
        "bust/BlazerBlackTee", "bust/ShirtCoat", "bust/StripedShirt",
    )
    val STANDING_DEFAULT = listOf(
        "standing/ShirtPantsBW", "standing/ShirtPantsWB", "standing/BlazerPantsBW", "standing/BlazerPantsWB", "standing/EasingBW",
        "standing/RestingWB", "standing/CrossedArmsWB", "standing/PointingFingerWB",
    )
    val DOCTOR_POSES = listOf("standing/DocStethoscope", "standing/Doc")
    val MEN_HAIR = listOf("Short", "ShortMessy", "ShortWavy", "ShortCurly", "ShortVolumed", "ShortScratch", "Medium", "MediumShort", "Pomp", "BaldTop", "FlatTop")
    val WOMEN_HAIR_FA = listOf("Hijab")
    val WOMEN_HAIR = listOf("Long", "LongCurly", "Bun", "MediumStraight", "LongBangs", "Medium", "Buns")
    val OLD_MEN_HAIR = listOf("GrayShort", "BaldSides", "GrayMedium")
    val OLD_WOMEN_HAIR = listOf("GrayBun", "GrayMedium")
    val BEARDS = listOf("Chin", "Full", "FullMedium", "Goatee", "MoustacheThin", "Painters")
    val GLASSES = listOf("GlassRound", "GlassRoundThick", "GlassClubmaster")
    val CALM_FACES = listOf("Calm", "Smile", "Serious", "Cute", "SmileNM")

    private fun set(vararg lines: String): Set<String> = lines.flatMap { line -> line.split(' ').filter { it.isNotBlank() }.map(TextKey::of) }.toSet()
}
