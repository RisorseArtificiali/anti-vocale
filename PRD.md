# PRD: LocalAI Tasker Bridge (Android)

## 1. Project Overview

| Field | Value |
|-------|-------|
| **Name** | LocalAI Tasker Bridge |
| **Package** | `com.localai.bridge` |
| **Description** | A headless (minimal UI) Android application acting as a local inference bridge using Google AI Edge SDK (MediaPipe Generative AI Tasks) to run local, offline LLMs and multimodal models (specifically Gemma 3n) on-device. |
| **Primary Goal** | Expose on-device Speech-to-Text (ASR) and text-generation capabilities to automation tools like Tasker without requiring internet access or cloud APIs. |

## 2. Core Objectives & Priorities

| Priority | Feature | Description |
|----------|---------|-------------|
| **P1** | Speech-to-Text (Audio Scribe) | Ingest audio files from Tasker, process locally using Gemma 3n multimodal model, return transcribed text |
| **P2** | Text-to-Text (LLM Prompts) | Accept text prompts from Tasker, process through local LLM, return generated response |
| **P3** | Tasker Integration | Seamless, low-latency communication via Android Broadcast Intents |

## 3. Technical Stack

| Component | Technology |
|-----------|------------|
| **Language** | Kotlin |
| **UI** | Jetpack Compose (minimal - model selection + debug logs) |
| **Architecture** | Background Service + BroadcastReceiver |
| **AI Framework** | MediaPipe Tasks GenAI (`com.google.mediapipe:tasks-genai`) |
| **Target Model** | Gemma 3n E2B/E4B (multimodal, `.litertlm` format) |
| **Audio Processing** | FFmpegKit Audio (`com.arthenica:ffmpeg-kit-audio:6.0-2`) |
| **Minimum SDK** | Android 8.0 (API 26) |
| **Target Device** | Flagship devices (8GB+ RAM) |

## 4. Key Components

### 4.1 Model Management & Initialization

**Storage:**
- User selects model file (`.litertlm` or `.task`) from device storage (Downloads/Documents)
- Path persisted in SharedPreferences

**Initialization:**
```kotlin
val inferenceOptions = LlmInference.LlmInferenceOptions.builder()
    .setModelPath(modelPath)
    .setMaxTokens(2048)
    .setAudioModelOptions(AudioModelOptions.builder().build())  // Enable audio
    .build()

val sessionOptions = LlmInferenceSessionOptions.builder()
    .setGraphOptions(GraphOptions.builder().setEnableAudioModality(true).build())
    .build()
```

**Memory Management:**
- Model loads on first request (cold start: 2-5 seconds)
- Configurable keep-alive timeout (default: 5 minutes)
- No adaptive memory management - optimized for flagship devices only

### 4.2 Audio Preprocessing Module

**Requirements:**
- Input: Any audio format Tasker can provide (.m4a, .aac, .mp3, .wav, .ogg, .opus, .amr)
- Output: 16kHz mono WAV ByteArray
- Limit: 30 seconds per inference (Gemma 3n constraint)

**Auto-Chunking Strategy:**
```
Audio > 30s → FFmpeg slice into 30s chunks → Sequential inference → Join with spaces → Return combined transcript
```

**Implementation:**
```kotlin
object AudioPreprocessor {
    /**
     * Converts arbitrary audio to 16kHz mono WAV ByteArray.
     * Auto-chunks if duration > 30 seconds.
     *
     * @param inputPath Path to source audio file
     * @param cacheDir Cache directory for intermediate files
     * @return List of WAV ByteArrays (one per chunk)
     */
    fun prepareAudioForMediaPipe(inputPath: String, cacheDir: File): List<ByteArray>

    /**
     * Gets audio duration in seconds using FFprobe
     */
    fun getAudioDuration(inputPath: String): Double
}
```

### 4.3 Tasker Communication Bridge

**Intent Protocol:**

| Direction | Action | Extras |
|-----------|--------|--------|
| Tasker → App | `com.localai.bridge.PROCESS_REQUEST` | `request_type`, `prompt`, `file_path`, `task_id` |
| App → Tasker | `net.dinglisch.android.tasker.ACTION_TASKER_INTENT` | `task_id`, `status`, `result_text`, `error_message` |

**Request Types:**
- `text` - Text-to-text generation
- `audio` - Speech-to-text transcription

**Concurrency Model:**
- FIFO queue for incoming requests
- Sequential processing (single model instance in memory)
- `goAsync()` in BroadcastReceiver for background processing

### 4.4 LlmManager (Singleton)

```kotlin
object LlmManager {
    fun initialize(context: Context, modelPath: String): Result<Unit>
    fun generateText(prompt: String): Result<String>
    fun generateFromAudio(prompt: String, audioChunks: List<ByteArray>): Result<String>
    fun isReady(): Boolean
    fun unload()
}
```

### 4.5 Request Queue Manager

