---
id: task-17.3
title: Create HuggingFace OAuth Configuration Class
status: Done
assignee: []
created_date: '2026-03-01 12:18'
updated_date: '2026-03-01 13:12'
labels:
  - kotlin
  - configuration
  - oauth
dependencies: []
parent_task_id: task-17
priority: high
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Create a configuration object for HuggingFace OAuth settings, similar to Edge Gallery's ProjectConfig.kt.

**Implementation:**

```kotlin
// HuggingFaceConfig.kt
object HuggingFaceConfig {
  // From HuggingFace OAuth App settings
  const val clientId = BuildConfig.HF_CLIENT_ID
  
  // Must match manifestPlaceholders["appAuthRedirectScheme"]
  const val redirectUri = "com.localai.bridge://oauth2callback"
  
  // HuggingFace OAuth 2.0 Endpoints
  private const val authEndpoint = "https://huggingface.co/oauth/authorize"
  private const val tokenEndpoint = "https://huggingface.co/oauth/token"
  
  val authServiceConfig = AuthorizationServiceConfiguration(
    authEndpoint.toUri(),
    tokenEndpoint.toUri(),
  )
}
```

Store Client ID in `local.properties` or `secrets.gradle` for security.
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 HuggingFaceConfig object created with OAuth endpoints
- [ ] #2 Client ID stored securely (not hardcoded in production)
- [ ] #3 Redirect URI matches manifestPlaceholders scheme
- [ ] #4 AuthorizationServiceConfiguration properly initialized
<!-- AC:END -->
