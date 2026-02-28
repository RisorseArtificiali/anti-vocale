---
id: task-2
title: Android Share Integration for Audio Transcription
status: To Do
assignee: []
created_date: '2026-02-28'
labels: [enhancement, ux, sharing]
dependencies: []
---

# Android Share Integration for Audio Transcription

## Description

Enable the app to receive audio files via Android's share functionality. Users should be able to share voice messages from WhatsApp, Telegram, or any audio app directly to LocalAI Bridge for transcription.

## User Story

As a WhatsApp user, I want to share a voice message directly to LocalAI Bridge for transcription, so I can quickly convert audio to text without manually saving and selecting files.

## User Flow

```
WhatsApp → Voice Message → Share → Select "LocalAI Bridge" →
  App copies file → Share completes → App starts transcription →
  Notification with result
```

## Requirements

### 1. AndroidManifest.xml Intent Filter
- Add intent filter for ACTION_SEND with audio/* MIME type
- Add intent filter for ACTION_SEND_MULTIPLE for batch sharing
- Handle both content:// URIs and file:// URIs

```xml
<activity android:name=".ShareReceiverActivity"
    android:exported="true"
    android:theme="@style/Theme.Transparent">
    <intent-filter>
        <action android:name="android.intent.action.SEND" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="audio/*" />
    </intent-filter>
    <intent-filter>
        <action android:name="android.intent.action.SEND" />
        <category android:name="android.intent.category.DEFAULT" />
        <data android:mimeType="application/ogg" />
    </intent-filter>
</activity>
```

### 2. ShareReceiverActivity (New)
- Create transparent/no-UI activity for receiving shares
- Extract URI from Intent.EXTRA_STREAM
- Copy file to app storage (synchronous - fast)
- Call setResult() to complete share quickly
- Start InferenceService for transcription (asynchronous)
- Show toast: "Audio received, transcribing..."

### 3. File Handling
- Handle content:// URIs via ContentResolver
- Copy to app-private storage (no permissions needed)
- Generate unique filename with timestamp
- Support formats: .ogg, .m4a, .mp3, .wav, .aac

### 4. Background Processing
- Ensure model is loaded (or load it)
- Start InferenceService with the copied file
- Show progress notification during transcription
- Show result notification when complete

### 5. Result Notification
- Show transcription result in notification
- Tap notification → opens app with full result
- Copy to clipboard action button
- Share result action button

## Technical Implementation

### ShareReceiverActivity.kt (Pseudocode)
```kotlin
class ShareReceiverActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        when (intent?.action) {
            Intent.ACTION_SEND -> handleSingleShare()
            Intent.ACTION_SEND_MULTIPLE -> handleMultipleShare()
        }

        finish() // Close activity quickly
    }

    private fun handleSingleShare() {
        val uri = intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        uri?.let { processSharedAudio(it) }
    }

    private fun processSharedAudio(uri: Uri) {
        // 1. Copy file synchronously (fast)
        val copiedPath = copyToAppStorage(uri)

        // 2. Complete the share
        setResult(RESULT_OK)

        // 3. Start async transcription
        InferenceService.startTranscription(this, copiedPath)

        // 4. Show feedback
        Toast.makeText(this, "Audio received, transcribing...", Toast.LENGTH_SHORT).show()
    }
}
```

## Edge Cases

- Model not loaded → Show notification: "Load model first"
- Multiple files shared → Queue them, process sequentially
- Unsupported format → Show error notification
- File too large (>100MB) → Show warning, offer to proceed anyway
- App not used recently → Model unloaded, auto-load first

## Acceptance Criteria

- [ ] Share from WhatsApp voice message works
- [ ] Share from Telegram voice message works
- [ ] Share from file manager works
- [ ] Share completes quickly (< 2 seconds)
- [ ] Transcription happens in background
- [ ] Result notification is tappable
- [ ] Result can be copied to clipboard
- [ ] Works when screen is locked

## Testing Checklist

1. Share .ogg from WhatsApp
2. Share .m4a from Telegram
3. Share .mp3 from file manager
4. Share when model not loaded
5. Share when app is closed
6. Share multiple files at once
7. Share when low storage

## Related Files
- AndroidManifest.xml (intent filters)
- ShareReceiverActivity.kt (new)
- InferenceService.kt (add startTranscription helper)
- NotificationHelper.kt (new or extend)
