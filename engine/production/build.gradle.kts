plugins {
    alias(libs.plugins.trimio.kmp.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Production: the app's one entry to the automatic engine. A video (or a voice recording) and a
 * prompt in, a stream of states out (stage, progress, preview frames, the jury's panel) and finally
 * the finished MP4. Common code runs [io.trimio.engine.autopilot.Autopilot]; each platform supplies
 * decoding, frames, footage, recognition and export ([ProductionPlatform]): ffmpeg and Skia on
 * desktop, MediaCodec and MediaMuxer on Android.
 */
kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.engine.autopilot)
            api(projects.engine.asr)
            api(projects.engine.models)
            api(projects.engine.llm)
            // Perception add-ons on ONNX Runtime (faces, shots, beats, punctuation, alignment, speakers, text): ships the runtime.
            api(projects.engine.perception)
            api(projects.engine.retrieval)
            // Music moods and beat grids for the editor's sound and cut tools.
            implementation(projects.engine.assets)
            // Hero pictures of explainer films (the CC0 library in the film's look).
            api(projects.engine.illustration)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.io.core)
        }
        // On-device models and whisper.cpp through JNI: shared by Android and desktop.
        val jvmCommonMain by creating { dependsOn(commonMain.get()) }
        jvmMain.get().dependsOn(jvmCommonMain)
        androidMain.get().dependsOn(jvmCommonMain)
        androidMain.dependencies {
            // MediaExtractor/MediaCodec audio decoding.
            implementation(projects.engine.media)
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
        }
    }
}

tasks.withType<Test>().configureEach {
    maxHeapSize = "3g"
    // ExplainerFilmProofTest on a real narration: -Pexplainer.wav=<voice> -Pexplainer.transcript=<words.json> -Pexplainer.out=<dir>.
    listOf("explainer.wav", "explainer.transcript", "explainer.out", "explainer.prompt").forEach { key ->
        providers.gradleProperty(key).orNull?.let { systemProperty(key, it) }
    }
    // SdEngineTest: -Psd.model=<sdxs gguf> / -Psd.klein=<dir> [-Psd.out=dir] [-Psd.only=queue,camel]; DeviceSpeedTestRun: -Pspeed.director=<gguf>
    // [-Pspeed.whisper=<bin>]; both with -Pauto.native=<host build of the native libraries>.
    listOf("sd.model", "sd.klein", "sd.out", "sd.only", "speed.director", "speed.whisper").forEach { key -> providers.gradleProperty(key).orNull?.let { systemProperty(key, it) } }
    // AddOnsOnRealVideosRun: -Paddons.dir=<add-on models> -Paddons.videos=<a.mp4,b.mp4>.
    listOf("addons.dir", "addons.videos").forEach { key -> providers.gradleProperty(key).orNull?.let { systemProperty(key, it) } }
    providers.gradleProperty("auto.native").orNull?.let { systemProperty("trimio.native.dir", it) }
}
