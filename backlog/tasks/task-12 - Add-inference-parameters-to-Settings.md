---
id: TASK-12
title: Add inference parameters to Settings
status: To Do
assignee: []
created_date: '2026-02-28 19:43'
updated_date: '2026-03-05 10:26'
labels:
  - settings
  - enhancement
  - future
dependencies: []
priority: low
---

## Description

<!-- SECTION:DESCRIPTION:BEGIN -->
Add settings for inference parameters like temperature, max tokens, top_k, top_p. These would be applied to all inference requests. Users could tune these for their use case (creative vs. precise responses).

## Technical Specification

### Current State (Hardcoded)
The `LlmManager` currently uses hardcoded values:
```kotlin
// LiteRT-LM backend
SamplerConfig(topK = 40, topP = 0.95, temperature = 0.8)

// MediaPipe backend  
.setMaxTokens(2048)
.setTemperature(0.8f)
.setTopK(40)
```

### Inference Parameters to Configure

| Parameter | Type | Range | Default | Description |
|-----------|------|-------|---------|-------------|
| `temperature` | Float | 0.0 - 2.0 | 0.8 | Controls randomness. Lower = more deterministic, Higher = more creative |
| `topK` | Int | 1 - 100 | 40 | Limits token selection to top K most probable tokens |
| `topP` | Float | 0.0 - 1.0 | 0.95 | Nucleus sampling - cumulative probability threshold |
| `maxTokens` | Int | 64 - 4096 | 2048 | Maximum tokens to generate in response |

### Preset Profiles (Optional Enhancement)
Provide quick-select presets for common use cases:
- **Precise** (temp=0.3, topK=20, topP=0.9): Factual, consistent responses
- **Balanced** (temp=0.8, topK=40, topP=0.95): Default, good for general use
- **Creative** (temp=1.2, topK=60, topP=0.99): More varied, creative responses

### Data Storage
```kotlin
// PreferencesManager.kt
private val INFERENCE_TEMPERATURE = floatPreferencesKey("inference_temperature")
private val INFERENCE_TOP_K = intPreferencesKey("inference_top_k")
private val INFERENCE_TOP_P = floatPreferencesKey("inference_top_p")
private val INFERENCE_MAX_TOKENS = intPreferencesKey("inference_max_tokens")

val inferenceTemperature: Flow<Float> = context.dataStore.data
    .map { it[INFERENCE_TEMPERATURE] ?: 0.8f }

val inferenceTopK: Flow<Int> = context.dataStore.data
    .map { it[INFERENCE_TOP_K] ?: 40 }

val inferenceTopP: Flow<Float> = context.dataStore.data
    .map { it[INFERENCE_TOP_P] ?: 0.95f }

val inferenceMaxTokens: Flow<Int> = context.dataStore.data
    .map { it[INFERENCE_MAX_TOKENS] ?: 2048 }
```

### UI Components (Settings Tab)
```
┌─────────────────────────────────────────────┐
│ Inference Parameters                        │
├─────────────────────────────────────────────┤
│ Temperature                     [0.8    ▼]  │
│ ├─ Slider: 0.0 ────●────── 2.0              │
│ └─ Controls randomness in responses         │
│                                             │
│ Top K                           [40     ▼]  │
│ ├─ Slider: 1 ──────●────── 100              │
│ └─ Limits token selection to top K          │
│                                             │
│ Top P (Nucleus)                 [0.95   ▼]  │
│ ├─ Slider: 0.0 ────●────── 1.0              │
│ └─ Cumulative probability threshold         │
│                                             │
│ Max Tokens                      [2048   ▼]  │
│ ├─ Slider: 64 ─────●────── 4096             │
│ └─ Maximum response length                  │
│                                             │
│ [Precise] [Balanced*] [Creative]            │
│ (* = current)                               │
└─────────────────────────────────────────────┘
```

### Integration Points

#### 1. LlmManager.kt - Apply Parameters on Initialization
```kotlin
suspend fun initializeWithSettings(context: Context, path: String): Result<Unit> {
    val temp = preferencesManager.inferenceTemperature.first()
    val topK = preferencesManager.inferenceTopK.first()
    val topP = preferencesManager.inferenceTopP.first()
    val maxTokens = preferencesManager.inferenceMaxTokens.first()
    
    val conversationConfig = ConversationConfig(
        samplerConfig = SamplerConfig(
            topK = topK,
            topP = topP,
            temperature = temp
        )
    )
    // ... apply to engine
}
```

#### 2. Runtime Parameter Updates
When settings change, update the active conversation:
```kotlin
fun updateInferenceParams(temp: Float, topK: Int, topP: Float) {
    // Recreate conversation with new params if engine is ready
    litertConversation?.close()
    litertConversation = litertEngine?.createConversation(
        ConversationConfig(
            samplerConfig = SamplerConfig(topK = topK, topP = topP, temperature = temp)
        )
    )
}
```

### Code Changes Required

1. **PreferencesManager.kt**: Add 4 new preference flows and save methods
2. **SettingsViewModel.kt**: Expose inference params as StateFlow, add save methods
3. **SettingsTab.kt**: Add UI section with sliders and preset buttons
4. **LlmManager.kt**: 
   - Add method to read params from preferences
   - Apply params on initialization
   - Add `updateInferenceParams()` for runtime changes

### Considerations
- **Performance**: Changing params requires recreating the conversation (minor overhead)
- **Backend Compatibility**: MediaPipe backend only supports temp and topK
- **Validation**: Clamp values to valid ranges before applying
- **Real-time Preview**: Consider showing effect description as user adjusts sliders
<!-- SECTION:DESCRIPTION:END -->

## Acceptance Criteria
<!-- AC:BEGIN -->
- [ ] #1 Temperature slider (0.0-2.0) with visual feedback in Settings tab
- [ ] #2 Top K slider (1-100) with current value display
- [ ] #3 Top P slider (0.0-1.0) with current value display
- [ ] #4 Max Tokens slider (64-4096) with current value display
- [ ] #5 Preset buttons: Precise, Balanced, Creative for quick selection
- [ ] #6 All parameters persisted in DataStore and survive app restarts
- [ ] #7 LlmManager reads params from preferences on model initialization
- [ ] #8 Runtime parameter updates applied without full model reload
- [ ] #9 Values clamped to valid ranges before applying
- [ ] #10 Settings UI follows existing patterns (similar to keep-alive timeout)
<!-- AC:END -->
