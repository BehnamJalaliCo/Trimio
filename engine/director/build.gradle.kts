plugins {
    alias(libs.plugins.trimio.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            api(projects.core.pipeline)
            api(projects.engine.llm)
            api(projects.engine.styles)
            implementation(projects.engine.assets)
            implementation(projects.engine.asr)
            implementation(libs.kotlinx.serialization.json)
        }
    }
}

tasks.named<Test>("jvmTest") {
    // ./gradlew :engine:director:jvmTest -Ptrimio.nativeTests — the full plan grammar through real llama.cpp.
    if (providers.gradleProperty("trimio.nativeTests").isPresent) {
        dependsOn(":engine:llm:buildHostNative", ":engine:llm:downloadLlamaTestModel")
        systemProperty("trimio.nativeTests", "true")
        systemProperty("trimio.native.dir", project(":engine:llm").layout.buildDirectory.dir("host-native").get().asFile.absolutePath)
        systemProperty("trimio.llama.model", gradle.gradleUserHomeDir.resolve("trimio-test-models/LFM2.5-230M-Q4_K_M.gguf").absolutePath)
    }
}
