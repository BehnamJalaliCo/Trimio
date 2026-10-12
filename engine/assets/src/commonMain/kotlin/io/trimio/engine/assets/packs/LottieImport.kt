package io.trimio.engine.assets.packs

import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Turns Lottie animations the creator (or the team) chose into an art pack: each document is
 * checked by the engine's own reader ([validate], the renderer's feature check), tagged from its
 * file name, its layer names and optional keywords (a CSV `file,tags[,tags_fa]`), and written in
 * the pack layout [ContentPacks] reads (`art/index.json`, `art/docs/<key>.json`). Used by the
 * in-app «افزودن انیمیشن» import (a local pack) and, with the same rules, by
 * tools/packs/import_lottie.py (a signed pack).
 */
class LottieImport(
    /** Null when the document can be drawn faithfully, otherwise why not ("text layer", "expression"…). */
    private val validate: (String) -> String?,
    /** The picture set's id in the catalogue (`mine`, `lottiefiles`, `team`). */
    private val source: String = "mine",
    private val title: String = "My animations",
    private val licence: String = OWNER_LICENCE,
) {
    /** One file to import: its name (for keys and tags) and its Lottie JSON. */
    data class Item(val name: String, val json: String, val tags: List<String> = emptyList(), val tagsFa: List<String> = emptyList())

    data class Report(val added: List<String>, val refused: Map<String, String>) {
        val count: Int get() = added.size
    }

    /** The pack's files after adding [items] to [existing] (an earlier import's files, kept), and what was added or refused. */
    fun build(items: List<Item>, existing: Map<String, ByteArray> = emptyMap()): Pair<Map<String, ByteArray>, Report> {
        val rows = existingRows(existing)
        val keys = rows.map { it.jsonArray[1].jsonPrimitive.content }.toMutableSet()
        val files = existing.filterKeys { it.startsWith(DOCS) }.toMutableMap()
        val added = mutableListOf<String>()
        val refused = LinkedHashMap<String, String>()
        for (item in items) {
            val reason = check(item.json)
            if (reason != null) {
                refused[item.name] = reason
                continue
            }
            val key = uniqueKey(keyOf(item.name), keys)
            val tags = (item.tags + tagsOf(item.name) + layerWords(item.json)).map { it.lowercase().trim() }.filter { it.length > 1 }.distinct().take(MAX_TAGS)
            val name = tagsOf(item.name).joinToString(" ").ifEmpty { key }
            rows += buildJsonArray {
                add(JsonPrimitive(source)); add(JsonPrimitive(key)); add(JsonPrimitive(name))
                add(JsonArray(tags.map(::JsonPrimitive))); add(JsonArray(item.tagsFa.map(::JsonPrimitive))); add(JsonPrimitive(0)); add(JsonPrimitive("imported"))
            }
            files["$DOCS$key.json"] = item.json.encodeToByteArray()
            added += key
        }
        files[INDEX] = index(rows).encodeToByteArray()
        return files to Report(added, refused)
    }

    private fun check(text: String): String? = runCatching { Json.parseToJsonElement(text).jsonObject }.fold(
        onSuccess = { o -> if ("layers" !in o) "not a Lottie animation" else validate(text) },
        onFailure = { "not JSON" },
    )

    private fun existingRows(existing: Map<String, ByteArray>): MutableList<JsonElement> = existing[INDEX]?.let { bytes ->
        runCatching { Json.parseToJsonElement(bytes.decodeToString()).jsonObject["art"]?.jsonArray?.toMutableList() }.getOrNull()
    } ?: mutableListOf()

    private fun index(rows: List<JsonElement>): String = buildJsonObject {
        put("v", 1)
        put("sources", buildJsonObject {
            put(source, buildJsonObject {
                put("title", title); put("author", ""); put("licence", licence); put("home", "")
                put("url", "local:$source/{id}"); put("docs", "$DOCS{id}.json")
                put("format", "lottie"); put("w", DEFAULT_BOX); put("h", DEFAULT_BOX); put("loops", true); put("flavour", "emoji")
            })
        })
        put("art", JsonArray(rows))
    }.toString()

    companion object {
        /** The licence id of the creator's own imports (they chose the files; [io.trimio.engine.assets.art.ArtLicence] accepts it). */
        const val OWNER_LICENCE = "LicenseRef-Owner"
        const val INDEX = "art/index.json"
        const val DOCS = "art/docs/"
        private const val MAX_TAGS = 16
        private const val DEFAULT_BOX = 512

        /** Words that name nothing (After Effects' default layer names, numbering). */
        private val NOISE = setOf(
            "layer", "layers", "shape", "shapes", "group", "null", "precomp", "comp", "pre", "outlines", "outline", "ellipse", "rectangle", "rect",
            "fill", "stroke", "transform", "path", "merge", "trim", "copy", "solid", "control", "controller", "obj", "lottie", "animation", "anim",
            "json", "final", "new", "untitled", "the", "and", "with", "for", "of", "a", "an", "v", "lf", "ae",
        )

        /** The key of a file: its name without folders and extension, as lower-case words joined by dashes. */
        fun keyOf(fileName: String): String = tagsOf(fileName).joinToString("-").ifEmpty { "animation" }.take(KEY_MAX)

        /** Words of a file name ("Rocket_Launch-02.json" → rocket, launch). */
        fun tagsOf(fileName: String): List<String> = fileName.substringAfterLast('/').substringAfterLast('\\').substringBeforeLast('.')
            .replace(Regex("([a-z])([A-Z])"), "$1 $2").lowercase().split(Regex("""[^\p{L}]+""")).filter { it.length > 1 && it !in NOISE }

        /** Distinct meaningful words of the animation's layer and asset names. */
        fun layerWords(text: String): List<String> {
            val o = runCatching { Json.parseToJsonElement(text).jsonObject }.getOrNull() ?: return emptyList()
            val names = mutableListOf<String>()
            fun layers(a: JsonElement?) = (a as? JsonArray)?.forEach { l -> (l as? JsonObject)?.get("nm")?.jsonPrimitive?.contentOrNull?.let(names::add) }
            layers(o["layers"])
            (o["assets"] as? JsonArray)?.forEach { a -> layers((a as? JsonObject)?.get("layers")) }
            o["nm"]?.jsonPrimitive?.contentOrNull?.let(names::add)
            return names.flatMap(::tagsOf).groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }.take(MAX_TAGS)
        }

        /** Keywords by file name from a CSV (`file,tags[,tags_fa]`; tags separated by `;` or `|`). */
        fun csvTags(csv: String): Map<String, Pair<List<String>, List<String>>> = csv.lines().drop(1).mapNotNull { line ->
            val cells = line.split(',').map { it.trim().trim('"') }
            if (cells.size < 2 || cells[0].isEmpty()) return@mapNotNull null
            fun split(s: String?) = s.orEmpty().split(';', '|').map { it.trim() }.filter { it.isNotEmpty() }
            cells[0].substringAfterLast('/') to (split(cells[1]) to split(cells.getOrNull(2)))
        }.toMap()

        /** The files of an installed pack folder, to add to (empty when there is none). */
        fun read(dir: String?, fs: FileSystem = SystemFileSystem): Map<String, ByteArray> {
            if (dir == null || !fs.exists(Path(dir))) return emptyMap()
            val out = LinkedHashMap<String, ByteArray>()
            fun walk(p: Path, rel: String) {
                if (fs.metadataOrNull(p)?.isDirectory == true) fs.list(p).forEach { walk(it, if (rel.isEmpty()) it.name else "$rel/${it.name}") }
                else out[rel] = fs.source(p).buffered().use { it.readByteArray() }
            }
            walk(Path(dir), "")
            return out
        }

        private fun uniqueKey(base: String, taken: MutableSet<String>): String {
            var key = base
            var n = 2
            while (key in taken) key = "$base-${n++}"
            taken += key
            return key
        }

        private const val KEY_MAX = 60
    }
}
