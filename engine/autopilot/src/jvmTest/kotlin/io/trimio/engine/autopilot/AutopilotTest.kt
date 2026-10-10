package io.trimio.engine.autopilot

import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.font.createFontFamilyResolver
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.text.Language
import io.trimio.core.model.time.TimeRange
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.audio.LoudnessMeter
import io.trimio.engine.assets.MusicMood
import io.trimio.engine.motion.MotionFonts
import io.trimio.engine.motion.TextLayoutEngine
import io.trimio.engine.motion.recipe.BrandLibrary
import io.trimio.engine.motion.score.Compiler
import io.trimio.engine.motion.score.EditPlan
import io.trimio.engine.motion.score.Score
import kotlinx.coroutines.runBlocking
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AutopilotTest {
    /** The benchmark's words with even timing: 0.38 s per word, a pause after each sentence. */
    private val sentence = "تازه یکی از قابلیت‌های این پروژه است. کافیه که این ریپو رو به Claude Code، Codex یا OpenCode یا ChatGPT بدی، برات نصب بکنه. " +
        "بعد از نصب یه مپ کامل از کل پروژه تهیه می‌کنه. و برای کارهای روزمره سریع‌تر به نتیجه می‌رسی. و باعث می‌شه ۹۰ درصد توی مصرف توکن صرفه‌جویی بشه. " +
        "برای دریافت آدرس ریپو کافیه کلمه «کد» رو برای من ارسال بکنی."

    private val words: List<Word> = run {
        var t = 300L
        sentence.split(' ').filter { it.isNotEmpty() }.map { w ->
            val word = Word(w, TimeRange(t, t + 320), language = Language.Persian, emphasis = if (w.length > 5) 0.7f else 0.2f)
            t += if (w.endsWith('.')) 700 else 380
            word
        }
    }
    private val transcript = Transcript(Language.Persian, words)
    private val prompt = "ریلز پرانرژی، سبک نوآر، کپشن کلمه‌به‌کلمه"

    @Test
    fun rngIsDeterministicAndSpreads() {
        val a = Rng(7)
        val b = Rng(7)
        repeat(50) { assertEquals(a.nextLong(), b.nextLong()) }
        val values = (1..200).map { Rng(it.toLong()).next() }
        assertTrue(values.all { it in 0f..1f })
        assertTrue(values.count { it < 0.5f } in 70..130, "uniform-ish")
    }

    @Test
    fun linesKeepNameListsTogether() {
        val lines = Lines.split(words)
        val texts = lines.map { l -> words.textOf(l.range) }
        assertTrue(texts.any { "Claude" in it && "ChatGPT" in it }, "names stay in one line: $texts")
        assertTrue(lines.zipWithNext().all { (a, b) -> b.first == a.last + 1 }, "lines tile the transcript")
        assertEquals(words.lastIndex, lines.last().last)
    }

    @Test
    fun rulesReadNamesNumbersAndTheCallToAction() {
        val u = RulesUnderstander.understand(transcript, prompt)
        assertTrue(u.entities.map { it.name }.containsAll(listOf("Claude Code", "Codex", "OpenCode", "ChatGPT")), "${u.entities}")
        assertEquals("کد", u.cta?.keyword)
        assertNotNull(u.hook)
        assertEquals("noir", u.brief.look)
        assertEquals("word", u.brief.captions)
        assertTrue(u.lines.any { it.show == "logos" } && u.lines.any { it.show == "counter" } && u.lines.any { it.show == "comment" })
    }

    @Test
    fun everySeedIsADifferentWellFormedEdit() {
        val lines = Lines.split(words)
        val u = RulesUnderstander.understand(transcript, "ریلز اینستاگرام").copy(mood = "energetic")
        val planner = Planner()
        val plans = (1L..8L).map { planner.plan(words, lines, u, it) }
        val encoded = plans.map { Score.encode(it.score) }
        assertTrue(encoded.toSet().size >= 6, "seeds should differ: ${encoded.toSet().size} distinct of 8")
        assertEquals(encoded[0], Score.encode(planner.plan(words, lines, u, 1L).score), "same seed, same edit")
        for (p in plans) {
            val beats = p.score.scenes.flatMap { it.beats }
            assertTrue(p.score.scenes.first().beats.isNotEmpty(), "a hook in the first scene")
            assertTrue(beats.any { it.recipe == "comment" && it.text == "کد" }, "the CTA keyword is shown")
            assertTrue(beats.any { it.recipe == "logos" && it.items.containsAll(listOf("Claude Code", "Codex")) }, "names become logos")
            val takeovers = p.score.scenes.withIndex().filter { it.value.bg != null }.map { it.index }
            assertTrue(takeovers.zipWithNext().none { (a, b) -> b == a + 1 }, "full-frame graphics never follow each other: $takeovers")
        }
        assertTrue(plans.map { it.score.look }.toSet().size >= 2, "looks vary across seeds")
    }

    @Test
    fun criticAndCompilerProduceACleanEdit() {
        val text = TextLayoutEngine(TextMeasurer(createFontFamilyResolver(), Density(1f), LayoutDirection.Ltr), runBlocking { MotionFonts.load() })
        val brands = runBlocking { BrandLibrary.load() }
        val lines = Lines.split(words)
        val u = RulesUnderstander.understand(transcript, prompt)
        val plan = Planner().plan(words, lines, u, 3L)
        val compiler = Compiler(text, brands)
        var score = plan.score
        var out = compiler.compile(Compiler.Input(score, transcript, footage = "main"))
        repeat(2) {
            val review = Critic().review(score, out, words, lines, u.cta)
            review.revised?.let { score = it; out = compiler.compile(Compiler.Input(score, transcript, footage = "main")) }
        }
        val final = Critic().review(score, out, words, lines, u.cta)
        assertTrue(final.issues.none { it.kind in setOf("hook", "cta") }, "${final.issues}")
        assertTrue(out.beats.any { it.recipe != "pop-captions" && it.at <= 1.5f }, "hook in the first 1.5 s")
    }

    @Test
    fun soundtrackIsMasteredToDeliveryLoudness() {
        val rate = 48_000
        val voice = PcmAudio(FloatArray(rate * 6) { i -> (0.08f * sin(2 * PI * 180 * i / rate) * (if ((i / (rate / 3)) % 2 == 0) 1f else 0.1f)).toFloat() }, rate)
        val w = (0 until 9).map { k -> Word("w$k", TimeRange(k * 666L, k * 666L + 330), language = Language.English) }
        val r = Soundtrack(rate).master(voice, EditPlan.identity(6f), w, 6f, MusicMood.Uplifting, 1, emptyList())
        assertTrue(r.lufs in -15.5..-12.5, "integrated ${r.lufs} LUFS")
        assertTrue(r.peakDb <= -1.0, "peak ${r.peakDb} dBFS")
        assertTrue(LoudnessMeter.measure(r.audio).integratedLufs.isFinite())
    }

    @Test
    fun subjectFinderLocatesAFace() {
        val w = 90
        val h = 160
        val frame = ByteArray(w * h * 4)
        for (y in 0 until h) for (x in 0 until w) {
            val i = (y * w + x) * 4
            val face = ((x - 45f) / 16f).let { it * it } + ((y - 70f) / 22f).let { it * it } <= 1f
            val (r, g, b) = if (face) Triple(224, 172, 138) else Triple(60, 70, 90)
            frame[i] = r.toByte(); frame[i + 1] = g.toByte(); frame[i + 2] = b.toByte(); frame[i + 3] = -1
        }
        val s = assertNotNull(SubjectFinder.find(listOf(frame, frame, frame), w, h))
        assertTrue(s.top in 0.25f..0.38f && s.bottom in 0.5f..0.66f, "$s")
        assertTrue(s.left in 0.25f..0.38f && s.right in 0.62f..0.75f, "$s")
    }

    @Test
    fun feedbackChangesTheNextEdit() {
        val c = Collaborator()
        val a = c.keywords("پرانرژی‌ترش کن و متن کمتر، بدون موسیقی")
        assertEquals(1f, a.energy)
        assertEquals(-1f, a.density)
        assertEquals(false, a.music)
        val t = Taste().adjust(a)
        assertTrue(t.energy > 0f && t.density < 1f && !t.music)
        val liked = Taste().learn(Choices(1, "paper", null, listOf("slam", "slam"), listOf("whip")), liked = true)
        assertTrue(liked.look("paper") > 1f && liked.recipe("slam") > 1f && liked.recipe("slam") < 1.2f, "one reaction moves weights a little")
    }
}

