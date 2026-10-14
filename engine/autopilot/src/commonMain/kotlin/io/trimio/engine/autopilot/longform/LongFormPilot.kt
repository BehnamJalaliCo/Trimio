package io.trimio.engine.autopilot.longform

import io.trimio.core.model.audio.PcmAudio
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.audio.LoudnessMeter
import io.trimio.engine.autopilot.Autopilot
import io.trimio.engine.autopilot.Choices
import io.trimio.engine.autopilot.Glossary
import io.trimio.engine.autopilot.Lines
import io.trimio.engine.autopilot.OnScreenSpelling
import io.trimio.engine.autopilot.PersianSpelling
import io.trimio.engine.autopilot.Planner
import io.trimio.engine.autopilot.Proofreader
import io.trimio.engine.autopilot.Soundtrack
import io.trimio.engine.autopilot.SpeechAnalysis
import io.trimio.engine.autopilot.Understanding
import io.trimio.engine.autopilot.withSpelling
import io.trimio.engine.llm.ChatMessage
import io.trimio.engine.llm.ChatRole
import io.trimio.engine.llm.GenerationRequest
import io.trimio.engine.llm.LanguageModel
import io.trimio.engine.llm.generateStructured
import io.trimio.engine.motion.TextLayoutEngine
import io.trimio.engine.motion.recipe.BrandLibrary
import io.trimio.engine.motion.score.BeatScore
import io.trimio.engine.motion.score.CaptionScore
import io.trimio.engine.motion.score.Compiler
import io.trimio.engine.motion.score.EditPlan
import io.trimio.engine.motion.score.SceneScore
import io.trimio.engine.motion.score.Score
import io.trimio.engine.motion.score.Subject
import io.trimio.engine.motion.visual.VisualLibrary
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** How much a long video is edited: subtitles only, or subtitles with YouTube's devices. */
enum class LongFormKind { Subtitles, Pro }

/** What a long video ships with besides itself: the chapters, the subtitle files, titles, thumbnail lines, Shorts. */
@Serializable
data class YouTubePackage(
    val chapters: List<Chapter> = emptyList(),
    /** The chapters as the description takes them (empty when the video gets none). */
    val description: String = "",
    val srt: String = "",
    val vtt: String = "",
    val titles: List<String> = emptyList(),
    val thumbnail: List<String> = emptyList(),
    val shorts: List<ShortClip> = emptyList(),
)

/** A stretch of the long video that stands alone as a Short (seconds on the source's clock). */
@Serializable
data class ShortClip(val from: Float, val to: Float, val title: String)

/**
 * The long-form edit (phase 9.7, docs/research/YOUTUBE_LONGFORM.md): the video plays as recorded — no cuts, no
 * silence removed, no cold open — with accurate subtitles; [LongFormKind.Pro] adds the channel's watermark, one
 * subscribe ask after the first real point, a card at every chapter and a slow push-in now and then. Built for an
 * hour of speech: the director only titles the chapters and the video (a few short calls), never reads it line by line,
 * and the file keeps the recording's own sound.
 */
