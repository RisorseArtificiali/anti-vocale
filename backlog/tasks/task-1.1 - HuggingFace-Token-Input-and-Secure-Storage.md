---
id: task-1.1
title: HuggingFace Token Input and Secure Storage
status: Done
assignee: []
created_date: '2026-02-28 17:59'
updated_date: '2026-03-01 10:43'
labels:
  - auth
  - storage
  - ui
dependencies: []
parent_task_id: task-1
priority: high
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Implement the UI and secure storage for HuggingFace authentication tokens.

**Scope:**
- Settings UI for token input
- Secure storage using EncryptedSharedPreferences
- Token validation API call

**Parent:** task-1
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 [UI] Settings screen has a labeled token input field
- [ ] #2 [Validation] Invalid tokens show an error message
- [ ] #3 [Persistence] Valid token persists after app restart
- [ ] #4 [Security] Token not readable from device backup
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
## Implementation Notes

### Dependencies
Add to `app/build.gradle.kts`: `implementation("androidx.security:security-crypto:1.1.0-alpha06")`

### New Files
1. **`data/HuggingFaceTokenManager.kt`** - EncryptedSharedPreferences for token storage
   - Use MasterKey with AES256_GCM scheme, store in "huggingface_secure_prefs"
   - Methods: saveToken(), getToken(), hasToken(), clearToken()
   - StateFlow<TokenState> for UI (Idle/Validating/Valid/Invalid)
   - Mask token display: `hf_****abc`

2. **`data/HuggingFaceApiClient.kt`** - Token validation via `https://huggingface.co/api/whoami-v2`

### Modified Files
1. **`di/AppContainer.kt`** - Initialize HuggingFaceTokenManager, HuggingFaceApiClient
2. **`ui/viewmodel/SettingsViewModel.kt`** - Add token state, onTokenInputChanged(), validateAndSaveToken(), clearToken()
3. **`ui/tabs/SettingsTab.kt`** - Add "HuggingFace Token" card with password field, validation button, link to HF

### Code Pattern
```kotlin
class HuggingFaceTokenManager(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    private val encryptedPrefs = EncryptedSharedPreferences.create(...)
}
```
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
### Dependencies
Add to `app/build.gradle.kts`: `implementation("androidx.security:security-crypto:1.1.0-alpha06")`

### New Files
1. **`data/HuggingFaceTokenManager.kt`** - EncryptedSharedPreferences for token storage
   - Use MasterKey with AES256_GCM scheme, store in "huggingface_secure_prefs"
   - Methods: saveToken(), getToken(), hasToken(), clearToken()
   - StateFlow<TokenState> for UI (Idle/Validating/Valid/Invalid)
   - Mask token display: `hf_****abc`

2. **`data/HuggingFaceApiClient.kt`** - Token validation via `https://huggingface.co/api/whoami-v2`

### Modified Files
1. **`di/AppContainer.kt`** - Initialize HuggingFaceTokenManager, HuggingFaceApiClient
2. **`ui/viewmodel/SettingsViewModel.kt`** - Add token state, onTokenInputChanged(), validateAndSaveToken(), clearToken()
3. **`ui/tabs/SettingsTab.kt`** - Add "HuggingFace Token" card with password field, validation button, link to HF

### Code Pattern
```kotlin
class HuggingFaceTokenManager(context: Context) {
    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    private val encryptedPrefs = EncryptedSharedPreferences.create(...)
}
```
<!-- SECTION:PLAN:END -->
<!-- SECTION:NOTES:END -->
