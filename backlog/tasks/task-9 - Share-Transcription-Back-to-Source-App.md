---
id: TASK-9
title: Share Transcription Back to Source App
status: Done
assignee:
  - Claude
created_date: '2026-02-28 18:10'
updated_date: '2026-03-04 18:25'
labels:
  - enhancement
  - sharing
  - ux
dependencies: []
priority: medium
ordinal: 33000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Enable users to share individual transcriptions back to other apps via Android's share functionality.

## User Story

As a user, I want to share a transcription back to the app it came from (e.g., reply to a WhatsApp voice message with its transcription), so I can communicate the transcribed content easily.

## Use Case

1. User receives WhatsApp voice message
2. User shares it to LocalAI Bridge for transcription
3. After transcription completes, user taps "Share"
4. User selects WhatsApp from share sheet
5. Transcription text is sent back to the conversation

## Scope

- Share button on each transcription entry
- ACTION_SEND intent with text/plain MIME type
- Pre-populated share intent with transcription text
- Optional: Include audio metadata (duration, source hint if available)

## Related

- Complements task-2 (Android Share Integration for receiving)
- Uses standard Android ACTION_SEND (no special permissions needed)
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 [Share Trigger] Each transcription entry has a share button or action
- [x] #2 [Share Sheet] Android share sheet appears with app options
- [x] #3 [Content] Shared text includes the transcription content
- [x] #4 [Source App] User can select the original source app (WhatsApp, Telegram, etc.) as destination
- [x] #5 [Multiple Formats] Share as plain text with optional title/header
<!-- AC:END -->
