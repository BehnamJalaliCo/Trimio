package io.trimio.engine.assets.packs

import io.trimio.core.model.audio.PcmAudio
import io.trimio.engine.assets.art.ArtCatalogue
import io.trimio.engine.assets.art.ArtEntry
import io.trimio.engine.audio.Resampler
import io.trimio.engine.media.WavCodec
import kotlinx.io.buffered
import kotlin.concurrent.Volatile
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readByteArray
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** An installed content pack: its id, kind (`Art`, `Sound`, `Music`, `Overlay`, `Look`) and the folder of its active release. */
data class PackDir(val id: String, val kind: String, val dir: String)

/** A recorded sound effect of a pack: the cue it voices (`impact`, `whoosh`, …), the palettes it suits and its file. */
data class PackSfx(val id: String, val cue: String, val palettes: Set<String>, val file: String, val tags: List<String>)

/** A recorded music track of a pack. */
data class PackMusic(val id: String, val title: String, val mood: String, val bpm: Float, val seconds: Float, val file: String, val tags: List<String>)

/** An overlay (texture, grain, dust, light leak) or a look (a 3D LUT) of a pack, by file. */
data class PackVisual(val id: String, val name: String, val nameFa: String, val kind: String, val file: String, val blend: String, val tags: List<String>)

/** A picture found online (an animation service the creator connected), with its document. */
data class OnlineHit(val entry: ArtEntry, val document: String)

/**
 * Animated pictures from an online service beyond the open sources (LottieFiles, with the
 * creator's own key): asked only when neither the bundled pictures nor the installed packs show a
 * query well, within the edit's time budget. [fetch] is the edit's budgeted HTTP GET.
 */
fun interface OnlineArt {
    suspend fun search(query: String, limit: Int, fetch: suspend (String) -> String?): List<OnlineHit>

    /** What the service already left on this device for [query] (asked with no network at all); none by default. */
    suspend fun local(query: String, limit: Int): List<OnlineHit> = emptyList()
}

/**
 * The content packs installed on this device, as the engine reads them: the art catalogue rows
 * and documents (merged into the director's picture search, [io.trimio.engine.assets.art.ArtCatalogue]),
 * recorded sound effects (which voice the soundtrack's cues in place of the synthesised ones),
 * recorded music, overlays and looks. The app keeps it in step with its pack manager ([use]);
 * everything is read lazily from disk and keyed by [generation], so a pack installed or removed
 * takes effect at the next edit. With nothing installed every answer is empty: the bundled
 * content decides, exactly as before.
 *
 * Pack layout (built by tools/packs): `art/index.json` (the bundled index format, each source
 * with a `docs` path pattern such as `art/docs/{id}.json` and a `flavour`, `emoji` or `line`),
 * `sound/index.json` (`sfx` and `music` rows), `overlay/index.json`, `look/index.json`.
 */
object ContentPacks {
    private class Snapshot(val dirs: List<PackDir>, val generation: Int, val extra: List<String>)

    // Edits read packs on several threads: every field holds an immutable value and is replaced whole
    // (a race costs at most a second parse of the same files, never a torn read).
    @Volatile private var snapshot = Snapshot(emptyList(), 0, emptyList())
    @Volatile private var art: Art? = null
    @Volatile private var sound: Sound? = null
    @Volatile private var visuals: Pair<List<PackVisual>, List<PackVisual>>? = null
    @Volatile private var decoded: Map<String, PcmAudio> = emptyMap()

    /** The file system packs are read from (tests may swap it). */
    var fs: FileSystem = SystemFileSystem

    /** An online picture service the creator connected (null: none; the open sources are always there). */
    var online: OnlineArt? = null

    /** Changes whenever the installed packs (or the extra catalogues) change. */
    val generation: Int get() = snapshot.generation

    val dirs: List<PackDir> get() = snapshot.dirs

    /** Adopts the installed packs (the app calls it whenever its pack manager's list changes). */
    fun use(dirs: List<PackDir>) = update(dirs, snapshot.extra)

    /**
     * Adds an index document in the art format that ships with the app: catalogue-only rows whose
     * pictures are fetched online when no pack holds them (the pack sets the app knows by name
     * before they are downloaded), or — with a `docs` object of documents by picture id — the
     * in-app core set, drawn with no download at all.
     */
    fun addCatalogue(index: String) = update(snapshot.dirs, snapshot.extra + index)

