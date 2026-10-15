package io.trimio.engine.autopilot

import io.trimio.engine.autopilot.AssetReading.Role
import io.trimio.engine.autopilot.AssetReading.Where
import io.trimio.engine.llm.ChatMessage
import io.trimio.engine.llm.ChatRole
import io.trimio.engine.llm.GenerationRequest
import io.trimio.engine.llm.LanguageModel
import io.trimio.engine.llm.generateStructured
import io.trimio.engine.motion.score.Words
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

/**
 * Understands each picture or clip the creator attached. With eyes (a model that sees) it asks,
 * under a JSON grammar, what the asset is — subject, text or brand on it, logo / product photo /
 * screenshot / B-roll — and 3–6 words a speaker would say about it, in the transcript's language
 * and in English. Without eyes, the rules read the file name and caption, the brief's own words
 * about it («عکس محصول رو وقتی گفتم سرم بذار», "use logo.png when I say Nike") and simple pixel
 * cues (a transparent or flat-coloured square is a logo, a flat tall or wide picture a screenshot).
 * Either way the brief's instructions win: they say when, and they may say what.
 */
class AssetReader(private val eyes: LanguageModel? = null) {

    @Serializable
    internal data class Seen(
        val kind: String = "photo",
        val subject: String = "",
        val text: String = "",
        val brand: String = "",
        val keywords: List<String> = emptyList(),
        @SerialName("keywords_en") val english: List<String> = emptyList(),
    )

    /** One reading per asset, in order. [persian]: the transcript's language, for the eyes' keywords. */
    suspend fun read(assets: List<UserAsset>, brief: String, persian: Boolean, log: (String) -> Unit = {}): List<AssetReading> = assets.map { a ->
        val rules = rules(a, brief, assets)
        val reading = if (rules.role == Role.Reference) {
            // A look to follow: the eyes say how it frames, captions and pictures things; nothing of it goes on screen.
            val devices = eyes?.takeIf { it.canSee }?.let { m -> runCatching { study(m, a) }.onFailure { log("asset ${a.id}: eyes failed (${it.message})") }.getOrNull() }
            rules.copy(devices = devices, by = if (devices != null) "eyes" else "rules")
        } else {
            val seen = eyes?.takeIf { it.canSee }?.let { m -> runCatching { look(m, a, persian) }.onFailure { log("asset ${a.id}: eyes failed (${it.message})") }.getOrNull() }
            if (seen == null) rules else merge(rules, seen, a)
        }
        reading.devices?.let { d -> log("asset ${a.id}: reference — speaker ${d.framing}, captions ${d.captions} (${d.captionWords} words, ${d.highlight}), graphics ${d.graphics}") }
        log("asset ${a.id}: ${reading.role} «${reading.subject}» by ${reading.by}; keywords ${reading.keywords.take(KEYWORDS)}; cue ${reading.cue}; where ${reading.where}")
        reading
    }

    /** The reference's devices, from a few of its frames: how a motion editor would describe what to copy. */
    private suspend fun study(m: LanguageModel, a: UserAsset): ReferenceStyle.Devices {
        val images = (listOf(a.thumbnail) + a.frames).let { all -> if (all.size <= STUDY_FRAMES) all else (0 until STUDY_FRAMES).map { all[it * (all.size - 1) / (STUDY_FRAMES - 1)] } }
        val question = "These are frames of a short video a creator wants their own edit to look like. Describe its editing devices: how the " +
            "speaker is framed (full: the speaker fills the frame; corner: the speaker shrinks to a small card while a graphic fills the frame; " +
            "none: no speaker), where the captions sit (top, middle, bottom or none) and how many words a caption shows, how a word is marked " +
            "(box: a coloured box behind it; color: a coloured word; none), which kinds of graphics appear (number, price, chat, list, steps, " +
            "chart, comparison, logos, quote, map, poll, comment) and the background of its graphics (dark, light, paper, gradient)."
        return m.generateStructured(
            GenerationRequest(system = STUDY_SYSTEM, messages = listOf(ChatMessage(ChatRole.User, question, images)), schema = STUDY_SCHEMA, maxTokens = 220, temperature = 0f),
            ReferenceStyle.Devices.serializer(),
        )
    }

