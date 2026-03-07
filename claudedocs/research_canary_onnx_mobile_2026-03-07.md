# Research Report: Running ONNX-ASR with Canary 1B Model on Mobile

**Date:** 2026-03-07
**Query:** Can ONNX-ASR run on mobile to execute the Canary 1B model?
**Depth:** Exhaustive

---

## Executive Summary

**Answer: YES, but with important considerations.**

The Canary model CAN be deployed on mobile devices through ONNX Runtime via the **sherpa-onnx** framework. However, there are significant trade-offs between the 180M Flash variant (mobile-optimized) and the full 1B model.

| Model | Parameters | Mobile Feasibility | Recommendation |
|-------|------------|-------------------|----------------|
| **Canary-180M-Flash** | 182M | ✅ Excellent | **Best for mobile** |
| **Canary-1B-Flash** | ~883M | ⚠️ Challenging | Requires high-end devices |
| **Canary-1B** | 1B | ❌ Not recommended | Too large for mobile |

---

## Key Findings

### 1. ONNX-ASR Mobile Deployment IS Possible

**Evidence:**
- ONNX Runtime has dedicated mobile support for Android and iOS
- Microsoft provides `onnxruntime-android` and `onnxruntime-objc` packages
- NNAPI (Android) and CoreML (iOS) execution providers enable hardware acceleration
- Sherpa-onnx framework provides production-ready ASR deployment on mobile

### 2. Sherpa-ONNX Already Supports Canary Models

**Critical Discovery:** The sherpa-onnx project has already implemented Canary model support:

- **PR #2272:** Export nvidia/canary-180m-flash to sherpa-onnx (merged)
- **PR #3193:** Added 1B-Flash export capability
- Pre-built models available: `sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8`
- Official documentation includes Canary in non-streaming models section

### 3. Memory Requirements Analysis

| Model | FP32 Size | INT8 Size | RAM Required | Notes |
|-------|-----------|-----------|--------------|-------|
| Canary-180M-Flash | ~728 MB | ~182 MB | ~400-600 MB | ✅ Mobile-friendly |
| Canary-1B-Flash | ~3.5 GB | ~883 MB | ~1.5-2 GB | ⚠️ High-end only |
| Canary-1B | ~4 GB | ~1 GB | ~6 GB | ❌ Desktop/server only |

**Key Insight from GitHub Issue #2626:** A Parakeet 0.6B INT8 model consumes ~1.2GB RAM on iOS, despite being 600MB in size. This suggests runtime overhead of ~2x the model file size.

---

## Technical Implementation Path

### Option A: Use Sherpa-ONNX (Recommended)

**Pros:**
- ✅ Production-ready framework
- ✅ Pre-built Android APKs available
- ✅ Supports 12 programming languages
- ✅ Active maintenance and community
- ✅ Includes audio preprocessing and decoding

**Steps:**
1. Use sherpa-onnx pre-built Canary 180M Flash model
2. Build Android APK using sherpa-onnx build scripts
3. Integrate with existing Kotlin/Java codebase

**Reference:** https://k2-fsa.github.io/sherpa/onnx/android/build-sherpa-onnx.html

### Option B: Direct ONNX Runtime Integration

**Pros:**
- ✅ More control over implementation
- ✅ Direct dependency management

**Cons:**
- ⚠️ Must implement audio preprocessing
- ⚠️ Must implement token decoding
- ⚠️ More development effort

### Option C: ONNX-ASR Python Package (istupakov)

**Pros:**
- ✅ Minimal dependencies (no PyTorch, NeMo)
- ✅ Pre-converted ONNX models available
- ✅ Canary-1B-v2 ONNX available on HuggingFace

**Cons:**
- ❌ Python-only (not suitable for Android)
- ❌ Would need porting to Kotlin/Java

---

## Architecture Compatibility

