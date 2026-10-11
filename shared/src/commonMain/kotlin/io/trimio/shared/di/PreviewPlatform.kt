package io.trimio.shared.di

import io.ktor.client.HttpClient
import io.trimio.core.data.DeviceInfo
import io.trimio.core.data.DeviceProfile
import io.trimio.core.data.ThermalMonitor
import io.trimio.core.data.MediaPicker
import io.trimio.core.data.Sharer
import io.trimio.core.model.input.MediaInfo
import io.trimio.core.model.input.MediaUri
import io.trimio.engine.llm.MemorySecretStore
import io.trimio.engine.llm.SecretStore
import io.trimio.engine.media.MediaProbe
import io.trimio.engine.production.AutoEditor
import io.trimio.engine.production.Checkpoints
import io.trimio.engine.production.SimulatedAutoEditor
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Services for platforms whose engines are not wired yet (web, iOS): in-memory storage and the
 * simulated pipeline, so the whole UI runs and can be reviewed. Phase 11 replaces it per platform.
 */
fun previewPlatformModule(platform: String, picker: MediaPicker = MediaPicker { null }): Module = module {
    single { AppPaths(projects = null, settingsFile = null, models = null) }
    single<SecretStore> { MemorySecretStore() }
    single { picker }
    single<Sharer> {
        object : Sharer {
            override fun share(uri: String, title: String) = Unit
        }
    }
    single { DeviceInfo(ramGb = 8, platform = platform, appVersion = "preview", previewOnly = true) }
    single<ThermalMonitor> { ThermalMonitor.None }
    single { DeviceProfile.of(8, 8) }
    single<MediaProbe> {
        object : MediaProbe {
            override suspend fun probe(uri: MediaUri) = MediaInfo(durationMs = 9_000, video = null, audio = null)
        }
    }
    single { HttpClient() }
    single<AutoEditor> { SimulatedAutoEditor() }
    single { Checkpoints.None }
}
