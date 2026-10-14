plugins {
    id("com.android.library")
}

// The image generator on the phone's GPU (Adreno, OpenCL): libtrimio_sd_gpu.so beside the processor's libtrimio_sd.so
// (:androidSd). stable-diffusion.cpp's OpenCL backend is made for Adreno and Q4_0 weights; the processor stays a device
// of this library, so the speed test places each part of the model. A phone without OpenCL keeps the processor library.
android {
    namespace = "io.trimio.sdgpu"
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
                    "-DTRIMIO_OPENCL=ON",
                    "-DTRIMIO_SD_TARGET=trimio_sd_gpu",
                )
                cppFlags += listOf("-O3")
                targets += "trimio_sd_gpu"
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
