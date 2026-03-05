---
id: TASK-1.3
title: Download Error Handling and User Feedback
status: Done
assignee: []
created_date: '2026-02-28 17:59'
updated_date: '2026-03-04 18:25'
labels:
  - error-handling
  - ux
dependencies:
  - task-1.2
parent_task_id: TASK-1
priority: medium
ordinal: 46000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Implement user-friendly error handling for download failures.

**Scope:**
- Auth error (401/403) → link to HF token settings
- License not accepted → link to model page
- Network errors → retry option
- Storage insufficient → show required space

**Parent:** task-1
**Depends on:** task-1.2
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 [Auth Error] User sees link to HuggingFace token settings
- [ ] #2 [License Error] User sees link to model page on HuggingFace
- [ ] #3 [Network Error] User sees retry button
- [ ] #4 [Storage Error] User sees required vs available storage
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
## Implementation Notes

### Modify `ui/tabs/ModelTab.kt` - Add Error Cards

1. **AuthError (401):** "Invalid or expired token" → Button to Settings tab
2. **LicenseError (403):** "Accept model license" → Link to HF model page
3. **NetworkError:** "Network error" → Retry button
4. **StorageError:** "Insufficient storage" → Show required vs available bytes

### Error Card Pattern
```kotlin
Card(colors = CardDefaults.cardColors(containerColor = errorContainer)) {
    Row {
        Icon(Icons.Default.Error, tint = errorColor)
        Column {
            Text(error.message)
            when (error) {
                is AuthError -> TextButton({ navToSettings() }) { Text("Go to Settings") }
                is LicenseError -> TextButton({ openBrowser(url) }) { Text("Accept License") }
                is NetworkError -> Button({ retry() }) { Text("Retry") }
                is StorageError -> Text("Need ${error.requiredBytes}, have ${error.availableBytes}")
            }
        }
    }
}
```
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
### Modify `ui/tabs/ModelTab.kt` - Add Error Cards

1. **AuthError (401):** "Invalid or expired token" → Button to Settings tab
2. **LicenseError (403):** "Accept model license" → Link to HF model page
3. **NetworkError:** "Network error" → Retry button
4. **StorageError:** "Insufficient storage" → Show required vs available bytes

### Error Card Pattern
```kotlin
Card(colors = CardDefaults.cardColors(containerColor = errorContainer)) {
    Row {
        Icon(Icons.Default.Error, tint = errorColor)
        Column {
            Text(error.message)
            when (error) {
                is AuthError -> TextButton({ navToSettings() }) { Text("Go to Settings") }
                is LicenseError -> TextButton({ openBrowser(url) }) { Text("Accept License") }
                is NetworkError -> Button({ retry() }) { Text("Retry") }
                is StorageError -> Text("Need ${error.requiredBytes}, have ${error.availableBytes}")
            }
        }
    }
}
```
<!-- SECTION:PLAN:END -->
<!-- SECTION:NOTES:END -->
