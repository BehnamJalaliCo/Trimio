# Native engines for Trimio. Built by :androidApp through the NDK (arm64-v8a) and by
# :engine:asr:buildHostNative for desktop/JVM integration tests.
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

option(TRIMIO_WHISPER "Build the whisper.cpp speech engine" ON)
option(TRIMIO_LLAMA "Build the llama.cpp language engine" ON)

if(NOT ANDROID)
    find_package(JNI REQUIRED)
endif()

function(trimio_jni_library target)
    if(ANDROID)
        target_link_libraries(${target} PRIVATE log android)
    else()
        target_include_directories(${target} PRIVATE ${JNI_INCLUDE_DIRS})
    endif()
    if(NOT APPLE)
        target_link_options(${target} PRIVATE "-Wl,--exclude-libs,ALL" "-Wl,--gc-sections")
    endif()
endfunction()

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

    add_library(trimio_llama SHARED llama/trimio_llama_jni.cpp)
    target_link_libraries(trimio_llama PRIVATE llama mtmd)
    trimio_jni_library(trimio_llama)
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
