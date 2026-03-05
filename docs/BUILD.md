# Build Guide

This document describes the verified steps to build the Anti-Vocale app with LiteRT-LM multimodal inference support.

## Prerequisites

- **Java**: 25.0.2-tem (via SDKMAN) - **Required version**
- **Gradle**: 9.3.1 (via SDKMAN) - **Do NOT use gradlew**
- **Android SDK**: API 34
- **adb**: Must be in PATH
- **Kotlin**: 2.1.10 (required for LiteRT-LM compatibility)

## Quick Start (One-Liner Build)

```bash
source ~/.sdkman/bin/sdkman-init.sh && sdk use java 25.0.2-tem && sdk use gradle 9.3.1 && export PATH="$HOME/Android/Sdk/platform-tools:$PATH" && gradle assembleDebug
```

## Environment Setup

Ensure you have the correct tools installed via SDKMAN:

```bash
# Install SDKMAN if not already installed
curl -s "https://get.sdkman.io" | bash
source ~/.sdkman/bin/sdkman-init.sh

# Install required versions
sdk install java 25.0.2-tem
sdk install gradle 9.3.1

# Activate correct tool versions
sdk use java 25.0.2-tem
sdk use gradle 9.3.1

# Add adb to PATH (required for install/deploy)
export PATH="$HOME/Android/Sdk/platform-tools:$PATH"

# Verify versions
java -version      # Should show 25.0.2-tem
gradle --version   # Should show 9.3.1
adb version        # Should not error
```

## Common Mistakes to Avoid

### 1. Using `./gradlew` Instead of System Gradle

**Don't do this:**
```bash
./gradlew assembleDebug  # ❌ WRONG - may fail with cryptic errors
```

**Do this instead:**
```bash
gradle assembleDebug     # ✅ CORRECT - uses SDKMAN-configured Gradle
```

**Why:** The gradle wrapper uses the system's default Java, not the SDKMAN-configured version. This can cause cryptic errors like `25.0.2` without clear context.

### 2. adb Not in PATH

**Error:** `adb: command not found`

**Fix:**
```bash
export PATH="$HOME/Android/Sdk/platform-tools:$PATH"
```

### 3. Multiple Devices Connected

**Error:** `adb: more than one device/emulator`

**Fix:** Specify device with `-s` flag:
```bash
adb devices                    # List connected devices
adb -s <device-id> install ... # Install to specific device
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

## Audio File Transcription via ADB

This section describes how to push an audio file to the device and trigger transcription remotely via ADB.

### Prerequisites

1. **ADB connected** to the device (USB or WiFi)
2. **Model loaded** in the app (check that status shows "Ready")
3. **App running in foreground** (required for foreground service on Android 12+)

### Step 1: Connect ADB (WiFi)

If connecting over WiFi, pair and connect:

```bash
# Pair the device (use the pairing code from Developer Options → Wireless Debugging)
adb pair <IP>:<pairing_port> <pairing_code>

# Example:
adb pair 192.168.20.174:35643 447549
```

Verify connection:
```bash
adb devices
# Should show: adb-<device-id>._adb-tls-connect._tcp	device
```

### Step 2: Push Audio File to Device

Push the audio file to the device's Download folder:

```bash
adb push /path/to/audio.ogg /sdcard/Download/

# Example:
adb push ~/Downloads/voice_message.ogg /sdcard/Download/
```

**Supported formats:** OGG, M4A, MP3, WAV, FLAC, OPUS (most common audio formats)

### Step 3: Launch App (Required)

The app must be in the foreground for the transcription service to start (Android 12+ restriction):

```bash
adb shell am start -n com.localai.bridge/.MainActivity
```

### Step 4: Trigger Transcription

Send a broadcast intent to start transcription:

```bash
adb shell am broadcast \
  -n com.localai.bridge/.receiver.TaskerRequestReceiver \
  -a com.localai.bridge.PROCESS_REQUEST \
  --es request_type "audio" \
  --es file_path "/sdcard/Download/voice_message.ogg" \
  --es prompt "Transcribe this voice message:" \
  --es task_id "transcribe_$(date +%s)"
```

**Parameters:**
- `request_type`: Must be `"audio"` for transcription
- `file_path`: Absolute path to the audio file on device
- `prompt`: Optional instruction for the model (defaults to "Transcribe this speech:")
- `task_id`: Unique identifier for tracking the request

### Step 5: Monitor Progress

Watch the transcription progress in logcat:

```bash
# Filter by app process
adb logcat -v time --pid=$(adb shell pidof com.localai.bridge) | grep -E "InferenceService|LlmManager|AudioPrepro|chunk"

