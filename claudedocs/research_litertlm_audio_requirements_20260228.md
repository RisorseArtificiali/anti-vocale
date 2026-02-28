# Research: LiteRT-LM Audio Input Requirements

## Executive Summary

The app was crashing (SIGSEGV) during audio processing because the `EngineConfig` was missing the required `audioBackend` parameter.

## Key Findings

### 1. Audio Format Requirements

From [Gemini Live API docs](https://docs.cloud.google.com/vertex-ai/generative-ai/docs/multimodal/audio-understanding):
- **Format**: Raw 16-bit PCM audio at **16kHz**, little-endian
- **Channels**: Mono (single channel)
- **Max duration**: 30 seconds per clip (Gemma 3n encoder limit)

From [Gemma 3n documentation](https://developers.googleblog.com/en/introducing-gemma-3n-developer-guide/):
> "At launch time, the Gemma 3n encoder is implemented to process audio clips up to 30 seconds."

### 2. EngineConfig Requirements (CRITICAL)

From [LiteRT-LM Issue #1131](https://github.com/google-ai-edge/LiteRT-LM/issues/1131):

```kotlin
val engineConfig = EngineConfig(
    modelPath = "/path/to/your/model.litertlm",
    backend = Backend.CPU,
    visionBackend = Backend.GPU,
    audioBackend = Backend.CPU,  // <-- REQUIRED for audio processing!
)
```

**Our code was missing `audioBackend = Backend.CPU`**, which caused SIGSEGV when processing audio.

### 3. Content.AudioBytes Usage

From LiteRT-LM documentation:
```kotlin
val multiModalMessage = Message.of(
    Content.AudioBytes(audioBytes),  // ByteArray of raw PCM audio
    Content.Text("Transcribe this speech:"),
)
```

The `Content.AudioBytes` expects a ByteArray of audio data. Based on the format requirements, this should be:
- Raw 16-bit PCM (no WAV header)
- 16kHz sample rate
- Mono channel

### 4. Known Issues

From [LiteRT Issue #5754](https://github.com/google-ai-edge/LiteRT/issues/5754):
- SIGSEGV crashes on Snapdragon 8 Elite (SM8850) with NPU/GPU
- Workaround: Use CPU-only inference (`Backend.CPU`)

## Audio Preprocessing

Our `AudioPreprocessor.kt` correctly:
1. Resamples to 16kHz ✓
2. Converts to mono ✓
3. Outputs as 16-bit PCM ✓
4. Chunks to 30-second segments ✓

However, we were wrapping in WAV format with 44-byte header. The fix should strip this header before sending to LiteRT.

## Fix Required

1. Add `audioBackend = Backend.CPU` to `EngineConfig`
2. Strip WAV header (44 bytes) before sending to `Content.AudioBytes`

## Sources

- [Gemma 3n Developer Guide](https://developers.googleblog.com/en/introducing-gemma-3n-developer-guide/)
- [LiteRT-LM GitHub](https://github.com/google-ai-edge/LiteRT-LM)
- [LiteRT-LM Issue #1131](https://github.com/google-ai-edge/LiteRT-LM/issues/1131)
- [MediaPipe LLM Inference Android](https://ai.google.dev/edge/mediapipe/solutions/genai/llm_inference/android)
- [Google AI Edge Documentation](https://ai.google.dev/edge/litert/android)
