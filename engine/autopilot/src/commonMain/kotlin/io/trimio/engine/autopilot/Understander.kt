package io.trimio.engine.autopilot

import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.llm.ChatMessage
import io.trimio.engine.llm.ChatRole
import io.trimio.engine.llm.GenerationRequest
import io.trimio.engine.llm.LanguageModel
import io.trimio.engine.llm.generateStructured
import io.trimio.engine.motion.score.ScoreGrammar
import io.trimio.engine.motion.score.StoryMap
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put

/**
 * Reads the transcript and the brief with a language model. Small on-device models answer through
 * a grammar compiled from [schema], so the reply is always a valid [Understanding]; [sanitize]
 * then drops anything that points outside the transcript.
 */
class Understander(private val model: LanguageModel) {

    /**
     * Two kinds of question, each small enough for a 4B model to answer well: one about the whole
     * piece (brief, names, hook, call to action), then a few lines at a time, each read about its line alone, with its
     * neighbours for context. A small model asked about every line at once loses its place.
     */
    suspend fun understand(
        transcript: Transcript,
        prompt: String,
        lines: List<Lines.Line> = Lines.split(transcript.words),
        seed: Int = 0,
        temperature: Float = 0.3f,
        onLine: (done: Int, total: Int) -> Unit = { _, _ -> },
        budget: Duration? = null,
        onText: (String) -> Unit = {},
        /** What an earlier attempt already read (the app was killed mid-way): those calls are not made again. */
        draft: UnderstandingDraft? = null,
        /** The reading so far, after the whole-piece call and after every group, for the checkpoint. */
        onDraft: (UnderstandingDraft) -> Unit = {},
    ): Understanding {
        val g = draft?.global?.takeIf { draft.lines == lines.size } ?: sanitize(
            model.generateStructured(
                GenerationRequest(
                    system = SYSTEM, messages = listOf(ChatMessage(ChatRole.User, userMessage(transcript, prompt, lines))),
                    // A cloud model thinks before it answers, and its thinking counts against the budget.
                    schema = globalSchema, maxTokens = if (model.isLocal) GLOBAL_TOKENS else CLOUD_GLOBAL_TOKENS, temperature = temperature, seed = seed,
                ),
                Understanding.serializer(), onText = onText,
            ),
            transcript.words.size, lines.size,
        ).also { onDraft(UnderstandingDraft(it, lines.size)) }
        // Lines in small groups: one reply reads GROUP lines (each about itself), so a long video costs
        // a third of the calls. Past [budget] the rest are read by the rules, so a slow device ends.
        val clock = TimeSource.Monotonic.markNow()
        // How an editor showed lines like these: each line is asked about with its nearest examples ([DesignBook]).
        val book = DesignBook.load()
        val rules by lazy { RulesUnderstander.understand(transcript, prompt, lines).lines }
        val reads = draft?.takeIf { it.global == g }?.reads.orEmpty().filterKeys { it in lines.indices }.toMutableMap()
        // On a slow device the budget runs out after a few groups: the model reads the lines that become
        // graphics first (figures, names, the ask, what the rules would show), the rules read the plain rest.
        val groups = lines.indices.chunked(GROUP).let { all -> if (budget == null) all else all.sortedByDescending { g -> g.sumOf { weight(it, transcript, lines, rules) } } }
        for (group in groups) {
            if (group.all { it in reads }) continue
            if (budget != null && clock.elapsedNow() > budget) {
                group.forEach { k -> reads[k] = rules.getOrNull(k) ?: LineRead(show = "none") }
            } else {
                val got = runCatching {
                    model.generateStructured(
                        GenerationRequest(
                            system = LINES_SYSTEM, messages = listOf(ChatMessage(ChatRole.User, linesMessage(transcript, prompt, lines, group, g, book))),
                            schema = linesSchema(group.size), maxTokens = (if (model.isLocal) LINE_TOKENS else CLOUD_LINE_TOKENS) * group.size, temperature = temperature, seed = seed + group.first(),
                        ),
                        LineReads.serializer(), onText = onText,
                    ).reads
                }.getOrDefault(emptyList())
                group.forEachIndexed { i, k -> reads[k] = got.getOrNull(i) ?: rules.getOrNull(k) ?: LineRead(show = "none") }
                onDraft(UnderstandingDraft(g, lines.size, reads.toMap()))
            }
            onLine(reads.size, lines.size)
        }
        return sanitize(g.copy(lines = lines.indices.map { reads[it] ?: LineRead(show = "none") }), transcript.words.size, lines.size)
    }

