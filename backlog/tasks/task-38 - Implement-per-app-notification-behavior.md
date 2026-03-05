---
id: TASK-38
title: Implement per-app notification behavior
status: To Do
assignee: []
created_date: '2026-03-02 21:03'
updated_date: '2026-03-04 15:57'
labels:
  - enhancement
  - notifications
  - settings
  - ux
  - advanced
dependencies: []
references:
  - >-
    [Fuzzy Search Research
    Report](claudedocs/research_fuzzy_search_app_detection_task38_2026-03-03.md)
  - >-
    [WhatsApp & Telegram ACTION_SEND Research
    Report](claudedocs/research_whatsapp_telegram_action_send_2026-03-03.md)
priority: medium
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Allow different notification behaviors based on which app shared the audio (WhatsApp vs Telegram vs others).

**Current state**: All notifications behave identically regardless of source app
**Desired state**: Customizable behavior per source app (auto-copy for WhatsApp, share action for Telegram, etc.)

**Location**: 
- `ShareReceiverActivity.kt` - detect and store source app
- `SettingsScreen.kt` - per-app configuration UI
- `TranscriptionService.kt` - apply per-app preferences
- `PreferencesManager.kt` - store preferences by package name

**Technical approach**:
- Detect source app from share intent (may be limited on newer Android due to privacy)
- Allow users to configure preferences mapped to package names
- Store preferences: auto-copy, show share action, notification sound
- May need fallback for when source app is not detectable

**Research**: See `claudedocs/research_fuzzy_search_app_detection_task38_2026-03-03.md` for app detection feasibility
<!-- SECTION:DESCRIPTION:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 if its a web ui related task
- [ ] #2 always validate visually with Playwright MCP and attache screenshots
<!-- DOD:END -->



## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Settings UI allows configuring per-app preferences
- [ ] #2 Source app detection works for WhatsApp and Telegram
- [ ] #3 Users can configure: auto-copy, share action visibility, notification sound per app
- [ ] #4 Preferences stored by package name
- [ ] #5 Graceful fallback when source app cannot be detected
- [ ] #6 Settings persist across app restarts
<!-- AC:END -->
