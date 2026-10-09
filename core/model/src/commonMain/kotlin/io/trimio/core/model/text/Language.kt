package io.trimio.core.model.text

import kotlinx.serialization.Serializable

@Serializable
enum class Language(val code: String, val isRtl: Boolean) {
    Persian("fa", isRtl = true),
    English("en", isRtl = false);

    companion object {
        fun fromCode(code: String): Language? = entries.firstOrNull { it.code.equals(code, ignoreCase = true) }
    }
}

/**
 * Detects the dominant script of a piece of text. Used per word, because Persian speakers
 * mix English words ("سیگنال BTC") and every word must be laid out with its own direction.
 */
object ScriptDetector {

    fun detect(text: String, fallback: Language = Language.Persian): Language {
        var arabicScript = 0
        var latin = 0
        for (ch in text) {
            when {
                ch.isArabicScript() -> arabicScript++
                ch in 'A'..'Z' || ch in 'a'..'z' -> latin++
            }
        }
        return when {
            arabicScript == 0 && latin == 0 -> fallback
            arabicScript >= latin -> Language.Persian
            else -> Language.English
        }
    }

    /** Arabic, Arabic Supplement and Arabic Presentation Forms A/B (covers Persian letters and ZWNJ-joined forms). */
    fun Char.isArabicScript(): Boolean =
        this in '؀'..'ۿ' ||
            this in 'ݐ'..'ݿ' ||
            this in 'ﭐ'..'﷿' ||
            this in 'ﹰ'..'﻿'

    /** Persian digits ۰-۹ and Arabic-Indic digits ٠-٩. */
    fun Char.isEasternDigit(): Boolean = this in '۰'..'۹' || this in '٠'..'٩'
}
