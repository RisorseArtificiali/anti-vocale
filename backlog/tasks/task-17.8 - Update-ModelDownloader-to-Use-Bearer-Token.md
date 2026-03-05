---
id: TASK-17.8
title: Update ModelDownloader to Use Bearer Token
status: Done
assignee: []
created_date: '2026-03-01 12:18'
updated_date: '2026-03-04 18:25'
labels:
  - network
  - download
  - authentication
dependencies: []
parent_task_id: TASK-17
priority: medium
ordinal: 26000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Update the model download logic to include the Bearer token when downloading from HuggingFace.

**Implementation:**

```kotlin
// In ModelDownloader or DownloadWorker
val connection = url.openConnection() as HttpURLConnection

// Add auth token if available
val token = tokenRepository.getTokenData()
if (token != null && !tokenRepository.isTokenExpired()) {
  connection.setRequestProperty("Authorization", "Bearer ${token.accessToken}")
}

connection.connect()
```
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 ModelDownloader uses stored token for gated models
- [ ] #2 Authorization header added to download requests
- [ ] #3 401 errors trigger token refresh and retry
- [ ] #4 Download progress works correctly with auth
<!-- AC:END -->
