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
}
