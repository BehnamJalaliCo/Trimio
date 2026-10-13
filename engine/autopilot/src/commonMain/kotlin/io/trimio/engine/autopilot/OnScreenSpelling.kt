package io.trimio.engine.autopilot

import io.trimio.core.model.text.Numerals
import io.trimio.core.model.transcript.Transcript
import io.trimio.engine.llm.ChatMessage
import io.trimio.engine.llm.ChatRole
import io.trimio.engine.llm.GenerationRequest
import io.trimio.engine.llm.LanguageModel
import io.trimio.engine.llm.generateStructured
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * The last pass over the words before anything is planned or drawn: every word that will be read
 * on screen must be one ([ScreenWords]). Each one that is not goes through the fix chain —
 *
 * 1. the word list's sure repair ([ScreenWords.respelled]: «کاراموز» → «کارآموز», «مودل» → «مدل»);
 * 2. the director's proofreading when a model is on the device: one word for the misheard one, in
 *    its sentence, kept only when it is a known word itself ([byModel]);
 * 3. what is left is not shown: the captions leave the word out ([Result.shown]) — unless it is a
 *    name the speech gives, which stays as said.
 *
 * Latin digits in a Persian talk are shown as Persian digits.
 */
internal object OnScreenSpelling {

    /** The words for planning ([words], repaired) and for the screen ([shown]: what may not be shown blanked). */
    class Result(val words: Transcript, val shown: Transcript, val fixes: Map<Int, String>, val hidden: List<Int>)

    suspend fun check(
        transcript: Transcript,
        lexicon: PersianSpelling,
        names: Collection<String>,
        model: LanguageModel?,
        log: (String) -> Unit,
    ): Result {
        val texts = transcript.words.map { it.text }
        val plain = ScreenWords(lexicon)
        val spoken = ScreenWords.namesIn(texts) { w -> plain.shows(w) || plain.respelled(w) != null }
        val screen = ScreenWords(lexicon, names + spoken)
        // 1. The word list's own repairs.
        val listed = texts.indices.mapNotNull { i -> if (screen.shows(texts[i])) null else screen.respelled(texts[i], texts.getOrNull(i + 1))?.let { i to it } }.toMap()
        listed.forEach { (i, w) -> log("screen words (lexicon): ${texts[i]} → $w") }
        val first = texts.mapIndexed { i, w -> listed[i] ?: w }
        // 2. The director's proofreading of what is still unknown.
        val asked = first.indices.filter { !screen.shows(first[it]) }
        val read = if (model == null || asked.isEmpty()) emptyMap() else runCatching { byModel(model, first, asked) { screen.shows(it) } }.getOrElse { e ->
            if (e is kotlinx.coroutines.CancellationException) throw e
            log("screen words: the director could not proofread (${e.message})")
            emptyMap()
        }
        read.forEach { (i, w) -> log("screen words (director): ${first[i]} → $w") }
        // Word for word (the lines and the reading share these indices): a fix of several words waits for the lexicon's pass.
        val fixes = (listed + read).filterValues { it.isNotBlank() && ' ' !in it.trim() }
        val words = transcript.withFixes(fixes)
        // 3. What is still no word stays out of the captions.
        val persian = words.words.count { w -> ScreenWords.persian(w.text) } * 2 > words.words.size
        val hidden = words.words.indices.filter { !screen.shows(words.words[it].text) }
        hidden.forEach { log("screen words: «${words.words[it].text}» is no word: left off the captions") }
        val shown = Transcript(
            words.language,
            words.words.mapIndexed { i, w ->
                when {
                    i in hidden -> w.copy(text = "")
                    persian && w.text.any { it in '0'..'9' } && w.text.none { it in 'A'..'Z' || it in 'a'..'z' } -> w.copy(text = Numerals.toPersian(w.text))
                    else -> w
                }
            },
        )
        return Result(words, shown, fixes, hidden)
    }

    @Serializable
    private data class Fix(val id: Int = -1, val word: String = "")

    @Serializable
    private data class Fixes(val fixes: List<Fix> = emptyList())

    /**
     * The director's one-word fix for each word at [at] (indices into [words]), asked in one go
     * with each word's sentence around it; a fix is kept only when it is one word that [ok] accepts.
     */
    suspend fun byModel(model: LanguageModel, words: List<String>, at: List<Int>, ok: (String) -> Boolean): Map<Int, String> {
        val asked = at.take(MAX_ASKED)
        val items = asked.mapIndexed { k, i ->
            val from = (i - CONTEXT).coerceAtLeast(0)
            val to = (i + CONTEXT).coerceAtMost(words.lastIndex)
            val sentence = (from..to).joinToString(" ") { j -> if (j == i) "[${words[j]}]" else words[j] }
            "$k. $sentence"
        }
        val request = GenerationRequest(
            system = SYSTEM,
            messages = listOf(ChatMessage(ChatRole.User, QUESTION + "\n" + items.joinToString("\n"))),
            schema = SCHEMA, maxTokens = MAX_TOKENS, temperature = 0f,
        )
        val reply = model.generateStructured(request, Fixes.serializer())
        return reply.fixes.mapNotNull { f ->
            val i = asked.getOrNull(f.id) ?: return@mapNotNull null
            val w = f.word.trim().trim('[', ']', '«', '»', '.', '،')
            val core = ScreenWords.core(words[i])
            val one = w.isNotEmpty() && ' ' !in w && ScreenWords.persian(w) && w != core && ok(w)
            // A fix sounds like what was heard: it starts with the same sound, or is at most half rewritten.
            val near = PersianSpelling.soundKey(w).firstOrNull() == PersianSpelling.soundKey(core).firstOrNull() ||
                PersianRepairs.distance(PersianSpelling.soundKey(w), PersianSpelling.soundKey(core)) * 2 <= core.length
            (i to words[i].replace(core, w)).takeIf { one && near }
        }.toMap()
    }

    private const val MAX_ASKED = 16
    private const val CONTEXT = 5
    private const val MAX_TOKENS = 400
    private const val SYSTEM = "You proofread Persian speech-recognition output. Reply in JSON only."
    private const val QUESTION = "Each line is a Persian sentence from a speech recogniser; the word in [brackets] was misheard. " +
        "For each, give the one Persian word the speaker most likely said (Persian letters, a half-space allowed, no spaces). " +
        "Tech, AI, crypto and social-media loanwords are written as Persians write them (توکن، ریپو، پرامپت، تسک). " +
        "If the word is a name, give it back unchanged. Reply as {\"fixes\":[{\"id\":0,\"word\":\"…\"}]}."
    private val SCHEMA: JsonObject = Json.parseToJsonElement(
        """{"type":"object","properties":{"fixes":{"type":"array","items":{"type":"object","properties":{
          "id":{"type":"integer"},"word":{"type":"string","maxLength":30}},"required":["id","word"]}}},"required":["fixes"]}""",
    ).jsonObject
}