class ProofreaderTest {
    @Test
    fun briefSpellingsWinOverSoundAlikes() {
        val brief = "نصب رو با ترمینال نشون بده. Claude Code و Codex. کامنت با کلمهٔ «کد». صرفه‌جویی در توکن"
        val fixes = Proofreader.fromBrief(listOf("نسب", "Cloud", "کود", "سرف", "که", "Code،", "توکن"), brief)
        assertEquals("نصب", fixes[0])
        assertEquals("Claude", fixes[1])
        assertEquals("کد", fixes[2])
        assertEquals("صرفه", fixes[3])
        assertTrue(4 !in fixes && 5 !in fixes && 6 !in fixes, "$fixes")
    }

    @Test
    fun lineRewritesAlignWithoutRewritingSpeech() {
        val heard = listOf("بعد", "از", "نسب", "یه", "مپ", "کامل", "تیه", "میکنه")
        val fixes = Proofreader.align(heard, 10, "بعد از نصب یه مپ کامل تهیه می‌کنه")
        assertEquals(mapOf(12 to "نصب", 16 to "تهیه", 17 to "می‌کنه"), fixes)
        assertTrue(Proofreader.align(listOf("بکنی."), 0, "کنی.").isEmpty(), "a first letter is never dropped")
        assertTrue(Proofreader.align(heard, 0, "after installing it maps the whole project").isEmpty(), "translations are not spelling")
    }
}
