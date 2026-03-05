---
id: TASK-11
title: Add model selection from Settings tab
status: Done
assignee: []
created_date: '2026-02-28 19:43'
updated_date: '2026-03-05 09:40'
labels:
  - settings
  - enhancement
  - future
dependencies: []
priority: low
ordinal: 32000
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Allow users to select/change the model directly from the Settings tab, in addition to the Model tab.

## Implementation Status: COMPLETED ✅

This feature has been implemented. The Settings tab now includes a model selection section that displays available models and allows switching between them.

### Implemented Features

#### Model Discovery
- **Location**: `ModelDiscovery.kt`
- **Function**: `discoverAvailableModels(context)` scans for:
  - Downloaded Gemma models (from HuggingFace downloads)
  - Google AI Edge Gallery models
  - User-selected local models
- **Returns**: `List<DiscoveredModel>` with name, path, source type, and size

#### Settings Tab UI
- **Section**: "Model Management" in Settings
- **Components**:
  1. Current model display showing active model name and path
  2. "Scan for Models" button to discover available models
  3. Model list showing all discovered models with selection indicator
  4. Click to select a model as active

#### ViewModel Integration
- **Location**: `SettingsViewModel.kt`
- **State Flows**:
  - `currentModelPath: StateFlow<String?>` - Path to active model
  - `currentModelName: StateFlow<String?>` - Display name of active model
  - `availableModels: StateFlow<List<DiscoveredModel>>` - Discovered models
- **Actions**:
  - `loadCurrentModel()` - Load active model from preferences
  - `scanAvailableModels()` - Discover all available models
  - `selectModel(model: DiscoveredModel)` - Set model as active

#### Backend Awareness
The implementation correctly handles multiple transcription backends:
- **LLM Backend**: Shows Gemma/other LLM models
- **Sherpa-onnx Backend**: Shows Parakeet TDT model
- **Whisper Backend**: Shows Whisper model variants

### Files Modified
- `app/src/main/java/com/localai/bridge/data/ModelDiscovery.kt` - Model discovery logic
- `app/src/main/java/com/localai/bridge/ui/viewmodel/SettingsViewModel.kt` - ViewModel state
- `app/src/main/java/com/localai/bridge/ui/tabs/SettingsTab.kt` - UI components

### User Flow
1. Open Settings tab
2. Scroll to "Model Management" section
3. Click "Scan for Models" to discover available models
4. Click on a model in the list to select it
5. Selected model becomes active immediately
<!-- SECTION:DESCRIPTION:END -->
