package io.trimio.engine.autopilot

import io.trimio.core.model.transcript.Word
import io.trimio.engine.motion.score.NumberWords
import io.trimio.engine.motion.score.Words

/**
 * What a word is, for the screen: a noun or adjective worth showing, or a verb, adverb, time
 * word, filler, function word. Titles, pops and stamps are built from these, so every text on
 * screen is a phrase that reads on its own («مواد لازم», «روغن زیاد», "garlic butter pasta") and
 * never a fragment («کاری که», «بذارید با», "companies in", "No make").
 */
object Phrases {

    /** A noun or adjective worth the screen: not a function word, verb, adverb, time word, number or unit. */
    fun nounish(w: String): Boolean {
        val n = key(w)
        // "you're", "Act's": contractions and possessives are not titles; "suddenly": adverbs either.
        val english = n.all { it.code < 128 }
        val shaped = !english || ('\'' !in n && '’' !in n && !(n.endsWith("ly") && n.length > 4))
        return Words.isContent(w) && n.length >= MIN_LETTERS && !verb(n) && n !in STOP && n !in ENGLISH_STOP && w.none { it.isDigit() } &&
            Quantities.unitOf(w) == null && shaped && PERCENT.none { n.startsWith(it) } && Fragments.content(w)
    }

    /** A conjugated verb («می‌گفتیم», «بکنی», «گذاشتم», «شده»; "make", "get", "is"), normalised. */
    fun verb(n: String): Boolean =
        ((n.startsWith("می") || n.startsWith("نمی")) && n.length >= MIN_VERB) || VERB.matches(n) || n in ENGLISH_VERBS || n.endsWith("'ll") || n.endsWith("n't")

    /**
     * The line's title: the noun phrase around its most stressed noun — two or three nouns and
     * adjectives in a row, in the transcript's words — or a single long noun; "" when the line
     * has none (no title beats a fragment).
     */
    fun title(
        words: List<Word>,
        line: Lines.Line,
        avoid: Set<String> = emptySet(),
        /** Whether a single long noun may stand in when the line has no phrase (never in a reel: a lone word says nothing). */
        alone: Boolean = true,
    ): String {
        val texts = words.map { it.text }
        val peaks = line.range.filter { nounish(texts[it]) && NumberWords.at(texts, it) == null }.sortedByDescending { Words.weight(words[it]) }
        // Only a phrase that reads on its own ([Fragments]): never «باید استفاده», «درد اکثر کارای».
        val phrases = peaks.map { phraseAt(texts, line, it) }.filter { key(it.replace(" ", "")) !in avoid && Fragments.fault(it) == null }
        // A phrase of two or three words first; a single long noun only when the line has no phrase.
        return phrases.firstOrNull { ' ' in it } ?: phrases.firstOrNull { alone && key(it).length >= LONE_NOUN } ?: ""
    }

    /** Two or three words around a noun: the noun and the nouns or adjectives after (or before) it, within its clause. */
    fun phraseAt(texts: List<String>, line: Lines.Line, i: Int): String {
        fun joins(a: Int, b: Int) = b in line.range && texts[a].lastOrNull()?.let { it !in ".،,:؛;!?؟" } == true && nounish(texts[b]) && NumberWords.at(texts, b) == null
        var end = i
        while (end - i < 2 && joins(end, end + 1)) end++
        // A plural or noun with its ezafe («کارای») takes the word it waits for, when there is room.
        if (Fragments.waits(texts[end]) && joins(end, end + 1)) end++
        val start = if (end == i && joins(i - 1, i - 1)) i - 1 else i
        return (start..end).joinToString(" ") { clean(texts[it]) }
    }

    /** A word as the screen shows it: trailing punctuation off. */
    fun clean(w: String) = w.trimEnd('.', '،', ',', ':', '؛', ';', '!', '?', '؟', '…')

    fun key(w: String) = NumberWords.normalize(w).trimEnd('.', '،', ',', ':', '؛', ';', '!', '?', '؟', '…')

