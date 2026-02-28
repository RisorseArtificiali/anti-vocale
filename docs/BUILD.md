# Build Guide

This document describes the verified steps to build the Voice Message Reader app with LiteRT-LM multimodal inference support.

## Prerequisites

- **Java**: 17+ (tested with 25.0.2-tem)
- **Gradle**: 8.5+ (tested with 9.3.1)
- **Android SDK**: API 34
- **Kotlin**: 2.1.10 (required for LiteRT-LM compatibility)

## Environment Setup

Ensure you have the correct tools installed via SDKMAN:

```bash
# Install SDKMAN if not already installed
curl -s "https://get.sdkman.io" | bash
source ~/.sdkman/bin/sdkman-init.sh

# Install required versions
sdk install java 25.0.2-tem
sdk install gradle 9.3.1

# Verify versions
java -version
gradle --version
```

## Project Configuration

The project requires specific Kotlin and dependency versions for LiteRT-LM compatibility:

### Root `build.gradle.kts`

```kotlin
plugins {
    id("com.android.application") version "8.3.0" apply false
    id("org.jetbrains.kotlin.android") version "2.1.10" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.10" apply false
}
```

### App `build.gradle.kts`

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    // ... standard config ...

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        jniLibs {
            // Required for LiteRT-LM native libraries
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // LiteRT-LM for multimodal inference (text + audio)
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.9.0-alpha02")

    // MediaPipe GenAI - fallback for text-only inference
    implementation("com.google.mediapipe:tasks-genai:0.10.14")

    // OkHttp for model downloads
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // ... other dependencies ...
}
```

### AndroidManifest.xml

Add GPU native library declarations inside `<application>`:

```xml
<application ...>
    <!-- Native libraries for LiteRT-LM GPU acceleration -->
    <uses-native-library android:name="libvndksupport.so" android:required="false"/>
    <uses-native-library android:name="libOpenCL.so" android:required="false"/>
    ...
</application>
```

## Build Commands

```bash
# Navigate to project directory
cd /path/to/voice_message_reader

# Clean build (recommended after dependency changes)
gradle clean

# Build debug APK
gradle assembleDebug

# Build release APK
gradle assembleRelease
```

## Build Output

After a successful build, the APK is located at:

```
app/build/outputs/apk/debug/app-debug.apk
```

## Install and Test

```bash
# Install on connected device
adb install app/build/outputs/apk/debug/app-debug.apk

# Test text inference via adb
adb shell am broadcast \
  -a com.localai.bridge.PROCESS_REQUEST \
  --es request_type "text" \
  --es prompt "Hello, how are you?" \
  --es task_id "test-001"

# Test audio inference (requires .litertlm model loaded)
adb shell am broadcast \
  -a com.localai.bridge.PROCESS_AUDIO \
  --es file_path "/sdcard/Download/test_audio.m4a" \
  --es prompt "Transcribe this speech:" \
  --es task_id "test-002"
```

## Model Setup

The app requires a `.litertlm` model file for inference. Options:

### Option 1: In-App Download

Use the ModelDownloader in the app's Model tab to download:
- **Gemma 3n E2B** (2.9GB) - Recommended, multimodal (text + audio)
- **Gemma 3n E4B** (4.2GB) - Higher quality, multimodal
- **Gemma3 1B** (557MB) - Lightweight, text only

### Option 2: Manual Download

Download from HuggingFace and push to device:

```bash
# Download Gemma 3n E2B (requires HuggingFace account)
# https://huggingface.co/google/gemma-3n-E2B-it-litert-lm-preview

# Push to device
adb push gemma-3n-E2B-it-litert-lm.q4.litertlm /sdcard/Download/
```

Then load the model in the app's Model tab.

## Troubleshooting

### Kotlin Version Mismatch

**Error**: `Module was compiled with an incompatible version of Kotlin. The binary version of its metadata is 2.2.0`

**Solution**: LiteRT-LM requires Kotlin 2.1+. Ensure `build.gradle.kts` uses:
```kotlin
id("org.jetbrains.kotlin.android") version "2.1.10"
id("org.jetbrains.kotlin.plugin.compose") version "2.1.10"
```

### Compose Compiler Mismatch

**Error**: `This version of the Compose Compiler requires Kotlin version X but you appear to be using Kotlin version Y`

**Solution**: For Kotlin 2.0+, use the Compose plugin instead of `kotlinCompilerExtensionVersion`:
```kotlin
// In root build.gradle.kts
id("org.jetbrains.kotlin.plugin.compose") version "2.1.10" apply false

// In app build.gradle.kts
plugins {
    id("org.jetbrains.kotlin.plugin.compose")
}
// Remove composeOptions { kotlinCompilerExtensionVersion = ... }
```

### Gradle Wrapper Missing

**Error**: `Could not find or load main class org.gradle.wrapper.GradleWrapperMain`

**Solution**: Use system Gradle or regenerate the wrapper:
```bash
gradle wrapper
```

### Native Library Issues

**Error**: GPU acceleration not working

**Solution**: Ensure `AndroidManifest.xml` includes native library declarations and the device supports OpenCL.

## Architecture

```
┌─────────────────────────────────────────────────────────────┐
│  Tasker → BroadcastReceiver → InferenceService              │
│              ↓                                               │
│         AudioPreprocessor (16kHz mono WAV, 30s chunks)      │
│              ↓                                               │
│         LlmManager                                           │
│              ├── LiteRT-LM (com.google.ai.edge.litertlm)    │
│              │   └── Engine + Conversation                   │
│              │   └── Content.AudioBytes() for multimodal    │
│              └── MediaPipe GenAI (text-only fallback)       │
└─────────────────────────────────────────────────────────────┘
```

## References

- [LiteRT-LM GitHub](https://github.com/google-ai-edge/LiteRT-LM)
- [LiteRT-LM Kotlin API Docs](https://github.com/google-ai-edge/LiteRT-LM/blob/main/docs/api/kotlin/getting_started.md)
- [Google Maven - litertlm-android](https://maven.google.com/web/index.html#com.google.ai.edge.litertlm:litertlm-android)
- [Gemma 3n Models](https://huggingface.co/google/gemma-3n-E2B-it-litert-lm-preview)