    private fun update(dirs: List<PackDir>, extra: List<String>) {
        if (dirs == snapshot.dirs && extra == snapshot.extra) return
        snapshot = Snapshot(dirs, snapshot.generation + 1, extra)
        art = null; sound = null; visuals = null
        decoded = emptyMap()
    }

    // ------------------------------------------------------------------------------------- art

    /** [docs]: files of installed packs by picture id; [inline]: documents carried in the app's own catalogues. */
    private class Art(val entries: List<ArtEntry>, val docs: Map<String, String>, val inline: Map<String, String>, val flavours: Map<String, String>)

    /** Every picture of the installed art packs (and the extra catalogues), with the packs' first. */
    fun artEntries(): List<ArtEntry> = artState().entries

    /** The document of picture [id] ("source:key") from an installed pack, or null. */
    fun artDocument(id: String): String? = artState().let { a -> a.docs[id]?.let(::readText) ?: a.inline[id] }

    /** The picture sets of the packs and catalogues in [flavour] (`emoji`: colourful pictures; `line`: drawn icons). */
    fun artSources(flavour: String): Set<String> = artState().flavours.filterValues { it == flavour }.keys

    private fun artState(): Art = art ?: buildArt().also { art = it }

    private fun buildArt(): Art {
        val entries = LinkedHashMap<String, ArtEntry>()
        val docs = HashMap<String, String>()
        val inline = HashMap<String, String>()
        val flavours = HashMap<String, String>()
        for (p in snapshot.dirs) {
            val text = readText(Path(p.dir, ART_INDEX).toString()) ?: continue
            addIndex(text, p.dir, entries, docs, flavours)
        }
        for (text in snapshot.extra) {
            addIndex(text, null, entries, docs, flavours)
            runCatching { json.parseToJsonElement(text).jsonObject["docs"] as? JsonObject }.getOrNull()?.forEach { (id, v) ->
                v.jsonPrimitive.contentOrNull?.let { inline.getOrPut(id) { it } }
            }
        }
        return Art(entries.values.toList(), docs, inline, flavours)
    }

    private fun addIndex(text: String, root: String?, entries: MutableMap<String, ArtEntry>, docs: MutableMap<String, String>, flavours: MutableMap<String, String>) {
        val parsed = runCatching { ArtCatalogue.parse(text) to json.parseToJsonElement(text).jsonObject["sources"]?.jsonObject }.getOrNull() ?: return
        val (catalogue, sources) = parsed
        for ((id, s) in sources.orEmpty()) {
            val o = s as? JsonObject ?: continue
            flavours.getOrPut(id) { o["flavour"]?.jsonPrimitive?.contentOrNull ?: if (o["format"]?.jsonPrimitive?.contentOrNull == "svg") "line" else "emoji" }
        }
        val patterns = sources.orEmpty().mapValues { (_, s) -> (s as? JsonObject)?.get("docs")?.jsonPrimitive?.contentOrNull }
        for (e in catalogue.entries) {
            entries.getOrPut(e.id) { e }
            val pattern = patterns[e.source.id] ?: continue
            if (root != null && e.id !in docs) docs[e.id] = Path(root, pattern.replace("{id}", e.key)).toString()
        }
    }

    // ----------------------------------------------------------------------------------- sound

    private class Sound(val sfx: List<Pair<PackSfx, String>>, val music: List<PackMusic>)

    /** The recorded effects of the installed packs. */
    fun sfx(): List<PackSfx> = soundState().sfx.map { it.first }

    /** The recorded music of the installed packs ([PackMusic.file] is absolute). */
    fun music(): List<PackMusic> = soundState().music

    /**
     * A recorded take of [cue] at [rate] Hz, preferring one made for [palette]; [seed] picks among
     * equals, so a cue played twice need not sound twice the same. Null when no pack voices it.
     */
    fun sfx(cue: String, palette: String?, seed: Int, rate: Int): PcmAudio? {
        val takes = soundState().sfx.filter { it.first.cue == cue }
        if (takes.isEmpty()) return null
        val fitting = takes.filter { palette != null && palette in it.first.palettes }.ifEmpty { takes }
        val file = fitting[((seed % fitting.size) + fitting.size) % fitting.size].second
        return decode(file, rate)
    }

