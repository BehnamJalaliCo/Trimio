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
                    "-DGGML_OPENMP=OFF",
                    "-DTRIMIO_WHISPER=OFF",
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