class LongFormPilot(
    private val text: TextLayoutEngine,
    private val brands: BrandLibrary,
    private val visuals: VisualLibrary,
    private val model: LanguageModel? = null,
) {
    data class Request(
        val transcript: Transcript,
        val voice: PcmAudio,
        val prompt: String,
        val seed: Long,
        val kind: LongFormKind,
        /** "16:9" for a landscape source (the usual), else the source's own frame. */
        val format: String = "16:9",
        val footage: String = "main",
        val fps: Int = 30,
        val subject: Subject? = null,
        /** The channel's logo (a media source id and its width ÷ height), for the watermark. */
        val logo: Pair<String, Float>? = null,
        /** Who speaks when (two voices or more): each voice's subtitles get their colour. */
        val voices: List<io.trimio.engine.motion.score.Voice> = emptyList(),
    )

    suspend fun produce(request: Request, onStage: (String, Float) -> Unit = { _, _ -> }): Autopilot.Production {
        val report = mutableListOf<String>()
        onStage("listen", 0f)
        val heard = SpeechAnalysis.of(request.voice, request.transcript, tightenCuts = false)
        val lexicon = PersianSpelling.load()
        val texts = heard.transcript.words.map { it.text }
        val spelled = Proofreader.fromBrief(texts, request.prompt, lexicon)
        val lexical = Proofreader.fromLexicon(texts, request.prompt, spelled)
        (spelled + lexical).forEach { (i, w) -> report += "spelling: ${texts[i]} → ${w.ifEmpty { "(joined)" }}" }
        val names = Glossary.read(request.prompt).terms.keys + request.prompt.split(' ', '\n', '،', ',')
        val checked = OnScreenSpelling.check(heard.transcript.withSpelling(spelled + lexical), lexicon, names, model) { report += it }
        val words = checked.words.words
        val duration = heard.duration
        report += "listen: ${words.size} words, ${round1(duration)}s (long-form: no cuts)"

        onStage("chapters", CHAPTERS_AT)
        val lines = Lines.split(words, pauses = heard.pauses)
        val chapters = chapters(words, lines, duration, report) { done, total -> onStage("chapters $done/$total", CHAPTERS_AT + CHAPTERS_SPAN * done / total.coerceAtLeast(1)) }
        // The director's titles and thumbnail lines; without one (or when it fails), the chapter titles.
        val written = headlines(words, chapters, report)
        val fallback = chapters.map { it.title }.take(HEADLINES)
        val titles = written?.titles ?: fallback
        val thumbnail = written?.thumbnail ?: fallback.map { it.split(' ').take(THUMB_WORDS).joinToString(" ") }
        val shorts = ShortPicker.pick(words, lines, chapters)

        onStage("compile", COMPILE_AT)
        val score = score(request, words, chapters, duration, written?.titles?.firstOrNull())
        val edit = EditPlan.identity(duration)
        val compiled = Compiler(text, brands, visuals).compile(
            Compiler.Input(score, checked.shown, duration = duration, footage = request.footage, fps = request.fps, edit = edit, subject = request.subject, voices = request.voices),
        )
        onStage("sound", SOUND_AT)
        // The file carries the recording's own sound (copied at export); the voice stands in for it in the editor's
        // preview. Nothing is mastered: an hour of sound mixed in memory would not fit a phone.
        val measured = LoudnessMeter.measure(request.voice)
        val sound = Soundtrack.Result(request.voice, measured.integratedLufs, measured.samplePeakDb)
        report += "sound: as recorded, ${round1(sound.lufs.toFloat())} LUFS"
        val cues = SubtitleFiles.cues(checked.shown.words)
        val pack = YouTubePackage(chapters, YouTubeChapters.description(chapters), SubtitleFiles.srt(cues), SubtitleFiles.vtt(cues), titles, thumbnail, shorts)
        report += "youtube: ${chapters.size} chapters, ${cues.size} subtitles, ${shorts.size} shorts"
        onStage("done", 1f)
        val understanding = Understanding(title = titles.firstOrNull() ?: chapters.firstOrNull()?.title.orEmpty(), topic = "youtube")
        val plan = Planner.Plan(score, null, Choices(request.seed, score.look, null, score.scenes.flatMap { s -> s.beats.map { it.recipe } }.distinct(), emptyList()), emptyList())
        return Autopilot.Production(compiled, score, plan, understanding, sound, edit, report, youtube = pack)
    }

    /** The chapters, titled by the director (one short call each) or, without one, by their opening words. */
    private suspend fun chapters(words: List<Word>, lines: List<Lines.Line>, duration: Float, report: MutableList<String>, onDone: (Int, Int) -> Unit): List<Chapter> {
        val starts = YouTubeChapters.starts(words, lines, duration)
        return starts.mapIndexed { i, first ->
            val end = starts.getOrNull(i + 1) ?: words.size
            val span = words.subList(first, end)
            val named = model?.let { m -> runCatching { title(m, span) }.onFailure { report += "chapters: title failed (${it.message})" }.getOrNull() }
                ?.takeIf { it.isNotBlank() }
            onDone(i + 1, starts.size)
            Chapter(words[first].range.startMs / MS, named ?: YouTubeChapters.rulesTitle(span), named = named != null)
        }.also { c -> c.forEach { report += "chapter ${YouTubeChapters.stamp(it.at)} ${it.title}" } }
    }

    private suspend fun title(m: LanguageModel, span: List<Word>): String = m.generateStructured(
        GenerationRequest(
            system = TITLE_SYSTEM, messages = listOf(ChatMessage(ChatRole.User, span.joinToString(" ") { it.text }.take(TITLE_CHARS))),
            schema = TITLE_SCHEMA, maxTokens = TITLE_TOKENS, temperature = 0.2f,
        ),
        Titled.serializer(),
    ).title.let(::clean).take(TITLE_MAX)

    /** A model's line without the stray marks a grammar lets through around it (a leading comma, quotes, a full stop). */
    private fun clean(line: String): String = line.trim().trim(',', '،', '.', '"', '«', '»', '\'', '-', ':', ' ').trim()

    /** Three titles for the video and three thumbnail lines from the chapters and the opening, by the director (null: none, or it failed). */
    private suspend fun headlines(words: List<Word>, chapters: List<Chapter>, report: MutableList<String>): Headlines? {
        val m = model ?: return null
        return runCatching {
            val opening = words.joinToString(" ") { it.text }.take(OPENING_CHARS)
            val brief = "Chapters: " + chapters.joinToString(" | ") { it.title } + "\nOpening: " + opening
            val got = m.generateStructured(
                GenerationRequest(system = HEADLINE_SYSTEM, messages = listOf(ChatMessage(ChatRole.User, brief)), schema = HEADLINE_SCHEMA, maxTokens = HEADLINE_TOKENS, temperature = 0.4f),
                Headlines.serializer(),
            )
            Headlines(got.titles.map(::clean).filter(String::isNotEmpty).take(HEADLINES), got.thumbnail.map(::clean).filter(String::isNotEmpty).take(HEADLINES))
        }.onFailure { report += "headlines: failed (${it.message}); the chapter titles instead" }
            .getOrNull()?.takeIf { it.titles.isNotEmpty() }.also { if (it == null) report += "headlines: none from the director; the chapter titles instead" }
    }

    /**
     * The score: subtitles always; for [LongFormKind.Pro] the watermark, the video's [title] as it opens (when the
     * director wrote one), a subscribe ask, chapter cards, the numbers and brands said, and slow push-ins.
     */
    private fun score(request: Request, words: List<Word>, chapters: List<Chapter>, duration: Float, title: String?): Score {
        val captions = CaptionScore(show = true, recipe = "subtitles", place = "lower", maxWords = SubtitleFiles.MAX_WORDS)
        if (request.kind == LongFormKind.Subtitles) {
            return Score(format = request.format, look = LOOK, captions = captions, scenes = listOf(SceneScore(from = 0, bg = "media", camera = "still")), auto = false)
        }
        val rtl = words.count { w -> w.text.any { it in '؀'..'ۿ' } } * 2 > words.size
        val endScreen = (duration - END_SCREEN).coerceAtLeast(0f)
        val beats = buildList {
            request.logo?.let { (media, aspect) ->
                if (endScreen > WATERMARK_FROM + MIN_WATERMARK) add(BeatScore("watermark", time = WATERMARK_FROM, hold = endScreen - WATERMARK_FROM, place = "top", media = media, aspect = aspect))
            }
            // A card only for a title the director wrote: opening words read as a broken sentence on screen.
            val cards = chapters.drop(1).mapIndexedNotNull { i, c -> (i to c).takeIf { c.named && c.at + CHAPTER_HOLD < endScreen } }
            cards.forEach { (i, c) -> add(BeatScore("chapter", time = c.at + CHAPTER_DELAY, hold = CHAPTER_HOLD, place = "top", text = c.title, label = chapterLabel(i + 2, rtl))) }
            subscribeAt(words, duration, cards.map { it.second.at })?.let { add(BeatScore("subscribe", time = it, hold = SUBSCRIBE_HOLD, place = "top")) }
            title?.takeIf { duration > MIN_SUBSCRIBE_VIDEO }?.let { add(BeatScore("lower-third", time = TITLE_AT, hold = TITLE_HOLD, place = "top", text = it)) }
            val busy = filter { it.recipe != "watermark" }.mapNotNull { b -> b.time?.let { it..(it + (b.hold ?: 0f)) } }
            addAll(SpokenFacts.beats(words, brands, busy, endScreen))
        }
        // A slow push-in at every chapter and every half minute or so in between; the frame rests otherwise.
        val pushes = pushStarts(words, chapters, endScreen)
        val scenes = listOf(SceneScore(from = 0, bg = "media", camera = "still", beats = beats)) +
            pushes.flatMap { (start, end) ->
                listOf(SceneScore(from = start, bg = "media", camera = "push-in", transition = "cut"), SceneScore(from = end, bg = "media", camera = "still", transition = "cut"))
            }
        return Score(format = request.format, look = LOOK, captions = captions, scenes = scenes.sortedBy { it.from ?: 0 }.distinctBy { it.from }, auto = false)
    }

    /**
     * The subscribe ask lands on a sentence start after the first minute (the first value given), never in the last
     * minutes and never while a chapter card (starting at [cards]) is up: both sit at the top of the frame.
     */
    private fun subscribeAt(words: List<Word>, duration: Float, cards: List<Float>): Float? {
        if (duration < MIN_SUBSCRIBE_VIDEO) return null
        val after = minOf(SUBSCRIBE_AFTER, duration * SUBSCRIBE_SHARE)
        val clear = { t: Float -> cards.none { c -> t < c + CHAPTER_DELAY + CHAPTER_HOLD + GAP && t + SUBSCRIBE_HOLD + GAP > c } }
        // A sentence start, or (a speaker who rarely ends one) the word after a breath.
        val k = (1 until words.size).firstOrNull { i ->
            val t = words[i].range.startMs / MS
            t >= after && clear(t) && (words[i - 1].endsSentence || t - words[i - 1].range.endMs / MS >= BREATH)
        } ?: return null
        return (words[k].range.startMs / MS).takeIf { it + SUBSCRIBE_HOLD < duration - END_SCREEN }
    }

    /** Word ranges for the push-ins: from a sentence start, about [PUSH_LENGTH] long, one every [PUSH_EVERY]. */
    private fun pushStarts(words: List<Word>, chapters: List<Chapter>, endScreen: Float): List<Pair<Int, Int>> {
        val out = mutableListOf<Pair<Int, Int>>()
        var next = PUSH_EVERY
        for (i in 1 until words.size) {
            val t = words[i].range.startMs / MS
            val end = if (pushHere(words, i, t, next, endScreen, chapters)) sentenceAfter(words, i, t + PUSH_LENGTH) else null
            if (end != null) {
                out += i to end
                next = words[end].range.startMs / MS + PUSH_EVERY
            }
        }
        return out
    }

    /** A push-in may start at word [i] (time [t]): a sentence start, before the end screen, at a chapter or once [next] has come. */
    private fun pushHere(words: List<Word>, i: Int, t: Float, next: Float, endScreen: Float, chapters: List<Chapter>): Boolean {
        if (t > endScreen || !words[i - 1].endsSentence) return false
        return t >= next || chapters.any { kotlin.math.abs(it.at - t) < CHAPTER_DELAY }
    }

    /** The first sentence start after word [i] at [from] seconds or later. */
    private fun sentenceAfter(words: List<Word>, i: Int, from: Float): Int? =
        (i + 1 until words.size).firstOrNull { j -> words[j].range.startMs / MS >= from && words[j - 1].endsSentence }

    private fun chapterLabel(n: Int, rtl: Boolean): String =
        if (rtl) "فصل " + n.toString().map { if (it.isDigit()) '۰' + (it - '0') else it }.joinToString("") else "Chapter $n"

    @Serializable
    private data class Titled(val title: String = "")

    @Serializable
    private data class Headlines(val titles: List<String> = emptyList(), val thumbnail: List<String> = emptyList())

    private companion object {
        const val MS = 1000f
        const val LOOK = "noir"
        const val CHAPTERS_AT = 0.2f
        const val CHAPTERS_SPAN = 0.4f
        const val COMPILE_AT = 0.7f
        const val SOUND_AT = 0.9f
        const val END_SCREEN = 20f
        const val WATERMARK_FROM = 6f
        const val MIN_WATERMARK = 30f
        const val SUBSCRIBE_AFTER = 60f
        const val SUBSCRIBE_SHARE = 0.25f
        const val SUBSCRIBE_HOLD = 4f
        const val MIN_SUBSCRIBE_VIDEO = 120f
        const val CHAPTER_DELAY = 0.6f
        const val CHAPTER_HOLD = 4f
        const val GAP = 1f
        const val BREATH = 0.35f
        const val TITLE_AT = 2f
        const val TITLE_HOLD = 4.5f
        const val PUSH_EVERY = 30f
        const val PUSH_LENGTH = 8f
        const val TITLE_CHARS = 1500
        const val TITLE_TOKENS = 60
        const val TITLE_MAX = 60
        const val HEADLINES = 3
        const val THUMB_WORDS = 4
        const val OPENING_CHARS = 1200
        const val HEADLINE_TOKENS = 300

        val TITLE_SYSTEM = """
            You title one chapter of a long YouTube video from its transcript. Reply with a title of 2-5 words in the transcript's
            language, correctly spelled, naming what the chapter is about; no quotes, no numbering, no emoji.
        """.trimIndent()

        val HEADLINE_SYSTEM = """
            You write for a YouTube video, from its chapter titles and its opening. titles: three video titles in the transcript's
            language, each under 70 characters, specific and honest (no clickbait the video does not deliver). thumbnail: three
            thumbnail lines of 2-4 strong words each, in the same language.
        """.trimIndent()

        val TITLE_SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") { putJsonObject("title") { put("type", "string") } }
        }

        val HEADLINE_SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                // Three of each, never an empty list (a small model under a grammar otherwise closes the array at once).
                putJsonObject("titles") { put("type", "array"); put("minItems", HEADLINES); put("maxItems", HEADLINES); putJsonObject("items") { put("type", "string") } }
                putJsonObject("thumbnail") { put("type", "array"); put("minItems", HEADLINES); put("maxItems", HEADLINES); putJsonObject("items") { put("type", "string") } }
            }
        }

        fun round1(v: Float): Float = (v * 10).toInt() / 10f
    }
}