    private fun decode(file: String, rate: Int): PcmAudio? {
        val key = "$file@$rate"
        decoded[key]?.let { return it }
        val bytes = readBytes(file) ?: return null
        val pcm = runCatching { WavCodec.decodeMono(bytes) }.getOrNull() ?: return null
        val out = if (pcm.sampleRate == rate) pcm else Resampler().resample(pcm, rate)
        decoded = (decoded + (key to out)).let { if (it.size > MAX_DECODED) it - it.keys.first() else it }
        return out
    }

    private fun soundState(): Sound = sound ?: buildSound().also { sound = it }

    private fun buildSound(): Sound {
        val sfx = mutableListOf<Pair<PackSfx, String>>()
        val music = mutableListOf<PackMusic>()
        for (p in snapshot.dirs) {
            val o = readText(Path(p.dir, SOUND_INDEX).toString())?.let { t -> runCatching { json.parseToJsonElement(t).jsonObject }.getOrNull() } ?: continue
            o["sfx"]?.jsonArray?.forEach { row ->
                val r = row.jsonObject
                val s = PackSfx(r.str("id"), r.str("cue"), r.list("palettes").toSet(), r.str("file"), r.list("tags"))
                if (s.cue.isNotEmpty() && PackPaths.safe(s.file)) sfx += s to Path(p.dir, s.file).toString()
            }
            o["music"]?.jsonArray?.forEach { row ->
                val r = row.jsonObject
                val file = r.str("file")
                if (PackPaths.safe(file)) {
                    music += PackMusic(r.str("id"), r.str("title"), r.str("mood"), r.num("bpm"), r.num("seconds"), Path(p.dir, file).toString(), r.list("tags"))
                }
            }
        }
        return Sound(sfx, music)
    }

    // --------------------------------------------------------------------------------- visuals

    /** Overlays of the installed packs ([PackVisual.file] is absolute). */
    fun overlays(): List<PackVisual> = visualState().first

    /** Looks (3D LUTs, `.cube`) of the installed packs ([PackVisual.file] is absolute). */
    fun looks(): List<PackVisual> = visualState().second

    private fun visualState(): Pair<List<PackVisual>, List<PackVisual>> =
        visuals ?: (visualRows(OVERLAY_INDEX, "overlays") to visualRows(LOOK_INDEX, "looks")).also { visuals = it }

    private fun visualRows(index: String, key: String): List<PackVisual> = snapshot.dirs.flatMap { p ->
        val o = readText(Path(p.dir, index).toString())?.let { t -> runCatching { json.parseToJsonElement(t).jsonObject }.getOrNull() }
        (o?.get(key) as? JsonArray).orEmpty().mapNotNull { row ->
            val r = row.jsonObject
            val file = r.str("file").takeIf(PackPaths::safe) ?: return@mapNotNull null
            PackVisual(r.str("id"), r.str("name"), r.str("nameFa"), r.str("kind"), Path(p.dir, file).toString(), r.str("blend"), r.list("tags"))
        }
    }

    // ---------------------------------------------------------------------------------- helpers

    private fun readBytes(path: String): ByteArray? = runCatching {
        val p = Path(path)
        if (!fs.exists(p)) null else fs.source(p).buffered().use { it.readByteArray() }
    }.getOrNull()

    private fun readText(path: String): String? = readBytes(path)?.decodeToString()

    private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.contentOrNull.orEmpty()
    private fun JsonObject.num(k: String) = this[k]?.jsonPrimitive?.floatOrNull ?: 0f
    private fun JsonObject.list(k: String) = (this[k] as? JsonArray)?.mapNotNull { it.jsonPrimitive.contentOrNull }.orEmpty()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
    private const val ART_INDEX = "art/index.json"
    private const val SOUND_INDEX = "sound/index.json"
    private const val OVERLAY_INDEX = "overlay/index.json"
    private const val LOOK_INDEX = "look/index.json"
    private const val MAX_DECODED = 48
}

/** Relative paths inside a pack (the same rule as the container's). */
object PackPaths {
    fun safe(name: String): Boolean =
        name.isNotEmpty() && !name.startsWith('/') && '\\' !in name && ':' !in name && name.split('/').none { it.isEmpty() || it == "." || it == ".." }
}
