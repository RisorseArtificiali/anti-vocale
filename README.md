<p align="center">
  <a href="https://play.google.com/store/apps/details?id=com.antivocale.app">
    <img alt="Get it on Google Play" src="https://play.google.com/intl/en_us/badges/static/images/badges/en_badge_web_generic.png" width="240"/>
  </a>
</p>

# Anti-Vocale

Android app for transcribing voice messages locally on-device, with no internet required.

Anti-Vocale intercepts shared audio files (from WhatsApp, Telegram, etc.), transcribes them using on-device ASR models, and delivers the result via notification with one-tap copy and share-back actions.

## Features

- **Fully offline** - All processing happens on-device, no data leaves your phone
- **Multiple ASR engines** - Choose between Gemma (LLM), Whisper, or Parakeet TDT models
- **Share integration** - Share audio from any messaging app to transcribe
- **Smart notifications** - Copy result or send it back to the source app with one tap
- **Persistent transcription log** - All transcriptions saved to local database with search
- **Queue-aware processing** - Handles multiple concurrent transcription requests with progress tracking
- **Calibration-based ETA** - Progress estimates improve as the model adapts to your device
- **VAD silence stripping** - Optionally strip silent segments before transcription for faster results
- **Configurable inference threads** - Auto-detects or manually sets thread count for performance tuning
- **Per-app settings** - Configure notification behavior per messaging app
- **Multilingual** - App UI in English and Italian; models support 25+ languages
- **Auto-copy** - Optionally copy transcription to clipboard automatically
- **Tasker/automation support** - Trigger transcription via broadcast intents

## Screenshots

### Transcription Log

<p align="center">
  <img src="docs/screenshots/log_tab.png" width="300" alt="Log tab showing transcription history with search">
</p>

### Model Selection

<p align="center">
  <img src="docs/screenshots/model_tab_top.png" width="300" alt="Model tab showing Gemma, Parakeet and Whisper models">
  <img src="docs/screenshots/model_tab_bottom.png" width="300" alt="Model tab showing Whisper variants and device model picker">
</p>

### Settings

<p align="center">
  <img src="docs/screenshots/settings_tab_top.png" width="300" alt="Settings showing active model, HuggingFace auth, auto-unload timeout">
  <img src="docs/screenshots/settings_tab_mid.png" width="300" alt="Settings showing auto-copy, language, theme options">
  <img src="docs/screenshots/settings_tab_bottom.png" width="300" alt="Settings showing theme, default prompt, per-app settings">
</p>

### Notification with Transcription Result

<p align="center">
  <img src="docs/screenshots/notification.png" width="300" alt="Notification showing transcription with Copy and Send to WhatsApp buttons">
</p>

## Supported Models

### LLM (Multimodal)

| Model | Size | Notes |
|-------|------|-------|
| **Gemma 4 E2B** | 2.6GB | Recommended, newest generation, best for most devices |
| **Gemma 4 E4B** | 3.7GB | Newest generation, higher quality |
| **Gemma 3n E2B** | 3.3GB | Previous generation |
| **Gemma 3n E4B** | 4.2GB | Previous generation |

### ASR (Encoder-Decoder)

| Model | Size | Languages | Notes |
|-------|------|-----------|-------|
| **Whisper Small** | ~358MB | 99 | Only for low-spec devices |
| **Whisper Turbo** | ~988MB | 99 | Near large-v3 quality, best value |
| **Whisper Medium** | ~903MB | 99 | Best for Italian and other languages |
| **Distil Italian** | ~939MB | Italian | Fastest Whisper variant, optimized for Italian |

### ASR (Transducer)

| Model | Size | Languages | Notes |
|-------|------|-----------|-------|
| **Parakeet TDT 0.6B v3** | ~464MB | 25 European | Faster but less accurate |

## Getting Started

### Prerequisites

- Android device with 4GB+ RAM
- Android 8.0 (API 26) or higher
- 500MB+ free storage (model size varies, up to 4.2GB for Gemma 3n E4B / 3.7GB for Gemma 4 E4B)

### Install

Download the latest APK from [Releases](../../releases) or build from source:

```bash
./gradlew installDebug
```

See [docs/BUILD.md](docs/BUILD.md) for detailed build instructions.

### First Use

1. Open Anti-Vocale and go to the **Model** tab
2. Download a model (Whisper Small recommended for first-time setup)
3. Go back to your messaging app, long-press a voice message, and share it to Anti-Vocale
4. The transcription appears in a notification with Copy and Share actions

## Architecture

```
Messaging App (WhatsApp/Telegram/...)
    |
    v  [Share Intent]
ShareReceiverActivity
    |
    v
InferenceService (Foreground Service)
    |
    v
AudioPreprocessor (16kHz mono WAV, 30s chunks)
    |
    v
TranscriptionBackendManager
    |--- SherpaOnnxBackend (Parakeet TDT)
    |--- WhisperBackend (Whisper models)
    |--- LlmTranscriptionBackend (Gemma via LiteRT-LM)
    |
    v
Notification (Copy / Send to [App])
```

## Automation

Anti-Vocale can be triggered via broadcast intents for use with Tasker or other automation tools.

```bash
# Transcribe an audio file
adb shell am broadcast \
  -n com.antivocale.app/.receiver.TaskerRequestReceiver \
  -a com.antivocale.app.PROCESS_REQUEST \
  --es request_type "audio" \
  --es file_path "/sdcard/Download/voice_message.ogg" \
  --es task_id "transcribe_$(date +%s)"

# Preload model into memory
adb shell am broadcast -a com.antivocale.app.PRELOAD_MODEL
```

See [docs/TASKER_GUIDE.md](docs/TASKER_GUIDE.md) for detailed automation setup.

## License

See [LICENSE](LICENSE) for details.
