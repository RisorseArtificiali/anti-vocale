---
id: TASK-64
title: Test Share Back compatibility across messaging apps
status: To Do
assignee: []
created_date: '2026-03-06 08:07'
labels:
  - testing
  - qa
  - share-back
  - compatibility
  - phase-5
dependencies: []
priority: medium
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Comprehensive testing of the Share Back feature across popular messaging apps to verify direct text injection works and document compatibility.

**Testing Matrix:**

**Core Apps (Must Work):**
- WhatsApp ✅ (expected: direct text injection)
- Telegram ✅ (expected: direct text injection)
- Signal ⚠️ (expected: clipboard fallback)

**Popular Messaging Apps:**
- Facebook Messenger
- Google Messages
- Samsung Messages
- Discord
- Slack
- Viber
- WeChat
- Line

**Testing Process:**
1. Share voice message from app to Anti-Vocale
2. Wait for transcription
3. Tap "Send to [App]" button
4. Verify: Does app open with text pre-filled?
5. Document result

**Success Criteria:**
- Text appears in message field ready to send
- No share sheet shown
- One-tap from notification to sent message

**Fallback Criteria:**
- App opens but text not pre-filled → clipboard copy acceptable
- App won't open → show error message

**Deliverables:**
- Compatibility spreadsheet (App → Support Level)
- Screenshots of successful Share Back for WhatsApp/Telegram
- Bug reports for incompatible apps
- Updated user documentation

**Files:**
- `claudedocs/share_back_compatibility_matrix.md` (new)
- `claudedocs/share_back_test_results.md` (new)
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 #1 Tested Share Back with WhatsApp (success)
- [ ] #2 #2 Tested Share Back with Telegram (success)
- [ ] #3 #3 Tested Share Back with Signal (clipboard fallback)
- [ ] #4 #4 Tested with unknown apps (regular share sheet)
- [ ] #5 #5 Documented which apps support direct text injection
<!-- AC:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 Code compiles without errors or warnings
- [ ] #2 Feature tested on physical device or emulator
- [ ] #3 No regressions in existing functionality
- [ ] #4 Edge cases handled appropriately
- [ ] #5 UI follows Material Design guidelines
- [ ] #6 Every text should support internationalisation and should be tracked
- [ ] #7 #1 Manual test matrix completed for 10+ apps
- [ ] #8 #2 Created app compatibility list in documentation
- [ ] #9 #3 Video demos of Share Back working with WhatsApp and Telegram
- [ ] #10 #4 Bug report filed for any incompatible apps discovered
- [ ] #11 #5 Update user docs with Share Back feature explanation
<!-- DOD:END -->
