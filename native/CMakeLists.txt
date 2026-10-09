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
option(TRIMIO_LLAMA "Build the llama.cpp language engine" OFF)

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

if(TRIMIO_WHISPER)
    set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
    set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
    add_subdirectory(third_party/whisper.cpp whisper-build EXCLUDE_FROM_ALL)

    add_library(trimio_whisper SHARED whisper/trimio_whisper_jni.cpp)
    target_link_libraries(trimio_whisper PRIVATE whisper)
    trimio_jni_library(trimio_whisper)
endif()
