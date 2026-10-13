package io.trimio.engine.retrieval

import io.trimio.core.model.text.TextKey
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** What kind of thing the creator taught the app. */
@Serializable
enum class MemoryKind {
    /** A term and its spelling (the creator's own glossary). */
    Term,

    /** A brand, product, channel or person to spell and show the creator's way. */
    Brand,

    /** One of the creator's own pictures or clips, described. */
    Asset,

    /** A word or claim never to show. */
    Banned,

    /** A correction the creator made to the screen text: [MemoryEntry.text] was wrong, [MemoryEntry.note] right. */
    Correction,

    /** How the creator wants to sound or look ("calm, no slang", "always end with the channel name"). */
    Tone,
}

/** One remembered thing; [pinned] ones are always included, whatever the sentence. */
@Serializable
data class MemoryEntry(
    val kind: MemoryKind,
    val text: String,
    val note: String = "",
    val domain: String = "",
    val pinned: Boolean = false,
    /** Asset entries: the media id the edit uses. */
    val ref: String = "",
)

/** Reads and writes the memory's JSON (a file in the app's data folder; in memory for tests). */
interface MemoryStore {
    suspend fun read(): String?

    suspend fun write(text: String)

    class InMemory(private var text: String? = null) : MemoryStore {
        override suspend fun read(): String? = text

        override suspend fun write(text: String) {
            this.text = text
        }
    }
}

/**
 * Brand and user memory: the creator's glossary, brands, assets, banned words, corrections and
 * tone, kept on the device and searched exactly first (a remembered spelling always wins), by
 * meaning second. It grows as the creator works: every correction made in the editor is one more
 * entry ([remember]).
 */
class UserMemory(private val store: MemoryStore) {
    @Serializable
    private data class File(val version: Int = 1, val entries: List<MemoryEntry> = emptyList())

    private val lock = Mutex()
    private var cached: List<MemoryEntry>? = null

    suspend fun entries(): List<MemoryEntry> = lock.withLock { load() }

    private suspend fun load(): List<MemoryEntry> = cached ?: (
        store.read()?.let { runCatching { json.decodeFromString(File.serializer(), it).entries }.getOrNull() }.orEmpty()
        ).also { cached = it }

    /** Adds [entry], replacing one of the same kind and text (case, letter forms and half-spaces aside). */
    suspend fun remember(entry: MemoryEntry) = lock.withLock {
        val key = TextKey.of(entry.text)
        val kept = load().filterNot { it.kind == entry.kind && TextKey.of(it.text) == key }
        val next = (kept + entry).takeLast(MAX_ENTRIES)
        cached = next
        store.write(json.encodeToString(File.serializer(), File(entries = next)))
    }

    suspend fun forget(kind: MemoryKind, text: String) = lock.withLock {
        val key = TextKey.of(text)
        val next = load().filterNot { it.kind == kind && TextKey.of(it.text) == key }
        cached = next
        store.write(json.encodeToString(File.serializer(), File(entries = next)))
    }

    /** Terms, brands and corrections as a glossary (corrections: the wrong spelling is a variant of the right one). */
    suspend fun glossary(): Glossary = Glossary(
        entries().mapNotNull { e ->
            when (e.kind) {
                MemoryKind.Term, MemoryKind.Brand -> Term(e.text, e.domain.ifEmpty { "user" }, note = e.note)
                MemoryKind.Correction -> e.note.takeIf { it.isNotBlank() }?.let { Term(it, e.domain.ifEmpty { "user" }, variants = listOf(e.text)) }
                else -> null
            }
        },
    )

    /** Words never to show (folded keys). */
    suspend fun banned(): Set<String> = entries().filter { it.kind == MemoryKind.Banned }.map { TextKey.of(it.text) }.toSet()

    /** Every entry as a searchable document. */
    suspend fun docs(): List<Doc> = entries().mapIndexed { i, e ->
        Doc("memory:$i", "memory-${e.kind.name.lowercase()}", e.text, e.note, "", emptyList(), mapOf("ref" to e.ref, "pinned" to e.pinned.toString()))
    }

    /** Entries that always go to the director: pinned ones, the tone, banned words. */
    suspend fun pinned(): List<MemoryEntry> = entries().filter { it.pinned || it.kind == MemoryKind.Tone || it.kind == MemoryKind.Banned }

    private companion object {
        const val MAX_ENTRIES = 2000
        val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }
    }
}
