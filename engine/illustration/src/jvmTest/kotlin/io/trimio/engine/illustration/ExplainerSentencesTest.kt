package io.trimio.engine.illustration

import io.trimio.engine.illustration.library.Library
import io.trimio.engine.illustration.library.LibrarySource
import io.trimio.engine.illustration.photo.PhotoSource
import io.trimio.engine.illustration.photo.PhotoStore
import io.trimio.engine.illustration.style.ArtStyle
import io.trimio.engine.illustration.style.FilmLook
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Thirty typical explainer sentences (finance, technology, health, news) as the screenwriter would ask
 * for them, each turned into a picture. Every request must be answered with a relevant picture, the
 * whole film must pass the jury's style check, and with -Pillustration.sheets=<dir> contact sheets are
 * written (docs/screenshots/illustration-*.png).
 */
class ExplainerSentencesTest {
    private val library by lazy { runBlocking { Library.bundled() } }

    private fun r(line: String, subject: String, action: String = "", objects: List<String> = emptyList(), place: String = "", mood: String = "", sign: String? = null) =
        IllustrationRequest(subject, action, objects, place, mood, sign, line = line, id = line.take(24))

    private val sentences = listOf(
        // Finance
        r("قضیه میلی گلد چیه؟", "گاوصندوق پر از طلا", objects = listOf("شمش طلا"), sign = "میلی"),
        r("کاربرا میگن طلاشونو فروختن ولی پولش هنوز واریز نشده", "مرد جوان نگران", action = "به گوشی‌اش نگاه می‌کند", mood = "نگران"),
        r("گزارش تجمع برخی کاربرا منتشر شده", "سه نفر", place = "مغازه", mood = "نگران", sign = "میلی"),
        r("قیمت دلار امروز دوباره بالا رفت", "نمودار رشد", objects = listOf("دلار")),
        r("بورس این هفته ریزش سنگینی داشت", "نمودار سقوط بازار", mood = "نگران"),
        r("تورم یعنی پولت هر روز کمتر می‌خره", "پول در حال پرواز", objects = listOf("سبد خرید")),
        r("وام مسکن هنوز به دست مردم نرسیده", "خانه", objects = listOf("کیسه پول")),
        r("بانک مرکزی نرخ سود را تغییر داد", "بانک", objects = listOf("نمودار")),
        r("سرمایه‌گذاری کوچک امروز، ثروت فرداست", "نهال", objects = listOf("سکه")),
        r("قبل از امضای قرارداد همه بندها را بخوانید", "woman", action = "reading a contract", mood = "serious"),
        // Technology
        r("هوش مصنوعی کارهای تکراری رو انجام میده", "ربات", objects = listOf("لپ‌تاپ")),
        r("رمز عبورتان را با هیچ‌کس در میان نگذارید", "قفل", objects = listOf("گوشی")),
        r("اینترنت امروز در بعضی شهرها قطع شد", "آنتن سیگنال", mood = "angry"),
        r("برنامه‌نویس‌ها بیشتر از همه از این ابزار استفاده می‌کنند", "programmer", action = "working", objects = listOf("laptop")),
        r("باتری گوشی‌ها با این ترفند بیشتر دوام میاره", "باتری", objects = listOf("گوشی")),
        r("کلاهبرداری پیامکی دوباره زیاد شده", "هشدار", objects = listOf("گوشی")),
        r("داده‌های شما در فضای ابری ذخیره می‌شود", "ابر", objects = listOf("کامپیوتر")),
        // Health
        r("پزشکان می‌گویند خواب کافی مهم‌ترین عامل سلامتی است", "پزشک", action = "توضیح می‌دهد", objects = listOf("تخت")),
        r("ویروس جدید در حال گسترش است", "ویروس", mood = "worried"),
        r("فشار خون بالا قاتل خاموش است", "قلب", objects = listOf("دماسنج")),
        r("روزی هشت لیوان آب بنوشید", "آب", objects = listOf("سیب")),
        r("سیگار ریه‌ها را نابود می‌کند", "ریه", objects = listOf("سیگار")),
        r("ورزش روزانه حتی ده دقیقه هم مفید است", "زن", action = "راه رفتن", mood = "خوشحال"),
        r("واکسن آنفلوانزا را جدی بگیرید", "سرنگ", objects = listOf("قرص")),
        // News
        r("مجلس قانون جدید را تصویب کرد", "ساختمان دولت", objects = listOf("ترازو")),
        r("قیمت بنزین از فردا تغییر می‌کند", "پمپ بنزین", objects = listOf("خودرو")),
        r("خشکسالی امسال کشاورزان را نگران کرده", "بیابان", mood = "نگران"),
        r("مردم در صف خرید ایستاده‌اند", "مردم", place = "فروشگاه", mood = "tired"),
        r("کارمندان دولت از فردا دورکار می‌شوند", "کارمند", action = "پشت لپ‌تاپ", objects = listOf("لپ‌تاپ")),
        r("بازار خودرو این ماه آرام بود", "خودرو", objects = listOf("نمودار")),
    )

