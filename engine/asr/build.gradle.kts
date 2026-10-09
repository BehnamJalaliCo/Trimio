plugins {
    alias(libs.plugins.trimio.kmp.library)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            api(projects.core.model)
            api(projects.core.pipeline)
            api(projects.engine.audio)
        }
        jvmTest.dependencies {
            implementation(projects.engine.media)
        }
        // JNI bridge to whisper.cpp, shared by Android (NDK build in :androidApp) and desktop JVM (host build).
        val jvmCommonMain by creating { dependsOn(commonMain.get()) }
        jvmMain.get().dependsOn(jvmCommonMain)
        androidMain.get().dependsOn(jvmCommonMain)
    }
}

// --- Host build of the native library, for desktop and JNI integration tests -------------------

val hostNativeDir = layout.buildDirectory.dir("host-native")
val buildHostNative by tasks.registering(Exec::class) {
    group = "native"
    description = "Builds libtrimio_whisper for the host so JVM tests can call whisper.cpp."
    val src = rootProject.layout.projectDirectory.dir("native").asFile
    val out = hostNativeDir.get().asFile
    inputs.dir(src.resolve("whisper"))
    outputs.dir(out)
    commandLine(
        "bash", "-c",
        "cmake -S '$src' -B '$out' -G Ninja -DCMAKE_BUILD_TYPE=Release -DTRIMIO_WHISPER=ON -DTRIMIO_LLAMA=OFF " +
            "&& cmake --build '$out' --target trimio_whisper -j 4",
    )
}

/** Small multilingual model for integration tests; downloaded once into the Gradle user home. */
val whisperTestModel = gradle.gradleUserHomeDir.resolve("trimio-test-models/ggml-tiny.bin")
val downloadWhisperTestModel by tasks.registering(Exec::class) {
    group = "native"
    // Declared output: Gradle skips the download once the file exists.
    outputs.file(whisperTestModel)
    commandLine(
        "bash", "-c",
        "mkdir -p '${whisperTestModel.parent}' && curl -sSfL -o '$whisperTestModel.part' " +
            "https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-tiny.bin && mv '$whisperTestModel.part' '$whisperTestModel'",
    )
}

tasks.named<Test>("jvmTest") {
    // Native tests run only when explicitly requested: ./gradlew :engine:asr:jvmTest -Ptrimio.nativeTests
    if (providers.gradleProperty("trimio.nativeTests").isPresent) {
        dependsOn(buildHostNative, downloadWhisperTestModel)
        systemProperty("trimio.nativeTests", "true")
        systemProperty("trimio.native.dir", hostNativeDir.get().asFile.absolutePath)
        systemProperty("trimio.whisper.model", whisperTestModel.absolutePath)
        systemProperty("trimio.whisper.sample", rootProject.file("native/third_party/whisper.cpp/samples/jfk.wav").absolutePath)
    }
}
