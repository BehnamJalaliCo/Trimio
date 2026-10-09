package io.trimio.shared.di

import io.ktor.client.HttpClient
import io.trimio.core.data.DeviceInfo
import io.trimio.core.data.DeviceProfile
import io.trimio.core.data.ThermalMonitor
import io.trimio.core.data.MediaPicker
import io.trimio.core.data.Sharer
import io.trimio.core.model.input.MediaInfo
import io.trimio.core.model.input.MediaUri
import io.trimio.core.pipeline.PipelineOrchestrator
import io.trimio.core.pipeline.demo.DemoPipeline
import io.trimio.core.pipeline.PipelineStage
import io.trimio.core.pipeline.StageContext
import io.trimio.core.pipeline.StageId
import io.trimio.core.pipeline.StandardArtifacts
import io.trimio.core.model.text.Language
import io.trimio.core.model.timeline.CaptionClip
import io.trimio.core.model.transcript.Transcript
import io.trimio.core.model.transcript.Word
import io.trimio.engine.render.SampleTimelines
import io.trimio.engine.llm.MemorySecretStore
import io.trimio.engine.llm.SecretStore
import io.trimio.engine.media.MediaProbe
import kotlinx.io.files.Path
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Services for platforms whose engines are not wired yet (web, iOS): in-memory storage and the
 * simulated pipeline, so the whole UI runs and can be reviewed. Phase 11 replaces it per platform.
 */
fun previewPlatformModule(platform: String, picker: MediaPicker = MediaPicker { null }): Module = module {
    single { AppPaths(projects = null, settingsFile = null, models = Path("models")) }
    single<SecretStore> { MemorySecretStore() }
    single { picker }
    single<Sharer> {
        object : Sharer {
            override fun share(uri: String, title: String) = Unit
        }
    }
    single { DeviceInfo(ramGb = 8, platform = platform, appVersion = "preview") }
    single<ThermalMonitor> { ThermalMonitor.None }
    single { DeviceProfile.of(8, 8) }
    single<MediaProbe> {
        object : MediaProbe {
            override suspend fun probe(uri: MediaUri) = MediaInfo(durationMs = 9_000, video = null, audio = null)
        }
    }
    single { HttpClient() }
    single<PipelineFactory> {
        object : PipelineFactory {
            override fun build() = PipelineOrchestrator(DemoPipeline.stages().map { if (it.id == StageId.Direction) SampleDirection(it) else it })
            override fun export() = PipelineOrchestrator(DemoPipeline.stages().takeLast(2))
        }
    }
}

/** The simulated Director also hands over the sample edit, so the editor and export screens have content. */
private class SampleDirection(private val inner: PipelineStage) : PipelineStage {
    override val id = StageId.Direction
    override suspend fun run(context: StageContext) {
        inner.run(context)
        val timeline = SampleTimelines.cryptoSignal()
        context.artifacts[StandardArtifacts.Timeline] = timeline
        context.artifacts[StandardArtifacts.Style] = SampleTimelines.previewStyle
        context.artifacts[StandardArtifacts.Transcript] = Transcript(
            Language.Persian,
            timeline.clipsOf<CaptionClip>().map { Word(it.text, it.range, language = it.language, emphasis = it.emphasis) },
        )
    }
}