    private suspend fun look(m: LanguageModel, a: UserAsset, persian: Boolean): Seen {
        val language = if (persian) "Persian" else "English"
        val images = listOf(a.thumbnail) + a.frames.drop(1).take(1)
        val question = "What is this ${if (a.kind == UserAsset.Kind.Video) "video clip" else "picture"}? Say its kind (a logo, a product photo, a screenshot " +
            "of an app or website, b-roll footage, or another photo), its subject in a few words, any text or brand it shows, and 3 to 6 keywords a " +
            "speaker would say about it, in $language (keywords) and in English (keywords_en)." + (a.caption?.let { " The creator wrote: $it" } ?: "")
        return m.generateStructured(
            GenerationRequest(system = SYSTEM, messages = listOf(ChatMessage(ChatRole.User, question, images)), schema = SCHEMA, maxTokens = 160, temperature = 0f),
            Seen.serializer(),
        )
    }

    companion object {
        private const val KEYWORDS = 6
        private const val SYSTEM = "You look at media a video creator attached to their edit and say what each one is. Answer in JSON."
        private val SCHEMA: JsonObject = Json.parseToJsonElement(
            """{"type":"object","properties":{
              "kind":{"type":"string","enum":["logo","product","screenshot","broll","photo"]},
              "subject":{"type":"string"},"text":{"type":"string"},"brand":{"type":"string"},
              "keywords":{"type":"array","items":{"type":"string"},"minItems":3,"maxItems":6},
              "keywords_en":{"type":"array","items":{"type":"string"},"minItems":3,"maxItems":6}},
              "required":["kind","subject","keywords","keywords_en"]}""",
        ).jsonObject

        /** What the eyes saw, under what the creator said: a kind the creator chose and the brief's cue stay. */
        internal fun merge(rules: AssetReading, seen: Seen, a: UserAsset): AssetReading {
            val role = when {
                a.kind != UserAsset.Kind.Image || rules.role.explicit -> rules.role
                else -> ROLES[seen.kind.lowercase()] ?: rules.role
            }
            val keywords = (seen.keywords + seen.english + listOf(seen.brand, seen.text).filter { it.isNotBlank() } + rules.keywords)
                .map { it.trim() }.filter { it.length >= 2 }.distinctBy { it.lowercase() }
            return rules.copy(
                role = role, subject = seen.subject.ifBlank { rules.subject }, text = seen.text, brand = seen.brand.ifBlank { rules.brand },
                keywords = keywords, by = "eyes",
            )
        }

        private const val STUDY_FRAMES = 4
        private const val STUDY_SYSTEM = "You are a motion editor studying a reference video's editing devices. Answer in JSON."
        private val STUDY_SCHEMA: JsonObject = Json.parseToJsonElement(
            """{"type":"object","properties":{
              "framing":{"type":"string","enum":["full","corner","none"]},
              "captions":{"type":"string","enum":["top","middle","bottom","none"]},
              "caption_words":{"type":"integer"},
              "highlight":{"type":"string","enum":["box","color","none"]},
              "graphics":{"type":"array","items":{"type":"string","enum":["number","price","chat","list","steps","chart","comparison","logos","quote","map","poll","comment"]},"maxItems":6},
              "background":{"type":"string","enum":["dark","light","paper","gradient"]}},
              "required":["framing","captions","caption_words","highlight","graphics","background"]}""",
        ).jsonObject

        private val ROLES = mapOf("logo" to Role.Logo, "product" to Role.Product, "screenshot" to Role.Screenshot, "broll" to Role.Broll, "photo" to Role.Photo)

        /** Whether the creator (or their brief) fixed this role, so the eyes do not overrule it. */
        private val Role.explicit: Boolean get() = this == Role.Logo || this == Role.Reference

        /** The words that name each kind of asset in a brief, a file name or a caption. */
        private val ROLE_WORDS: List<Pair<Role, List<String>>> = listOf(
            // «مثل این ویدیو بساز», «به سبک این موشن»: a look to follow, not a picture to show (before «ویدیو» means a clip).
            Role.Reference to listOf(
                "رفرنس", "مثل این ویدیو", "مثل این ویدئو", "مثل این کلیپ", "مثل این موشن", "مثل این ریلز", "شبیه این ویدیو", "شبیه این کلیپ", "به سبک", "سبک این",
                "استایل این", "reference", "style of", "in the style", "like this video", "like this clip",
            ),
            Role.Logo to listOf("لوگو", "logo", "نشان تجاری", "brand mark"),
            Role.Screenshot to listOf("اسکرین‌شات", "اسکرینشات", "اسکرین شات", "اسکرین", "screenshot", "screen shot", "screen", "صفحه اپ", "صفحه سایت"),
            Role.Product to listOf("محصول", "product", "packshot", "پکیج"),
            Role.Broll to listOf("ویدیو", "ویدئو", "کلیپ", "فیلم", "video", "clip", "b-roll", "broll", "footage"),
            Role.Photo to listOf("عکس", "تصویر", "photo", "image", "picture"),
        )
        private val INSET_WORDS = listOf("تصویر در تصویر", "گوشه", "pip", "picture in picture", "picture-in-picture", "corner")
        private val START_WORDS = listOf("اول ویدیو", "اولش", "ابتدا", "شروع", "start", "beginning", "intro", "opening")
        private val END_WORDS = listOf("آخر", "پایان", "انتها", "تهش", "the end", "ending", "outro", "at the end")

        /** «وقتی گفتم سرم», "when I say Nike": the words after a trigger are the cue. */
        private val TRIGGERS = listOf(
            Regex("(?:وقتی|موقعی که|هر وقت|زمانی که|هروقت)\\s*(?:که\\s+)?(?:گفتم|می‌گم|میگم|گفت|اسم|حرف)\\s+(?:از\\s+)?(?:اسم\\s+)?(.+)"),
            Regex("(?i)(?:when|whenever|as soon as|once)\\s+(?:i|we)\\s+(?:say|said|mention|talk about|name|get to)\\s+(.+)"),
        )
        private val CUE_STOP = setOf(
            "بذار", "بزار", "بیار", "بیاد", "نشون", "نشان", "بده", "بدی", "کن", "رو", "را", "اضافه", "بنداز", "بذاری", "بیاری", "بیارش", "بذارش", "نمایش",
            "then", "and", "put", "show", "use", "add", "insert", "it", "please",
        )
        private const val MAX_CUE = 3

        /** The rules' reading: kind, file name and caption, the brief's clause about it, then the pixels. */
        internal fun rules(a: UserAsset, brief: String, all: List<UserAsset> = listOf(a)): AssetReading {
            val pixels = Pixels.of(a)
            val named = listOfNotNull(a.fileName, a.caption).joinToString(" ")
            val guess = when (a.kind) {
                UserAsset.Kind.Logo -> Role.Logo
                UserAsset.Kind.Video -> roleIn(named)?.takeIf { it == Role.Screenshot } ?: Role.Broll
                UserAsset.Kind.Image -> roleIn(named)?.takeIf { it != Role.Broll } ?: pixels.role(a.aspect)
            }
            val clause = clause(a, guess, brief, all)
            val role = when {
                referenced(a, named, brief, all) -> Role.Reference
                a.kind != UserAsset.Kind.Image -> guess
                else -> clause?.let { roleIn(it) }?.takeIf { it != Role.Broll && it != Role.Photo } ?: guess
            }.let { if (it == Role.Photo) Role.Product else it }
            val cue = clause?.let(::cue).orEmpty()
            val lower = clause?.lowercase().orEmpty()
            val where = when {
                cue.isNotEmpty() || clause == null -> null
                START_WORDS.any { it in lower } -> Where.Start
                END_WORDS.any { it in lower } -> Where.End
                else -> null
            }
            val words = (stemWords(a.fileName) + a.caption.orEmpty().split(' ', '،', ',', '.').map { it.trim() } + cue)
                .filter { it.length >= 2 && Words.isContent(it) }.distinctBy { it.lowercase() }
            // A word of the file name or caption the brief writes as a name is the brand, in the brief's spelling ("velora" → Velora).
            val brand = words.firstNotNullOfOrNull { w -> Regex("\\b${Regex.escape(w)}\\b", RegexOption.IGNORE_CASE).find(brief)?.value?.takeIf { it.first().isUpperCase() } }.orEmpty()
            return AssetReading(
                a.id, role, subject = words.take(2).joinToString(" "), brand = brand, keywords = words, cue = cue, where = where,
                inset = INSET_WORDS.any { it in lower }, light = role == Role.Logo && pixels.light && pixels.clear >= Pixels.CLEAR,
            )
        }

        /**
         * The creator offers [a] as a look to follow: its name or caption says so, or a clause of the brief does and names
         * its file, or names none while [a] is the only clip attached (or, with no clip, the only picture).
         */
        private fun referenced(a: UserAsset, named: String, brief: String, all: List<UserAsset>): Boolean {
            if (roleIn(named) == Role.Reference) return true
            val clauses = brief.split(Regex("[\\n.!?؟؛;]+")).map { it.trim() }.filter { roleIn(it) == Role.Reference }
            if (clauses.isEmpty()) return false
            val stem = a.fileName.substringBeforeLast('.').lowercase()
            if (clauses.any { c -> a.fileName.isNotBlank() && stem.length >= MIN_STEM && stem in c.lowercase() }) return true
            val others = all.filter { o -> o.fileName.isNotBlank() && clauses.any { c -> o.fileName.substringBeforeLast('.').lowercase().let { it.length >= MIN_STEM && it in c.lowercase() } } }
            if (others.isNotEmpty()) return false
            // A look to follow is a clip when one is attached (never the logo beside it); else the one picture.
            val clips = all.filter { it.kind == UserAsset.Kind.Video }
            return if (clips.isNotEmpty()) clips.singleOrNull() === a else all.singleOrNull() === a
        }

        private fun roleIn(text: String): Role? {
            val t = text.lowercase().replace('_', ' ').replace('-', ' ')
            return ROLE_WORDS.firstOrNull { (_, ws) -> ws.any { w -> Regex("(^|[^\\p{L}])${Regex.escape(w)}").containsMatchIn(t) } }?.first
        }

        /**
         * The brief's clause about [a]: the one naming its file, else one naming its kind («لوگو»,
         * «عکس محصول», "the clip") when no other attached asset of that kind could be meant.
         */
        private fun clause(a: UserAsset, role: Role, brief: String, all: List<UserAsset>): String? {
            val clauses = brief.split(Regex("[\\n.!?؟؛;،,]+(?=\\s|$)|\\n")).map { it.trim() }.filter { it.isNotEmpty() }
            val stem = a.fileName.substringBeforeLast('.').lowercase()
            clauses.firstOrNull { c -> a.fileName.isNotBlank() && (c.contains(a.fileName, ignoreCase = true) || (stem.length >= MIN_STEM && c.lowercase().contains(stem))) }?.let { return it }
            val same = all.count { o -> o.kind == a.kind || (a.kind != UserAsset.Kind.Video && o.kind != UserAsset.Kind.Video) }
            return clauses.firstOrNull { c -> roleIn(c) == role } ?: clauses.firstOrNull { c ->
                same == 1 && roleIn(c).let { r -> r == Role.Photo || (a.kind == UserAsset.Kind.Video && r == Role.Broll) || (a.kind != UserAsset.Kind.Video && r == Role.Product) }
            }
        }

        /** The words after a trigger, up to the verb that ends the instruction: «وقتی گفتم سرم بذار» → سرم. */
        private fun cue(clause: String): List<String> {
            val tail = TRIGGERS.firstNotNullOfOrNull { it.find(clause)?.groupValues?.get(1) } ?: return emptyList()
            val quoted = Regex("[«\"“']([^»\"”']{2,30})[»\"”']").find(tail)?.groupValues?.get(1)
            val words = (quoted ?: tail).split(' ').map { it.trim { c -> !c.isLetterOrDigit() } }.filter { it.isNotEmpty() }
            val kept = (if (quoted != null) words else words.takeWhile { it.lowercase() !in CUE_STOP }).filter { Words.isContent(it) }
            return listOf(kept.take(MAX_CUE).joinToString(" ")).filter { it.isNotBlank() }
        }

        /** Words of a file name ("nike-air_max.png" → nike, air, max), generic ones left out. */
        private fun stemWords(name: String): List<String> = name.substringBeforeLast('.')
            .split(Regex("[^\\p{L}\\p{N}]+")).filter { w -> w.length >= MIN_STEM && w.lowercase() !in GENERIC && !w.all { it.isDigit() } }

        private const val MIN_STEM = 3
        private val GENERIC = setOf(
            "img", "image", "photo", "pic", "picture", "final", "copy", "edit", "edited", "screenshot", "screen", "logo", "video", "clip", "file", "new",
            "untitled", "whatsapp", "telegram", "export",
        )
    }

