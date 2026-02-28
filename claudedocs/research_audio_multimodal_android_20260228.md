# Research Report: Audio Multimodal Inference on Android

**Date:** 2026-02-28
**Depth:** Exhaustive
**Confidence:** High (95%)

---

## Executive Summary

**CRITICAL FINDING:** The MediaPipe Tasks GenAI Android API does NOT support audio input for Gemma 3n. However, **there IS a working solution** using Google's lower-level **LiteRT-LM** library directly with JNI bindings.

### Recommended Solution Path

| Priority | Solution | Feasibility | Effort | Audio Quality |
|----------|----------|-------------|--------|---------------|
| **#1** | **LiteRT-LM + Gemma 3n** | ✅ PROVEN | Medium | Excellent (multimodal understanding) |
| #2 | WhisperKit Android | ✅ Ready SDK | Low | Good (transcription only) |
| #3 | Nvidia Parakeet via ONNX | ✅ Possible | Medium | Excellent (best WER) |

---

## Solution #1: LiteRT-LM Direct (RECOMMENDED)

### What is LiteRT-LM?

LiteRT-LM is Google's lower-level C++ library for on-device LLM inference. Unlike the high-level MediaPipe Tasks GenAI wrapper, LiteRT-LM **DOES support multimodal audio input** on Android.

### Proof of Concept Exists

A developer has already successfully built an Android app called **"AI Scribe"** (available on Play Store) that:
- Runs Gemma 3n E2B fully on-device
- Processes audio voice notes with multimodal understanding
- Uses LiteRT directly via JNI bindings
- Handles the 30-second audio chunk limit with sequential processing

