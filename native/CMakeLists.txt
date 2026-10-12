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
# Android GPU variant (:androidGpu): llama.cpp on the Adreno GPU through OpenCL, as its own library
# so a phone without OpenCL simply keeps the CPU one (LlamaNative loads the GPU one when it can).
option(TRIMIO_OPENCL "Build llama.cpp with the OpenCL (Adreno) backend" OFF)
set(TRIMIO_LLAMA_TARGET "trimio_llama" CACHE STRING "Name of the llama.cpp JNI library")

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
endif()

# One ggml for both engines, from llama.cpp (the more recent of the two): whisper.cpp and llama.cpp
# both skip their own copy when a `ggml` target already exists, so their CMake targets never clash.
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
