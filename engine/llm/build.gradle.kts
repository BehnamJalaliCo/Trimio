plugins {
    alias(libs.plugins.trimio.kmp.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.engine.models)
            api(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
        }
        // Claude through the official Java SDK, and llama.cpp through JNI: Android + desktop JVM.
        val jvmCommonMain by creating {
            dependsOn(commonMain.get())
            dependencies { implementation(libs.anthropic.java) }
        }
        jvmMain.get().dependsOn(jvmCommonMain)
        androidMain.get().dependsOn(jvmCommonMain)
        jvmTest.dependencies { implementation(libs.ktor.client.mock) }
    }
}

// --- Native integration test: real grammar-constrained generation with a tiny GGUF -------------

val hostNativeDir = layout.buildDirectory.dir("host-native")
val buildHostNative by tasks.registering(Exec::class) {
    group = "native"
    description = "Builds libtrimio_llama for the host so JVM tests can call llama.cpp."
    val src = rootProject.layout.projectDirectory.dir("native").asFile
    val out = hostNativeDir.get().asFile
    inputs.dir(src.resolve("llama"))
    outputs.dir(out)
    commandLine(
        "bash", "-c",
        "cmake -S '$src' -B '$out' -G Ninja -DCMAKE_BUILD_TYPE=Release -DTRIMIO_WHISPER=OFF -DTRIMIO_LLAMA=ON " +
            "&& cmake --build '$out' --target trimio_llama -j 4",
    )
}

/** 230M-parameter model (150 MB): enough to prove the bridge, templates and grammar end to end. */
val llamaTestModel = gradle.gradleUserHomeDir.resolve("trimio-test-models/LFM2.5-230M-Q4_K_M.gguf")
val downloadLlamaTestModel by tasks.registering(Exec::class) {
    group = "native"
    outputs.file(llamaTestModel)
    commandLine(
        "bash", "-c",
        "mkdir -p '${llamaTestModel.parent}' && curl -sSfL -o '$llamaTestModel.part' " +
            "https://huggingface.co/LiquidAI/LFM2.5-230M-GGUF/resolve/main/LFM2.5-230M-Q4_K_M.gguf && mv '$llamaTestModel.part' '$llamaTestModel'",
    )
}

tasks.named<Test>("jvmTest") {
    // ./gradlew :engine:llm:jvmTest -Ptrimio.nativeTests
    if (providers.gradleProperty("trimio.nativeTests").isPresent) {
        dependsOn(buildHostNative, downloadLlamaTestModel)
        systemProperty("trimio.nativeTests", "true")
        systemProperty("trimio.native.dir", hostNativeDir.get().asFile.absolutePath)
        systemProperty("trimio.llama.model", llamaTestModel.absolutePath)
    }
}