```kotlin
object RequestQueueManager {
    private val queue = ConcurrentLinkedQueue<PendingRequest>()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    fun enqueue(request: PendingRequest)
    private fun processNext()
}
```

## 5. UI Components

### 5.1 Main Screen (Single Activity)

**Tabs:**
1. **Model** - Select/load model file, show status
2. **Logs** - Debug log viewer (last 10 requests)

**Model Tab Features:**
- File picker for model selection
- Load/Unload button
- Status indicator (Ready/Loading/Error)
- Memory usage display

**Logs Tab Features:**
- Scrollable list of request/response entries
- Each entry shows: timestamp, request type, status, result/error
- Tap to expand full details and stack traces

## 6. Error Handling

| Error Type | Handling |
|------------|----------|
| Model not found | Return error broadcast with "MODEL_NOT_FOUND" |
| Model load failed | Return error broadcast with exception message |
| Audio conversion failed | Return error broadcast with "AUDIO_CONVERSION_FAILED" |
| Audio > 30s (before chunking) | Auto-chunk (transparent to user) |
| Inference timeout | Return error broadcast with "INFERENCE_TIMEOUT" |
| Queue full | Return error broadcast with "QUEUE_FULL" |

## 7. Android Manifest Configuration

```xml
<manifest>
    <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE" />
    <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" />
    <uses-permission android:name="android.permission.FOREGROUND_SERVICE" />
    <uses-permission android:name="android.permission.RECEIVE_BOOT_COMPLETED" />

    <application>
        <receiver android:name=".TaskerRequestReceiver"
            android:exported="true">
            <intent-filter>
                <action android:name="com.localai.bridge.PROCESS_REQUEST" />
            </intent-filter>
        </receiver>

        <service android:name=".InferenceService"
            android:exported="false" />
    </application>
</manifest>
```

## 8. Dependencies

```kotlin
// build.gradle.kts (app level)
dependencies {
    // MediaPipe GenAI
    implementation("com.google.mediapipe:tasks-genai:latest.release")

    // FFmpegKit for audio processing
    implementation("com.arthenica:ffmpeg-kit-audio:6.0-2")

    // Jetpack Compose
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // DataStore for preferences
    implementation("androidx.datastore:datastore-preferences:1.0.0")
}
```

## 9. Known Constraints

| Constraint | Impact | Mitigation |
|------------|--------|------------|
| Cold start: 2-5s | First request latency | Document in Tasker setup guide; user can pre-load model |
| 30s audio limit per inference | Long voice messages | Auto-chunking with space-separated concatenation |
| GPU backend only | NPU not utilized | N/A - GPU sufficient for target devices |
| ~2-4GB RAM usage | Low-end devices excluded | Target flagship devices only |
| WAV format required | Conversion overhead | FFmpegKit handles transparently |

## 10. Development Phases

### Phase 1: Project Scaffolding & UI ✅
- Android project structure
- Gradle configuration with all dependencies
- AndroidManifest with permissions
- Jetpack Compose UI (Model tab + Logs tab)
- File picker integration

### Phase 2: MediaPipe Integration
- LlmManager singleton wrapper
- Async initialization with error handling
- Text generation (generateText)
- Model lifecycle (load/unload/timeout)

### Phase 3: Audio Preprocessing
- FFmpegKit integration
- AudioPreprocessor utility
- Duration detection
- Auto-chunking for >30s audio
- WAV ByteArray output

### Phase 4: Integration & Testing
- TaskerRequestReceiver implementation
- RequestQueueManager
- InferenceService
- End-to-end testing with Tasker
- Error handling validation

## 11. Tasker User Guide (End Users)

### Setup
1. Install app, grant storage permissions
2. Download Gemma 3n E2B `.litertlm` from HuggingFace
3. Place model in Downloads folder
4. Open app, select model file, tap "Load"

### Tasker Configuration

**Profile: Voice Message Transcription**
```
Event: Intent Received
Action: com.localai.bridge.PROCESS_REQUEST

Task:
1. Variable Set: %request_type = "audio"
2. Variable Set: %file_path = "/path/to/voice.m4a"
3. Variable Set: %prompt = "Transcribe this speech:"
4. Variable Set: %task_id = "task_123"
5. Intent Send:
   - Action: com.localai.bridge.PROCESS_REQUEST
   - Extra: request_type:%request_type
   - Extra: file_path:%file_path
   - Extra: prompt:%prompt
   - Extra: task_id:%task_id
6. Wait: 10 seconds (adjust based on audio length)
7. Event: Intent Received
   - Action: net.dinglisch.android.tasker.ACTION_TASKER_INTENT
   - Variable: %result_text contains transcription
```

### Expected Latency
| Request Type | Cold Start | Warm |
|--------------|------------|------|
| Text prompt | 3-5s | 0.5-2s |
| 10s audio | 5-8s | 2-4s |
| 30s audio | 8-15s | 5-10s |
| 60s audio (2 chunks) | 15-25s | 10-20s |

---

*Document Version: 1.0*
*Last Updated: 2026-02-28*
