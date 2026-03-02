---
id: task-17.4
title: Implement Secure Token Storage with DataStore
status: Done
assignee: []
created_date: '2026-03-01 12:18'
updated_date: '2026-03-01 13:12'
labels:
  - kotlin
  - datastore
  - security
  - storage
dependencies: []
parent_task_id: task-17
priority: high
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Implement secure token storage using DataStore with encryption to persist OAuth tokens between app sessions.

**Implementation:**

```kotlin
// TokenData.kt
data class TokenData(
  val accessToken: String,
  val refreshToken: String,
  val expiresAt: Long,  // Timestamp in milliseconds
)

// TokenRepository.kt
class TokenRepository(context: Context) {
  private val masterKey = MasterKey.Builder(context)
    .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
    .build()
  
  private val dataStore = EncryptedDataStore.create(
    context,
    "huggingface_tokens",
    masterKey
  )
  
  suspend fun saveTokenData(token: TokenData)
  suspend fun getTokenData(): TokenData?
  suspend fun clearTokenData()
  fun isTokenExpired(): Boolean
  fun isTokenValid(): Boolean
}
```
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 TokenData data class defined with accessToken, refreshToken, expiresAt
- [ ] #2 DataStore repository methods for save/get/clear tokens
- [ ] #3 Tokens stored in encrypted preferences
- [ ] #4 Token expiration check method implemented
<!-- AC:END -->
