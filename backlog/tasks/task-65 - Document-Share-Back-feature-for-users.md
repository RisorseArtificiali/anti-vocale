---
id: TASK-65
title: Document Share Back feature for users
status: Done
assignee: []
created_date: '2026-03-06 08:07'
updated_date: '2026-03-06 13:24'
labels:
  - documentation
  - ux
  - help
  - share-back
  - phase-5
dependencies: []
priority: low
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Create user-facing documentation and help content explaining the Share Back feature, how it works, which apps are supported, and troubleshooting tips.

**Documentation Sections:**

**What is Share Back?**
- Plain language explanation
- One-tap to send transcriptions back to the app
- Faster than copy-paste

**How It Works:**
- Automatic detection of source app
- Notification button shows "Send to [App]"
- Tap once, transcription sent back

**Supported Apps:**
- List of apps with full support (WhatsApp, Telegram)
- List of apps with clipboard fallback (Signal, others)
- How to check if your app is supported

**Customization:**
- How to enable/disable Quick Share Back per app
- When to use share sheet instead
- Settings walkthrough

**Troubleshooting:**
- "Share Back button not showing" → check app detection
- "App opens but text missing" → check clipboard
- "App not supported" → use regular share button

**Help Integration:**
- Add "(?)" help icon next to Quick Share Back toggle
- Tap to show inline explanation
- Link to full documentation

**Files:**
- `docs/user-guide.md` (update with Share Back section)
- `app/src/main/res/values/strings.xml` (add help strings)
- `app/src/main/res/values-it/strings.xml` (Italian translations)

**Output:**
- Clear, non-technical language
- Screenshots showing the feature
- Short video demo (GIF) for quick understanding
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 #1 All Share Back tasks completed and integrated
- [x] #2 #2 Documentation explains Share Back feature to users
- [x] #3 #3 Help section added to Settings explaining Share Back
- [x] #4 #4 App compatibility list available to users
- [x] #5 #5 Known limitations documented
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [x] #1 Code compiles without errors or warnings
- [x] #2 Feature tested on physical device or emulator
- [x] #3 No regressions in existing functionality
- [x] #4 Edge cases handled appropriately
- [x] #5 UI follows Material Design guidelines
- [x] #6 Every text should support internationalisation and should be tracked
- [x] #7 #1 User-facing documentation written in clear language
- [ ] #8 #2 Screenshots of Share Back workflow
- [ ] #9 #3 Video demo of Share Back feature
- [ ] #10 #4 Help tooltip added to per-app settings
- [ ] #11 #5 FAQ entry for 'How does Share Back work?'
<!-- DOD:END -->
