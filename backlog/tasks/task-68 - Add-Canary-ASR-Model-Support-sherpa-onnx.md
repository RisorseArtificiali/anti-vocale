Task TASK-68 - Add Canary ASR Model Support (sherpa-onnx)
==================================================

Status: ✗ Won't Do
Priority: Medium
Created: 2026-03-06 23:36
Updated: 2026-03-07 16:15
Labels: model-download, sherpa-onnx, canary, feature

Description:
--------------------------------------------------
Add NVIDIA Canary as a third ASR model option alongside Parakeet TDT and Whisper.

## Decision: Won't Implement

After research and testing, Canary ASR was found to be **not viable for mobile**:

1. **Canary 1B v2** (~1GB): Too large, slow initialization (~15s), and the ONNX conversion from HuggingFace is incompatible with sherpa-onnx's `OfflineCanaryModelConfig` (file naming and model graph structure differ)

2. **Canary 180M Flash** (~200MB): Smaller but still slow initialization, and only supports 4 languages vs Parakeet's 25

3. **Performance**: Both models had slow initialization compared to Parakeet TDT (~464MB, fast init, 25 languages)

## Conclusion

Parakeet TDT remains the best ASR option for this app - small, fast, multilingual. Whisper models are available for users who need multilingual support with proper punctuation.

**Code removed**: All Canary-related code was removed in commit e2998f5.

Final Summary:
--------------------------------------------------
## Won't Implement

After research and testing, Canary ASR was found to be **not viable for mobile**:

1. **Canary 1B v2** (~1GB): Too large, slow initialization (~15s), and the ONNX conversion from HuggingFace is incompatible with sherpa-onnx's `OfflineCanaryModelConfig` (file naming and model graph structure differ)

2. **Canary 180M Flash** (~200MB): Smaller but still slow initialization, and only supports 4 languages vs Parakeet's 25

3. **Performance**: Both models had slow initialization compared to Parakeet TDT (~464MB, fast init, 25 languages)

**Conclusion**: Parakeet TDT remains the best ASR option for this app - small, fast, multilingual. Whisper models are available for users who need multilingual support with proper punctuation.

**Code removed**: All Canary-related code that was initially added has been removed in commit e2998f5.
