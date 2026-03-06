---
id: TASK-61
title: Add smart Share Back button to notifications
status: Done
assignee: []
created_date: '2026-03-06 08:07'
updated_date: '2026-03-06 13:20'
labels:
  - feature
  - ux
  - notifications
  - share-back
  - phase-4
dependencies: []
priority: high
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Add a smart "Share Back" button to transcription notifications that knows which app the voice message came from and provides a one-tap experience to send the transcription back to that app.

**Implementation:**
- Read detected package name from TranscriptionService intent
- Map package names to display names: "com.whatsapp" → "WhatsApp"
- Customize notification action button text to "Send to [App Name]"
- Copy transcription to clipboard before opening target app
- Launch target app directly using PackageManager
- Handle errors gracefully (fallback to regular share sheet)

**Package Name Mapping:**
```kotlin
fun getAppName(packageName: String): String {
    return when(packageName) {
        "com.whatsapp" -> "WhatsApp"
        "org.telegram.messenger" -> "Telegram"
        "org.thoughtcrime.securesms" -> "Signal"
        else -> PackageManager.getApplicationLabel(packageName)
    }
}
```

**Files:**
- `app/src/main/java/com/localai/bridge/service/TranscriptionService.kt` (modify)
- `app/src/main/java/com/localai/bridge/util/AppInfoUtils.kt` (new)

**User Experience:**
Instead of generic "Share" button, user sees "Send to WhatsApp" making it crystal clear where the text will go.

**Error Handling:**
- If app not installed → show regular share dialog
- If app fails to open → show error toast with retry option
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 #1 Notification button shows 'Send to [App Name]' when source detected
- [x] #2 #2 Tapping button opens source app directly
- [x] #3 #3 Transcription text copied to clipboard before opening app
- [x] #4 #4 Fallback to regular share sheet when direct opening fails
- [x] #5 #5 Works reliably for WhatsApp and Telegram
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Code compiles without errors or warnings
- [x] #2 Feature tested on physical device or emulator
- [x] #3 No regressions in existing functionality
- [x] #4 Edge cases handled appropriately
- [x] #5 UI follows Material Design guidelines
- [x] #6 Every text should support internationalisation and should be tracked
- [x] #7 #1 Manual test: Share from WhatsApp → 'Send to WhatsApp' button appears
- [x] #8 #2 Manual test: Tap button → WhatsApp opens with text in clipboard
- [x] #9 #3 Manual test: Share from Telegram → 'Send to Telegram' button appears
- [x] #10 #4 Manual test: Tap button → Telegram opens with text in clipboard
- [x] #11 #5 Manual test: Share from unknown app → regular 'Share' button shown
<!-- DOD:END -->
