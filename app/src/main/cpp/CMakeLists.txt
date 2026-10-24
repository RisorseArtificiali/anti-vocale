# TASK-853: the Whistle JNI bridge. libneedle.a comes from
# scripts/fetch-whistle-engine.sh (Apache-2.0, Cactus Compute) per ABI;
# x86_64 has no upstream engine, so that ABI builds the stub form instead
# (the library exists, every call reports failure; Kotlin gates on it).
# TASK-854: -PwhistleEngineDisabled (the F-Droid recipe sets it) forces
# the stub form for EVERY ABI: the engine ships only as a prebuilt .a
# with no buildable source upstream, which F-Droid's native-binary policy
# rejects, so the fdroid build carries the bridge-stub and the backend
# answers unavailable there. Reversible the day upstream opens the engine
# source (drop the property from the recipe).
cmake_minimum_required(VERSION 3.22.1)
project("whistle_jni" CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# The bridge source is selected by ABI (and the F-Droid property): both
# files define the same JNI symbol set (pinned by
# WhistleJniSymbolSetTest), exactly one compiles in.
if(WHISTLE_ENGINE_DISABLED)
    set(BRIDGE_SOURCE needle_stubs.cpp)
    set(BRIDGE_LIBS log)
elseif(CMAKE_ANDROID_ARCH_ABI STREQUAL "arm64-v8a" OR CMAKE_ANDROID_ARCH_ABI STREQUAL "armeabi-v7a")
    set(BRIDGE_SOURCE whistle_jni.cpp)
    set(NEEDLE_LIB_DIR "${CMAKE_SOURCE_DIR}/../../../libs/whistle/${CMAKE_ANDROID_ARCH_ABI}")
    if(NOT EXISTS "${NEEDLE_LIB_DIR}/libneedle.a")
        message(FATAL_ERROR "libneedle.a missing for ${CMAKE_ANDROID_ARCH_ABI}: run scripts/fetch-whistle-engine.sh (TASK-853)")
    endif()
    set(BRIDGE_LIBS "${NEEDLE_LIB_DIR}/libneedle.a" log)
else()
    set(BRIDGE_SOURCE needle_stubs.cpp)
    set(BRIDGE_LIBS log)
endif()

add_library(whistle_jni SHARED ${BRIDGE_SOURCE})
target_link_libraries(whistle_jni PRIVATE ${BRIDGE_LIBS})
# 16 KB page-size support: NDK r27 (the pinned ndkVersion) still links
# LOAD segments at 4 KB by default (r28+ defaults to 16 KB), so without
# this the bridge was the one 4 KB-aligned library in the APK (llvm-readelf
# on a 2026-10-20 arm64 build; every prebuilt .so is already 0x4000). A
# 16 KB-page device cannot load such a library, and Play requires 16 KB
# support for API 35+ targets. Harmless on 4 KB devices and 32-bit ABIs.
target_link_options(whistle_jni PRIVATE "-Wl,-z,max-page-size=16384")
target_include_directories(whistle_jni PRIVATE "${CMAKE_SOURCE_DIR}")
