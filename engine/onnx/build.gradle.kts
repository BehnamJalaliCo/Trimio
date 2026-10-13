plugins {
    alias(libs.plugins.trimio.kmp.library)
}

/**
 * ONNX Runtime as Trimio's third engine (next to llama.cpp and whisper.cpp), for the small
 * perception networks llama.cpp cannot run: faces, shots, beats, punctuation, alignment,
 * diarization and text in the picture. Common code sees only [io.trimio.engine.onnx.NeuralEngine];
 * Android and desktop share one implementation over the ORT Java API.
 *
 * The native runtime ships inside the app (Google Play forbids downloading executable code):
 * Android takes the official AAR (CPU, XNNPACK and NNAPI), desktop the official jar (CPU). GPU
 * providers are used when the native library present supports them, never required.
 */
val ortVersion = "1.29.0"

kotlin {
    sourceSets {
        val jvmCommonMain by creating {
            dependsOn(commonMain.get())
            // Same Java API on both platforms: compile against the desktop jar, ship each platform's own.
            dependencies { compileOnly("com.microsoft.onnxruntime:onnxruntime:$ortVersion") }
        }
        jvmMain.get().dependsOn(jvmCommonMain)
        androidMain.get().dependsOn(jvmCommonMain)
        jvmMain.dependencies { implementation("com.microsoft.onnxruntime:onnxruntime:$ortVersion") }
        androidMain.dependencies { implementation("com.microsoft.onnxruntime:onnxruntime-android:$ortVersion") }
    }
}
