# Native engines for Trimio. Built by :androidApp through the NDK (arm64-v8a), by
# :engine:asr:buildHostNative for desktop/JVM integration tests, and by the Windows desktop
# workflow with MSVC (.github/workflows/desktop-windows.yml, docs/DESKTOP.md).
cmake_minimum_required(VERSION 3.22)
project(trimio_native C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)
set(CMAKE_POSITION_INDEPENDENT_CODE ON)
# Each engine is its own shared library with its own static ggml; hiding every symbol except the
# JNI entry points lets whisper.cpp and llama.cpp ship side by side without ggml symbol clashes.
set(CMAKE_C_VISIBILITY_PRESET hidden)
set(CMAKE_CXX_VISIBILITY_PRESET hidden)
set(CMAKE_VISIBILITY_INLINES_HIDDEN ON)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
# Windows: the C runtime is linked statically, so the DLLs need no Visual C++ redistributable.
set(CMAKE_MSVC_RUNTIME_LIBRARY "MultiThreaded$<$<CONFIG:Debug>:Debug>")

option(TRIMIO_WHISPER "Build the whisper.cpp speech engine" ON)
option(TRIMIO_LLAMA "Build the llama.cpp language engine" ON)
# The illustration generator add-on (stable-diffusion.cpp: SDXS-512, FLUX.2 klein). It builds alone (the other engines
# off), on stable-diffusion.cpp's own pinned ggml: its patched ops do not run on llama.cpp's (an assertion in the text
# encoder), and one configure cannot hold two ggml targets. Every symbol but the JNI entry points is hidden, so the
# three libraries load side by side.
option(TRIMIO_SD "Build the stable-diffusion.cpp image engine alone, on its own ggml" OFF)
# Android GPU variants (:androidGpu, :androidSdGpu): llama.cpp or stable-diffusion.cpp on the Adreno GPU through
# OpenCL, each as its own library so a phone without OpenCL simply keeps the processor one.
option(TRIMIO_OPENCL "Build llama.cpp or stable-diffusion.cpp with the OpenCL (Adreno) backend" OFF)
set(TRIMIO_LLAMA_TARGET "trimio_llama" CACHE STRING "Name of the llama.cpp JNI library")
# The image engine's library name: trimio_sd (processor), trimio_sd_gpu (with OpenCL for the Adreno GPU, :androidSdGpu).
set(TRIMIO_SD_TARGET "trimio_sd" CACHE STRING "Name of the stable-diffusion.cpp JNI library")

if(NOT ANDROID)
    find_package(JNI REQUIRED)
endif()

function(trimio_jni_library target)
    if(ANDROID)
        target_link_libraries(${target} PRIVATE log android)
    else()
        target_include_directories(${target} PRIVATE ${JNI_INCLUDE_DIRS})
    endif()
    # GNU ld only; MSVC exports nothing but the JNIEXPORT (dllexport) entry points anyway.
    if(NOT APPLE AND NOT MSVC)
        target_link_options(${target} PRIVATE "-Wl,--exclude-libs,ALL" "-Wl,--gc-sections")
    endif()
endfunction()

