package io.trimio.engine.assets

import io.trimio.core.model.audio.PcmAudio
import io.trimio.engine.audio.AudioAssetSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Every audio asset the app can use, resolved by id: `sfx/<name>` and `music/<mood>` are
 * synthesised on first use and cached for the session. Downloaded packs (phase 10 CDN) plug in
 * through [external], which is consulted first so a recorded asset can override a procedural one.
 */
class AssetLibrary(
    private val rate: Int = 48_000,
    private val external: AudioAssetSource? = null,
) : AudioAssetSource {
    private val sfx = ProceduralSfx(rate)
    private val music = ProceduralMusic(rate)
    private val mutex = Mutex()
    private val audio = mutableMapOf<String, PcmAudio>()
    private val tracks = mutableMapOf<MusicMood, MusicTrack>()

    override suspend fun load(assetId: String): PcmAudio? {
        external?.load(assetId)?.let { return it }
        mutex.withLock { audio[assetId] }?.let { return it }
        val kind = assetId.substringBefore('/')
        val name = assetId.substringAfter('/', "")
        val generated = when (kind) {
            "sfx" -> withContext(Dispatchers.Default) { sfx.generate(name) }
            "music" -> MusicMood.fromId(name)?.let { track(it).audio }
            else -> null
        } ?: return null
        mutex.withLock { audio[assetId] = generated }
        return generated
    }

    /** The bed for [mood] with its beat grid. */
    suspend fun track(mood: MusicMood): MusicTrack {
        mutex.withLock { tracks[mood] }?.let { return it }
        val track = withContext(Dispatchers.Default) { music.render(mood) }
        mutex.withLock { tracks[mood] = track }
        return track
    }

    val effects: List<ProceduralSfx.Effect> get() = sfx.catalog
}
