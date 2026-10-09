package io.trimio.shared.di

import io.trimio.core.model.input.InputSource
import io.trimio.core.model.input.MediaUri
import io.trimio.core.model.input.VideoFormat
import io.trimio.core.model.style.DesignStyle
import io.trimio.core.pipeline.CheckpointStore
import io.trimio.core.pipeline.InMemoryCheckpointStore
import io.trimio.core.pipeline.JobSpec
import io.trimio.core.pipeline.PipelineOrchestrator
import io.trimio.core.pipeline.demo.DemoPipeline
import io.trimio.feature.stream.BuildStreamViewModel
import org.koin.compose.viewmodel.dsl.viewModel
import org.koin.dsl.module

/**
 * Dependency graph shared by every platform. Engine modules will replace [DemoPipeline]
 * with real stages per platform; until then the app runs the simulated pipeline end to end.
 */
val appModule = module {
    single<CheckpointStore> { InMemoryCheckpointStore() }
    factory { PipelineOrchestrator(DemoPipeline.stages(), get()) }
    viewModel { BuildStreamViewModel(get(), demoJob()) }
}

private fun demoJob() = JobSpec(
    id = "demo",
    input = InputSource.Video(MediaUri("demo://sample.mp4"), 42_000, VideoFormat(1080, 1920, 30f)),
    prompt = "یه ویدیوی پرانرژی برای کانال سیگنال، با تأکید روی اعداد",
    styleId = DesignStyle.LiquidGlass.id,
)
