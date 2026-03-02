---
id: task-1.4
title: Model Selection UI Integration
status: Done
assignee: []
created_date: '2026-02-28 17:59'
updated_date: '2026-03-01 10:43'
labels:
  - ui
  - model-selection
dependencies:
  - task-1.2
parent_task_id: task-1
priority: medium
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Integrate downloaded models into the model selection interface.

**Scope:**
- Show downloaded models in model list
- Display download status and progress
- Auto-select newly downloaded model

**Parent:** task-1
**Depends on:** task-1.2
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 [Visibility] Downloaded models appear in model selection list
- [ ] #2 [Status] Download in progress shows percentage indicator
- [ ] #3 [Selection] Newly downloaded model becomes selected automatically
<!-- AC:END -->

## Implementation Plan

<!-- SECTION:PLAN:BEGIN -->
## Implementation Notes

### Modify `ui/viewmodel/ModelViewModel.kt` - Add Download State

```kotlin
data class DownloadUiState(
    val selectedVariant: ModelVariant? = null,
    val isDownloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val downloadState: DownloadState = DownloadState.Idle,
    val downloadError: DownloadError? = null
)

fun startDownload(variant: ModelVariant) { ... }
fun cancelDownload() { ... }

// Auto-select on completion
onSuccess = { file ->
    preferencesManager.saveModelPath(file.absolutePath)
    _uiState.update { it.copy(modelPath = file.absolutePath) }
}
```

### Modify `ui/tabs/ModelTab.kt` - Model List UI

For each ModelVariant, show:
- Name and description
- Status: Downloaded (✓), Downloading (progress bar), Not downloaded
- Actions: Use/Select, Download, Cancel
<!-- SECTION:PLAN:END -->

## Implementation Notes

<!-- SECTION:NOTES:BEGIN -->
### Modify `ui/viewmodel/ModelViewModel.kt` - Add Download State

```kotlin
data class DownloadUiState(
    val selectedVariant: ModelVariant? = null,
    val isDownloading: Boolean = false,
    val downloadProgress: Float = 0f,
    val downloadState: DownloadState = DownloadState.Idle,
    val downloadError: DownloadError? = null
)

fun startDownload(variant: ModelVariant) { ... }
fun cancelDownload() { ... }

// Auto-select on completion
onSuccess = { file ->
    preferencesManager.saveModelPath(file.absolutePath)
    _uiState.update { it.copy(modelPath = file.absolutePath) }
}
```

### Modify `ui/tabs/ModelTab.kt` - Model List UI

For each ModelVariant, show:
- Name and description
- Status: Downloaded (✓), Downloading (progress bar), Not downloaded
- Actions: Use/Select, Download, Cancel
<!-- SECTION:PLAN:END -->
<!-- SECTION:NOTES:END -->
