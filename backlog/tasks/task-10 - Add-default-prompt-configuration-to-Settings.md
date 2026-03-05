---
id: TASK-10
title: Add default prompt configuration to Settings
status: Done
assignee:
  - claude
created_date: '2026-02-28 19:43'
updated_date: '2026-03-05 11:31'
labels:
  - settings
  - enhancement
  - future
dependencies: []
priority: low
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Allow users to configure a default prompt that will be used when no prompt is provided in requests. This is useful for audio transcription where the same prompt (e.g., "Transcribe this speech:") is typically used.

## Technical Specification

### Data Storage
- **Preference Key**: `default_prompt` (String)
- **Storage**: `PreferencesManager` using DataStore
- **Default Value**: Empty string (no default prompt)
- **Max Length**: 500 characters

### UI Components (Settings Tab)
1. **Section Header**: "Default Transcription Prompt"
2. **Text Field**: 
   - Single-line or multi-line text input
   - Placeholder: "e.g., Transcribe this speech:"
   - Character counter showing current/max length
   - Validation: max 500 chars
3. **Helper Text**: "This prompt will be used when transcribing audio. Leave empty to use the default system prompt."
4. **Save Button**: Persist to DataStore on click

### Integration Points
1. **TranscriptionService**: Check `PreferencesManager.defaultPrompt` before using hardcoded prompt
2. **TranscriptionViewModel**: Expose default prompt as StateFlow for UI binding
3. **AudioTranscriptionWorker**: Use default prompt when `prompt` parameter is null or empty

### Code Changes Required

#### PreferencesManager.kt
```kotlin
private val DEFAULT_PROMPT = stringPreferencesKey("default_prompt")

val defaultPrompt: Flow<String> = context.dataStore.data
    .map { preferences -> preferences[DEFAULT_PROMPT] ?: "" }

suspend fun saveDefaultPrompt(prompt: String) {
    context.dataStore.edit { preferences ->
        preferences[DEFAULT_PROMPT] = prompt.take(500) // Enforce max length
    }
}
```

#### SettingsViewModel.kt
```kotlin
val defaultPrompt: StateFlow<String> = preferencesManager.defaultPrompt
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "")

fun saveDefaultPrompt(prompt: String) {
    viewModelScope.launch {
        preferencesManager.saveDefaultPrompt(prompt)
        _uiState.update { it.copy(saveSuccess = true) }
    }
}
```

#### SettingsTab.kt
Add new section after "Auto-copy" section with OutlinedTextField component.

### Acceptance Criteria
- User can enter/edit a default prompt in Settings
- Prompt persists across app restarts
- Empty prompt means use system default
- Character limit enforced at 500 chars
- Prompt is used in all transcription operations when no custom prompt provided
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [x] #1 User can enter/edit a default prompt in Settings tab
- [x] #2 Prompt is persisted in DataStore and survives app restarts
- [x] #3 Empty prompt field means use system default transcription prompt
- [x] #4 Character limit of 500 is enforced with visual counter
- [x] #5 Default prompt is used in TranscriptionService when no custom prompt provided
- [x] #6 Default prompt is used in AudioTranscriptionWorker when prompt is null/empty
- [x] #7 Settings UI follows existing patterns (keep-alive timeout style)
<!-- AC:END -->

## Final Summary

<!-- SECTION:FINAL_SUMMARY:BEGIN -->
## Implementation Complete

Added default prompt configuration to Settings tab with the following changes:

### Files Modified
1. **PreferencesManager.kt** - Added `defaultPrompt` Flow and `saveDefaultPrompt()` method with 500 char limit
2. **SettingsViewModel.kt** - Exposed `defaultPrompt` StateFlow and `saveDefaultPrompt()` save method
3. **SettingsTab.kt** - Added UI section with OutlinedTextField, character counter, and save button
4. **InferenceService.kt** - Integrated default prompt: request prompt → saved default → hardcoded fallback
5. **strings.xml** - Added string resources for the new UI

### Acceptance Criteria Status
All criteria met:
- ✅ User can enter/edit default prompt in Settings tab
- ✅ Prompt persisted in DataStore (survives restarts)
- ✅ Empty prompt uses system default ("Transcribe this speech:")
- ✅ 500 character limit with visual counter
- ✅ Used in TranscriptionService when no custom prompt
- ✅ N/A - No AudioTranscriptionWorker exists in codebase
- ✅ UI follows existing patterns (Card with OutlinedTextField)

### Build Status
`gradle assembleDebug` completed successfully.
<!-- SECTION:FINAL_SUMMARY:END -->
