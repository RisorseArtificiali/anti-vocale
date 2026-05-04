# Project Index: Anti-Vocale

Generated: 2026-05-01

## Project Overview

On-device voice message transcription for Android. Multiple ASR backends (Whisper, Parakeet, Qwen3, Gemma4/GGUF) run entirely locally via sherpa-onnx and LiteRT-LM. Integrates with Tasker for automation and shares transcriptions to other apps.

**Package:** `com.antivocale.app` | **Language:** Kotlin | **UI:** Jetpack Compose | **DI:** Hilt | **~23K LOC**

## Directory Structure

```
anti-vocale/
├── app/
│   ├── build.gradle.kts          # App module config (SDK, deps, signing)
│   ├── proguard-rules.pro        # R8 keep rules (critical for JNI)
│   ├── libs/sherpa-onnx.aar      # sherpa-onnx ASR library (Git LFS)
│   └── src/main/java/com/antivocale/app/
│       ├── MainActivity.kt       # Single-activity entry point
│       ├── BridgeApplication.kt  # Hilt application class
│       ├── audio/                # Audio preprocessing & VAD
│       ├── data/                 # Preferences, HuggingFace API, downloads
│       ├── di/                   # Hilt DI modules
│       ├── llm/                  # GGUF inference engines (LlamaCpp-based)
│       ├── manager/              # LLM manager orchestration
│       ├── receiver/             # Broadcast receivers (Tasker, share, preload)
│       ├── service/              # Foreground services (inference, extraction)
│       ├── transcription/        # ASR backends & orchestration
│       ├── ui/                   # Compose UI (screens, tabs, viewmodels)
│       ├── benchmark/            # Benchmarking manager
│       └── util/                 # Utilities (WAV, locale, crash, sharing)
├── docs/                         # Build guide, research, scout reports
├── scripts/                      # Install script, Python benchmarks
├── build.gradle.kts              # Root build config
├── settings.gradle.kts           # Module declarations
└── CLAUDE.md                     # Project instructions for Claude
```

## Entry Points

| Entry Point | Path | Purpose |
|---|---|---|
| App | `app/.../MainActivity.kt` | Single activity, Compose host |
| Tasker trigger | `app/.../receiver/TaskerRequestReceiver.kt` | External transcription requests |
| Share target | `app/.../receiver/ShareReceiverActivity.kt` | Android share sheet entry |
| Preload | `app/.../receiver/ModelPreloadReceiver.kt` | Pre-load model via broadcast |
| Inference service | `app/.../service/InferenceService.kt` | Foreground service for transcription |
| Extraction service | `app/.../service/ExtractionService.kt` | Audio extraction from video/attachments |

## Core Modules

### transcription/ (17 files)
ASR backend abstraction and orchestration. Key interfaces and classes:

- `TranscriptionBackend` — Interface for all ASR backends
- `TranscriptionOrchestrator` — Coordinates backend selection, VAD, and result delivery
- `TranscriptionBackendManager` — Registers/loads available backends
- `SherpaOnnxBackend` — sherpa-onnx based (Whisper + Parakeet models)
- `WhisperBackend` / `WhisperModelManager` — Whisper-specific via sherpa-onnx
- `Qwen3AsrBackend` / `Qwen3AsrModelManager` — Qwen3 ASR via sherpa-onnx
- `Gemma4GgufBackend` / `Gemma4GgufModelManager` — Gemma 4 via GGUF/LlamaCpp
- `ParakeetModelManager` — NVIDIA Parakeet models via sherpa-onnx
- `InferenceProvider` — Enum of available inference providers
- `Language` / `LanguageFilter` — Language selection and filtering

### audio/ (2 files)
- `AudioPreprocessor` — Resamples to 16kHz mono, chunks audio for backends
- `VadProcessor` — Voice Activity Detection using Silero VAD

### data/ (12 files)
- `PreferencesManager` / `PreferencesManagerImpl` — App settings (model, language, theme)
- `PerAppPreferencesManager` — Per-app transcription preferences
- `HuggingFaceApiClient` — HuggingFace Hub API for model downloads
- `HuggingFaceAuthManager` / `HuggingFaceTokenManager` — HF OAuth flow
- `ModelDiscovery` — Finds available models on device and remote
- `ModelDownloader` — Downloads and verifies model files
- `ShareTargetManager` — Dynamic share target configuration
- `TranscriptionCalibrator` — Auto-selects best backend per language

### ui/ (23 files)
- `MainScreen.kt` — Tab navigation (Model, Logs, Settings)
- `tabs/ModelTab.kt` — Model selection, download, loading
- `tabs/LogsTab.kt` — Transcription history with swipe actions
- `tabs/SettingsTab.kt` — App settings
- `viewmodel/LogsViewModel.kt` — Transcription log state management
- `viewmodel/ModelViewModel.kt` — Model loading/downloading state
- `components/` — Reusable Compose components (VadAdvisoryCard, PipTranscriptionView, etc.)

### service/ (3 files)
- `InferenceService` — Foreground service, manages transcription lifecycle
- `ExtractionService` — Extracts audio from video/shared files
- `TranscriptionListener` — Callback interface for transcription events

### receiver/ (6 files)
- `TaskerRequestReceiver` — Handles Tasker/broadcast transcription requests
- `ShareReceiverActivity` — Android share intent handler
- `ChooserBroadcastReceiver` — Custom share sheet logic
- `NotificationActionReceiver` — Notification button actions
- `ModelPreloadReceiver` — Pre-load model via broadcast intent
- `BenchmarkActivity` — Runs benchmarks from notification

### llm/ (2 files)
- `GgufInferenceEngine` — GGUF model inference interface
- `LlamaBroEngine` — LlamaCpp-based GGUF engine wrapper

## Test Coverage

- **Unit tests:** 49 files in `app/src/test/`
- **Instrumented tests:** 2 files in `app/src/androidTest/`
- **Key test areas:** TranscriptionOrchestrator (8 test files), AudioPreprocessor, Preferences, Receivers, ViewModels

## Configuration

| File | Purpose |
|---|---|
| `app/build.gradle.kts` | SDK versions, dependencies, signing, ProGuard |
| `build.gradle.kts` | Root plugins (AGP, Kotlin, Compose) |
| `settings.gradle.kts` | Module includes |
| `gradle.properties` | JVM args, AndroidX flags |
| `app/proguard-rules.pro` | R8 keep rules for JNI/native code |
| `keystore.properties` | Signing config (gitignored) |

## Key Dependencies

| Dependency | Purpose |
|---|---|
| sherpa-onnx (AAR) | On-device ASR (Whisper, Parakeet, Qwen3) |
| LiteRT-LM | On-device LLM inference (Gemma multimodal) |
| Jetpack Compose | UI framework |
| Hilt | Dependency injection |
| Room | Local database for transcription logs |
| Silero VAD | Voice Activity Detection |
| OkHttp | Network requests (HuggingFace API) |
