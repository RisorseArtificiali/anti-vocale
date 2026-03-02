---
id: task-17.2
title: Add AppAuth Dependency and Configure Build
status: Done
assignee: []
created_date: '2026-03-01 12:18'
updated_date: '2026-03-01 13:12'
labels:
  - android
  - build
  - dependency
dependencies: []
parent_task_id: task-17
priority: high
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Add the OpenID AppAuth-Android library dependency and configure the build for OAuth redirect handling.

**Implementation:**

```kotlin
// app/build.gradle.kts
dependencies {
  implementation("net.openid.appauth:appauth:0.11.1")
}

android {
  defaultConfig {
    // Needed for HuggingFace auth workflows
    manifestPlaceholders["appAuthRedirectScheme"] = "com.localai.bridge"
  }
}
```
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 AppAuth dependency added to build.gradle.kts
- [ ] #2 manifestPlaceholders configured with app redirect scheme
- [ ] #3 Proguard rules added if needed for AppAuth
<!-- AC:END -->
