package io.trimio.core.model.text

/** Conversions between Latin (0-9) and Persian (۰-۹) digits, for captions and number counters. */
object Numerals {
    private const val PERSIAN_ZERO = '۰'
    private const val ARABIC_INDIC_ZERO = '٠'

    fun toPersian(text: String): String = buildString(text.length) {
        for (ch in text) append(if (ch in '0'..'9') PERSIAN_ZERO + (ch - '0') else ch)
    }

    fun toLatin(text: String): String = buildString(text.length) {
        for (ch in text) {
            append(
                when (ch) {
                    in '۰'..'۹' -> '0' + (ch - PERSIAN_ZERO)
                    in '٠'..'٩' -> '0' + (ch - ARABIC_INDIC_ZERO)
                    else -> ch
                },
            )
        }
    }

    fun localize(text: String, language: Language): String =
        if (language == Language.Persian) toPersian(text) else toLatin(text)
}
