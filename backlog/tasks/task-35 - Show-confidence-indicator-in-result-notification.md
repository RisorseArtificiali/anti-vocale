---
id: TASK-35
title: Show confidence indicator in result notification
status: To Do
assignee: []
created_date: '2026-03-02 21:03'
updated_date: '2026-03-04 15:57'
labels:
  - enhancement
  - notifications
  - ux
  - research
dependencies: []
priority: medium
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Display a confidence indicator in the transcription result notification showing how confident the model was in its transcription.

**Current state**: No indication of transcription quality/confidence
**Desired state**: Visual indicator showing confidence level (high/medium/low or percentage)

**Location**: `TranscriptionService.kt` - result notification builder

**Technical approach**:
- Extract confidence score from ASR backend output
- Display as icon (✓/⚠/❌), color coding, or text label
- Consider showing only for low-confidence transcriptions
- May need to calculate aggregate confidence for multi-chunk audio
<!-- SECTION:DESCRIPTION:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 if its a web ui related task
- [ ] #2 always validate visually with Playwright MCP and attache screenshots
<!-- DOD:END -->



## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Confidence indicator visible in result notification
- [ ] #2 Indicator reflects actual model confidence/score from ASR output
- [ ] #3 Visual representation is clear (icon, color, or text)
- [ ] #4 Low confidence results are visually distinguishable
- [ ] #5 Works for both Whisper and Parakeet backends
- [ ] #6 Indicator does not overwhelm notification content
<!-- AC:END -->
