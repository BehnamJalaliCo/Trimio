package io.trimio.engine.retrieval

import io.trimio.engine.retrieval.resources.Res
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.jetbrains.compose.resources.ExperimentalResourceApi

/**
 * The corpora and the kinds of their documents. Pictures come from the caller (the art catalogue
 * lives with the autopilot: [art]); primitives, card families, sounds, the domain glossary and the
 * golden exemplars ship here ([bundled]).
 */
object Corpora {
    /** Pictures and what can stand for one: animated pictures, still icons, explainer primitives, card families. */
    const val VISUALS = "visuals"
    const val SOUNDS = "sounds"
    const val EXEMPLARS = "exemplars"
    const val TERMS = "terms"

    const val ANIMATION = "animation"
    const val ICON = "icon"
    const val PRIMITIVE = "primitive"
    const val FAMILY = "family"
    const val MUSIC = "music"
    const val SFX = "sfx"

    /** Picture kinds (a sentence's literal or metaphorical picture). */
    val PICTURES = setOf(ANIMATION, ICON)

    /** What can carry a sentence when no picture fits: the diagram language and the card families. */
    val STRUCTURES = setOf(PRIMITIVE, FAMILY)

    /**
     * One picture of the catalogue as the caller knows it: id ("noto:1f680"), its English name and
     * keywords, Persian keywords, category, and whether it moves.
     */
    data class Picture(
        val id: String,
        val name: String,
        val tags: List<String>,
        val tagsFa: List<String> = emptyList(),
        val category: String = "",
        val animated: Boolean = true,
        /** A longer description (VLM-written, offline), when the catalogue has one. */
        val description: String = "",
    )

    /** Picture documents: the name, a description from its keywords, Persian keywords, the category as a tag. */
    fun art(pictures: List<Picture>): List<Doc> = pictures.map { p ->
        val words = (listOf(p.name) + p.tags).map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        val text = p.description.ifBlank { words.joinToString(", ") }
        Doc(
            p.id, if (p.animated) ANIMATION else ICON, p.name, text, p.tagsFa.distinct().joinToString("، "),
            listOfNotNull(p.category.ifBlank { null }), mapOf("set" to p.id.substringBefore(':')),
        )
    }

    private val json = Json { ignoreUnknownKeys = true }

    fun parseDocs(text: String): List<Doc> = json.decodeFromString(ListSerializer(Doc.serializer()), text)

    fun writeDocs(docs: List<Doc>): String = Json { prettyPrint = false }.encodeToString(ListSerializer(Doc.serializer()), docs)

    /** What ships in this module's resources. */
    class Bundled(
        val primitives: List<Doc>,
        val families: List<Doc>,
        val sounds: List<Doc>,
        val glossary: Glossary,
        val exemplars: List<Exemplar>,
        /** Vector packs by space ([VectorPack.space]); empty when none ship for that embedder. */
        val packs: List<VectorPack>,
    )

    private var cached: Bundled? = null

    /** Reads the bundled corpora (once) and the vector packs shipped for [spaces]. */
    @OptIn(ExperimentalResourceApi::class)
    suspend fun bundled(spaces: Set<String> = emptySet()): Bundled {
        val base = cached ?: Bundled(
            primitives = parseDocs(read("primitives.json")),
            families = parseDocs(read("families.json")),
            sounds = parseDocs(read("sounds.json")),
            glossary = Glossary.parse(read("glossary.json")),
            exemplars = Exemplar.parse(read("exemplars.json")),
            packs = emptyList(),
        ).also { cached = it }
        val packs = spaces.flatMap { space ->
            runCatching { VectorPack.readAll(Res.readBytes("files/retrieval/packs/${packFile(space)}")) }.getOrDefault(emptyList())
        }
        return if (packs.isEmpty()) base else Bundled(base.primitives, base.families, base.sounds, base.glossary, base.exemplars, packs)
    }

    /** File name of the pack bundle for a vector space ("embeddinggemma-2-q8/768/p1" → "embeddinggemma-2-q8_768_p1.trvb"). */
    fun packFile(space: String): String = space.replace('/', '_') + ".trvb"

    @OptIn(ExperimentalResourceApi::class)
    private suspend fun read(name: String): String = Res.readBytes("files/retrieval/$name").decodeToString()
}
