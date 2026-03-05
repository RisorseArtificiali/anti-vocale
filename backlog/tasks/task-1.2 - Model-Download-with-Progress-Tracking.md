---
id: TASK-1.2
title: Model Download with Progress Tracking
status: Done
assignee: []
created_date: '2026-02-28 17:59'
updated_date: '2026-03-04 18:25'
labels:
  - download
  - networking
dependencies:
  - task-1.1
parent_task_id: TASK-1
priority: high
ordinal: 45000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Implement authenticated model downloads from HuggingFace with progress indication.

**Scope:**
- Download service with auth header injection
- Progress callback integration
- File verification after download

**Parent:** task-1
**Depends on:** task-1.1 (token storage)
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 [Auth] Downloads include Authorization header with saved token
- [ ] #2 [Progress] Progress percentage updates during download
- [ ] #3 [Completion] Downloaded file size matches expected size
- [ ] #4 [Resume] Partial downloads can be resumed after interruption
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
## Implementation Notes

### Modify `data/ModelDownloader.kt`

1. **Add DownloadError sealed class:**
```kotlin
sealed class DownloadError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class AuthError(message: String) : DownloadError(message)
    class LicenseError(message: String) : DownloadError(message)
    class NetworkError(message: String, cause: Throwable? = null) : DownloadError(message)
    class StorageError(message: String, val requiredBytes: Long, val availableBytes: Long) : DownloadError(message)
}
```

2. **Modify downloadModel() signature** - add `tokenManager: HuggingFaceTokenManager` parameter

3. **Add Authorization header:**
```kotlin
val token = tokenManager.getToken()
if (!token.isNullOrBlank()) {
    requestBuilder.addHeader("Authorization", "Bearer $token")
}
```

4. **Handle error codes:** 401 → AuthError, 403 → LicenseError

5. **Pre-download storage check:**
```kotlin
val requiredBytes = variant.estimatedSizeMB * 1024 * 1024
val availableBytes = context.filesDir.usableSpace
if (availableBytes < requiredBytes) return StorageError
```
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
### Modify `data/ModelDownloader.kt`

1. **Add DownloadError sealed class:**
```kotlin
sealed class DownloadError(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class AuthError(message: String) : DownloadError(message)
    class LicenseError(message: String) : DownloadError(message)
    class NetworkError(message: String, cause: Throwable? = null) : DownloadError(message)
    class StorageError(message: String, val requiredBytes: Long, val availableBytes: Long) : DownloadError(message)
}
```

2. **Modify downloadModel() signature** - add `tokenManager: HuggingFaceTokenManager` parameter

3. **Add Authorization header:**
```kotlin
val token = tokenManager.getToken()
if (!token.isNullOrBlank()) {
    requestBuilder.addHeader("Authorization", "Bearer $token")
}
```

4. **Handle error codes:** 401 → AuthError, 403 → LicenseError

5. **Pre-download storage check:**
```kotlin
val requiredBytes = variant.estimatedSizeMB * 1024 * 1024
val availableBytes = context.filesDir.usableSpace
if (availableBytes < requiredBytes) return StorageError
```
<!-- SECTION:PLAN:END -->
<!-- SECTION:NOTES:END -->