# Example output:
# AudioPreprocessor: Extracted 2517552 bytes of PCM audio
# AudioPreprocessor: Audio duration: 78.6735s
# AudioPreprocessor: Created chunk 0: 480000 samples
# InferenceService: Audio preprocessed: 3 chunks, 78.6735s
# InferenceService: Processing chunk 1/3
# LlmManager: Processing audio: 960044 bytes with backend: LITERT_LM
# LlmManager: LiteRT audio processing complete: 392 chars
# InferenceService: Processing chunk 3/3
# InferenceService: Audio transcription complete: 1006 chars
```

### Step 6: View Results

The transcription result appears in:

1. **App's Logs tab** - Open the app → Logs tab → tap the entry to see full text
2. **Broadcast reply** - Sent to `net.dinglisch.android.tasker.ACTION_TASKER_INTENT` (for Tasker integration)

### Complete One-Liner

For convenience, here's a complete command sequence:

```bash
# Push file, launch app, and trigger transcription
FILE="/sdcard/Download/voice_message.ogg" && \
adb push ~/Downloads/voice_message.ogg /sdcard/Download/ && \
adb shell am start -n com.localai.bridge/.MainActivity && \
sleep 1 && \
adb shell am broadcast \
  -n com.localai.bridge/.receiver.TaskerRequestReceiver \
  -a com.localai.bridge.PROCESS_REQUEST \
  --es request_type "audio" \
  --es file_path "$FILE" \
  --es task_id "transcribe_$(date +%s)"
```

### Troubleshooting

**Error: `ForegroundServiceStartNotAllowedException`**
- The app must be in the foreground when the broadcast is sent
- Solution: Launch the app first with `adb shell am start -n com.localai.bridge/.MainActivity`

**Error: `NO_MODEL_CONFIGURED`**
- No model has been selected in the app
- Solution: Open the app → Model tab → Select and load a model

**Error: `MODEL_NOT_FOUND`**
- The model file was moved or deleted
- Solution: Re-select the model in the app

**No transcription output**
- Check the audio file format is supported
- Check the model supports audio (Gemma 3n E2B/E4B, not text-only models)
- Check logcat for preprocessing errors

## Auto-Load and Preload

The app supports automatic model loading and explicit preloading:

### Auto-Load (Transparent)

When a request is received and the model is not in memory, the app automatically loads the configured model from saved preferences. This means:

- **No manual intervention required** - just send requests
- **First request has latency** - model loads before processing
- **Subsequent requests are fast** - model stays in memory until timeout

### Explicit Preload

To avoid first-request latency, you can preload the model before sending content:

```bash
# Via ADB
adb shell am broadcast -a com.localai.bridge.PRELOAD_MODEL

# With multiple devices
adb -s <device-id> shell am broadcast -a com.localai.bridge.PRELOAD_MODEL
```

**Via Tasker:**
```
Action: Send Intent
  Action: com.localai.bridge.PRELOAD_MODEL
```

**Use case:** Trigger preload when connecting to WiFi, opening a specific app, or via NFC tag to have the model ready before you need it.

### Auto-Unload Settings

The model automatically unloads after a period of inactivity to free memory. Configure the timeout in:

**App → Settings tab → Auto-Unload Timeout**

Options: 1 minute, 2 minutes, 5 minutes (default), 10 minutes, 15 minutes, 30 minutes, 1 hour

## Model Setup

The app requires a `.litertlm` model file for inference. Options:

### Option 1: Google AI Edge Gallery (Recommended - Free & Easy)

**This is the easiest way to get a working model for free.**

1. **Install Google AI Edge Gallery** from the Play Store:
   - [Google AI Edge Gallery](https://play.google.com/store/apps/details?id=com.google.ai.edge.gallery)

2. **Download Gemma 3n E2B** inside the Gallery app:
   - Open Gallery → Models → Gemma 3n E2B → Download
   - This downloads the model to the Gallery's private storage

3. **Copy model to accessible location** (Gallery's storage is protected on Android 11+):
   ```bash
   # Find the model in Gallery's storage
   adb shell "find /sdcard/Android/data/com.google.ai.edge.gallery -name '*.litertlm' 2>/dev/null"

   # Example output:
   # /sdcard/Android/data/com.google.ai.edge.gallery/files/Gemma_3n_E2B_it/<hash>/gemma-3n-E2B-it-int4.litertlm

   # Copy to Downloads folder
   adb shell "cp /sdcard/Android/data/com.google.ai.edge.gallery/files/Gemma_3n_E2B_it/*/gemma-3n-E2B-it-int4.litertlm /sdcard/Download/"
   ```

4. **In the app**, tap "Select Model" and navigate to `/sdcard/Download/gemma-3n-E2B-it-int4.litertlm`

**Why this works:** Google AI Edge Gallery downloads a pre-optimized, quantized model that works with LiteRT-LM. The model is ~3.6GB and supports multimodal inference (text + audio).

### Option 2: HuggingFace Download (Alternative)

Download directly from HuggingFace (requires account and license agreement):

```bash
# Download Gemma 3n E2B LiteRT-LM model
# https://huggingface.co/google/gemma-3n-E2B-it-litert-lm

# Push to device
adb push gemma-3n-E2B-it-int4.litertlm /sdcard/Download/
```

### Option 3: In-App Download (Coming Soon)

Future versions will support direct download from HuggingFace within the app.

### Model Requirements

For **audio transcription**, you need a **multimodal model** that supports audio:
- ✅ **Gemma 3n E2B** (3.6GB) - Recommended
- ✅ **Gemma 3n E4B** (4.2GB) - Higher quality, more RAM needed
- ❌ **Gemma3 1B** - Text only, no audio support

The model file must have `.litertlm` extension for LiteRT-LM multimodal inference.

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
