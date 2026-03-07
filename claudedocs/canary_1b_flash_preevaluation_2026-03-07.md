# Canary-1B-Flash Mobile Deployment Pre-Evaluation

**Date:** 2026-03-07
**Status:** ⚠️ CRITICAL FINDINGS - Read before proceeding

---

## 🚨 Critical Discovery

After thorough investigation, I found that **Canary-1B-Flash does NOT have a pre-built sherpa-onnx model available**.

### Evidence

1. **GitHub Issue #3190** (Opened: Feb 15, 2026) - **STILL OPEN**
   > "Add pretrained model support for canary-1b-flash? This model ranks really high for multilingual and fast. Would be really nice to have this."
   - Status: **OPEN** - No pre-built model exists yet

2. **PR #3193** (Opened: Feb 16, 2026) - **NOT MERGED**
   > "fixed 180m-flash export and added 1b-flash export"
   - This only adds **export scripts**, not pre-built models
   - You would need to export the model yourself

3. **Official sherpa-onnx documentation** only lists:
   - `sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8` ✅
   - **NO** `sherpa-onnx-nemo-canary-1b-flash-*` ❌

---

## Comparison: What's Actually Available

| Feature | Canary-180M-Flash | Canary-1B-Flash |
|---------|-------------------|-----------------|
| **Pre-built sherpa-onnx model** | ✅ Yes | ❌ No |
| **Android APK available** | ✅ Yes | ❌ No |
| **Kotlin/Java API** | ✅ Yes | ⚠️ Only export scripts |
| **Mobile memory benchmarks** | ⚠️ Limited | ❌ None |
| **Official documentation** | ✅ Yes | ❌ No |
| **Risk level** | **LOW** | **HIGH** |

---

## Canary-1B-Flash: What Would Be Required

If you want to use Canary-1B-Flash on mobile, you would need to:

### Step 1: Export the Model Yourself
```bash
# From sherpa-onnx repository
cd scripts/nemo/canary
./run_1b_flash.sh  # This requires NeMo + PyTorch environment
```

**Requirements:**
- Python environment with NeMo toolkit
- NVIDIA GPU recommended for export
- ~16GB RAM for export process
- Understanding of ONNX export process

### Step 2: Verify Mobile Compatibility
- No existing benchmarks for mobile
- Would need to test memory usage on device
- Unknown if it fits in 2GB RAM (typical mobile limit)

### Step 3: Integrate into Android App
- Use sherpa-onnx Android bindings
- But no pre-existing example code for 1B-Flash

---

## Memory Estimation for Canary-1B-Flash

Based on similar models:

| Model | Parameters | FP32 Size | INT8 Size | Est. RAM Needed |
|-------|------------|-----------|-----------|-----------------|
| Canary-180M-Flash | 182M | ~728 MB | ~182 MB | ~400-600 MB |
| Canary-1B-Flash | 883M | ~3.5 GB | ~883 MB | **~1.8-2.5 GB** |

**Critical Issue:** Most Android devices only allocate 1-2GB for a single app. The 1B-Flash model may exceed this limit.

---

## Real-World Evidence: Canary-1B-v2 on Jetson

From NVIDIA forum (Jetson AGX Orin 64GB):
> "OOM Error Running canary-1b-v2 on Jetson AGX Orin"
> "canary-1b-v2 runs on an A100 GPU with a 30-minute audio file, using around **20 GB of GPU memory**"

Even the 1B-v2 (similar size to 1B-Flash) has memory issues on embedded devices with 64GB RAM!

---

## Risk Assessment

### Canary-180M-Flash: **LOW RISK**
- ✅ Pre-built model exists
- ✅ Documented and tested
- ✅ Designed for mobile
- ⚠️ Limited to 4 languages (EN, ES, DE, FR)

### Canary-1B-Flash: **HIGH RISK**
- ❌ No pre-built model
- ❌ No mobile benchmarks
- ❌ Likely memory issues
- ❌ Requires manual export process
- ⚠️ Could waste weeks like onnx-asr did

---

## Honest Recommendation

Given your experience with onnx-asr wasting time:

### Option A: Use Canary-180M-Flash (RECOMMENDED)
- **Time to working prototype:** 1-2 days
- **Risk of failure:** LOW
- **Languages:** EN, ES, DE, FR (4 languages)
- **Trade-off:** Slightly lower accuracy than 1B

### Option B: Wait for Canary-1B-Flash Support
- **Risk:** sherpa-onnx may never add pre-built 1B-Flash
- **Alternative:** Export yourself (HIGH risk of mobile memory issues)

### Option C: Consider Parakeet-TDT-0.6B-v3
- **Pre-built:** ✅ Yes
- **Languages:** 25 European languages
- **WER:** 6.32% (slightly higher than Canary 5.2%)
- **RTFx:** 3332 (faster than Canary's 1045)
- **Designed for:** Mobile-friendly

---

## The onnx-asr Lesson

Your concern is valid. The onnx-asr situation was:
- Python-only package
- No mobile support
- Wasted development time

With Canary-1B-Flash, the risk is similar:
- No pre-built model
- Unknown mobile feasibility
- Could invest time and discover memory issues

---

## Conclusion

**Canary-1B-Flash is NOT ready for mobile deployment.**

The safest path is:
1. **Use Canary-180M-Flash** for 4-language support
2. **Or use Parakeet-TDT-0.6B-v3** for 25-language support
3. **Wait** until sherpa-onnx officially adds Canary-1B-Flash pre-built model

**Do not attempt Canary-1B-Flash** without:
- Pre-built model from sherpa-onnx
- Verified mobile memory benchmarks
- Android example code

---

## Next Steps (If You Want to Proceed Safely)

1. **Verify Canary-180M-Flash works on your device:**
   ```bash
   wget https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8.tar.bz2
   ```

2. **Test memory usage with a simple Android app first**

3. **Only after 180M-Flash is working**, consider 1B-Flash (if/when pre-built)

---

**Final Verdict:** Canary-1B-Flash is **NOT recommended** for mobile at this time. Use Canary-180M-Flash or Parakeet-TDT-0.6B-v3 instead.