    /** Adverbs, time words and fillers: said with stress, but nothing to show. */
    private val STOP = setOf(
        "دیروز", "امروز", "فردا", "پریروز", "الان", "الآن", "حالا", "هنوز", "همیشه", "هرگز", "فقط", "خیلی", "راحت", "سریع", "درجا", "اول", "آخر", "دیگه",
        "دیگر", "فعال", "دریافت", "باز", "تموم", "تمام", "پر", "یعنی", "بعد", "قبل", "اینجا", "اونجا", "همونجا", "اینجوری", "اونجوری", "واقعا", "اصلا",
        "کلا", "دقیقا", "عزیزم", "بابا", "فلان", "همون", "همین", "یکی", "بیشتر", "کمتر", "نیازی", "مخصوص", "اسمش", "اولش", "برام", "براتون", "موفق",
        "سلام", "خب", "خوب", "حتما", "معمولا", "تقریبا", "رسما", "کاری", "چیزی", "جایی", "کسی", "همه", "هاتون", "هامون", "چیکار", "چرا", "چون", "اگه",
        "وقتی", "ولی", "اما", "بالاخره", "اونجا", "اینا", "اونا", "کجا", "چی", "چه", "کدوم", "یه", "دو", "سه", "بذارید", "بیاید", "ببینید", "نگاه",
        "واقعاً", "اصلاً", "حتماً", "معمولاً", "تقریباً", "کلاً", "دقیقاً", "رسماً", "قطعاً", "فعلاً", "اتفاقاً", "حدود", "بالای", "بالا", "زیر",
        "پایین", "روی", "کنار", "جلوی", "پشت", "بین", "مثل", "طبق", "اینجاست", "اونجاست", "همهاش", "اینقدر", "انقدر",
        "خیلیها", "بیشترش", "حتی", "شاید", "کلی", "بهتون", "بهت", "براش", "ازش", "باهاش", "توش", "رک", "یهکم", "یهچیزی", "خودت", "خودم",
    )

    /** English words that are not things: function words, pronouns, adverbs, auxiliaries, time words. */
    private val ENGLISH_STOP = setOf(
        "at", "by", "from", "into", "about", "under", "over", "up", "down", "out", "off", "as", "than", "be", "been", "was", "were", "do", "does",
        "did", "have", "has", "had", "will", "would", "can", "could", "should", "i", "me", "he", "she", "we", "they", "them", "us", "his", "her", "our",
        "their", "its", "just", "really", "so", "very", "too", "also", "then", "now", "here", "there", "not", "no", "yes", "some", "any", "every",
        "all", "each", "more", "most", "like", "okay", "ok", "um", "uh", "honestly", "actually", "while", "when", "what", "which", "who", "how", "why",
        "where", "if", "but", "because", "until", "till", "only", "even", "still", "yet", "again", "one", "two", "three", "first", "second", "third",
        "today", "yesterday", "tomorrow", "week", "weeks", "time", "thing", "things", "way", "lot", "lots", "these", "those", "this", "that", "it's",
        "that's", "there's", "here's", "let's", "don't", "go", "done", "number", "before", "after", "during", "through", "without", "within",
        "since", "though", "although", "unless", "whether", "between", "times", "per", "around", "across", "against", "among", "toward",
        "towards", "upon", "onto", "via", "versus", "else", "maybe", "almost", "already", "always", "never", "often", "something", "anything",
        "everything", "nothing", "someone", "everyone", "anyone", "people", "guys", "kind", "sort", "bit", "plus", "equals", "minus",
    )

    /** Percent words: the number's unit, never a title on their own. */
    private val PERCENT = listOf("درصد", "percent")

    /** Common English verbs: "No make", "Get your water boiling" never become titles. */
    private val ENGLISH_VERBS = setOf(
        "get", "got", "make", "made", "take", "took", "go", "went", "come", "came", "know", "think", "want", "need", "say", "said", "tell", "told",
        "try", "start", "keep", "let", "put", "see", "look", "use", "find", "give", "work", "works", "call", "feel", "add", "type", "turn", "turns",
        "save", "comment", "send", "follow", "cook", "melt", "boil", "toss", "stir", "drain", "splash", "fills", "sums", "spent", "building", "built",
        "tuned", "dragged", "quit", "stuck", "tried", "ran", "run", "lift", "sleep", "walk", "kicked", "found", "dropped", "goes", "means", "makes",
        "gets", "is", "are", "am", "isn't", "aren't", "wasn't", "went", "watch", "talking", "stopping", "typing", "cooks", "boiling", "stirring",
    )

    /** Conjugations of everyday Persian verbs («بکنی», «گذاشتم», «شده», «بریم», «نبود»), normalised. */
    private val VERB = Regex(
        "^(بر|در|ور)?(ن|ب|نمی|می)?(کن|کرد|شد|شو|دار|داشت|ذار|ذاشت|زار|گذار|گذاشت|گیر|گرفت|داد|ده|رو|رفت|ر|گو|گ|گفت|خور|خورد|تون|تونست|خواه|خواست|" +
            "بین|دید|یا|اومد|آمد|باش|بود|هست|است|نیست|زن|زد|فرست|فرستاد|نویس|نوشت|کش|کشید|ریز|ریخت|بر|برد|پز|پخت|گذر|ساز|ساخت|رس|رسید|" +
            "خواد|خوای|تونی|دون|دونست|موند|مون|گرد|گردون|گشت)" +
            "(م|ی|ه|ید|یم|ند|ین|ن|ش|یت|مون|تون|شون|یدش|ینش|ش)?$",
    )

    private const val MIN_LETTERS = 3
    private const val MIN_VERB = 4
    private const val LONE_NOUN = 4
}