    /** Pixel cues of a thumbnail: how much is clear, how flat its colours are, whether its ink is light. */
    internal class Pixels(val clear: Float, val flat: Float, val light: Boolean) {
        /** A transparent or flat square is a logo; a flat tall or wide picture is a screenshot; a photo otherwise. */
        fun role(aspect: Float): Role = when {
            clear >= CLEAR -> Role.Logo
            flat >= FLAT && aspect in SQUARE -> Role.Logo
            flat >= FLAT -> Role.Screenshot
            else -> Role.Product
        }

        companion object {
            const val CLEAR = 0.1f
            private const val FLAT = 0.72f
            private const val LIGHT = 0.8f
            private const val SAMPLES = 12_000
            private val SQUARE = 0.6f..1.7f

            fun of(a: UserAsset): Pixels {
                val img = a.thumbnail
                val n = img.width * img.height
                if (n == 0) return Pixels(0f, 0f, false)
                val step = maxOf(1, n / SAMPLES)
                val bins = HashMap<Int, Int>()
                var clear = 0
                var seen = 0
                var inked = 0
                var luma = 0f
                for (p in 0 until n step step) {
                    val alpha = a.alpha?.getOrNull(p)?.toInt()?.and(0xFF) ?: 255
                    seen++
                    if (alpha < 128) { clear++; continue }
                    val r = img.rgb[p * 3].toInt() and 0xFF
                    val g = img.rgb[p * 3 + 1].toInt() and 0xFF
                    val b = img.rgb[p * 3 + 2].toInt() and 0xFF
                    val bin = (r shr 4) shl 8 or ((g shr 4) shl 4) or (b shr 4)
                    bins[bin] = (bins[bin] ?: 0) + 1
                    luma += (0.2126f * r + 0.7152f * g + 0.0722f * b) / 255f
                    inked++
                }
                val top = bins.values.sortedDescending().take(3).sum()
                return Pixels(clear / seen.toFloat(), if (inked == 0) 1f else top / inked.toFloat(), inked > 0 && luma / inked > LIGHT)
            }
        }
    }
}