    @Test
    fun thirtySentencesEachGetARelevantPictureInOneStyle() = runBlocking {
        assertEquals(30, sentences.size)
        val look = FilmLook(style = ArtStyle.Pencil, seed = 1402)
        val illustrator = Illustrator(LibrarySource(library))
        val started = System.nanoTime()
        val (pictures, report) = illustrator.film(sentences, look, 512)
        val ms = (System.nanoTime() - started) / 1_000_000
        val answered = pictures.count { it != null }
        println("answered $answered/30 in $ms ms (${ms / 30} ms each); style issues: ${report.issues}")
        pictures.forEachIndexed { i, p ->
            println("%2d fit=%.2f %s | %s | missing=%s".format(i, p?.fit ?: 0f, sentences[i].line, p?.items?.joinToString(",").orEmpty().take(90), p?.missing))
        }
        assertEquals(30, answered, "every sentence has a picture")
        assertTrue(pictures.filterNotNull().all { it.fit >= 0.5f }, "every picture shows its request")
        assertTrue(report.consistent, "one look across the film: ${report.issues}")
        assertTrue(report.issues.none { it.kind == "text" }, "no text artefacts")
        val dir = System.getProperty("illustration.sheets") ?: return@runBlocking
        val cells = pictures.mapIndexed { i, p -> p!!.image to sentences[i].line }
        Png.sheet(cells.take(15), 5, 300, File(dir, "illustration-film-1.png"), "Persian explainer sentences 1-15 (pencil, one film)") { if (it % 3 == 0) look.accent else null }
        Png.sheet(cells.drop(15), 5, 300, File(dir, "illustration-film-2.png"), "Persian explainer sentences 16-30 (pencil, one film)") { if (it % 3 == 0) look.accent else null }
    }

    @Test
    fun threeStylesAndAPhoto() = runBlocking {
        val dir = System.getProperty("illustration.sheets") ?: return@runBlocking
        val source = LibrarySource(library)
        val picks = listOf(sentences[1], sentences[0], sentences[17])
        val cells = ArrayList<Pair<Raster, String>>()
        for ((style, accent) in listOf(ArtStyle.Pencil to FilmLook.YELLOW, ArtStyle.Ink to -0x1fb4c0, ArtStyle.Flat to -0xd06b3a)) {
            val look = FilmLook(style = style, accent = accent, seed = 77)
            picks.forEach { req -> cells += source.illustrate(req, look, 420)!!.image to "${style.name}: ${req.line}" }
        }
        val photo = PhotoSource(PhotoStore { synthetic(640, 480) }).illustrate(IllustrationRequest("photo", photo = "p"), FilmLook(seed = 77), 420)!!
        cells += synthetic(640, 480) to "creator photo (synthetic test picture)"
        cells += photo.image to "the photo in the film's pencil look"
        Png.sheet(cells, 4, 280, File(dir, "illustration-styles.png"), "One request set in three looks; a photo styled into pencil")
    }

    /** A test "photo": a lit grey vase and a yellow ball on a plain studio background, with gradients and noise. */
    private fun synthetic(w: Int, h: Int): Raster {
        val r = Raster(w, h)
        val rnd = java.util.Random(3)
        for (y in 0 until h) {
            for (x in 0 until w) {
                var c = Colors.argb(255, 236 - y / 12, 233 - y / 12, 228 - y / 12)
                val vx = (x - 250f) / (70f + 40f * sin(y / 40f))
                if (y in 90..420 && vx * vx < 1f) {
                    val shade = (160 - 70 * vx + (y - 90) / 6).toInt()
                    c = Colors.argb(255, shade, shade - 4, shade - 10)
                }
                val dx = x - 450f
                val dy = y - 340f
                if (dx * dx + dy * dy < 75f * 75f) {
                    val lit = 1f - (dx + dy) / 220f
                    c = Colors.argb(255, (250 * lit).toInt(), (190 * lit).toInt(), (20 * lit).toInt())
                }
                val n = rnd.nextInt(9) - 4
                r[x, y] = Colors.argb(255, Colors.r(c) + n, Colors.g(c) + n, Colors.b(c) + n)
            }
        }
        return r
    }
}
