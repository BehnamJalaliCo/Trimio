package io.trimio.engine.llm

/**
 * Where the user's own API keys live. Android encrypts them with a non-exportable Android Keystore
 * key; keys never leave the device except in the Authorization header of the provider they belong to.
 */
interface SecretStore {
    suspend fun read(name: String): String?
    suspend fun write(name: String, value: String)
    suspend fun delete(name: String)
}

/** In-memory store for tests and for platforms whose secure store is not wired yet. */
class MemorySecretStore : SecretStore {
    private val values = mutableMapOf<String, String>()
    override suspend fun read(name: String) = values[name]
    override suspend fun write(name: String, value: String) { values[name] = value }
    override suspend fun delete(name: String) { values.remove(name) }
}
