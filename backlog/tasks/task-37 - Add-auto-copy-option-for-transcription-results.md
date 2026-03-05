---
id: TASK-37
title: Add auto-copy option for transcription results
status: Done
assignee: []
created_date: '2026-03-02 21:03'
updated_date: '2026-03-04 18:25'
labels:
  - enhancement
  - notifications
  - settings
  - ux
dependencies: []
priority: low
ordinal: 3000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Add a setting to automatically copy transcription results to the clipboard, saving users a tap for the common copy-to-clipboard workflow.

**Current state**: User must tap Copy button in notification to copy text
**Desired state**: Optional setting to auto-copy transcription on completion

**Location**: 
- `SettingsScreen.kt` - add toggle preference
- `TranscriptionService.kt` - check setting and auto-copy
- `PreferencesManager.kt` - store setting

**Technical approach**:
- Add boolean preference in Settings UI
- Check preference in TranscriptionService when result is ready
- Use ClipboardManager to copy text if setting is enabled
- Show toast confirming auto-copy
<!-- SECTION:DESCRIPTION:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 if its a web ui related task
- [ ] #2 always validate visually with Playwright MCP and attache screenshots
<!-- DOD:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 Auto-copy option available in Settings
- [x] #2 When enabled, transcription text automatically copied to clipboard
- [x] #3 User receives toast notification that text was copied
- [x] #4 Setting persists across app restarts
- [x] #5 Does not interfere with manual Copy action
- [x] #6 Works for all transcription sources (share intent, file picker)
<!-- AC:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
## Implementation Complete (2026-03-04)

**Files modified:**

- PreferencesManager.kt: Added autoCopyEnabled Flow + save method

- SettingsViewModel.kt: Added autoCopyEnabled StateFlow + saveAutoCopyEnabled()

- SettingsTab.kt: Added Switch toggle card

- InferenceService.kt: Added autoCopyIfEnabled() method

- strings.xml: Added auto_copy_title/description

**Build:** ✅ Successful
<!-- SECTION:NOTES:END -->