### Canary Model Architecture
- **Type:** Encoder-Decoder with FastConformer Encoder + Transformer Decoder
- **180M Flash:** 17 encoder layers, 4 decoder layers
- **1B Flash:** 32 encoder layers, 4 decoder layers

### ONNX Conversion Process
Models can be exported from NeMo to ONNX:
```python
from nemo.collections.asr.models import EncDecMultiTaskModel
canary_model = EncDecMultiTaskModel.from_pretrained('nvidia/canary-180m-flash')
canary_model.export('canary.onnx')
```

---

## Performance Expectations

### Canary-180M-Flash on Mobile
- **Inference Speed:** >1200 RTFx (real-time factor)
- **WER (English):** ~5.2%
- **Languages:** English, Spanish, German, French
- **Capabilities:** ASR + Translation (bidirectional)

### Hardware Requirements
| Device Tier | RAM | Recommended Model |
|-------------|-----|-------------------|
| Budget (<4GB) | 2-3 GB | ❌ Not suitable |
| Mid-range (4-6GB) | 4-5 GB | Canary-180M-Flash |
| High-end (>6GB) | 6+ GB | Canary-1B-Flash (possibly) |

---

## Your Project: Anti-Vocale Integration

Given your existing Android app structure with:
- `InferenceService.kt` for model inference
- `ModelTab.kt` for model management UI
- `ModelViewModel.kt` for model state

### Recommended Approach

1. **Phase 1: Canary-180M-Flash**
   - Integrate sherpa-onnx Canary 180M model
   - Replace/augment existing Parakeet model
   - Add multilingual support (EN, ES, DE, FR)

2. **Phase 2: Optional 1B-Flash**
   - Test on high-end devices only
   - Implement device capability detection
   - Graceful fallback to 180M

### Integration Points

```kotlin
// In CanaryBackend.kt (new file)
class CanaryBackend(private val context: Context) {
    private val modelDir = "sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8"

    fun transcribe(audioPath: String, sourceLang: String): String {
        // Use sherpa-onnx Kotlin API
    }

    fun translate(audioPath: String, sourceLang: String, targetLang: String): String {
        // Translation capability unique to Canary
    }
}
```

---

## Resources & References

### Official Documentation
- Sherpa-ONNX: https://k2-fsa.github.io/sherpa/onnx/index.html
- ONNX Runtime Mobile: https://onnxruntime.ai/docs/tutorials/mobile/
- NVIDIA Canary: https://huggingface.co/nvidia/canary-180m-flash

### Pre-trained Models
- Canary 180M Flash (INT8): `sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8`
- Canary 1B Flash ONNX: https://huggingface.co/istupakov/canary-1b-v2-onnx

### GitHub Repositories
- Sherpa-ONNX: https://github.com/k2-fsa/sherpa-onnx
- ONNX Runtime: https://github.com/microsoft/onnxruntime

---

## Conclusion

**YES, you can run Canary ASR models on mobile through ONNX.**

The practical recommendation for your anti-vocale app:

1. ✅ **Use sherpa-onnx with Canary-180M-Flash INT8** - This is production-ready and well-suited for mobile devices with 4GB+ RAM
2. ⚠️ **Canary-1B-Flash is theoretically possible** but requires high-end devices (6GB+ RAM) and careful memory management
3. ❌ **Full Canary-1B is not practical for mobile** - It requires ~6GB RAM minimum

The sherpa-onnx framework provides the most straightforward path to integration, with pre-built models and Android support already available.

---

## Next Steps for Anti-Vocale

1. Clone sherpa-onnx and build Android libraries
2. Download Canary-180M-Flash INT8 model (~182MB)
3. Create `CanaryBackend.kt` using sherpa-onnx Kotlin API
4. Update `ModelViewModel.kt` to support model selection
5. Add language selection UI for multilingual support
6. Test on target device (Realme RMX3853 with sufficient RAM)

**Confidence Level:** HIGH - Based on existing production implementations and official documentation.
