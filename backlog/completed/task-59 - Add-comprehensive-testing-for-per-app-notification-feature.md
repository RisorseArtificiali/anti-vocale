---
id: TASK-59
title: Add comprehensive testing for per-app notification feature
status: Done
assignee: []
created_date: '2026-03-06 07:49'
updated_date: '2026-03-06 13:48'
labels:
  - testing
  - unit-tests
  - integration-tests
  - phase-5
dependencies: []
priority: medium
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Write unit tests, integration tests, and perform manual testing for the per-app notification behavior feature across all implementation phases.

**Unit Tests:**
- ChooserBroadcastReceiverTest:
  - Valid ComponentInfo parsing
  - Malformed ComponentInfo handling
  - Null/empty component info handling
- PerAppPreferencesManagerTest:
  - Default preferences for known apps
  - Preference updates and persistence
  - Flow emissions on changes
  - Concurrent access safety

**Integration Tests:**
- ShareReceiverActivity integration test:
  - Simulate share from WhatsApp → verify package detection
  - Simulate share from Telegram → verify package detection
  - Verify TranscriptionService receives package name
- TranscriptionService integration test:
  - Per-app preferences applied correctly
  - Fallback to defaults for unknown packages

**Manual Testing Checklist:**
- Test on Android 12, 13, and 14 devices
- Test with WhatsApp, Telegram, Signal, Facebook Messenger
- Test auto-copy functionality per app
- Test share action visibility per app
- Test notification sound selection per app
- Test manual app override
- Test reset functionality
- Test preferences persist across app restarts

**Files:**
- `app/src/test/java/com/localai/bridge/receiver/ChooserBroadcastReceiverTest.kt` (new)
- `app/src/test/java/com/localai/bridge/data/PerAppPreferencesManagerTest.kt` (new)
- `app/src/androidTest/java/com/localai/bridge/integration/ShareIntegrationTest.kt` (new)
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 #1 Unit tests achieve >80% code coverage
- [ ] #2 #2 Integration tests cover main user flows
- [ ] #3 #3 Manual testing completed on Android 12, 13, and 14
- [ ] #4 #4 All tests pass consistently
- [ ] #5 #5 Edge cases identified and documented
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Code compiles without errors or warnings
- [ ] #2 Feature tested on physical device or emulator
- [ ] #3 No regressions in existing functionality
- [ ] #4 Edge cases handled appropriately
- [ ] #5 UI follows Material Design guidelines
- [ ] #6 Every text should support internationalisation and should be tracked
- [ ] #7 #1 Unit tests pass in CI/CD pipeline
- [ ] #8 #2 Integration tests pass on multiple API levels
- [ ] #9 #3 Manual testing report documented
- [ ] #10 #4 Performance benchmarks recorded
- [ ] #11 #5 No critical bugs found
<!-- DOD:END -->