**Source:** [Reddit r/LocalLLaMA](https://www.reddit.com/r/LocalLLaMA/comments/1r77plf/running_gemma_3n_e2b_natively_on_android_via/)

### Technical Details

| Aspect | Details |
|--------|---------|
| **Library** | google-ai-edge/LiteRT-LM |
| **Language** | C++ with Kotlin JNI bindings |
| **Model Format** | `.litertlm` (Gemma 3n E2B/E4B) |
| **Audio Limit** | 30 seconds per chunk (encoder limitation) |
| **Solution** | Sequential chunking + recombination |
| **Platforms** | Android, iOS, macOS, Windows, Linux |

### Implementation Approach

```kotlin
// LiteRT-LM Kotlin API (conceptual)
val engine = LiteRTEngine.builder()
    .setModelPath("/path/to/gemma-3n-E2B.litertlm")
    .setBackend(Backend.GPU)  // or CPU
    .build()

// Process audio chunk
val audioData: ByteArray = loadAudioChunk(audioFile, chunkIndex)
val result = engine.processWithAudio(
    prompt = "Transcribe this speech:",
    audioData = audioData,
    audioFormat = AudioFormat.WAV_16KHZ_MONO
)
```

### Key Files from LiteRT-LM Repository

- `kotlin/` - Kotlin API bindings
- `docs/api/kotlin.md` - Kotlin API documentation
- `docs/api/cpp/conversation.md` - Multimodal conversation API
- `BUILD.miniaudio` - Audio processing support

### Audio Chunking Strategy

```kotlin
// Handle 30-second limit
fun processLongAudio(audioPath: String): String {
    val chunks = AudioPreprocessor.chunkAudio(audioPath, maxSeconds = 30)
    val transcripts = chunks.map { chunk ->
        llmEngine.generateFromAudio("Transcribe:", chunk)
    }

    // Recombine with LLM
    val combined = transcripts.joinToString("\n")
    return llmEngine.generateText("Combine these transcripts into one coherent text: $combined")
}
```

### Pros & Cons

| Pros | Cons |
|------|------|
| ✅ True multimodal (audio + text) | ⚠️ 30-second chunk limit |
| ✅ Uses Gemma 3n as intended | ⚠️ Requires JNI/C++ integration |
| ✅ Google's official library | ⚠️ More complex than MediaPipe Tasks |
| ✅ GPU acceleration supported | ⚠️ Less documentation available |
| ✅ Proven working implementation | |

---

## Solution #2: WhisperKit Android

### Overview

WhisperKit Android by Argmax Inc. is a ready-to-use Android SDK for on-device speech recognition.

### Key Details

| Aspect | Details |
|--------|---------|
| **Repository** | [argmaxinc/WhisperKitAndroid](https://github.com/argmaxinc/WhisperKitAndroid) |
| **License** | MIT |
| **Latest Version** | 0.3.3 (Sep 2025) |
| **Languages** | Kotlin (43.5%), C++ (38.6%) |
| **Stars** | 201+ |

### Gradle Integration

```kotlin
// build.gradle.kts
dependencies {
    // WhisperKit SDK
    implementation("com.argmaxinc:whisperkit:0.3.3")

    // Optional: QNN hardware acceleration for Qualcomm devices
    implementation("com.qualcomm.qnn:qnn-runtime:2.34.0")
    implementation("com.qualcomm.qnn:qnn-litert-delegate:2.34.0")
}

android {
    packaging {
        jniLibs.useLegacyPackaging = true
    }
}
```

### Usage Example

```kotlin
@OptIn(ExperimentalWhisperKit::class)
class SpeechRecognizer(context: Context) {
    private val whisperKit = WhisperKit(context)

    suspend fun transcribe(audioPath: String): String {
        val result = whisperKit.transcribe(audioPath)
        return result.text
    }
}
```

### Pros & Cons

| Pros | Cons |
|------|------|
| ✅ Ready-to-use Kotlin SDK | ⚠️ Transcription only (no multimodal understanding) |
| ✅ Simple Gradle dependency | ⚠️ Larger model size |
| ✅ QNN hardware acceleration | ⚠️ No semantic understanding |
| ✅ MIT license | |
| ✅ Active development | |

---

## Solution #3: Nvidia Parakeet TDT via ONNX

### Overview

Nvidia Parakeet TDT 0.6B V2 is a state-of-the-art ASR model that ranks #3 on Open ASR Leaderboard with excellent performance metrics.

### Performance Metrics

| Metric | Value |
|--------|-------|
| **Parameters** | 600M |
| **WER** | 6.05% (better than Whisper's 10-12%) |
| **RTFx** | 3386 (extremely fast - 3386x realtime) |
| **License** | CC-BY-4.0 |

### ONNX Export Available

Pre-exported ONNX models are available:
- [istupakov/parakeet-tdt-0.6b-v2-onnx](https://huggingface.co/istupakov/parakeet-tdt-0.6b-v2-onnx)
- Includes INT8 quantized versions
- FP16 via TensorRT conversion

### Android Deployment via ONNX Runtime

```kotlin
// ONNX Runtime Android
dependencies {
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.17.0")
}

// Usage
val session = OrtEnvironment.getEnvironment().createSession(
    "parakeet-tdt-0.6b-v2.onnx",
    OrtSession.SessionOptions()
)

// Process audio
val audioFeatures = extractMFCC(audioData)  // Preprocessing needed
val inputName = session.inputNames.iterator().next()
val result = session.run(mapOf(inputName to OnnxTensor.createTensor(env, audioFeatures)))
```

### Pros & Cons

| Pros | Cons |
|------|------|
| ✅ Best-in-class WER | ⚠️ Requires ONNX Runtime integration |
| ✅ Extremely fast inference | ⚠️ Need to implement audio preprocessing (MFCC) |
| ✅ Small model (600M) | ⚠️ More setup complexity |
| ✅ Permissive license | ⚠️ Transcription only |

---

## Comparison Matrix

| Feature | LiteRT-LM + Gemma 3n | WhisperKit | Parakeet ONNX |
|---------|---------------------|------------|---------------|
| **Multimodal** | ✅ Yes | ❌ No | ❌ No |
| **Audio Understanding** | ✅ Full | ❌ STT only | ❌ STT only |
| **WER** | N/A (understanding) | ~10-12% | **6.05%** |
| **Setup Complexity** | Medium | **Low** | Medium |
| **Ready SDK** | ⚠️ JNI needed | ✅ Yes | ⚠️ ONNX Runtime |
| **Hardware Accel.** | ✅ GPU/NPU | ✅ QNN | ✅ NNAPI |
| **Model Size** | 2.9GB (E2B) | ~150-300MB | ~600MB |
| **License** | Apache 2.0 | MIT | CC-BY-4.0 |

---

## Recommended Implementation Plan

### Phase 1: LiteRT-LM Integration (Priority)

1. **Clone and build LiteRT-LM**
   ```bash
   git lfs install
   git clone https://github.com/google-ai-edge/LiteRT-LM.git
   ```

2. **Build Android AAR**
   - Follow `docs/build.md` for Android build instructions
   - Output: `litertlm-android.aar`

3. **Integrate into project**
   - Add AAR to `app/libs/`
   - Update `build.gradle.kts`

4. **Implement JNI wrapper**
   - Use existing Kotlin API from `kotlin/` directory
   - Reference `docs/api/kotlin.md`

5. **Implement audio chunking**
   - 30-second chunk size
   - Sequential processing
   - Recombination with LLM

### Phase 2: Fallback STT (Optional)

If LiteRT-LM proves too complex, add WhisperKit as fallback:
```kotlin
implementation("com.argmaxinc:whisperkit:0.3.3")
```

---

## Sources

1. [LiteRT-LM GitHub Repository](https://github.com/google-ai-edge/LiteRT-LM)
2. [Reddit: Running Gemma 3n E2B natively on Android via LiteRT](https://www.reddit.com/r/LocalLLaMA/comments/1r77plf/)
3. [WhisperKitAndroid GitHub](https://github.com/argmaxinc/WhisperKitAndroid)
4. [Parakeet TDT 0.6B V2 ONNX](https://huggingface.co/istupakov/parakeet-tdt-0.6b-v2-onnx)
5. [Open ASR Leaderboard](https://huggingface.co/spaces/hf-audio/open_asr_leaderboard)
6. [Modal Blog: Top Open Source STT Models 2025](https://modal.com/blog/open-source-stt)

---

## Conclusion

**The audio functionality IS achievable on Android.** The key is to bypass the high-level MediaPipe Tasks GenAI API and use **LiteRT-LM directly** with JNI bindings. This is exactly how the AI Scribe app achieved full multimodal audio support with Gemma 3n.

The implementation requires:
1. Building LiteRT-LM for Android
2. Creating JNI bindings (or using the provided Kotlin API)
3. Implementing 30-second audio chunking
4. Sequential processing with recombination

This approach maintains the original PRD goal of using Gemma 3n for multimodal audio understanding, rather than falling back to simple transcription.