    /**
     * The narrative map behind the edit ([StoryMap]): the beats the story runs through — setup, turn,
     * payoff and callback — each with the claim it makes and the reason it earns its place, the
     * objects carried across them and what no beat may show. Written before any picture is chosen,
     * against [ScoreGrammar.storyJsonSchema]; the story path plans from it ([DesignPass],
     * [StoryPlanner]) and the rule planner plans without it.
     */
    suspend fun story(
        words: List<Word>,
        prompt: String,
        seed: Int = 0,
        temperature: Float = 0.3f,
    ): StoryMap = StoryMap.sanitize(
        model.generateStructured(
            GenerationRequest(
                system = STORY_SYSTEM,
                messages = listOf(ChatMessage(ChatRole.User, storyMessage(words, prompt))),
                schema = storySchema,
                maxTokens = if (model.isLocal) STORY_TOKENS else CLOUD_STORY_TOKENS,
                temperature = temperature,
                seed = seed,
            ),
            StoryMap.serializer(),
        ),
        words.size,
    )

    /** How much line [k] needs the model: a figure, a name, the ask, or a graphic the rules would show. */
    private fun weight(k: Int, transcript: Transcript, lines: List<Lines.Line>, rules: List<LineRead>): Int {
        val texts = transcript.words.subList(lines[k].first, lines[k].last + 1).map { it.text }
        val figure = if (io.trimio.engine.motion.score.NumberWords.findAll(texts).any { it.value >= 2 }) 2 else 0
        val name = if (texts.any { w -> w.any { it in 'A'..'Z' || it in 'a'..'z' } }) 1 else 0
        val shown = if (rules.getOrNull(k)?.show !in setOf(null, "none", "headline")) 2 else 0
        val ask = if (k == lines.lastIndex || k == lines.lastIndex - 1) 2 else 0
        return figure + name + shown + ask
    }

