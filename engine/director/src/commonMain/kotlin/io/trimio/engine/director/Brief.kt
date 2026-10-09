package io.trimio.engine.director

import io.trimio.core.model.style.CaptionMode
import io.trimio.core.model.style.DesignStyle

/**
 * What the user asked for, read deterministically from the prompt. Works for one-word prompts
 * ("پرانرژی") and long ChatGPT-style briefs alike; an LLM, when available, refines the plan built
 * from it but never replaces this baseline.
 */
data class Brief(
    val prompt: String,
    /** A style named in the prompt ("نئوبروتال", "liquid glass"), if any. */
    val styleId: String? = null,
    /** Mood/topic tags matched against style-pack tags. */
    val tags: Set<String> = emptySet(),
    val topics: Set<Lexicon.Topic> = emptySet(),
    /** 0 calm … 1 hyper; null when the prompt does not say. */
    val energy: Float? = null,
    val cutSilences: Boolean = true,
    val cutFillers: Boolean = true,
    val music: Boolean = true,
    val sfx: Boolean = true,
    val captionMode: CaptionMode? = null,
    /** Words the user quoted («سود», "BTC"): always emphasised when spoken. */
    val emphasize: Set<String> = emptySet(),
    /** Headline for the hook, from `عنوان: …` / `title: …`. */
    val headline: String? = null,
)

object BriefParser {

    private val quoted = Regex("[«\"“](.{1,40}?)[»\"”]")
    private val headlineRe = Regex("(?:عنوان|تیتر|headline|title)\\s*[:：]\\s*([^\\n.،]{2,60})", RegexOption.IGNORE_CASE)

    fun parse(prompt: String): Brief {
        val lower = prompt.lowercase()
        val tokens = prompt.split(Regex("[\\s,،.!?؟:;()\\[\\]{}«»\"“”/]+")).map(Lexicon::norm).filter { it.isNotEmpty() }
        // Two-word names ("liquid glass", "بیت کوین") are matched on joined neighbours too.
        val keys = tokens + tokens.zipWithNext { a, b -> a + b }

        val styleId = keys.firstNotNullOfOrNull { Lexicon.styleAliases[it] }
            ?: DesignStyle.entries.firstOrNull { s ->
                Lexicon.norm(s.nameEn) in keys || Lexicon.norm(s.nameFa) in keys || Lexicon.norm(s.id) in keys
            }?.id

        val topics = Lexicon.topics.filterValues { words -> keys.any { it in words } }.keys
        val energetic = keys.count { it in Lexicon.energetic }
        val calm = keys.count { it in Lexicon.calm }
        val energy = when {
            energetic == 0 && calm == 0 -> null
            energetic > calm -> 0.85f
            calm > energetic -> 0.35f
            else -> 0.6f
        }

        val tags = buildSet {
            topics.forEach { addAll(it.tags) }
            keys.forEach { k -> if (k in Lexicon.energetic) add("energetic") }
            keys.forEach { k -> if (k in Lexicon.calm) add("calm") }
            addAll(keys) // raw words also match pack tags written in either language
        }

        val mode = when {
            listOf("کارائوکه", "karaoke").any { it in lower } -> CaptionMode.Karaoke
            listOf("تک کلمه", "کلمه به کلمه", "one word", "single word", "word by word").any { it in lower } -> CaptionMode.SingleWord
            listOf("زیرنویس کامل", "جمله کامل", "full sentence", "full subtitles").any { it in lower } -> CaptionMode.Phrase
            else -> null
        }

        return Brief(
            prompt = prompt,
            styleId = styleId,
            tags = tags,
            topics = topics,
            energy = energy,
            cutSilences = Lexicon.keepPauses.none { it in lower },
            cutFillers = Lexicon.keepFillers.none { it in lower },
            music = Lexicon.noMusic.none { it in lower },
            sfx = Lexicon.noSfx.none { it in lower },
            captionMode = mode,
            emphasize = quoted.findAll(prompt).map { Lexicon.norm(it.groupValues[1]) }.filter { it.isNotEmpty() }.toSet(),
            headline = headlineRe.find(prompt)?.groupValues?.get(1)?.trim(),
        )
    }
}
