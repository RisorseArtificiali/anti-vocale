---
id: TASK-60
title: Add onboarding tooltip for per-app notification settings
status: To Do
assignee: []
created_date: '2026-03-06 07:49'
labels:
  - feature
  - ui
  - onboarding
  - ux
  - phase-5
dependencies: []
priority: low
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Implement a first-run onboarding experience to educate users about the per-app notification feature when they first access it.

**Implementation:**
- Detect first access to PerAppSettingsScreen
- Show tooltip/bubble explaining the feature:
  - "Customize how notifications work for each app"
  - Point to first detected app as example
- "Got it" button to dismiss
- Store "seen onboarding" flag in DataStore
- Don't show again after first dismissal

**Visual Design:**
- Material 3 tooltip/bubble styling
- Arrow pointing to relevant UI element
- Concise copy (2-3 sentences max)
- Optional: "Learn more" link to documentation

**Files:**
- `app/src/main/java/com/localai/bridge/ui/screens/PerAppSettingsScreen.kt` (modify)
- `app/src/main/java/com/localai/bridge/ui/component/OnboardingTooltip.kt` (new)
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 #1 Onboarding tooltip shows on first access to PerAppSettingsScreen
- [ ] #2 #2 Tooltip explains feature value clearly
- [ ] #3 #3 Dismissed tooltip doesn't show again
- [ ] #4 #4 'Seen onboarding' flag persists across app restarts
- [ ] #5 #5 Visual design matches Material 3 guidelines
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Code compiles without errors or warnings
- [ ] #2 Feature tested on physical device or emulator
- [ ] #3 No regressions in existing functionality
- [ ] #4 Edge cases handled appropriately
- [ ] #5 UI follows Material Design guidelines
- [ ] #6 Every text should support internationalisation and should be tracked
- [ ] #7 #1 UI test: Tooltip appears on first visit
- [ ] #8 #2 UI test: Dismissing tooltip stores flag
- [ ] #9 #3 UI test: Flag persists across restarts
- [ ] #10 #4 Manual test: Tooltip doesn't show after dismissal
<!-- DOD:END -->
