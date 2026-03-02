---
id: task-17.5
title: Create AuthManager Class for OAuth Flow
status: Done
assignee: []
created_date: '2026-03-01 12:18'
updated_date: '2026-03-01 13:12'
labels:
  - kotlin
  - oauth
  - authentication
dependencies: []
parent_task_id: task-17
priority: high
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Create the main authentication manager that handles the OAuth flow using AppAuth library.

**Implementation:**

```kotlin
// HuggingFaceAuthManager.kt
class HuggingFaceAuthManager(
  private val context: Context,
  private val tokenRepository: TokenRepository
) {
  private val authService = AuthorizationService(context)
  
  enum class AuthResult { SUCCESS, CANCELLED, ERROR }
  
  fun getAuthorizationRequest(): AuthorizationRequest {
    return AuthorizationRequest.Builder(
      HuggingFaceConfig.authServiceConfig,
      HuggingFaceConfig.clientId,
      ResponseTypeValues.CODE,
      HuggingFaceConfig.redirectUri.toUri(),
    )
      .setScope("read-repos")
      .build()
  }
  
  fun startAuthFlow(launcher: ActivityResultLauncher<Intent>) {
    val request = getAuthorizationRequest()
    val intent = authService.getAuthorizationRequestIntent(request)
    launcher.launch(intent)
  }
  
  suspend fun handleAuthResult(result: ActivityResult): AuthResult {
    // Exchange code for tokens, store them
  }
  
  suspend fun refreshTokenIfNeeded(): String?
  
  fun dispose() {
    authService.dispose()
  }
}
```
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 AuthManager class created with AuthorizationService
- [ ] #2 startAuthFlow() method launches OAuth via Chrome Custom Tabs
- [ ] #3 handleAuthResult() exchanges auth code for tokens
- [ ] #4 Token refresh logic implemented
- [ ] #5 Activity result launcher properly registered
<!-- AC:END -->
