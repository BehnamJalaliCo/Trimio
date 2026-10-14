plugins {
    id("com.android.library")
}

// The language engine on the phone's GPU (Adreno, OpenCL): libtrimio_llama_gpu.so beside the CPU
// libtrimio_llama.so from :androidApp. LlamaNative loads this one first and falls back to the CPU
// build when the phone has no OpenCL driver (native/CMakeLists.txt, TRIMIO_OPENCL).
android {
    namespace = "io.trimio.gpu"
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
                    // Arm's KleidiAI kernels for Q4_0/Q8_0: each phone gets SME2, SME, i8mm or dotprod
                    // as its CPU reports at run time, so this one build stays safe on 2020 phones.
                    "-DGGML_CPU_KLEIDIAI=ON",
                    "-DGGML_OPENMP=OFF",
                    "-DTRIMIO_WHISPER=OFF",
                    "-DTRIMIO_SD=OFF",
                    "-DTRIMIO_LLAMA=ON",
                    "-DTRIMIO_OPENCL=ON",
                    "-DTRIMIO_LLAMA_TARGET=trimio_llama_gpu",
                )
                cppFlags += listOf("-O3")
                targets += "trimio_llama_gpu"
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
