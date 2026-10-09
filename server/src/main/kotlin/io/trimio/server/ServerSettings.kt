package io.trimio.server

import java.nio.file.Path
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Settings from the environment:
 *  - `TRIMIO_DATA_DIR`: where config, models and packs live (default `./data`),
 *  - `TRIMIO_ADMIN_TOKEN`: bearer token for the admin routes (admin routes are off when unset),
 *  - `TRIMIO_PACK_KEYS`: trusted public keys, `keyId=base64Der` separated by commas,
 *  - `PORT`: listen port (default 8080).
 * Private signing keys never reach the server: packs are signed offline and uploaded signed.
 */
data class ServerSettings(val dataDir: Path, val adminToken: String?, val trustedKeys: Map<String, ByteArray>) {
    companion object {
        @OptIn(ExperimentalEncodingApi::class)
        fun fromEnv(env: Map<String, String> = System.getenv()) = ServerSettings(
            dataDir = Path.of(env["TRIMIO_DATA_DIR"] ?: "data"),
            adminToken = env["TRIMIO_ADMIN_TOKEN"]?.takeIf { it.length >= MIN_TOKEN },
            trustedKeys = env["TRIMIO_PACK_KEYS"].orEmpty().split(',').filter { '=' in it }.associate {
                val (id, key) = it.split('=', limit = 2)
                id.trim() to Base64.decode(key.trim())
            },
        )

        const val MIN_TOKEN = 32
    }
}
