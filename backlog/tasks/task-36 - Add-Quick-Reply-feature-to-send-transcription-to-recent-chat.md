---
id: TASK-36
title: Add Quick Reply feature to send transcription to recent chat
status: Done
assignee: []
created_date: '2026-03-02 21:03'
updated_date: '2026-03-04 18:25'
labels:
  - enhancement
  - notifications
  - ux
  - advanced
dependencies: []
references:
  - claudedocs/research_whatsapp_telegram_action_send_2026-03-03.md
priority: medium
ordinal: 1500
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Add a Quick Reply feature that sends the transcription directly back to the chat/app that shared the audio, streamlining the reply workflow.

**Current state**: User must copy text, open source app, find conversation, paste
**Desired state**: One-tap reply to the same conversation that sent the audio

**Location**: `TranscriptionService.kt` - result notification builder

**Technical approach**:
- Detect source app package name from share intent (may be limited on newer Android)
- Store source app info with audio file during share handling
- Create intent to send text back to detected app
- May need different approaches for WhatsApp vs Telegram
- See research docs: `claudedocs/research_whatsapp_telegram_action_send_2026-03-03.md`
<!-- SECTION:DESCRIPTION:END -->

## Definition of Done
<!-- DOD:BEGIN -->
- [ ] #1 if its a web ui related task
- [ ] #2 always validate visually with Playwright MCP and attache screenshots
<!-- DOD:END -->



## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Quick Reply action visible in result notification
- [ ] #2 Tapping opens share dialog pre-addressed to recent chat
- [ ] #3 Correctly identifies source app (WhatsApp vs Telegram)
- [ ] #4 Transcription text pre-filled in message
- [ ] #5 Works when source app is detectable
- [ ] #6 Graceful fallback when source app cannot be determined
<!-- AC:END -->
