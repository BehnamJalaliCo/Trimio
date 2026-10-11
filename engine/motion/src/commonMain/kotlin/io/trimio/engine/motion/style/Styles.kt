package io.trimio.engine.motion.style

import androidx.compose.ui.graphics.Color
import io.trimio.core.brand.BrandFonts.Role.Accent
import io.trimio.core.brand.BrandFonts.Role.Display
import io.trimio.core.brand.BrandFonts.Role.Expressive
import io.trimio.core.brand.BrandFonts.Role.Ui
import io.trimio.engine.motion.Grade
import io.trimio.engine.motion.Guard
import io.trimio.engine.motion.anim
import io.trimio.engine.motion.recipe.Look
import io.trimio.engine.motion.recipe.Look.Mark
import io.trimio.engine.motion.recipe.Look.Voice

/**
 * The 28 design languages, as data. Each answers one entry of the app's style picker
 * ([StyleLanguage.pack]) and covers a real range of briefs: luxury and editorial, brutal and Swiss,
 * retro and neon, broadcast, documentary and trailer, soft beauty and warm food, product, class,
 * sport, kids, corporate, gold, street, wellness, travel, property, gaming and podcast.
 *
 * Palettes are set so every text role clears the contrast floor on its canvas, its stages and its
 * cards for every accent variant ([StyleBook.check]); type stays heavy and untracked where words
 * are read (headlines, captions), and voices pair Persian and Latin by role (the accent face is
 * Latin-only and hands Persian to the display face). Light styles set their ink on paper plates
 * over footage, dark ones ring it with a halo ([Guard]).
 */
@Suppress("LargeClass") // a data table: 28 entries, one block each
internal object Styles {
    val all: List<StyleLanguage> get() = LIST

    /** A plate's corner radius as a fraction of the font size (Swiss-sharp styles keep square plates). */
    private const val PLATE_RADIUS = 0.06f

    /** "slam 1.3, stack 1.2" → a weight map (compact, so a style reads as one block). */
    private fun weights(spec: String): Map<String, Float> = spec.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        .associate { p -> p.substringBefore(' ') to p.substringAfter(' ').toFloat() }

    @Suppress("LongParameterList")
    private fun look(
        name: String, canvas: Long, aurora: List<Long>, ink: Long, muted: Long, accent: Long, positive: Long, negative: Long,
        hot: Long, card: Long, rim: Long, headline: Voice, caption: Voice, body: Voice, number: Voice, label: Voice,
        grain: Float, vignette: Float, roundness: Float, mark: Mark, caps: Boolean, plate: Boolean, grade: Grade, ease: Ease,
        backdrop: String?, lens: String?,
    ): Look {
        val c = Color(canvas)
        val i = Color(ink)
        val a = Color(accent)
        return Look(
            name = name, canvas = c, aurora = aurora.map(::Color), ink = i, muted = Color(muted), accent = a, onAccent = Look.textOn(a),
            positive = Color(positive), negative = Color(negative), hot = Color(hot), card = Color(card), cardRim = Color(rim),
            headline = headline, caption = caption, body = body, number = number, label = label,
            grain = grain, vignette = vignette, roundness = roundness, mark = mark,
            textShadow = if (plate) Color.Transparent else Look.Noir.textShadow,
            guard = if (plate) Guard(Guard.Style.Plate, light = c, dark = i, radius = if (roundness == 0f) 0f else PLATE_RADIUS) else Guard(Guard.Style.Halo, light = i, dark = c),
            backdrop = backdrop, lens = lens, grade = grade, settle = ease.curve, caps = caps,
        )
    }

