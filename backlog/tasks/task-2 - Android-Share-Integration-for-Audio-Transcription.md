---
id: task-2
title: Android Share Integration for Audio Transcription
status: Done
assignee:
  - claude
created_date: '2026-02-28'
updated_date: '2026-03-01 08:16'
labels:
  - enhancement
  - ux
  - sharing
dependencies: []
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Enable users to share audio files from other apps (WhatsApp, Telegram, file managers) directly to LocalAI Bridge for transcription.

## User Story

As a user, I want to share a voice message from any app to LocalAI Bridge, so I can transcribe it without manually saving and importing files.

## Value

- Reduces friction for voice message transcription
- Integrates with common messaging workflows
- Enables hands-free operation via share menu

## Technical Constraints

**Sender App Identification**: Android does NOT reliably provide sender app metadata (package name, app name) with ACTION_SEND intents. Methods like `getCallingPackage()` return null because share intents use `startActivity()` not `startActivityForResult()`. The `getReferrer()` method may sometimes return the sender's package but is unreliable and should not be depended upon for core functionality.

**Implication**: The app cannot reliably distinguish between WhatsApp, Telegram, or other sources. Focus on handling the audio content itself rather than source-specific behavior.
<!-- SECTION:DESCRIPTION:END -->

# Android Share Integration for Audio Transcription

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 [Discovery] LocalAI Bridge appears in Android share sheet for audio files
- [ ] #2 [Quick Handoff] Share action completes and returns to source app promptly
- [ ] #3 [Background Processing] Transcription continues after share completes
- [ ] #4 [Result Access] User can view transcription result from notification
- [ ] #5 [Copy Result] User can copy transcription to clipboard from notification

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
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
# Implementation Plan

## Architecture Overview

Reuse existing infrastructure:
- **InferenceService** - Full transcription capability exists
- **AudioPreprocessor** - Supports MP3, M4A, OGG, WAV, AAC, 3GP, FLAC
- **Permissions** - All required (FOREGROUND_SERVICE, POST_NOTIFICATIONS, READ_MEDIA_AUDIO)

## Files to Create/Modify

| File | Action | Purpose |
|------|--------|---------|
| `receiver/ShareReceiverActivity.kt` | CREATE | Transparent activity for share intent |
| `util/SharedAudioHandler.kt` | CREATE | Content URI to file handling |
| `receiver/NotificationActionReceiver.kt` | CREATE | Handle notification copy action |
| `AndroidManifest.xml` | MODIFY | Add intent filters, register activities |
| `service/InferenceService.kt` | MODIFY | Add shared audio entry point, result notifications |

## Subtask Execution Order

1. **task-2.1** → ShareReceiverActivity + AndroidManifest intent filters
2. **task-2.2** → SharedAudioHandler for content:// URI handling
3. **task-2.3** → InferenceService integration + result notifications

## Key Implementation Details

### ShareReceiverActivity (task-2.1)
- Transparent, no-UI activity
- Extract `Intent.EXTRA_STREAM` URI
- Show toast "Audio received"
- Delegate and finish() within 500ms

### SharedAudioHandler (task-2.2)
- `ContentResolver.openInputStream()` for content:// URIs
- Copy to `filesDir/shared_audio/`
- Generate unique filename with timestamp
- Validate audio format by extension

### Notification Flow (task-2.3)
- Progress notification (exists)
- Result notification with:
  - Text preview (100 chars)
  - Tap → open MainActivity
  - Copy button → clipboard
<!-- SECTION:PLAN:END -->