    companion object {
        val SYSTEM = """
            You are the story editor of a studio that turns talking videos into premium motion-graphics edits for any field: medicine, cars, cooking, finance, software, fitness, real estate, education and more.
            You get the creator's brief, the transcript as numbered words (index:word) and the same transcript cut into numbered lines. Never invent facts.

            Fields:
            - title: 2-6 words naming the piece, in the transcript's language. topic: 2-5 English words. domain, mood: the closest.
            - brief: what the brief asks for. look: noir (dark, bold), paper (light, editorial), lumen (cool, calm, luxury) or auto. energy 0-1, or -1 when not said. pace: fast, normal, calm or auto. captions: word (word by word), phrase, none or auto. music: uplifting, energetic, chill, cinematic, corporate, tense, or auto; none only when the brief says no music. payoffFirst: true unless the brief wants a linear story.
            - fixes: leave empty.
            - entities: names and concrete things said: brands, apps, products, people, places, organisations, objects. at/until are word indices. name is the canonical spelling. visual is an English noun phrase for a picture of it ("electric car", "stethoscope", "pizza"), or "" when it is abstract.
            - lines: leave empty.
            - hook: the line number holding the payoff the piece promises (the result, the number, the surprise), even if said late; title: 2-5 words for the first second.
            - cta: the call to action at the end: action, and the keyword viewers should send, correctly spelled ("" if none).
        """.trimIndent()

        val LINE_SYSTEM = """
            You are a motion designer deciding how to picture ONE line of a talking video. Answer about that LINE only, never about other lines.
            fixed: the LINE copied word for word with spelling mistakes of the speech recogniser corrected (sound-alike Persian letters, half-spaces, brand names); same words, same order, nothing added.
            gist: what the LINE says, 3-8 English words.
            role: what the line does in the story.
            show: headline = a few strong words; counter = a number or percentage said in the line; logos = brand, app or tool names said in the line (items = the names, official spelling); terminal = installing or running software (items = [a short shell command, its result]); network = a map, overview, structure or connections (items = 5-9 short node labels in the transcript's language); meter = a saving, reduction or before/after with a percentage (items = [before label, after label], in the transcript's language); chart = a trend; object = one concrete thing (visual); objects = several concrete things said together (items in the transcript's language, visual = one English query per item separated by commas); list = steps or a checklist (items, in the transcript's language); comment = asking viewers to comment a keyword; lower-third = the speaker introducing themself; stamp = a verdict; countdown = time left until something ends (48 hours left); stats = two or three numbers said together (views, comments, likes, followers); progress = a quota or capacity filling up (the first 1000 people, sold out, 80% full); voucher = an amount of money or crypto viewers get (items = [the currency's official name, e.g. Tether]); message = someone else's words being told (a DM, a comment, a question someone asked) or the speaker's reply to them (role dialogue); none = nothing, keep the speaker clean.
            Follow the brief when it says how to show this kind of line.
            Under a line you may see how an editor showed lines like it: follow the closest example when it fits THIS line (its show, whether a figure, a price, a list or someone's words; a title in its spirit made of THIS line's words), and pick another show when the line says something else.
            Everything shown on screen (title, items) is in the transcript's language; only brand, app, product and site names keep their own spelling (the brief's spelling when it gives one). Pick the show that pictures what THIS line says; none when nothing fits.
            title: a 2-5 word headline for the screen in the transcript's language, correctly written (not slang): the LINE's claim or figure in the speaker's own words or their written forms, a number in digits; no word the speaker did not say, never the whole line.
            visual: English picture query or "". energy: 0 calm to 1 explosive.
        """.trimIndent()

        /** [LINE_SYSTEM] for a few numbered lines at once: one read per line, in order. */
        val LINES_SYSTEM = LINE_SYSTEM.replaceFirst(
            "You are a motion designer deciding how to picture ONE line of a talking video. Answer about that LINE only, never about other lines.",
            "You are a motion designer deciding how to picture the numbered LINES of a talking video. Answer with one read per LINE, in the same order; " +
                "each read is about its own LINE only, never about the others. In each read, \"the LINE\" means that read's line.",
        )

        /**
         * The story map's own question: the narrative beats behind the edit, written before any
         * picture is chosen — what the story does and why each doing earns its place.
         */
        val STORY_SYSTEM = """
            You are the story editor of a studio that turns talking videos into premium motion-graphics edits.
            You get the creator's brief and the transcript as numbered words (index:word). Never invent facts.

            Write the narrative map behind the edit — the beats the story runs through, decided before any picture:
            - beats: two to four, in the order the story runs: setup (what is at stake), turn (the surprise or the verdict), payoff (the promise, paid), callback (the echo of the setup).
            - Each beat: from and until are word indices in the transcript (until inclusive); claim is 2-6 words of what the beat claims, in the transcript's own language; rationale is why the piece needs this beat here; energy is 0 calm to 1 explosive.
            - objects: what is carried from beat to beat ("the voucher", "the channel card"): one thread, not a pile of stickers.
            - banned: what no beat may show ("no countdown", "no stock smiles"): your own restraint.
        """.trimIndent()

        fun linesMessage(
            transcript: Transcript,
            prompt: String,
            lines: List<Lines.Line>,
            group: List<Int>,
            g: Understanding,
            book: List<DesignBook.Moment> = emptyList(),
        ): String = buildString {
            val words = transcript.words
            append("Brief: ").append(prompt.trim().take(BRIEF_CHARS).ifEmpty { "(none)" }).append('\n')
            append("Video: ").append(g.title).append(" (").append(g.topic).append(", ").append(g.domain).append(")\n")
            lines.getOrNull(group.first() - 1)?.let { append("Previous line: ").append(words.textOf(it.range)).append('\n') }
            append("\nLINES (").append(group.size).append("):\n")
            group.forEachIndexed { i, k ->
                append(i + 1).append(": ").append(words.textOf(lines[k].range)).append('\n')
                val names = g.entities.filter { it.at in lines[k].range }.map { it.name }
                if (names.isNotEmpty()) append("   names in this line: ").append(names.joinToString(", ")).append('\n')
                if (k == lines.lastIndex && g.cta != null) append("   this is the last line; the call to action keyword is: ").append(g.cta.keyword).append('\n')
                val said = words.subList(lines[k].first, lines[k].last + 1).map { it.text }
                DesignBook.recall(said, book, EXAMPLES, latin = said.none { w -> w.any { it in '؀'..'ۿ' } }).takeIf { it.isNotEmpty() }
                    ?.let { append(DesignBook.examples(it)).append('\n') }
            }
        }

        fun linesSchema(n: Int): JsonObject = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("reads", buildJsonObject { put("type", "array"); put("items", lineSchema); put("minItems", n); put("maxItems", n) })
            })
        }

        /** The story map's question: the brief and the transcript, in so many numbered words. */
        fun storyMessage(words: List<Word>, prompt: String): String = buildString {
            append("Brief:\n").append(prompt.trim().ifEmpty { "(none: decide everything)" }).append("\n\n")
            append("Words:\n")
            words.forEachIndexed { i, w -> append(i).append(':').append(w.text).append(' ') }
        }

        /** [ScoreGrammar.storyJsonSchema] as the structured-output schema the model answers against. */
        val storySchema: JsonObject = Json.parseToJsonElement(ScoreGrammar.storyJsonSchema).jsonObject

        /** Lines per call, and the reply budget per line. */
        private const val GROUP = 3
        private const val LINE_TOKENS = 320
        private const val GLOBAL_TOKENS = 900
        private const val STORY_TOKENS = 420
        private const val CLOUD_LINE_TOKENS = 2_400
        private const val CLOUD_GLOBAL_TOKENS = 8_000
        private const val CLOUD_STORY_TOKENS = 2_400

        fun userMessage(transcript: Transcript, prompt: String, lines: List<Lines.Line>): String = buildString {
            append("Brief:\n").append(prompt.trim().ifEmpty { "(none: decide everything)" }).append("\n\n")
            append("Words:\n")
            transcript.words.forEachIndexed { i, w -> append(i).append(':').append(w.text).append(' ') }
            append("\n\nLines (").append(lines.size).append("):\n")
            lines.forEachIndexed { k, l -> append(k).append(": ").append(transcript.words.textOf(l.range)).append('\n') }
        }

        private fun str(max: Int) = buildJsonObject { put("type", "string"); put("maxLength", max) }
        private fun enum(values: List<String>) = buildJsonObject { put("type", "string"); put("enum", JsonArray(values.map(::JsonPrimitive))) }
        private val int = buildJsonObject { put("type", "integer") }
        private val num = buildJsonObject { put("type", "number") }
        private fun obj(vararg props: Pair<String, JsonObject>) = buildJsonObject {
            put("type", "object")
            put("properties", JsonObject(props.toMap()))
        }
        private fun array(item: JsonObject, max: Int) = buildJsonObject {
            put("type", "array"); put("items", item); put("maxItems", max)
        }

        val globalSchema: JsonObject = obj(
            "title" to str(48),
            "topic" to str(40),
            "domain" to enum(Understanding.DOMAINS),
            "mood" to enum(Understanding.MOODS),
            "brief" to obj(
                "look" to enum(listOf("noir", "paper", "lumen", "auto")),
                "energy" to num,
                "pace" to enum(listOf("fast", "normal", "calm", "auto")),
                "captions" to enum(listOf("word", "phrase", "none", "auto")),
                "music" to enum(listOf("uplifting", "energetic", "chill", "cinematic", "corporate", "tense", "none", "auto")),
                "payoffFirst" to buildJsonObject { put("type", "boolean") },
            ),
            "entities" to array(obj("at" to int, "until" to int, "name" to str(32), "kind" to enum(Understanding.KINDS), "visual" to str(32)), 10),
            "hook" to obj("line" to int, "title" to str(40)),
            "cta" to obj("action" to enum(listOf("comment", "follow", "save", "share", "link", "dm", "none")), "keyword" to str(20)),
        )

        val lineSchema: JsonObject = obj(
            "fixed" to str(160), "gist" to str(48), "role" to enum(Understanding.ROLES), "show" to enum(Understanding.SHOWS), "title" to str(32),
            "visual" to str(64), "items" to array(str(24), 9), "energy" to num,
        )

        private const val BRIEF_CHARS = 700

        /** Examples shown under each line. */
        private const val EXAMPLES = 2

        /** Keeps only what points inside a transcript of [n] words and [lineCount] lines. */
        fun sanitize(u: Understanding, n: Int, lineCount: Int): Understanding {
            fun ok(i: Int) = i in 0 until n
            fun end(at: Int, until: Int) = if (until in at until n) until else at
            return u.copy(
                domain = u.domain.takeIf { it in Understanding.DOMAINS } ?: "general",
                mood = u.mood.takeIf { it in Understanding.MOODS } ?: "confident",
                fixes = u.fixes.filter { ok(it.at) && it.text.isNotBlank() && ' ' !in it.text.trim() }.map { it.copy(text = it.text.trim()) },
                entities = u.entities.filter { ok(it.at) && it.name.isNotBlank() }.map { it.copy(until = end(it.at, it.until), name = it.name.trim()) },
                lines = (0 until lineCount).map { k ->
                    val l = u.lines.getOrNull(k) ?: LineRead(show = "none")
                    l.copy(
                        show = l.show.takeIf { it in Understanding.SHOWS } ?: "headline",
                        energy = l.energy.coerceIn(0f, 1f), title = l.title.trim(), visual = l.visual.trim(),
                        items = l.items.map { it.trim() }.filter { it.isNotEmpty() },
                    )
                },
                hook = u.hook?.takeIf { it.line in 0 until lineCount },
                cta = u.cta?.takeIf { it.action != "none" },
            )
        }
    }
}

/**
 * A reading in progress, kept in the run's checkpoint: the whole-piece answer ([global]) for a transcript cut into
 * [lines] lines, and the lines read so far by index. A run the system killed mid-way starts again from here.
 */
@Serializable
data class UnderstandingDraft(val global: Understanding, val lines: Int, val reads: Map<Int, LineRead> = emptyMap())

/** A group of line reads, in the order of the lines asked about. */
@Serializable
data class LineReads(val reads: List<LineRead> = emptyList())