if(TRIMIO_OPENCL)
    # Headers and the Khronos loader to link against; at run time the phone's own libOpenCL.so
    # (the vendor driver, declared with <uses-native-library>) answers, so ours is never packaged.
    include(FetchContent)
    FetchContent_Declare(opencl_headers GIT_REPOSITORY https://github.com/KhronosGroup/OpenCL-Headers.git GIT_TAG v2025.07.22)
    FetchContent_Declare(opencl_loader GIT_REPOSITORY https://github.com/KhronosGroup/OpenCL-ICD-Loader.git GIT_TAG v2025.07.22)
    FetchContent_MakeAvailable(opencl_headers)
    set(OPENCL_ICD_LOADER_HEADERS_DIR "${opencl_headers_SOURCE_DIR}" CACHE PATH "" FORCE)
    set(BUILD_TESTING OFF CACHE BOOL "" FORCE)
    set(ENABLE_OPENCL_LAYERS OFF CACHE BOOL "" FORCE)
    # Shared, so the engine needs libOpenCL.so by name and the phone's driver is the one loaded.
    set(BUILD_SHARED_LIBS ON)
    FetchContent_MakeAvailable(opencl_loader)
    set(BUILD_SHARED_LIBS OFF)
    set_target_properties(OpenCL PROPERTIES LIBRARY_OUTPUT_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/opencl-loader"
        C_VISIBILITY_PRESET default)
    # ggml's find_package(OpenCL) then resolves to these instead of searching the NDK.
    set(OpenCL_INCLUDE_DIR "${opencl_headers_SOURCE_DIR}" CACHE PATH "" FORCE)
    set(OpenCL_LIBRARY OpenCL CACHE STRING "" FORCE)
    set(GGML_OPENCL ON CACHE BOOL "" FORCE)
    set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "" FORCE)
    set(GGML_OPENCL_EMBED_KERNELS ON CACHE BOOL "" FORCE)

    # On Adreno a model's kernels hold the GPU the screen draws with: the owner's phone froze for seconds while the
    # speed test drew a picture there. Qualcomm's low priority hint (cl_qcom_priority_hint) lets the screen go first;
    # it goes into ggml's OpenCL context (both copies) only when the device offers it and the driver accepts it.
    function(trimio_opencl_low_priority file)
        if(NOT EXISTS "${file}")
            return()
        endif()
        file(READ "${file}" text)
        set(old [=[cl_context_properties properties[] = { (intptr_t) CL_CONTEXT_PLATFORM, (intptr_t) default_device->platform->id, 0 };]=])
        string(FIND "${text}" "${old}" at)
        if(at EQUAL -1)
            string(FIND "${text}" "cl_qcom_priority_hint" patched)
            if(patched EQUAL -1)
                message(WARNING "OpenCL low priority hint: the context line was not found in ${file}")
            endif()
            return()
        endif()
        set(new [=[cl_context_properties properties[] = { (intptr_t) CL_CONTEXT_PLATFORM, (intptr_t) default_device->platform->id, 0, 0, 0 };
    // Trimio: Qualcomm's low priority hint, so the screen draws first while a model runs; kept only if the driver takes it.
    {
        size_t ext_size = 0;
        if (clGetDeviceInfo(default_device->id, CL_DEVICE_EXTENSIONS, 0, NULL, &ext_size) == CL_SUCCESS && ext_size > 0) {
            std::string ext(ext_size, ' ');
            clGetDeviceInfo(default_device->id, CL_DEVICE_EXTENSIONS, ext_size, &ext[0], NULL);
            if (ext.find("cl_qcom_priority_hint") != std::string::npos) {
                properties[2] = (cl_context_properties) 0x40C9; // CL_CONTEXT_PRIORITY_HINT_QCOM
                properties[3] = (cl_context_properties) 0x40CC; // CL_PRIORITY_HINT_LOW_QCOM
                cl_int hint_err = CL_SUCCESS;
                cl_context probe = clCreateContext(properties, device_ids.size(), device_ids.data(), NULL, NULL, &hint_err);
                if (hint_err == CL_SUCCESS && probe != NULL) {
                    clReleaseContext(probe);
                    GGML_LOG_INFO("ggml_opencl: low priority hint, the screen draws first\n");
                } else {
                    properties[2] = 0;
                    properties[3] = 0;
                }
            }
        }
    }]=])
        string(REPLACE "${old}" "${new}" text "${text}")
        file(WRITE "${file}" "${text}")
    endfunction()
    trimio_opencl_low_priority("${CMAKE_CURRENT_SOURCE_DIR}/third_party/llama.cpp/ggml/src/ggml-opencl/ggml-opencl.cpp")
    trimio_opencl_low_priority("${CMAKE_CURRENT_SOURCE_DIR}/third_party/stable-diffusion.cpp/ggml/src/ggml-opencl/ggml-opencl.cpp")
endif()

# One ggml for both engines, from llama.cpp (the more recent of the two): whisper.cpp and llama.cpp
# both skip their own copy when a `ggml` target already exists, so their CMake targets never clash.
if(TRIMIO_SD AND (TRIMIO_WHISPER OR TRIMIO_LLAMA))
    message(FATAL_ERROR "TRIMIO_SD builds alone (-DTRIMIO_WHISPER=OFF -DTRIMIO_LLAMA=OFF): it takes stable-diffusion.cpp's own ggml")
endif()
if(TRIMIO_WHISPER OR TRIMIO_LLAMA)
    add_subdirectory(third_party/llama.cpp/ggml ggml-build EXCLUDE_FROM_ALL)
endif()

if(TRIMIO_LLAMA)
    set(LLAMA_BUILD_COMMON OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_TOOLS OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    set(LLAMA_BUILD_APP OFF CACHE BOOL "" FORCE)
    set(LLAMA_OPENSSL OFF CACHE BOOL "" FORCE)
    # Sight: the multimodal library alone (vision projectors for Qwen3.5 / Gemma 4), no tools.
    set(LLAMA_BUILD_MTMD ON CACHE BOOL "" FORCE)
    set(MTMD_VIDEO OFF CACHE BOOL "" FORCE)
    add_subdirectory(third_party/llama.cpp llama-build EXCLUDE_FROM_ALL)

    add_library(${TRIMIO_LLAMA_TARGET} SHARED llama/trimio_llama_jni.cpp)
    target_link_libraries(${TRIMIO_LLAMA_TARGET} PRIVATE llama mtmd)
    trimio_jni_library(${TRIMIO_LLAMA_TARGET})
endif()

if(TRIMIO_WHISPER)
    set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    add_subdirectory(third_party/whisper.cpp whisper-build EXCLUDE_FROM_ALL)

    add_library(trimio_whisper SHARED whisper/trimio_whisper_jni.cpp)
    target_link_libraries(trimio_whisper PRIVATE whisper)
    trimio_jni_library(trimio_whisper)
endif()

if(TRIMIO_SD)
    # stable-diffusion.cpp with its own ggml (third_party/stable-diffusion.cpp/ggml, the leejet/ggml commit it pins);
    # no examples, no WebP/WebM, no server.
    set(SD_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(SD_WEBP OFF CACHE BOOL "" FORCE)
    set(SD_WEBM OFF CACHE BOOL "" FORCE)
    set(SD_BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)
    # The GPU variant: sd.cpp's OpenCL backend, made for Adreno and Q4_0 weights (FLUX.2 klein); the processor stays a
    # device of the same library, so the speed test can place each part of the model (text encoder, diffusion, VAE).
    if(TRIMIO_OPENCL)
        set(SD_OPENCL ON CACHE BOOL "" FORCE)
    endif()
    add_subdirectory(third_party/stable-diffusion.cpp sd-build EXCLUDE_FROM_ALL)
    # Only the tokenizer data our picture models read (sd/vocab_lean.cpp): the rest weighed about 26 MB per library.
    get_target_property(SD_SOURCES stable-diffusion SOURCES)
    list(FILTER SD_SOURCES EXCLUDE REGEX "tokenizers/vocab/vocab\\.cpp$")
    set_property(TARGET stable-diffusion PROPERTY SOURCES ${SD_SOURCES} ${CMAKE_CURRENT_SOURCE_DIR}/sd/vocab_lean.cpp)

    add_library(${TRIMIO_SD_TARGET} SHARED sd/trimio_sd_jni.cpp)
    target_link_libraries(${TRIMIO_SD_TARGET} PRIVATE stable-diffusion)
    trimio_jni_library(${TRIMIO_SD_TARGET})
endif()
