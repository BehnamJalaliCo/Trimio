package io.trimio.engine.autopilot.explainer

import kotlinx.serialization.Serializable

/**
 * What a scene's hero illustration must show, for whoever draws it (a CC0 library that composes
 * characters and poses, an on-device generator, the creator's own photo in the film's style). All
 * English keywords except [sign]: the name written on an object (a shop's sign, a safe), only when the
 * narration says that name, in the script it is said in. It travels in [io.trimio.engine.motion.explainer.BeatContent.illustration]
 * as [encode]d text ("subject=…;action=…;objects=a,b;sign=…;style=…"), read back with [parse].
 */
@Serializable
data class IllustrationRequest(
    /** Who or what: "worried young man", "group of people", "safe". */
    val subject: String,
    /** Pose or action: "looking at phone", "talking", "standing in front of a shop". */
    val action: String = "",
    val objects: List<String> = emptyList(),
    /** A name on an object, exactly as the narration says it; "" when none is said. */
    val sign: String = "",
    /** The film's illustration style ("pencil sketch", "flat"). */
    val style: String = "pencil sketch",
) {
    fun encode(): String = buildList {
        add("subject=$subject")
        if (action.isNotBlank()) add("action=$action")
        if (objects.isNotEmpty()) add("objects=" + objects.joinToString(","))
        if (sign.isNotBlank()) add("sign=$sign")
        add("style=$style")
    }.joinToString(";")

    /** Everything as one search query ("worried young man looking at phone smartphone"). */
    val query: String get() = (listOf(subject, action) + objects).filter { it.isNotBlank() }.joinToString(" ")

    companion object {
        fun parse(s: String): IllustrationRequest? {
            val parts = s.split(';').mapNotNull { p -> p.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }.toMap()
            val subject = parts["subject"]?.takeIf { it.isNotBlank() } ?: return null
            return IllustrationRequest(subject, parts["action"].orEmpty(), parts["objects"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }.orEmpty(),
                parts["sign"].orEmpty(), parts["style"] ?: "pencil sketch")
        }
    }
}

/** Resolves an illustration request to something the stage can draw (an id, a file, a query); null when it cannot. */
fun interface Illustrator {
    suspend fun resolve(request: IllustrationRequest): String?

    companion object {
        /** No source: the request itself goes to the stage as text. */
        val Requests = Illustrator { it.encode() }
    }
}

/**
 * The rules' reading of a scene into an [IllustrationRequest]: the people or the thing it is about
 * (users → a group of people, «طلا» → gold bars, «گاوصندوق» → a safe, a shop → a storefront), what they
 * do or feel (said → talking, «هنوز … نشده», waiting → worried, looking at a phone; gathered →
 * standing together in front of a building), and the name said in the scene for a sign.
 */
object Illustrations {

    fun request(n: Narration, words: IntRange, sign: String, style: String = "pencil sketch"): IllustrationRequest? {
        val keys = words.map { Metaphors.bare(n.texts[it]) }
        val people = keys.firstNotNullOfOrNull { k -> PEOPLE.entries.firstOrNull { (s, _) -> k.startsWith(s) }?.value }
        val things = keys.mapNotNull { k -> THINGS.entries.firstOrNull { (s, _) -> k.startsWith(s) }?.value }.distinct()
        val action = ACTIONS.entries.firstOrNull { (s, _) -> keys.any { it.startsWith(s) } || s in keys.joinToString(" ") }?.value.orEmpty()
        val subject = people ?: things.firstOrNull() ?: words.firstNotNullOfOrNull { Said.visualOf(n.texts[it]) } ?: return null
        val objects = (if (people != null) things else things.drop(1)).take(MAX_OBJECTS)
        val worried = action.contains("worried")
        val who = if (worried && people != null && people.startsWith("group")) "worried young man" else subject
        val withPhone = if (worried && "smartphone" !in objects) objects + "smartphone" else objects
        return IllustrationRequest(who, action, withPhone.take(MAX_OBJECTS), sign, style)
    }

    private val PEOPLE = linkedMapOf(
        "کاربر" to "group of people", "مردم" to "group of people", "مشتری" to "group of customers", "سرمایهگذار" to "group of investors", "users" to "group of people",
        "people" to "group of people", "customers" to "group of customers", "investors" to "group of investors", "مدیر" to "businessman", "ceo" to "businessman",
        "پلیس" to "police officer", "police" to "police officer", "دانشجو" to "student", "student" to "student", "کارمند" to "office worker", "employee" to "office worker",
        "دکتر" to "doctor", "doctor" to "doctor",
    )
    private val THINGS = linkedMapOf(
        "طلا" to "gold bars", "gold" to "gold bars", "گاوصندوق" to "safe", "صندوق" to "safe", "safe" to "safe", "پول" to "money", "money" to "money",
        "گوشی" to "smartphone", "phone" to "smartphone", "فروشگاه" to "storefront", "مغازه" to "storefront", "شعبه" to "storefront", "store" to "storefront",
        "shop" to "storefront", "بانک" to "bank building", "bank" to "bank building", "سکه" to "gold coins", "coin" to "gold coins", "خونه" to "house",
        "خانه" to "house", "house" to "house", "ماشین" to "car", "car" to "car", "شرکت" to "office building", "company" to "office building",
        "دادگاه" to "courthouse", "court" to "courthouse", "گزارش" to "newspaper", "report" to "newspaper", "نامه" to "letter", "letter" to "letter",
        "قرارداد" to "contract paper", "contract" to "contract paper", "اپ" to "smartphone", "app" to "smartphone", "سایت" to "laptop", "website" to "laptop",
    )
    private val ACTIONS = linkedMapOf(
        "تجمع" to "standing together in front of a building", "اعتراض" to "protesting", "protest" to "protesting", "gathered" to "standing together in front of a building",
        "هنوز" to "worried, looking at phone", "انتظار" to "worried, looking at phone", "نگران" to "worried, looking at phone", "waiting" to "worried, looking at phone",
        "worried" to "worried, looking at phone", "میگن" to "talking", "گفتن" to "talking", "said" to "talking", "say" to "talking", "فروخت" to "selling",
        "sold" to "selling", "خرید" to "buying", "bought" to "buying", "شکایت" to "complaining", "complain" to "complaining", "خوشحال" to "smiling", "happy" to "smiling",
    )
    private const val MAX_OBJECTS = 3
}
