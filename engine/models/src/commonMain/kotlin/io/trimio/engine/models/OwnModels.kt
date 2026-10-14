package io.trimio.engine.models

import kotlinx.serialization.Serializable

/**
 * Directors the creator adds from their own disk (desktop, «افزودن مدل از روی سیستم»): any GGUF language model they
 * already have, so a stronger machine can run a bigger director than the catalogue offers. The app keeps where the file
 * is, never a copy; the model's own chat template formats its prompts, and its family's profile (the reasoning switch,
 * the box convention) is read from its file name ([DirectorProfile.of]).
 */
object OwnModels {
    /** The four bytes every GGUF file starts with. */
    val MAGIC: ByteArray = "GGUF".encodeToByteArray()

    /** Smaller files are not directors (a projector, a draft model, a test file). */
    private const val MIN_BYTES = 200L * 1024 * 1024
    private const val PREFIX = "own-"
    private const val FNV_OFFSET = 0xcbf29ce484222325uL
    private const val FNV_PRIME = 0x100000001b3uL
    private const val HEX_DIGITS = 8

    /** Why a file cannot be added as a director (the models screen says it in the creator's language). */
    enum class Refusal { Missing, NotGguf, Projector, TooSmall }

    class RefusedException(val reason: Refusal) : Exception(reason.name)

    /** One own director as kept in the models folder: its id, where its file is, its name and size. */
    @Serializable
    data class Entry(val id: String, val path: String, val name: String, val sizeBytes: Long)

    /** The entry for the file at [path] ([size] bytes, starting with [head]), or why it cannot direct. */
    fun entry(path: String, size: Long, head: ByteArray): Result<Entry> = runCatching {
        val file = fileName(path)
        fun refuse(reason: Refusal): Nothing = throw RefusedException(reason)
        if (!file.endsWith(".gguf", ignoreCase = true) || !head.contentEquals(MAGIC)) refuse(Refusal.NotGguf)
        if ("mmproj" in file.lowercase()) refuse(Refusal.Projector)
        if (size < MIN_BYTES) refuse(Refusal.TooSmall)
        Entry(PREFIX + hash(path), path, file.substringBeforeLast('.'), size)
    }

    /** [e] as a catalogue entry the director choice, the fit check and the models screen read like any other. */
    fun spec(e: Entry): ModelSpec = ModelSpec(
        id = e.id, kind = ModelKind.Language, titleFa = e.name, titleEn = e.name, fileName = fileName(e.path), urls = emptyList(),
        sizeBytes = e.sizeBytes, sha256 = "", tier = DeviceTier.Max, license = "", chatFormat = format(e.name), localPath = e.path,
    )

    fun isOwn(spec: ModelSpec): Boolean = spec.localPath != null

    /** The prompt layout used when the file carries no template llama.cpp knows, by the family in its name. */
    fun format(name: String): ChatFormat {
        val n = name.lowercase()
        return when {
            "gemma-4" in n || "gemma4" in n -> ChatFormat.Gemma4
            "gemma" in n -> ChatFormat.Gemma
            "lfm" in n -> ChatFormat.Lfm
            else -> ChatFormat.ChatMl
        }
    }

    private fun fileName(path: String) = path.substringAfterLast('/').substringAfterLast('\\')

    private fun hash(path: String): String {
        var h = FNV_OFFSET
        for (c in path) h = (h xor c.code.toULong()) * FNV_PRIME
        return h.toString(16).takeLast(HEX_DIGITS).padStart(HEX_DIGITS, '0')
    }
}
