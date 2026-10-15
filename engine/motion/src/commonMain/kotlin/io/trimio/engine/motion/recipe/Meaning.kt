package io.trimio.engine.motion.recipe

/**
 * What a word means for colour, as the storyboard's cards and captions use it ([Board]): a gain (profit, free,
 * winning) reads green, a loss (stop-loss, a fee, a scam) red, anything else that is stressed yellow. Persian and
 * English, matched on the word's start so «سودها», «سودشون» and "profits" all count.
 */
object Meaning {
    enum class Tone { Gain, Loss, Mark }

    private val GAIN = listOf(
        "سود", "رایگان", "مجانی", "برد", "بردی", "موفق", "درآمد", "رشد", "شفاف", "دغدغه", "بگیر", "امن", "تضمین", "پاداش", "جایزه", "هدیه", "تخفیف",
        "profit", "free", "win", "gain", "growth", "safe", "bonus", "reward", "gift", "transparent", "earn",
    )
    private val LOSS = listOf(
        "ضرر", "استاپ", "استاب", "باخت", "ورشکست", "کلاه", "کلاهبردار", "پولی", "گرون", "گران", "هزینه", "عضویت", "میلیون", "چرت", "دروغ", "ریسک", "خطر",
        "loss", "stop", "scam", "fee", "expensive", "risk", "lose", "fake", "debt",
    )

    /** A word as compared: no joiner, Arabic letters as Persian, no punctuation, lower case. */
    private fun normal(word: String): String = buildString {
        for (c in word.lowercase()) when (c) {
            'ي', 'ى' -> append('ی')
            'ك' -> append('ک')
            '‌' -> {}
            else -> if (c.isLetterOrDigit()) append(c)
        }
    }

    fun tone(word: String): Tone {
        val w = normal(word)
        // «بدون دغدغه», «بی‌دغدغه»: the worry is gone, which is a gain; a fee or a loss is red.
        return when {
            w.isEmpty() -> Tone.Mark
            LOSS.any { w.startsWith(it) } && !w.startsWith("بی") -> Tone.Loss
            GAIN.any { w.startsWith(it) } -> Tone.Gain
            else -> Tone.Mark
        }
    }

    /** The tone of several words: a loss wins over a gain (a warning matters more), either over plain stress. */
    fun tone(words: List<String>): Tone {
        val tones = words.map(::tone)
        return when {
            Tone.Loss in tones -> Tone.Loss
            Tone.Gain in tones -> Tone.Gain
            else -> Tone.Mark
        }
    }

    /** The tone's name as cards take it ("gain", "loss", "mark"). */
    fun name(t: Tone): String = t.name.lowercase()
}
