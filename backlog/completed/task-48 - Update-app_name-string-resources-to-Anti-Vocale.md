---
id: TASK-48
title: Update app_name string resources to Anti-Vocale
status: Done
assignee: []
created_date: '2026-03-05 15:28'
updated_date: '2026-03-05 15:30'
labels:
  - branding
  - i18n
  - strings
milestone: App Rebrand
dependencies:
  - TASK-47
priority: high
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Update the app display name from "LocalAI Bridge" to "Anti-Vocale" in all string resource files.

**FILES TO MODIFY:**
- `app/src/main/res/values/strings.xml` - Change `<string name="app_name">LocalAI Bridge</string>` to "Anti-Vocale"
- `app/src/main/res/values-it/strings.xml` - Change Italian app_name to "Anti-Vocale"

**IMPLEMENTATION:**
1. Edit `app/src/main/res/values/strings.xml`, line 4
2. Edit `app/src/main/res/values-it/strings.xml`, line 4
3. Build the app to verify: `gradle assembleDebug`
4. Install APK to confirm the name appears correctly in launcher
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 [ ] app_name changed to "Anti-Vocale" in values/strings.xml
- [x] #2 [ ] app_name changed to "Anti-Vocale" in values-it/strings.xml
- [x] #3 [ ] Build compiles successfully
- [x] #4 [ ] APK shows "Anti-Vocale" when installed
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Code compiles without errors or warnings
- [ ] #2 Feature tested on physical device or emulator
- [ ] #3 No regressions in existing functionality
- [ ] #4 Edge cases handled appropriately
- [ ] #5 UI follows Material Design guidelines
<!-- DOD:END -->
