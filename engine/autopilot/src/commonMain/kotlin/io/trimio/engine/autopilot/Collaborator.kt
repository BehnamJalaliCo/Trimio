package io.trimio.engine.autopilot

import io.trimio.engine.llm.ChatMessage
import io.trimio.engine.llm.ChatRole
import io.trimio.engine.llm.GenerationRequest
import io.trimio.engine.llm.LanguageModel
import io.trimio.engine.llm.generateStructured
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * The two-way part: the creator reacts in their own words ("پرانرژی‌ترش کن", "too much text",
 * "no music") and the next edit follows; keeping or regenerating an edit teaches the [Taste]
 * without a word. The model reads free text; a keyword reader covers the common asks offline.
 */
class Collaborator(private val model: LanguageModel? = null) {

    suspend fun read(feedback: String): Adjustment {
        val words = keywords(feedback)
        val m = model ?: return words
        return runCatching {
            val read = m.generateStructured(
                GenerationRequest(system = SYSTEM, messages = listOf(ChatMessage(ChatRole.User, feedback)), schema = schema, maxTokens = 200, temperature = 0f),
                Adjustment.serializer(),
            )
            // Switches (captions, music, look) are unambiguous words: the keyword reader decides them.
            clean(read).copy(captions = words.captions, music = words.music, look = words.look, captionWords = words.captionWords)
        }.getOrDefault(words)
    }

    private fun clean(a: Adjustment) = a.copy(
        energy = a.energy.coerceIn(-1f, 1f), density = a.density.coerceIn(-1f, 1f), takeover = a.takeover.coerceIn(-1f, 1f),
        look = a.look?.takeIf { it in setOf("noir", "paper", "lumen") },
        moreOf = a.moreOf.filter { it in RECIPES }, lessOf = a.lessOf.filter { it in RECIPES },
        captionWords = a.captionWords?.coerceIn(1, 6),
    )

    /** Offline reading of the most common requests, Persian and English. */
    fun keywords(feedback: String): Adjustment {
        val f = feedback.lowercase()
        fun has(vararg k: String) = k.any { it in f }
        return Adjustment(
            energy = when {
                has("پرانرژی", "انرژی بیشتر", "تندتر", "هیجان", "more energy", "faster", "punchier") -> 1f
                has("آرام", "آروم", "ملایم", "calmer", "slower", "softer") -> -1f
                else -> 0f
            },
            density = when {
                has("شلوغ", "متن کمتر", "کمتر", "too much", "less text", "cleaner", "fewer") -> -1f
                has("بیشتر المان", "المان بیشتر", "more graphics", "more elements") -> 1f
                else -> 0f
            },
            takeover = when {
                has("صورتم", "خودم بیشتر", "more of me", "less full screen") -> -1f
                has("تمام صفحه", "full screen", "more b-roll") -> 1f
                else -> 0f
            },
            captions = when {
                has("بدون زیرنویس", "بدون کپشن", "no captions", "remove captions") -> false
                has("زیرنویس بذار", "کپشن بذار", "add captions") -> true
                else -> null
            },
            music = when {
                has("بدون موسیقی", "بدون موزیک", "no music") -> false
                has("موسیقی بذار", "موزیک بذار", "add music") -> true
                else -> null
            },
            look = when {
                has("روشن", "light", "paper", "کاغذ") -> "paper"
                has("تیره", "dark", "noir", "نوآر") -> "noir"
                has("لوکس", "luxury", "lumen", "آبی") -> "lumen"
                else -> null
            },
        )
    }

    private companion object {
        val RECIPES = listOf(
            "slam", "mask-rise", "type-on", "blur-in", "flip", "spread", "stack", "glitch", "counter", "logos", "terminal", "network", "meter",
            "chart", "object", "objects", "list", "comment", "lower-third", "stamp",
        )
        const val SYSTEM = "You turn a creator's feedback on a video edit into adjustments. Values are relative: -1 less … +1 more; null when not mentioned. Answer in JSON."
        private fun num() = buildJsonObject { put("type", "number") }
        private fun list() = buildJsonObject {
            put("type", "array"); put("maxItems", 4)
            put("items", buildJsonObject { put("type", "string"); put("enum", JsonArray(RECIPES.map(::JsonPrimitive))) })
        }
        val schema = buildJsonObject {
            put("type", "object")
            put(
                "properties",
                JsonObject(
                    mapOf(
                        "energy" to num(), "density" to num(), "takeover" to num(),
                        "moreOf" to list(), "lessOf" to list(),
                    ),
                ),
            )
        }
    }
}
