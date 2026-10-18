# TASK-853: the Whistle JNI bridge. libneedle.a comes from
# scripts/fetch-whistle-engine.sh (Apache-2.0, Cactus Compute) per ABI;
# x86_64 has no upstream engine, so that ABI builds the stub form instead
# (the library exists, every call reports failure; Kotlin gates on it).
cmake_minimum_required(VERSION 3.22.1)
project("whistle_jni" CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# The bridge source is selected by ABI: both files define the same JNI
# symbols, so exactly one compiles in.
if(CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a" OR CMAKE_ANDROID_ARCH_ABI STREQUAL "armeabi-v7a")
    set(BRIDGE_SOURCE whistle_jni.cpp)
    set(NEEDLE_LIB_DIR "${CMAKE_SOURCE_DIR}/../../../libs/whistle/${CMAKE_ANDROID_ARCH_ABI}")
    if(NOT EXISTS "${NEEDLE_LIB_DIR}/libneedle.a")
        message(FATAL_ERROR "libneedle.a missing for ${CMAKE_ANDROID_ARCH_ABI}: run scripts/fetch-whistle-engine.sh (TASK-853)")
    endif()
else()
    set(BRIDGE_SOURCE needle_stubs.cpp)
endif()

add_library(whistle_jni SHARED ${BRIDGE_SOURCE})

if(CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a" OR CMAKE_ANDROID_ARCH_ABI STREQUAL "armeabi-v7a")
    target_link_libraries(whistle_jni PRIVATE "${NEEDLE_LIB_DIR}/libneedle.a" log)
else()
    target_link_libraries(whistle_jni PRIVATE log)
endif()

target_include_directories(whistle_jni PRIVATE "${CMAKE_SOURCE_DIR}")
