plugins {
    alias(libs.plugins.trimio.kmp.compose)
    alias(libs.plugins.kotlin.serialization)
}

/**
 * Autopilot: one video and one prompt in, a finished motion-graphics edit out, with no hand
 * written score. Understanding (on-device or cloud model), knowledge from the web, a planner that
 * gives a different but always well-made edit for every seed, a critic, preference memory and the
 * mastered soundtrack.
 */
// Bundled data: the Persian word-frequency list behind PersianSpelling (files/spelling, see its NOTICE.md).
compose.resources {
    packageOfResClass = "io.trimio.engine.autopilot.resources"
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.engine.motion)
            api(projects.engine.llm)
            api(projects.engine.audio)
            implementation(projects.engine.assets)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
        }
        jvmMain.dependencies {
            implementation(projects.engine.asr)
            implementation(libs.kotlinx.coroutines.core)
        }
        jvmTest.dependencies {
            implementation(compose.desktop.currentOs)
            implementation(libs.ktor.client.okhttp)
            implementation(libs.ktor.client.mock)
        }
    }
}

// ./gradlew :engine:autopilot:jvmTest -Pauto.video=… -Pauto.prompt=… -Pauto.seeds=1,2,3 (see AutopilotProofTest)
tasks.withType<Test>().configureEach {
    listOf("auto.video", "auto.prompt", "auto.reuse", "auto.vision", "auto.image", "auto.imagetokens", "auto.bench", "auto.format", "auto.mode", "auto.seeds", "auto.llm", "auto.llm.id", "auto.assets", "auto.gold", "auto.transcript", "auto.whisper", "auto.hint", "auto.out", "auto.frames", "auto.video.out", "auto.sheet", "auto.threads", "visuals.dir", "workshop.stills", "workshop.only", "workshop.seeds", "workshop.out", "jury.benchmark", "golden.update", "golden.rebase", "golden.only", "golden.seeds", "golden.styles", "styles.sheets", "styles.only", "aesthetic.out").forEach { key ->
        providers.gradleProperty(key).orNull?.let { systemProperty(key, it) }
    }
    // ArtSheetsTest: -Part.sheets=<dir> [-Part.video=<mp4>] [-Part.styles=a,b,c] (animated pictures fetched and compiled, for review)
    listOf("art.sheets", "art.video", "art.styles", "lottie.live").forEach { key -> providers.gradleProperty(key).orNull?.let { systemProperty(key, it) } }
    // ColdOpenAudioTest: -Pcoldopen.wav=voice.wav -Pcoldopen.transcript=transcript.json [-Pcoldopen.out=dir]
    listOf("coldopen.wav", "coldopen.transcript", "coldopen.out").forEach { key -> providers.gradleProperty(key).orNull?.let { systemProperty(key, it) } }
    // Host builds of libtrimio_llama and libtrimio_whisper (:engine:llm/:engine:asr buildHostNative).
    systemProperty("trimio.native.dir", providers.gradleProperty("auto.native").getOrElse(layout.buildDirectory.dir("host-native").get().asFile.absolutePath))
    maxHeapSize = "4g"
}
