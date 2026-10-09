package io.trimio.core.model.text

/**
 * Canonical matching key for Persian/English words and phrases: spelling variants (ي/ی, ك/ک,
 * hamza forms), ZWNJ, diacritics, case and punctuation all map to the same key.
 */
object TextKey {
    fun of(text: String): String = buildString(text.length) {
        for (ch in text.lowercase()) {
            when (ch) {
                'ي', 'ى' -> append('ی')
                'ك' -> append('ک')
                'ة' -> append('ه')
                'أ', 'إ', 'آ' -> append('ا')
                '\u200C', '\u200D', '\u200F', '\u200E', 'ـ' -> Unit
                in '\u064B'..'\u065F', '\u0670' -> Unit
                else -> if (ch.isLetterOrDigit() || ch == '%' || ch == '$') append(ch)
            }
        }
    }
}
