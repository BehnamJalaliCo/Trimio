plugins {
    id("com.android.library")
}

// The image generator (stable-diffusion.cpp) on the phone: libtrimio_sd.so beside the director's and Whisper's libraries.
// It is configured alone because it builds on its own pinned ggml (native/CMakeLists.txt, TRIMIO_SD); every symbol but
// its JNI entry points is hidden, so the three engines load side by side.
android {
    namespace = "io.trimio.sd"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
        ndk { abiFilters += "arm64-v8a" }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                    "-DGGML_NATIVE=OFF",
                    "-DGGML_CPU_ARM_ARCH=armv8.2-a+dotprod+fp16",
                    // Arm's KleidiAI kernels for the Q4_0/Q8_0 weights (FLUX.2 klein Q4_0): SME2, SME, i8mm or dotprod at run time.
                    "-DGGML_CPU_KLEIDIAI=ON",
                    "-DGGML_OPENMP=OFF",
                    "-DTRIMIO_WHISPER=OFF",
                    "-DTRIMIO_LLAMA=OFF",
                    "-DTRIMIO_SD=ON",
                )
                cppFlags += listOf("-O3")
                targets += "trimio_sd"
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("../native/CMakeLists.txt")
            version = "4.1.2"
        }
    }
}