    private val LIST = listOf(
    StyleLanguage(
        id = "noir", nameFa = "نوآر لوکس", nameEn = "Luxury Noir", pack = "aurora-ui", tones = setOf("noir", "lumen"),
        words = listOf("نوآر", "noir", "آتلیه", "atelier"),
        look = Look.Noir,
        accents = listOf("#D7FF3A", "#FF6B3D", "#3AE0FF", "#FFC93A", "#B79CFF"),
        motion = MotionGrammar(
            energy = -1f, ease = Ease.Expo,
            entrances = emptyMap(),
            enter = emptyMap(), exit = emptyMap(), push = 1f, still = 1f,
        ),
        captions = CaptionTreatment(marks = weights("block 1, ink 0.5, underline 0.4, circle 0.3"), words = listOf(3, 3, 4)),
        rhythm = Cadence(density = 1f, takeover = 1f, stages = weights("grid 0.5, aurora 0.5")),
        sound = "punchy",
        fit = Fit(
            domains = weights("crypto 0.9, finance 0.7, tech 0.7, marketing 0.8, business 0.7, general 0.8, lifestyle 0.6, fashion 0.6, automotive 0.7, software 0.5, ai 0.5"),
            moods = weights("confident 1, energetic 1, urgent 0.9, luxury 0.6, serious 0.6, playful 0.5"),
        ),
    ),
    StyleLanguage(
        id = "paper", nameFa = "کاغذ ادیتوریال", nameEn = "Editorial Paper", pack = "experimental-typography", tones = setOf("paper"),
        words = listOf("کاغذ", "paper", "ادیتوریال", "editorial", "مجله", "magazine"),
        look = Look.Paper,
        accents = listOf("#D7FF3A", "#FF7A45", "#4C7DFF", "#FF5FA8"),
        motion = MotionGrammar(
            energy = -1f, ease = Ease.Expo,
            entrances = emptyMap(),
            enter = emptyMap(), exit = emptyMap(), push = 1f, still = 1f,
        ),
        captions = CaptionTreatment(marks = weights("block 1, underline 0.7, circle 0.5"), words = listOf(3, 3, 4)),
        rhythm = Cadence(density = 1f, takeover = 1f, stages = weights("grid 0.5, aurora 0.5")),
        sound = "elegant",
        fit = Fit(
            domains = weights("education 0.9, news 0.7, business 0.7, software 0.7, marketing 0.6, general 0.7, medical 0.6, lifestyle 0.5, fashion 0.4"),
            moods = weights("serious 1, playful 0.8, confident 0.6, inspiring 0.5, energetic 0.4"),
        ),
    ),
    StyleLanguage(
        id = "lumen", nameFa = "شیشهٔ لومن", nameEn = "Lumen Glass", pack = "glassmorphism", tones = setOf("lumen"),
        words = listOf("لومن", "lumen", "شیشه", "شیشه‌ای", "glass", "iridescent", "هولوگرافیک", "holographic"),
        look = Look.Lumen,
        accents = listOf("#9BE7FF", "#B7A6FF", "#7CFFC4", "#FFC6E0"),
        motion = MotionGrammar(
            energy = -1f, ease = Ease.Expo,
            entrances = emptyMap(),
            enter = emptyMap(), exit = emptyMap(), push = 1f, still = 1f,
        ),
        captions = CaptionTreatment(marks = weights("ink 1, underline 0.6, block 0.3"), words = listOf(3, 3, 4)),
        rhythm = Cadence(density = 1f, takeover = 1f, stages = weights("grid 0.5, aurora 0.5")),
        sound = "ambient",
        fit = Fit(
            domains = weights("ai 0.8, tech 0.7, beauty 0.6, real-estate 0.5, lifestyle 0.7, health 0.6, medical 0.6, software 0.5, general 0.5"),
            moods = weights("calm 1, luxury 0.8, inspiring 1, serious 0.4, confident 0.4"),
        ),
    ),
    StyleLanguage(
        id = "bold-brutalist", nameFa = "بروتال جسور", nameEn = "Bold Brutalist", pack = "brutalism", tones = setOf("paper"),
        words = listOf("بروتال", "بروتالیست", "brutal", "brutalist", "خام", "raw", "جسور", "bold"),
        look = look(
            "bold-brutalist", canvas = 0xFFF2F0EB, aurora = listOf(0xFFF2F0EB, 0xFFE4E1D8, 0xFFF7F5F0),
            ink = 0xFF0A0A0A, muted = 0xFF57544E, accent = 0xFFFF3B00, positive = 0xFF0A0A0A, negative = 0xFFD42A00,
            hot = 0xFFE03400, card = 0xFFFFFFFF, rim = 0x330A0A0A,
            headline = Voice(Display, 1000, 0.96f), caption = Voice(Ui, 900, 1.26f), body = Voice(Ui, 700, 1.32f),
            number = Voice(Display, 1000, 1f), label = Voice(Accent, 700, 1.2f, 0.2f),
            grain = 0f, vignette = 0.05f, roundness = 0f, mark = Mark.Block, caps = true, plate = true,
            grade = Grade(0f.anim, 1.12f.anim, 0.7f.anim, 0f.anim), ease = Ease.Snap, backdrop = null, lens = null,
        ),
        accents = listOf("#FF3B00", "#0047FF", "#FFE500"),
        motion = MotionGrammar(
            energy = 0.8f, ease = Ease.Snap,
            entrances = weights("slam 1.3, stack 1.2, mask-rise 0.7, type-on 0.5, spread 0.5, flip 0.2, blur-in 0.2"),
            enter = weights("flash 1, cut 0.9, whip 0.8, zoom 0.3"), exit = weights("cut 1.2, whip 0.6"), push = 0.5f, still = 1.4f,
        ),
        captions = CaptionTreatment(marks = weights("block 1, underline 0.5"), words = listOf(2, 3)),
        rhythm = Cadence(density = 1f, takeover = 1.15f, stages = weights("grid 1, plain 0.4, aurora 0.2")),
        sound = "punchy",
        fit = Fit(
            domains = weights("fashion 0.8, marketing 0.9, news 0.5, software 0.4, business 0.5, general 0.5, sports 0.4"),
            moods = weights("confident 1, urgent 0.9, energetic 0.8, serious 0.5"),
        ),
    ),
    StyleLanguage(
        id = "kinetic-poster", nameFa = "پوستر کینتیک", nameEn = "Kinetic Poster", pack = "kinetic-typography", tones = setOf("noir"),
        words = listOf("پوستر", "poster", "کینتیک", "kinetic", "تایپوگرافی", "typography", "typographic"),
        look = look(
            "kinetic-poster", canvas = 0xFF121014, aurora = listOf(0xFF121014, 0xFF3A0F1E, 0xFF0F1E3A, 0xFF2A230A),
            ink = 0xFFFFF4E6, muted = 0xFFA39A92, accent = 0xFFFF2E63, positive = 0xFFFFD23F, negative = 0xFFFF5470,
            hot = 0xFFFF7A00, card = 0x1FFFF4E6, rim = 0x40FFFFFF,
            headline = Voice(Display, 1000, 0.94f), caption = Voice(Expressive, 900, 1.26f), body = Voice(Expressive, 800, 1.3f),
            number = Voice(Display, 1000, 1f), label = Voice(Accent, 700, 1.2f, 0.18f),
            grain = 0f, vignette = 0.4f, roundness = 0.3f, mark = Mark.Block, caps = true, plate = false,
            grade = Grade(0f.anim, 1.08f.anim, 1.05f.anim, 0.05f.anim), ease = Ease.Back, backdrop = "liquid", lens = null,
        ),
        accents = listOf("#FF2E63", "#FFD23F", "#2EC4FF"),
        motion = MotionGrammar(
            energy = 0.85f, ease = Ease.Back,
            entrances = weights("stack 1.4, spread 1.2, slam 1, flip 0.8, mask-rise 0.6, type-on 0.2, blur-in 0.2"),
            enter = weights("whip 1, zoom 1, flash 0.6"), exit = weights("whip 1, zoom 0.8"), push = 1.4f, still = 0.5f,
        ),
        captions = CaptionTreatment(marks = weights("block 1, circle 0.4"), words = listOf(2, 3)),
        rhythm = Cadence(density = 1.15f, takeover = 1.2f, stages = weights("aurora 0.6, grid 0.4")),
        sound = "hype",
        fit = Fit(
            domains = weights("marketing 1, fashion 0.8, lifestyle 0.6, general 0.6, education 0.4"),
            moods = weights("energetic 1, playful 0.8, confident 0.8, urgent 0.7"),
        ),
    ),
    StyleLanguage(
        id = "swiss-grid", nameFa = "گرید سوئیسی", nameEn = "Swiss Grid", pack = "bento-grid", tones = setOf("paper"),
        words = listOf("سوئیسی", "swiss", "گرید", "grid", "مدولار", "modular"),
        look = look(
            "swiss-grid", canvas = 0xFFF4F4F0, aurora = listOf(0xFFF4F4F0, 0xFFE8E8E3, 0xFFF9F9F6),
            ink = 0xFF111111, muted = 0xFF5E5E5A, accent = 0xFFD0021B, positive = 0xFF111111, negative = 0xFFD0021B,
            hot = 0xFFD0021B, card = 0xFFFFFFFF, rim = 0x24111111,
            headline = Voice(Ui, 900, 1f), caption = Voice(Ui, 800, 1.28f), body = Voice(Ui, 600, 1.34f),
            number = Voice(Accent, 800, 1f), label = Voice(Ui, 700, 1.2f, 0.12f),
            grain = 0f, vignette = 0f, roundness = 0f, mark = Mark.Block, caps = false, plate = true,
            grade = Grade(0f.anim, 1.04f.anim, 0.9f.anim, 0f.anim), ease = Ease.Snap, backdrop = null, lens = null,
        ),
        accents = listOf("#D0021B", "#1F4BFF", "#111111"),
        motion = MotionGrammar(
            energy = 0.55f, ease = Ease.Snap,
            entrances = weights("mask-rise 1.3, type-on 0.6, slam 0.5, spread 0.4, flip 0.3, blur-in 0.3"),
            enter = weights("cut 1, whip 0.8, zoom 0.6"), exit = weights("cut 1, whip 0.6"), push = 0.4f, still = 1.6f,
        ),
        captions = CaptionTreatment(marks = weights("block 1, underline 0.8"), words = listOf(3, 4)),
        rhythm = Cadence(density = 0.9f, takeover = 1f, stages = weights("grid 1.4, plain 0.3, aurora 0.2")),
        sound = "minimal",
        fit = Fit(
            domains = weights("education 0.8, business 0.9, software 0.7, news 0.6, finance 0.6, marketing 0.6, general 0.5"),
            moods = weights("serious 1, confident 0.8, calm 0.5"),
        ),
    ),
    StyleLanguage(
        id = "retro-vhs", nameFa = "رترو VHS", nameEn = "Retro VHS", pack = "retrofuturism", tones = setOf("noir"),
        words = listOf("رترو", "retro", "vhs", "نوستالژی", "nostalgia", "nostalgic", "دهه شصت", "eighties", "80s", "synthwave", "سینت‌ویو"),
        look = look(
            "retro-vhs", canvas = 0xFF0D0B1A, aurora = listOf(0xFF0D0B1A, 0xFF3B1250, 0xFF0E3C4F, 0xFF4A2A0C),
            ink = 0xFFFDF6E3, muted = 0xFFA79FB5, accent = 0xFFFF4FD8, positive = 0xFF43F2FF, negative = 0xFFFF6F87,
            hot = 0xFFFFB000, card = 0x1FFDF6E3, rim = 0x4DFF4FD8,
            headline = Voice(Display, 1000, 1f), caption = Voice(Expressive, 900, 1.28f), body = Voice(Expressive, 700, 1.3f),
            number = Voice(Accent, 900, 1f), label = Voice(Accent, 600, 1.2f, 0.2f),
            grain = 0.1f, vignette = 0.55f, roundness = 0.6f, mark = Mark.Block, caps = true, plate = false,
            grade = Grade(0.02f.anim, 1f.anim, 1.15f.anim, 0.2f.anim), ease = Ease.Snap, backdrop = "leaks", lens = "chromatic",
        ),
        accents = listOf("#FF4FD8", "#43F2FF", "#FFB000"),
        motion = MotionGrammar(
            energy = 0.7f, ease = Ease.Snap,
            entrances = weights("glitch 1.2, type-on 1, slam 0.8, flip 0.6, mask-rise 0.6, spread 0.4, blur-in 0.2"),
            enter = weights("flash 1, whip 0.6, zoom 0.5, leak 0.4"), exit = weights("cut 0.8, whip 0.6"), push = 1f, still = 0.6f,
        ),
        captions = CaptionTreatment(marks = weights("block 1, ink 0.6"), words = listOf(3, 4)),
        rhythm = Cadence(density = 1.05f, takeover = 1f, stages = weights("grid 1, aurora 0.6")),
        sound = "retro",
        fit = Fit(
            domains = weights("gaming 0.7, lifestyle 0.6, marketing 0.6, fashion 0.6, general 0.4, tech 0.4"),
            moods = weights("playful 1, energetic 0.8, inspiring 0.4"),
        ),
    ),
    StyleLanguage(
        id = "neon-cyber", nameFa = "نئون سایبر", nameEn = "Neon Cyber", pack = "y2k", tones = setOf("noir"),
        words = listOf("نئون", "neon", "سایبر", "cyber", "سایبرپانک", "cyberpunk", "فیوچریستیک", "futuristic", "y2k"),
        look = look(
            "neon-cyber", canvas = 0xFF05060F, aurora = listOf(0xFF05060F, 0xFF0A2A4A, 0xFF2A0A4A, 0xFF003B36),
            ink = 0xFFEAFBFF, muted = 0xFF7D8BA6, accent = 0xFF00F0FF, positive = 0xFF39FF88, negative = 0xFFFF3D81,
            hot = 0xFFFF3DF2, card = 0x1A00F0FF, rim = 0x5500F0FF,
            headline = Voice(Display, 1000, 1f), caption = Voice(Ui, 900, 1.26f), body = Voice(Ui, 700, 1.3f),
            number = Voice(Accent, 900, 1f), label = Voice(Accent, 600, 1.2f, 0.24f),
            grain = 0f, vignette = 0.5f, roundness = 0.4f, mark = Mark.Ink, caps = true, plate = false,
            grade = Grade(0f.anim, 1.1f.anim, 1.1f.anim, (-0.5f).anim), ease = Ease.Snap, backdrop = "glow", lens = null,
        ),
        accents = listOf("#00F0FF", "#FF3DF2", "#39FF88"),
        motion = MotionGrammar(
            energy = 0.85f, ease = Ease.Snap,
            entrances = weights("glitch 1.5, type-on 0.9, slam 0.8, spread 0.6, flip 0.5, mask-rise 0.4"),
            enter = weights("flash 1, whip 0.8, zoom 0.5"), exit = weights("whip 0.8, cut 0.8"), push = 1.2f, still = 0.6f,
        ),
        captions = CaptionTreatment(marks = weights("ink 1, block 0.6, underline 0.4"), words = listOf(2, 3)),
        rhythm = Cadence(density = 1.2f, takeover = 1.1f, stages = weights("grid 1.2, aurora 0.6")),
        sound = "glitch",
        fit = Fit(
            domains = weights("crypto 1, gaming 0.9, ai 0.8, tech 0.8, software 0.6"),
            moods = weights("energetic 1, urgent 0.9, confident 0.7"),
        ),
    ),
    StyleLanguage(
        id = "minimal-mono", nameFa = "مونو مینیمال", nameEn = "Minimal Mono", pack = "minimalism", tones = setOf("paper"),
        words = listOf("مینیمال", "minimal", "minimalist", "مونو", "mono", "monochrome", "تک‌رنگ", "سیاه و سفید", "black and white"),
        look = look(
            "minimal-mono", canvas = 0xFFFAFAF8, aurora = listOf(0xFFFAFAF8, 0xFFEFEFEB, 0xFFF5F5F2),
            ink = 0xFF0E0E0E, muted = 0xFF62625E, accent = 0xFF0E0E0E, positive = 0xFF0E0E0E, negative = 0xFFB00020,
            hot = 0xFF0E0E0E, card = 0xFFFFFFFF, rim = 0x1F0E0E0E,
            headline = Voice(Ui, 800, 1.06f), caption = Voice(Ui, 800, 1.3f), body = Voice(Ui, 500, 1.4f),
            number = Voice(Ui, 400, 1f), label = Voice(Ui, 600, 1.2f, 0.2f),
            grain = 0f, vignette = 0f, roundness = 0.2f, mark = Mark.Underline, caps = false, plate = true,
            grade = Grade(0f.anim, 1.02f.anim, 0.8f.anim, 0f.anim), ease = Ease.Sine, backdrop = null, lens = null,
        ),
        accents = listOf("#0E0E0E", "#1F3BFF"),
        motion = MotionGrammar(
            energy = 0.45f, ease = Ease.Sine,
            entrances = weights("mask-rise 1.2, blur-in 1, type-on 0.7, spread 0.3, flip 0.2, slam 0.2"),
            enter = weights("cut 1, zoom 0.5, leak 0.2"), exit = weights("cut 1, zoom 0.3"), push = 0.3f, still = 2f,
        ),
        captions = CaptionTreatment(marks = weights("underline 1, block 0.5"), words = listOf(3, 4, 4)),
        rhythm = Cadence(density = 0.8f, takeover = 0.85f, stages = weights("plain 1, grid 0.6, aurora 0.3")),
        sound = "minimal",
        fit = Fit(
            domains = weights("software 0.7, tech 0.7, education 0.6, business 0.7, fashion 0.6, real-estate 0.5, general 0.6, health 0.4"),
            moods = weights("calm 1, serious 0.9, confident 0.6, luxury 0.5"),
        ),
    ),
    StyleLanguage(
        id = "playful-pop", nameFa = "پاپ شاد", nameEn = "Playful Pop", pack = "neobrutalism", tones = setOf("paper"),
        words = listOf("پاپ", "pop", "شاد", "playful", "فان", "fun", "رنگی‌رنگی", "colorful", "colourful"),
        look = look(
            "playful-pop", canvas = 0xFFFFF6E5, aurora = listOf(0xFFFFF6E5, 0xFFFFE3EC, 0xFFE3F4FF, 0xFFFFF0C2),
            ink = 0xFF1A1036, muted = 0xFF5E5478, accent = 0xFFFF5FA2, positive = 0xFF6C3BFF, negative = 0xFFD9381E,
            hot = 0xFFE2541A, card = 0xFFFFFFFF, rim = 0x331A1036,
            headline = Voice(Display, 1000, 1.02f), caption = Voice(Expressive, 900, 1.28f), body = Voice(Expressive, 700, 1.3f),
            number = Voice(Display, 1000, 1f), label = Voice(Accent, 700, 1.2f, 0.1f),
            grain = 0f, vignette = 0f, roundness = 2f, mark = Mark.Block, caps = false, plate = true,
            grade = Grade(0.03f.anim, 1f.anim, 1.15f.anim, 0.1f.anim), ease = Ease.Spring, backdrop = "liquid", lens = null,
        ),
        accents = listOf("#FF5FA2", "#FFC531", "#4FD1FF", "#7CF29A"),
        motion = MotionGrammar(
            energy = 0.75f, ease = Ease.Spring,
            entrances = weights("flip 1.2, slam 1, spread 1, stack 0.8, mask-rise 0.6, type-on 0.3, blur-in 0.2"),
            enter = weights("zoom 1, whip 0.8, flash 0.5"), exit = weights("zoom 0.8, whip 0.8"), push = 1.2f, still = 0.6f,
        ),
        captions = CaptionTreatment(marks = weights("block 1, circle 0.8, underline 0.4"), words = listOf(2, 3)),
        rhythm = Cadence(density = 1.2f, takeover = 1.05f, stages = weights("aurora 1, grid 0.4")),
        sound = "playful",
        fit = Fit(
            domains = weights("lifestyle 0.8, food 0.6, marketing 0.8, fashion 0.7, education 0.5, general 0.6, beauty 0.4"),
            moods = weights("playful 1, energetic 0.8, inspiring 0.5"),
        ),
    ),
    StyleLanguage(
        id = "finance-terminal", nameFa = "ترمینال مالی", nameEn = "Finance Terminal", pack = "dark-mode-ui", tones = setOf("noir"),
        words = listOf("ترمینال", "terminal", "داشبورد", "dashboard", "تابلوی بورس", "ticker tape", "trading desk"),
        look = look(
            "finance-terminal", canvas = 0xFF070A0C, aurora = listOf(0xFF070A0C, 0xFF0B2218, 0xFF1A1408, 0xFF0A1626),
            ink = 0xFFE6F1EC, muted = 0xFF7F918A, accent = 0xFF2BFF88, positive = 0xFF2BFF88, negative = 0xFFFF4D4D,
            hot = 0xFFFFB800, card = 0x1A2BFF88, rim = 0x402BFF88,
            headline = Voice(Ui, 900, 1.05f), caption = Voice(Ui, 800, 1.28f), body = Voice(Ui, 600, 1.34f),
            number = Voice(Accent, 900, 1f), label = Voice(Accent, 600, 1.2f, 0.22f),
            grain = 0f, vignette = 0.35f, roundness = 0.3f, mark = Mark.Block, caps = true, plate = false,
            grade = Grade(0f.anim, 1.08f.anim, 0.85f.anim, (-0.2f).anim), ease = Ease.Snap, backdrop = null, lens = null,
        ),
        accents = listOf("#2BFF88", "#FFB800", "#4DA3FF"),
        motion = MotionGrammar(
            energy = 0.65f, ease = Ease.Snap,
            entrances = weights("type-on 1.4, mask-rise 1, glitch 0.5, slam 0.5, flip 0.3, blur-in 0.2, spread 0.2"),
            enter = weights("cut 1, whip 0.6, flash 0.5, zoom 0.4"), exit = weights("cut 1, whip 0.5"), push = 0.6f, still = 1.4f,
        ),
        captions = CaptionTreatment(marks = weights("block 1, underline 0.7"), words = listOf(3, 4)),
        rhythm = Cadence(density = 1.1f, takeover = 1.15f, stages = weights("grid 1.5, aurora 0.3")),
        sound = "tech",
        fit = Fit(
            domains = weights("finance 1, crypto 1, business 0.7, news 0.5, tech 0.4"),
            moods = weights("serious 1, confident 0.9, urgent 0.8, energetic 0.6"),
        ),
    ),
    StyleLanguage(
        id = "news-broadcast", nameFa = "پخش خبری", nameEn = "News Broadcast", pack = "material-design", tones = setOf("noir"),
        words = listOf("خبری", "broadcast", "اخبار", "newsroom", "تیتر خبری", "breaking news"),
        look = look(
            "news-broadcast", canvas = 0xFF0A1430, aurora = listOf(0xFF0A1430, 0xFF14285C, 0xFF2A0E1A, 0xFF0E2240),
            ink = 0xFFFFFFFF, muted = 0xFFA9B4CC, accent = 0xFFD90F28, positive = 0xFFFFC400, negative = 0xFFFF5E6C,
            hot = 0xFFFF4A5E, card = 0x26FFFFFF, rim = 0x40FFFFFF,
            headline = Voice(Ui, 900, 1.05f), caption = Voice(Ui, 800, 1.28f), body = Voice(Ui, 600, 1.34f),
            number = Voice(Ui, 900, 1f), label = Voice(Ui, 700, 1.2f, 0.14f),
            grain = 0f, vignette = 0.3f, roundness = 0.25f, mark = Mark.Block, caps = true, plate = false,
            grade = Grade(0f.anim, 1.04f.anim, 0.95f.anim, 0f.anim), ease = Ease.Expo, backdrop = "glow", lens = null,
        ),
        accents = listOf("#D90F28", "#FFC400", "#FFFFFF"),
        motion = MotionGrammar(
            energy = 0.6f, ease = Ease.Expo,
            entrances = weights("mask-rise 1.4, type-on 0.8, flip 0.7, slam 0.4, spread 0.2, blur-in 0.2"),
            enter = weights("zoom 1, whip 0.8, flash 0.3"), exit = weights("whip 1, cut 0.7"), push = 0.8f, still = 1f,
        ),
        captions = CaptionTreatment(marks = weights("block 1, underline 0.6"), words = listOf(3, 4)),
        rhythm = Cadence(density = 1f, takeover = 1.1f, stages = weights("aurora 1, grid 0.6")),
        sound = "corporate",
        fit = Fit(
            domains = weights("news 1, finance 0.6, business 0.6, ai 0.5, tech 0.4, crypto 0.4"),
            moods = weights("serious 1, urgent 0.9, confident 0.6"),
        ),
    ),
    StyleLanguage(
        id = "documentary", nameFa = "مستند", nameEn = "Documentary", pack = "grain-noise", tones = setOf("noir"),
        words = listOf("مستند", "documentary", "روایی", "narrative", "گرم", "warm", "صادقانه", "honest"),
        look = look(
            "documentary", canvas = 0xFF12110E, aurora = listOf(0xFF12110E, 0xFF2A241A, 0xFF1A2220, 0xFF241A14),
            ink = 0xFFF1EADB, muted = 0xFF9E9583, accent = 0xFFE9C46A, positive = 0xFFE9C46A, negative = 0xFFE76F51,
            hot = 0xFFE76F51, card = 0x1AF1EADB, rim = 0x38F1EADB,
            headline = Voice(Expressive, 800, 1.18f), caption = Voice(Expressive, 800, 1.3f), body = Voice(Expressive, 600, 1.36f),
            number = Voice(Accent, 700, 1f), label = Voice(Accent, 500, 1.2f, 0.2f),
            grain = 0.08f, vignette = 0.5f, roundness = 0.5f, mark = Mark.Underline, caps = false, plate = false,
            grade = Grade((-0.02f).anim, 1f.anim, 0.8f.anim, 0.25f.anim), ease = Ease.Sine, backdrop = "dust", lens = "halation",
        ),
        accents = listOf("#E9C46A", "#E76F51", "#8ECAE6"),
        motion = MotionGrammar(
            energy = 0.4f, ease = Ease.Sine,
            entrances = weights("blur-in 1.2, mask-rise 1.2, type-on 0.8, flip 0.2, slam 0.2, spread 0.2"),
            enter = weights("leak 0.8, zoom 0.8, cut 0.8"), exit = weights("cut 1, zoom 0.4"), push = 0.8f, still = 1f,
        ),
        captions = CaptionTreatment(marks = weights("underline 1, ink 0.6, block 0.4"), words = listOf(4, 5)),
        rhythm = Cadence(density = 0.85f, takeover = 0.85f, stages = weights("aurora 1, grid 0.2")),
        sound = "organic",
        fit = Fit(
            domains = weights("news 0.6, travel 0.7, lifestyle 0.7, health 0.5, medical 0.5, education 0.6, general 0.6, food 0.4, fitness 0.4"),
            moods = weights("serious 0.9, inspiring 0.9, calm 0.8"),
        ),
    ),
    StyleLanguage(
        id = "cinematic-trailer", nameFa = "تریلر سینمایی", nameEn = "Cinematic Trailer", pack = "motion-driven-ui", tones = setOf("noir"),
        words = listOf("سینمایی", "cinematic", "تریلر", "trailer", "حماسی", "epic", "فیلمیک", "filmic"),
        look = look(
            "cinematic-trailer", canvas = 0xFF050505, aurora = listOf(0xFF050505, 0xFF1B1206, 0xFF06121B, 0xFF140606),
            ink = 0xFFF5F1EA, muted = 0xFF9B958C, accent = 0xFFF2B33D, positive = 0xFFF2B33D, negative = 0xFFE0573C,
            hot = 0xFFFF6A1F, card = 0x14FFFFFF, rim = 0x33FFFFFF,
            headline = Voice(Display, 900, 1f), caption = Voice(Expressive, 800, 1.28f), body = Voice(Expressive, 600, 1.32f),
            number = Voice(Display, 900, 1f), label = Voice(Accent, 500, 1.2f, 0.3f),
            grain = 0.06f, vignette = 0.7f, roundness = 0.3f, mark = Mark.Ink, caps = true, plate = false,
            grade = Grade((-0.03f).anim, 1.12f.anim, 0.9f.anim, 0.1f.anim), ease = Ease.Expo, backdrop = "glow", lens = "halation",
        ),
        accents = listOf("#F2B33D", "#5FB7FF", "#FF6A1F"),
        motion = MotionGrammar(
            energy = 0.7f, ease = Ease.Expo,
            entrances = weights("blur-in 1, mask-rise 1, spread 1, slam 0.7, stack 0.4, flip 0.2, type-on 0.2"),
            enter = weights("flash 1, leak 0.7, zoom 0.6, whip 0.2"), exit = weights("cut 1.2, zoom 0.6"), push = 1.6f, still = 0.4f,
        ),
        captions = CaptionTreatment(marks = weights("ink 1, underline 0.5, block 0.4"), words = listOf(3, 4)),
        rhythm = Cadence(density = 0.9f, takeover = 1.2f, stages = weights("aurora 1, grid 0.1")),
        sound = "epic",
        fit = Fit(
            domains = weights("marketing 0.7, automotive 0.8, gaming 0.6, tech 0.6, travel 0.5, lifestyle 0.5, general 0.5, sports 0.5"),
            moods = weights("inspiring 1, confident 0.8, urgent 0.7, luxury 0.6, serious 0.5"),
        ),
    ),
    StyleLanguage(
        id = "beauty-soft", nameFa = "بیوتی نرم", nameEn = "Beauty Soft", pack = "claymorphism", tones = setOf("paper", "lumen"),
        words = listOf("بیوتی", "beauty look", "نرم", "soft", "پاستلی", "pastel", "لطیف", "delicate", "تمیز", "clean"),
        look = look(
            "beauty-soft", canvas = 0xFFFBF1EE, aurora = listOf(0xFFFBF1EE, 0xFFF6DCD8, 0xFFEFE3F6, 0xFFFDF0E2),
            ink = 0xFF2B1A1F, muted = 0xFF7A6268, accent = 0xFFE8A5B5, positive = 0xFFA33F62, negative = 0xFFA33F62,
            hot = 0xFFC9566F, card = 0xFFFFFFFF, rim = 0x262B1A1F,
            headline = Voice(Expressive, 800, 1.15f), caption = Voice(Expressive, 800, 1.3f), body = Voice(Expressive, 600, 1.36f),
            number = Voice(Expressive, 800, 1f), label = Voice(Accent, 500, 1.2f, 0.18f),
            grain = 0f, vignette = 0.1f, roundness = 1.8f, mark = Mark.Underline, caps = false, plate = true,
            grade = Grade(0.04f.anim, 0.98f.anim, 0.92f.anim, 0.15f.anim), ease = Ease.Sine, backdrop = "bokeh", lens = null,
        ),
        accents = listOf("#E8A5B5", "#C9B6F2", "#F5C9A8"),
        motion = MotionGrammar(
            energy = 0.45f, ease = Ease.Sine,
            entrances = weights("blur-in 1.4, mask-rise 1, spread 0.5, flip 0.4, type-on 0.2, slam 0.15"),
            enter = weights("leak 1, zoom 0.8, whip 0.2"), exit = weights("zoom 0.8, cut 0.6"), push = 0.8f, still = 1f,
        ),
        captions = CaptionTreatment(marks = weights("underline 1, block 0.7, circle 0.4"), words = listOf(3, 4)),
        rhythm = Cadence(density = 0.95f, takeover = 0.9f, stages = weights("aurora 1, grid 0.1")),
        sound = "elegant",
        fit = Fit(
            domains = weights("beauty 1, fashion 0.7, health 0.6, lifestyle 0.6"),
            moods = weights("calm 1, luxury 0.8, inspiring 0.6, playful 0.4, energetic 0.4"),
        ),
    ),
    StyleLanguage(
        id = "food-warm", nameFa = "آشپزخانهٔ گرم", nameEn = "Warm Kitchen", pack = "organic-ui", tones = setOf("paper", "lumen"),
        words = listOf("گرم", "warm", "صمیمی", "cozy", "خانگی", "homemade", "دنج", "rustic"),
        look = look(
            "food-warm", canvas = 0xFFFBF3E6, aurora = listOf(0xFFFBF3E6, 0xFFF6DFC3, 0xFFEFE7D2, 0xFFF9E8D9),
            ink = 0xFF2A1A10, muted = 0xFF765F4B, accent = 0xFFF2A541, positive = 0xFFA63E1B, negative = 0xFFA63E1B,
            hot = 0xFFC9501F, card = 0xFFFFFDF8, rim = 0x262A1A10,
            headline = Voice(Display, 900, 1.05f), caption = Voice(Expressive, 900, 1.28f), body = Voice(Expressive, 700, 1.32f),
            number = Voice(Display, 900, 1f), label = Voice(Accent, 600, 1.2f, 0.14f),
            grain = 0f, vignette = 0.12f, roundness = 1.5f, mark = Mark.Circle, caps = false, plate = true,
            grade = Grade(0.03f.anim, 1.02f.anim, 1.1f.anim, 0.45f.anim), ease = Ease.Back, backdrop = "bokeh", lens = null,
        ),
        accents = listOf("#F2A541", "#8FBF6A", "#F28C6A"),
        motion = MotionGrammar(
            energy = 0.55f, ease = Ease.Back,
            entrances = weights("mask-rise 1, flip 0.7, blur-in 0.7, spread 0.6, slam 0.4, type-on 0.3, stack 0.3"),
            enter = weights("leak 1, zoom 0.8, whip 0.5"), exit = weights("zoom 0.7, whip 0.6, cut 0.5"), push = 1f, still = 0.8f,
        ),
        captions = CaptionTreatment(marks = weights("circle 1, block 0.7, underline 0.6"), words = listOf(3, 4)),
        rhythm = Cadence(density = 1f, takeover = 0.95f, stages = weights("aurora 1, grid 0.2")),
        sound = "warm",
        fit = Fit(
            domains = weights("food 1, lifestyle 0.4, travel 0.4, health 0.4"),
            moods = weights("calm 0.8, playful 0.8, inspiring 0.6, energetic 0.5"),
        ),
    ),
    StyleLanguage(
        id = "tech-product", nameFa = "محصول فناوری", nameEn = "Tech Product", pack = "liquid-glass", tones = setOf("noir", "lumen"),
        words = listOf("فناوری", "تکنولوژی", "technology", "sleek", "keynote", "محصولی", "product reveal"),
        look = look(
            "tech-product", canvas = 0xFF050607, aurora = listOf(0xFF050607, 0xFF0D1B2E, 0xFF1A1A1F, 0xFF0A2420),
            ink = 0xFFF5F7FA, muted = 0xFF8C93A0, accent = 0xFF5B9CFF, positive = 0xFF5BE49B, negative = 0xFFFF6A6F,
            hot = 0xFFA970FF, card = 0x1FFFFFFF, rim = 0x47FFFFFF,
            headline = Voice(Ui, 900, 1.05f), caption = Voice(Ui, 800, 1.28f), body = Voice(Ui, 500, 1.36f),
            number = Voice(Accent, 800, 1f), label = Voice(Ui, 600, 1.2f, 0.12f),
            grain = 0f, vignette = 0.4f, roundness = 1.6f, mark = Mark.Ink, caps = false, plate = false,
            grade = Grade(0f.anim, 1.05f.anim, 0.95f.anim, (-0.3f).anim), ease = Ease.Expo, backdrop = "liquid", lens = null,
        ),
        accents = listOf("#5B9CFF", "#5BE49B", "#F5F7FA"),
        motion = MotionGrammar(
            energy = 0.6f, ease = Ease.Expo,
            entrances = weights("mask-rise 1.2, blur-in 1.1, flip 0.8, type-on 0.5, spread 0.4, slam 0.4"),
            enter = weights("zoom 1.2, whip 0.6, leak 0.4, flash 0.3"), exit = weights("zoom 1, whip 0.5"), push = 1.3f, still = 0.6f,
        ),
        captions = CaptionTreatment(marks = weights("ink 1, block 0.5, underline 0.5"), words = listOf(3, 4)),
        rhythm = Cadence(density = 1f, takeover = 1.1f, stages = weights("aurora 1, grid 0.5")),
        sound = "tech",
        fit = Fit(
            domains = weights("tech 1, software 0.8, ai 0.8, automotive 0.5, gaming 0.3"),
            moods = weights("confident 1, inspiring 0.7, calm 0.6, luxury 0.5, energetic 0.5"),
        ),
    ),
    StyleLanguage(
        id = "education-chalk", nameFa = "تخته‌گچی", nameEn = "Education Chalk", pack = "skeuomorphism", tones = setOf("noir"),
        words = listOf("گچی", "chalk", "تخته", "chalkboard", "blackboard", "کلاسی", "classroom", "مدرسه‌ای"),
        look = look(
            "education-chalk", canvas = 0xFF1E2B24, aurora = listOf(0xFF1E2B24, 0xFF25362D, 0xFF1B2621, 0xFF2C3A30),
            ink = 0xFFF4F1E8, muted = 0xFFA7B3A9, accent = 0xFFFFE27A, positive = 0xFF9EE6A0, negative = 0xFFFF9A8A,
            hot = 0xFFFFB86B, card = 0x1AF4F1E8, rim = 0x40F4F1E8,
            headline = Voice(Expressive, 900, 1.12f), caption = Voice(Expressive, 900, 1.28f), body = Voice(Expressive, 700, 1.32f),
            number = Voice(Display, 900, 1f), label = Voice(Accent, 600, 1.2f, 0.14f),
            grain = 0.05f, vignette = 0.45f, roundness = 0.6f, mark = Mark.Circle, caps = false, plate = false,
            grade = Grade(0f.anim, 1.02f.anim, 0.95f.anim, 0.1f.anim), ease = Ease.Back, backdrop = "dust", lens = null,
        ),
        accents = listOf("#FFE27A", "#9EE6A0", "#8ED6FF", "#FFB3C7"),
        motion = MotionGrammar(
            energy = 0.55f, ease = Ease.Back,
            entrances = weights("type-on 1.2, mask-rise 1, flip 0.5, spread 0.4, blur-in 0.4, slam 0.3, stack 0.3"),
            enter = weights("zoom 1, whip 0.7, leak 0.3"), exit = weights("whip 0.8, zoom 0.6, cut 0.5"), push = 0.8f, still = 1f,
        ),
        captions = CaptionTreatment(marks = weights("circle 1, underline 1, block 0.5"), words = listOf(3, 4)),
        rhythm = Cadence(density = 1.05f, takeover = 1.1f, stages = weights("grid 1.2, aurora 0.5")),
        sound = "bright",
        fit = Fit(
            domains = weights("education 1, medical 0.5, health 0.4, software 0.4, general 0.4"),
            moods = weights("playful 0.8, inspiring 0.8, serious 0.6, confident 0.6"),
        ),
    ),
    StyleLanguage(
        id = "sports-energy", nameFa = "اسپرت پرانرژی", nameEn = "Sports Energy", pack = "maximalism", tones = setOf("noir"),
        words = listOf("اسپرت", "sport", "sporty", "ورزشی", "athletic", "استادیوم", "stadium"),
        look = look(
            "sports-energy", canvas = 0xFF0A0A0A, aurora = listOf(0xFF0A0A0A, 0xFF3A0A00, 0xFF002A3A, 0xFF2A2A00),
            ink = 0xFFFFFFFF, muted = 0xFFA6A6A6, accent = 0xFFC6FF00, positive = 0xFFC6FF00, negative = 0xFFFF5A2E,
            hot = 0xFFFF3D00, card = 0x1FFFFFFF, rim = 0x40FFFFFF,
            headline = Voice(Display, 1000, 0.95f), caption = Voice(Expressive, 900, 1.26f), body = Voice(Expressive, 700, 1.3f),
            number = Voice(Display, 1000, 1f), label = Voice(Accent, 800, 1.2f, 0.2f),
            grain = 0f, vignette = 0.45f, roundness = 0.2f, mark = Mark.Block, caps = true, plate = false,
            grade = Grade(0.02f.anim, 1.14f.anim, 1.15f.anim, 0f.anim), ease = Ease.Snap, backdrop = "glow", lens = null,
        ),
        accents = listOf("#C6FF00", "#FF3D00", "#00E5FF"),
        motion = MotionGrammar(
            energy = 0.95f, ease = Ease.Snap,
            entrances = weights("slam 1.4, stack 1.1, spread 0.8, flip 0.6, mask-rise 0.5, glitch 0.3, blur-in 0.1"),
            enter = weights("flash 1.1, whip 1, zoom 0.7"), exit = weights("whip 1, cut 0.7"), push = 2f, still = 0.3f,
        ),
        captions = CaptionTreatment(marks = weights("block 1, circle 0.3"), words = listOf(2, 3)),
        rhythm = Cadence(density = 1.25f, takeover = 1.15f, stages = weights("grid 0.7, aurora 0.8")),
        sound = "hype",
        fit = Fit(
            domains = weights("sports 1, fitness 1, automotive 0.5, gaming 0.5"),
            moods = weights("energetic 1, urgent 0.9, confident 0.8"),
        ),
    ),
    StyleLanguage(
        id = "kids-bright", nameFa = "کودکانهٔ رنگی", nameEn = "Kids Bright", pack = "sticker-ui", tones = setOf("paper"),
        words = listOf("کودک", "کودکانه", "kids", "children", "بچه‌ها", "کارتونی", "cartoon", "رنگی"),
        look = look(
            "kids-bright", canvas = 0xFFFFFBEA, aurora = listOf(0xFFFFFBEA, 0xFFDFF3FF, 0xFFFFE1EC, 0xFFE6FFD9),
            ink = 0xFF1B1B3A, muted = 0xFF585878, accent = 0xFFFFC531, positive = 0xFF2F6BFF, negative = 0xFFE5383B,
            hot = 0xFFE8457F, card = 0xFFFFFFFF, rim = 0x331B1B3A,
            headline = Voice(Display, 1000, 1.05f), caption = Voice(Expressive, 900, 1.28f), body = Voice(Expressive, 700, 1.32f),
            number = Voice(Display, 1000, 1f), label = Voice(Expressive, 700, 1.2f),
            grain = 0f, vignette = 0f, roundness = 2.2f, mark = Mark.Circle, caps = false, plate = true,
            grade = Grade(0.04f.anim, 1f.anim, 1.2f.anim, 0.05f.anim), ease = Ease.Spring, backdrop = "bokeh", lens = null,
        ),
        accents = listOf("#FFC531", "#7CF29A", "#7FD4FF", "#FF9EC7"),
        motion = MotionGrammar(
            energy = 0.75f, ease = Ease.Spring,
            entrances = weights("flip 1.2, spread 1.1, slam 0.8, stack 0.6, mask-rise 0.6, type-on 0.2, blur-in 0.1"),
            enter = weights("zoom 1.1, whip 0.8"), exit = weights("zoom 1, whip 0.7"), push = 1f, still = 0.8f,
        ),
        captions = CaptionTreatment(marks = weights("block 1, circle 1"), words = listOf(2, 3)),
        rhythm = Cadence(density = 1.2f, takeover = 1.1f, stages = weights("aurora 1, grid 0.3")),
        sound = "playful",
        fit = Fit(
            domains = weights("education 0.7, lifestyle 0.5, food 0.4, general 0.4, gaming 0.4"),
            moods = weights("playful 1, energetic 0.7, inspiring 0.5"),
        ),
    ),
    StyleLanguage(
        id = "corporate-clean", nameFa = "شرکتی تمیز", nameEn = "Corporate Clean", pack = "flat-design", tones = setOf("paper"),
        words = listOf("شرکتی", "corporate", "تمیز", "clean", "رسمی", "formal", "professional"),
        look = look(
            "corporate-clean", canvas = 0xFFF5F7FA, aurora = listOf(0xFFF5F7FA, 0xFFE4EBF5, 0xFFEEF2F7, 0xFFE9F1FB),
            ink = 0xFF0F1B2D, muted = 0xFF566377, accent = 0xFF1F5BFF, positive = 0xFF0F7A54, negative = 0xFFC62E2E,
            hot = 0xFF1F5BFF, card = 0xFFFFFFFF, rim = 0x1F0F1B2D,
            headline = Voice(Ui, 900, 1.05f), caption = Voice(Ui, 800, 1.28f), body = Voice(Ui, 600, 1.34f),
            number = Voice(Ui, 900, 1f), label = Voice(Ui, 600, 1.2f, 0.1f),
            grain = 0f, vignette = 0f, roundness = 0.8f, mark = Mark.Block, caps = false, plate = true,
            grade = Grade(0.02f.anim, 1f.anim, 0.95f.anim, (-0.1f).anim), ease = Ease.Expo, backdrop = "liquid", lens = null,
        ),
        accents = listOf("#1F5BFF", "#0F7A54", "#5B3FD9"),
        motion = MotionGrammar(
            energy = 0.55f, ease = Ease.Expo,
            entrances = weights("mask-rise 1.3, flip 0.6, blur-in 0.6, type-on 0.5, slam 0.3, spread 0.3"),
            enter = weights("zoom 1, whip 0.7, leak 0.2"), exit = weights("zoom 0.8, whip 0.7, cut 0.5"), push = 0.8f, still = 1f,
        ),
        captions = CaptionTreatment(marks = weights("block 1, underline 0.8"), words = listOf(3, 4)),
        rhythm = Cadence(density = 1f, takeover = 1f, stages = weights("aurora 1, grid 0.8")),
        sound = "corporate",
        fit = Fit(
            domains = weights("business 1, finance 0.7, marketing 0.6, software 0.6, education 0.5, medical 0.5, real-estate 0.4, general 0.6"),
            moods = weights("confident 1, serious 0.8, inspiring 0.5"),
        ),
    ),
    StyleLanguage(
        id = "luxury-gold", nameFa = "طلایی لوکس", nameEn = "Luxury Gold", pack = "chrome-liquid-metal", tones = setOf("noir", "lumen"),
        words = listOf("طلایی", "gold", "golden", "لوکس", "luxury", "مجلل", "lavish", "شیک", "chic"),
        look = look(
            "luxury-gold", canvas = 0xFF0C0A07, aurora = listOf(0xFF0C0A07, 0xFF2A1F0E, 0xFF1A140B, 0xFF241A10),
            ink = 0xFFF7EFDC, muted = 0xFFA89A7E, accent = 0xFFD4AF6A, positive = 0xFFE8C987, negative = 0xFFD07A5A,
            hot = 0xFFE8C987, card = 0x14F7EFDC, rim = 0x55D4AF6A,
            headline = Voice(Expressive, 700, 1.2f), caption = Voice(Expressive, 800, 1.3f), body = Voice(Expressive, 500, 1.4f),
            number = Voice(Accent, 600, 1f), label = Voice(Accent, 500, 1.2f, 0.3f),
            grain = 0f, vignette = 0.6f, roundness = 0.5f, mark = Mark.Underline, caps = true, plate = false,
            grade = Grade((-0.02f).anim, 1.04f.anim, 0.9f.anim, 0.3f.anim), ease = Ease.Sine, backdrop = "dust", lens = null,
        ),
        accents = listOf("#D4AF6A", "#E8E1D3", "#C9A27E"),
        motion = MotionGrammar(
            energy = 0.4f, ease = Ease.Sine,
            entrances = weights("blur-in 1.4, mask-rise 1, spread 0.7, flip 0.2, type-on 0.2, slam 0.1"),
            enter = weights("leak 1.2, zoom 0.8"), exit = weights("zoom 0.8, cut 0.6, whip 0.2"), push = 1.2f, still = 0.6f,
        ),
        captions = CaptionTreatment(marks = weights("underline 1, ink 0.8, block 0.3"), words = listOf(3, 4)),
        rhythm = Cadence(density = 0.8f, takeover = 0.9f, stages = weights("aurora 1, grid 0.1")),
        sound = "luxury",
        fit = Fit(
            domains = weights("fashion 0.9, beauty 0.7, real-estate 0.8, automotive 0.7, finance 0.5, lifestyle 0.6, crypto 0.4"),
            moods = weights("luxury 1, calm 0.7, confident 0.7, inspiring 0.5"),
        ),
    ),
    StyleLanguage(
        id = "street-hype", nameFa = "استریت هایپ", nameEn = "Street Hype", pack = "anti-design", tones = setOf("noir"),
        words = listOf("استریت", "street", "هایپ", "hype", "streetwear", "خیابانی", "گرافیتی", "graffiti"),
        look = look(
            "street-hype", canvas = 0xFF0E0E0E, aurora = listOf(0xFF0E0E0E, 0xFF2B0F3A, 0xFF3A1A00, 0xFF0F2B1A),
            ink = 0xFFFFFFFF, muted = 0xFFA3A3A3, accent = 0xFFFF6A00, positive = 0xFFFFE600, negative = 0xFFFF4D6A,
            hot = 0xFFFF2D55, card = 0x22FFFFFF, rim = 0x55FFFFFF,
            headline = Voice(Display, 1000, 0.92f), caption = Voice(Display, 900, 1.24f), body = Voice(Expressive, 800, 1.3f),
            number = Voice(Display, 1000, 1f), label = Voice(Accent, 800, 1.2f, 0.12f),
            grain = 0.05f, vignette = 0.45f, roundness = 0f, mark = Mark.Block, caps = true, plate = false,
            grade = Grade(0f.anim, 1.15f.anim, 1.05f.anim, 0f.anim), ease = Ease.Snap, backdrop = null, lens = "chromatic",
        ),
        accents = listOf("#FF6A00", "#FFE600", "#B388FF"),
        motion = MotionGrammar(
            energy = 0.95f, ease = Ease.Snap,
            entrances = weights("stack 1.3, slam 1.1, spread 1, glitch 0.9, flip 0.5, mask-rise 0.3, blur-in 0.1"),
            enter = weights("flash 1, whip 1, cut 0.6"), exit = weights("whip 1, cut 1"), push = 1.8f, still = 0.4f,
        ),
        captions = CaptionTreatment(marks = weights("block 1, circle 0.6, ink 0.3"), words = listOf(2, 3)),
        rhythm = Cadence(density = 1.25f, takeover = 1.15f, stages = weights("grid 0.5, aurora 0.8")),
        sound = "hype",
        fit = Fit(
            domains = weights("fashion 1, marketing 0.8, lifestyle 0.6, sports 0.5, gaming 0.5"),
            moods = weights("energetic 1, urgent 1, confident 0.8, playful 0.6"),
        ),
    ),
    StyleLanguage(
        id = "calm-wellness", nameFa = "آرامش و سلامت", nameEn = "Calm Wellness", pack = "biophilic-design", tones = setOf("lumen", "paper"),
        words = listOf("آرامش", "wellness", "ولنس", "مدیتیشن", "meditation", "یوگا", "yoga", "طبیعت", "nature", "zen", "گرم", "warm"),
        look = look(
            "calm-wellness", canvas = 0xFFF1F3EC, aurora = listOf(0xFFF1F3EC, 0xFFDCE7D8, 0xFFEAE6D9, 0xFFE2EEE9),
            ink = 0xFF1E2B24, muted = 0xFF56655B, accent = 0xFF8FB996, positive = 0xFF2F6B4F, negative = 0xFFA8553B,
            hot = 0xFFB9733A, card = 0xE6FFFFFF, rim = 0x1F1E2B24,
            headline = Voice(Expressive, 800, 1.18f), caption = Voice(Expressive, 800, 1.3f), body = Voice(Expressive, 500, 1.4f),
            number = Voice(Expressive, 700, 1f), label = Voice(Accent, 500, 1.2f, 0.2f),
            grain = 0f, vignette = 0.08f, roundness = 1.8f, mark = Mark.Underline, caps = false, plate = true,
            grade = Grade(0.03f.anim, 0.97f.anim, 0.9f.anim, 0.1f.anim), ease = Ease.Sine, backdrop = "liquid", lens = null,
        ),
        accents = listOf("#8FB996", "#D9B88F", "#A9C8E0"),
        motion = MotionGrammar(
            energy = 0.3f, ease = Ease.Sine,
            entrances = weights("blur-in 1.5, mask-rise 1, spread 0.3, type-on 0.3, flip 0.2, slam 0.05"),
            enter = weights("leak 1, zoom 0.6"), exit = weights("cut 0.8, zoom 0.6"), push = 0.5f, still = 1.4f,
        ),
        captions = CaptionTreatment(marks = weights("underline 1, block 0.6, circle 0.3"), words = listOf(3, 4, 5)),
        rhythm = Cadence(density = 0.75f, takeover = 0.8f, stages = weights("aurora 1")),
        sound = "ambient",
        fit = Fit(
            domains = weights("health 1, medical 0.7, fitness 0.5, lifestyle 0.8, beauty 0.5, food 0.3"),
            moods = weights("calm 1, inspiring 0.8, luxury 0.4"),
        ),
    ),
    StyleLanguage(
        id = "travel-film", nameFa = "فیلم سفر", nameEn = "Travel Film", pack = "scrapbook", tones = setOf("noir"),
        words = listOf("سفرنامه", "wanderlust", "غروب", "sunset", "golden hour", "گرم", "warm", "اسکرپ‌بوک"),
        look = look(
            "travel-film", canvas = 0xFF0F0D0A, aurora = listOf(0xFF0F0D0A, 0xFF2E2112, 0xFF122630, 0xFF2A1A12),
            ink = 0xFFFFF8EC, muted = 0xFFABA08E, accent = 0xFFFFB347, positive = 0xFF7FD1D8, negative = 0xFFFF8A66,
            hot = 0xFFFF7B54, card = 0x1AFFF8EC, rim = 0x38FFF8EC,
            headline = Voice(Display, 900, 1.05f), caption = Voice(Expressive, 900, 1.28f), body = Voice(Expressive, 600, 1.34f),
            number = Voice(Display, 900, 1f), label = Voice(Accent, 600, 1.2f, 0.26f),
            grain = 0.06f, vignette = 0.5f, roundness = 0.8f, mark = Mark.Underline, caps = true, plate = false,
            grade = Grade(0.02f.anim, 1.06f.anim, 1.1f.anim, 0.35f.anim), ease = Ease.Sine, backdrop = "leaks", lens = "halation",
        ),
        accents = listOf("#FFB347", "#7FD1D8", "#FF8A66"),
        motion = MotionGrammar(
            energy = 0.6f, ease = Ease.Sine,
            entrances = weights("spread 1.2, blur-in 1, mask-rise 1, flip 0.3, slam 0.3, type-on 0.3"),
            enter = weights("leak 1.2, whip 0.8, zoom 0.6"), exit = weights("whip 0.8, zoom 0.6"), push = 1.4f, still = 0.5f,
        ),
        captions = CaptionTreatment(marks = weights("underline 1, ink 0.8, block 0.5"), words = listOf(3, 4)),
        rhythm = Cadence(density = 0.95f, takeover = 0.95f, stages = weights("aurora 1, grid 0.1")),
        sound = "cinematic",
        fit = Fit(
            domains = weights("travel 1, lifestyle 0.7, food 0.4, real-estate 0.3"),
            moods = weights("inspiring 1, calm 0.6, playful 0.6, energetic 0.5"),
        ),
    ),
    StyleLanguage(
        id = "real-estate-premium", nameFa = "املاک پریمیوم", nameEn = "Real-Estate Premium", pack = "spatial-ui", tones = setOf("paper", "lumen"),
        words = listOf("پریمیوم", "premium", "لوکس", "luxury", "معماری", "architectural", "شیک", "chic"),
        look = look(
            "real-estate-premium", canvas = 0xFFF2EEE7, aurora = listOf(0xFFF2EEE7, 0xFFE3D9CB, 0xFFECE6DC, 0xFFDCD5CA),
            ink = 0xFF1C1A17, muted = 0xFF665F55, accent = 0xFF7A5A2E, positive = 0xFF7A5A2E, negative = 0xFFA2463A,
            hot = 0xFF8E6A3A, card = 0xE6FFFFFF, rim = 0x261C1A17,
            headline = Voice(Display, 800, 1.05f), caption = Voice(Ui, 800, 1.28f), body = Voice(Ui, 500, 1.4f),
            number = Voice(Accent, 500, 1f), label = Voice(Ui, 600, 1.2f, 0.28f),
            grain = 0f, vignette = 0.15f, roundness = 0.4f, mark = Mark.Underline, caps = true, plate = true,
            grade = Grade(0.03f.anim, 1.03f.anim, 0.92f.anim, 0.2f.anim), ease = Ease.Sine, backdrop = "glow", lens = null,
        ),
        accents = listOf("#7A5A2E", "#1C1A17", "#3F5A4A"),
        motion = MotionGrammar(
            energy = 0.4f, ease = Ease.Sine,
            entrances = weights("mask-rise 1.3, blur-in 1.2, spread 0.6, type-on 0.3, flip 0.3, slam 0.1"),
            enter = weights("zoom 1, leak 0.8, whip 0.2"), exit = weights("zoom 0.8, cut 0.6"), push = 1.6f, still = 0.5f,
        ),
        captions = CaptionTreatment(marks = weights("underline 1, block 0.5"), words = listOf(3, 4)),
        rhythm = Cadence(density = 0.85f, takeover = 1f, stages = weights("aurora 0.8, grid 0.6")),
        sound = "luxury",
        fit = Fit(
            domains = weights("real-estate 1, automotive 0.5, business 0.4, lifestyle 0.4, travel 0.4"),
            moods = weights("luxury 1, calm 0.8, confident 0.7, serious 0.5"),
        ),
    ),
    StyleLanguage(
        id = "gaming", nameFa = "گیمینگ", nameEn = "Gaming", pack = "3d-immersive", tones = setOf("noir"),
        words = listOf("گیمینگ", "gaming", "گیمر", "gamer", "esports", "arcade", "آرکید", "استریمر"),
        look = look(
            "gaming", canvas = 0xFF07040F, aurora = listOf(0xFF07040F, 0xFF2A0A4A, 0xFF0A1A4A, 0xFF3A0A2A),
            ink = 0xFFF4F0FF, muted = 0xFF9A90B8, accent = 0xFFA57BFF, positive = 0xFF3DFFB0, negative = 0xFFFF5C85,
            hot = 0xFFFF3DF2, card = 0x22A57BFF, rim = 0x66A57BFF,
            headline = Voice(Display, 1000, 0.98f), caption = Voice(Display, 900, 1.24f), body = Voice(Expressive, 700, 1.3f),
            number = Voice(Display, 1000, 1f), label = Voice(Accent, 700, 1.2f, 0.22f),
            grain = 0f, vignette = 0.5f, roundness = 0.6f, mark = Mark.Block, caps = true, plate = false,
            grade = Grade(0f.anim, 1.1f.anim, 1.2f.anim, (-0.3f).anim), ease = Ease.Back, backdrop = "glow", lens = null,
        ),
        accents = listOf("#A57BFF", "#3DFFB0", "#FF3DF2"),
        motion = MotionGrammar(
            energy = 0.9f, ease = Ease.Back,
            entrances = weights("glitch 1.2, slam 1.1, flip 1, stack 0.8, spread 0.6, mask-rise 0.4"),
            enter = weights("flash 1, zoom 1, whip 0.8"), exit = weights("zoom 0.9, whip 0.8"), push = 1.6f, still = 0.5f,
        ),
        captions = CaptionTreatment(marks = weights("block 1, ink 0.7"), words = listOf(2, 3)),
        rhythm = Cadence(density = 1.2f, takeover = 1.15f, stages = weights("grid 1, aurora 0.8")),
        sound = "glitch",
        fit = Fit(
            domains = weights("gaming 1, tech 0.5, software 0.4, ai 0.3"),
            moods = weights("energetic 1, playful 0.9, urgent 0.7"),
        ),
    ),
    StyleLanguage(
        id = "podcast-studio", nameFa = "استودیو پادکست", nameEn = "Podcast Studio", pack = "neomorphism", tones = setOf("noir"),
        words = listOf("استودیو", "studio", "رادیو", "radio", "on air", "میکروفون", "microphone"),
        look = look(
            "podcast-studio", canvas = 0xFF141210, aurora = listOf(0xFF141210, 0xFF2B1E16, 0xFF1C1F24, 0xFF26170F),
            ink = 0xFFF3ECE2, muted = 0xFFA0968A, accent = 0xFFFF8A3D, positive = 0xFFFFC56B, negative = 0xFFFF7A6A,
            hot = 0xFFFF5A3D, card = 0x1AF3ECE2, rim = 0x40F3ECE2,
            headline = Voice(Expressive, 900, 1.14f), caption = Voice(Expressive, 900, 1.28f), body = Voice(Expressive, 600, 1.34f),
            number = Voice(Accent, 800, 1f), label = Voice(Accent, 600, 1.2f, 0.2f),
            grain = 0.04f, vignette = 0.5f, roundness = 1.4f, mark = Mark.Block, caps = false, plate = false,
            grade = Grade(0f.anim, 1.04f.anim, 0.9f.anim, 0.3f.anim), ease = Ease.Sine, backdrop = "dust", lens = null,
        ),
        accents = listOf("#FF8A3D", "#FFC56B", "#7FC8F8"),
        motion = MotionGrammar(
            energy = 0.45f, ease = Ease.Sine,
            entrances = weights("mask-rise 1.2, type-on 0.9, blur-in 0.9, flip 0.3, slam 0.3, spread 0.3"),
            enter = weights("zoom 1, leak 0.6, cut 0.6"), exit = weights("cut 1, zoom 0.6"), push = 0.6f, still = 1.4f,
        ),
        captions = CaptionTreatment(marks = weights("block 1, underline 0.8, ink 0.5"), words = listOf(4, 5)),
        rhythm = Cadence(density = 0.85f, takeover = 0.9f, stages = weights("aurora 1, grid 0.2")),
        sound = "lofi",
        fit = Fit(
            domains = weights("finance 0.4, business 0.6, education 0.5, lifestyle 0.6, general 0.6, news 0.4, health 0.3"),
            moods = weights("serious 0.8, calm 0.8, inspiring 0.7, confident 0.6"),
        ),
    ),
    )
}
