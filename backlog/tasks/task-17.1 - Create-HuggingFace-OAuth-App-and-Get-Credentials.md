---
id: TASK-17.1
title: Create HuggingFace OAuth App and Get Credentials
status: Done
assignee: []
created_date: '2026-03-01 12:18'
updated_date: '2026-03-04 18:25'
labels:
  - setup
  - huggingface
  - authentication
dependencies: []
parent_task_id: TASK-17
priority: high
ordinal: 19000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Set up the HuggingFace OAuth application in the HuggingFace developer settings. This is the first step before any code changes.

**Steps:**
1. Go to https://huggingface.co/settings/oauth/apps
2. Click "New OAuth Application"
3. Configure:
   - Application name: "Voice Message Reader" (or appropriate name)
   - Homepage URL: App's website or GitHub repo
   - Redirect URI: `com.localai.bridge://oauth2callback` (must match app scheme)
   - Scopes: `read-repos` (for downloading gated models)
4. Copy Client ID and Client Secret for app configuration
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 HuggingFace OAuth App is created with correct settings
- [ ] #2 Client ID and Client Secret are obtained
- [ ] #3 Redirect URI is configured (e.g., com.localai.bridge://oauth2callback)
- [ ] #4 Scopes include read-repos for model access
<!-- AC:END -->
