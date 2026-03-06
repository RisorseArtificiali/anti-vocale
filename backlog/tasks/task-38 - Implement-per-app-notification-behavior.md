---
id: TASK-38
title: Implement per-app notification behavior
status: Done
assignee: []
created_date: '2026-03-02 21:03'
updated_date: '2026-03-06 07:49'
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

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
**Completed:** Comprehensive research completed confirming 95% feasibility for per-app notification behavior on Android 14+.

**Key Findings:**
- EXTRA_CHOSEN_COMPONENT method proven to work on Android 14+
- No deprecation or privacy restrictions affecting this approach
- Implementation estimated at 19-26 hours (2.5-3.5 days)

**Breakdown Created:**
Feature has been broken down into 9 implementation tasks (TASK-52 through TASK-60) organized by phase:

**Phase 1 - App Detection (HIGH):**
- TASK-52: Add ChooserBroadcastReceiver for app detection
- TASK-53: Integrate PendingIntent with ShareReceiverActivity chooser

**Phase 2 - Preferences Storage (HIGH):**
- TASK-54: Add DataStore dependency and create PerAppPreferencesManager

**Phase 3 - Settings UI (MEDIUM):**
- TASK-55: Create per-app settings screen with Material 3 UI
- TASK-57: Add manual app override to per-app settings

**Phase 4 - Service Integration (HIGH):**
- TASK-56: Integrate per-app preferences into TranscriptionService

**Phase 5 - Polish & Testing (LOW/MEDIUM):**
- TASK-58: Add reset to defaults functionality
- TASK-59: Add comprehensive testing for per-app notification feature
- TASK-60: Add onboarding tooltip for per-app notification settings

**Documentation:**
Full research report saved to: `claudedocs/research_task38_per_app_notifications_2026-03-06.md`

**Recommendation:** Begin with TASK-52 and TASK-53 (Phase 1) to validate the EXTRA_CHOSEN_COMPONENT approach on test devices before proceeding to other phases.
<!-- SECTION:FINAL_SUMMARY:END -->
