---
id: TASK-16
title: Improve HuggingFace Token UX and Documentation
status: Done
assignee: []
created_date: '2026-03-01 11:37'
updated_date: '2026-03-04 18:25'
labels:
  - enhancement
  - ux
  - huggingface
  - onboarding
dependencies: []
priority: medium
ordinal: 28000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Track token permission requirements and explore simpler workflows for users.

## Current State
- Users need a HuggingFace token with **Read access** to download gated models
- Token validation uses `/api/whoami-v2` endpoint
- Model downloads use `Authorization: Bearer $token` header

## Required Token Permissions

| Token Type | What to Select |
|------------|----------------|
| **Fine-grained** (recommended) | ✅ `Read access to contents of all public gated repos you have access to` |
| **Classic** | ✅ `Read` permission (minimal) |

## Required User Actions (Current)
1. Create token at huggingface.co/settings/tokens
2. Configure correct permissions (confusing for non-technical users)
3. Accept license on each model page separately:
   - `google/gemma-3n-E2B-it-litert-lm`
   - `google/gemma-3n-E4B-it-litert-lm`
   - `litert-community/Gemma3-1B-IT`
4. Paste token in app Settings

## Brainstorm: Simpler Workflows

### Option A: In-App License Acceptance
- Detect 403 errors and show "Accept License" button
- Button opens in-app browser to model page (user logs in via browser)
- After accepting, retry download automatically
- **Pro**: Users stay in app, clearer flow
- **Con**: Still requires web login

### Option B: OAuth Flow
- Implement HuggingFace OAuth (if available)
- User clicks "Login with HuggingFace"
- Redirects to HF, user authorizes, returns to app
- **Pro**: No manual token copy-paste
- **Con**: More complex implementation, needs OAuth client registration

### Option C: Pre-bundled Models
- Ship one small model (Gemma3-1B) with the APK
- Users can start immediately without any setup
- Optional: Download larger models later
- **Pro**: Zero setup for basic use
- **Con**: Increases APK size by ~557MB

### Option D: Model Hub Browser
- In-app browser showing available models
- Shows license status, download progress
- Single-click download after one-time login
- **Pro**: Better UX visibility
- **Con**: More UI work

### Option E: Clearer Onboarding
- Add a "Setup Guide" screen for first-time users
- Step-by-step with screenshots/links
- Link directly to token creation with correct scope
- **Pro**: Minimal code changes
- **Con**: Still manual process

## Questions to Resolve
- Does HuggingFace support OAuth for third-party apps?
- Can we detect if user has accepted a model's license via API?
- What's the minimum viable first-run experience?
<!-- SECTION:DESCRIPTION:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
# Implementation Plan: Improve HuggingFace Token UX

## Approach: Enhanced Onboarding + Better Error UX

### Changes
1. **Clipboard Paste Button** - Add paste button next to token input field
2. **OAuth as Primary** - Make "Login with HuggingFace" prominent, hide manual token in "Advanced" section
3. **Fix Buttons** - Add "Create Token" and "Retry" buttons when validation fails
4. **Setup Guide** - Add collapsible step-by-step guide in the UI

### Files to Modify
- `tabs/SettingsTab.kt` - Main UI changes
- `viewmodel/SettingsViewModel.kt` - Optional clipboard helper

### Acceptance Criteria
- [ ] OAuth button is prominently displayed above manual token input
- [ ] Manual token section is collapsible under "Advanced"
- [ ] Clipboard paste button works next to token input
- [ ] Invalid token shows "Create Token" button that opens browser
- [ ] Setup guide is expandable with clear steps
<!-- SECTION:PLAN:END -->
