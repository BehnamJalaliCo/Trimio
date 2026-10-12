package io.trimio.engine.autopilot

import io.trimio.core.model.transcript.Transcript
import io.trimio.engine.llm.ChatMessage
import io.trimio.engine.llm.ChatRole
import io.trimio.engine.llm.GenerationRequest
import io.trimio.engine.llm.LanguageModel
import io.trimio.engine.llm.generateStructured
import kotlin.time.Duration
import kotlin.time.TimeSource
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
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
    ): Understanding {
        val global = model.generateStructured(
            GenerationRequest(
                system = SYSTEM, messages = listOf(ChatMessage(ChatRole.User, userMessage(transcript, prompt, lines))),
                schema = globalSchema, maxTokens = 900, temperature = temperature, seed = seed,
            ),
            Understanding.serializer(), onText = onText,
        )
        val g = sanitize(global, transcript.words.size, lines.size)
        // Lines in small groups: one reply reads GROUP lines (each about itself), so a long video costs
        // a third of the calls. Past [budget] the rest are read by the rules, so a slow device ends.
        val clock = TimeSource.Monotonic.markNow()
        val rules by lazy { RulesUnderstander.understand(transcript, prompt, lines).lines }
        val reads = mutableListOf<LineRead>()
        for (group in lines.indices.chunked(GROUP)) {
            if (budget != null && clock.elapsedNow() > budget) {
                group.forEach { k -> reads += rules.getOrNull(k) ?: LineRead(show = "none") }
            } else {
                val got = runCatching {
                    model.generateStructured(
                        GenerationRequest(
                            system = LINES_SYSTEM, messages = listOf(ChatMessage(ChatRole.User, linesMessage(transcript, prompt, lines, group, g))),
                            schema = linesSchema(group.size), maxTokens = LINE_TOKENS * group.size, temperature = temperature, seed = seed + group.first(),
                        ),
                        LineReads.serializer(), onText = onText,
                    ).reads
                }.getOrDefault(emptyList())
                group.forEachIndexed { i, k -> reads += got.getOrNull(i) ?: rules.getOrNull(k) ?: LineRead(show = "none") }
            }
            onLine(reads.size, lines.size)
        }
        return sanitize(g.copy(lines = reads), transcript.words.size, lines.size)
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
            Everything shown on screen (title, items) is in the transcript's language; only brand, app, product and site names keep their own spelling (the brief's spelling when it gives one). Pick the show that pictures what THIS line says; none when nothing fits.
            title: 1-4 punchy words for the screen in the transcript's language, from the line's meaning, correctly spelled; never the whole line.
            visual: English picture query or "". energy: 0 calm to 1 explosive.
        """.trimIndent()

        /** [LINE_SYSTEM] for a few numbered lines at once: one read per line, in order. */
        val LINES_SYSTEM = LINE_SYSTEM.replaceFirst(
            "You are a motion designer deciding how to picture ONE line of a talking video. Answer about that LINE only, never about other lines.",
            "You are a motion designer deciding how to picture the numbered LINES of a talking video. Answer with one read per LINE, in the same order; " +
                "each read is about its own LINE only, never about the others. In each read, \"the LINE\" means that read's line.",
        )

        fun linesMessage(transcript: Transcript, prompt: String, lines: List<Lines.Line>, group: List<Int>, g: Understanding): String = buildString {
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
            }
        }

        fun linesSchema(n: Int): JsonObject = buildJsonObject {
            put("type", "object")
            put("properties", buildJsonObject {
                put("reads", buildJsonObject { put("type", "array"); put("items", lineSchema); put("minItems", n); put("maxItems", n) })
            })
        }

        /** Lines per call, and the reply budget per line. */
        private const val GROUP = 3
        private const val LINE_TOKENS = 320

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

/** A group of line reads, in the order of the lines asked about. */
@Serializable
data class LineReads(val reads: List<LineRead> = emptyList())
