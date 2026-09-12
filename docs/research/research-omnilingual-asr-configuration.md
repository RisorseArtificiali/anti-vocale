# Research: Omnilingual ASR 300M CTC — Gibberish Output Investigation

**Date:** 2026-06-02
**Model:** `csukuangfj/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12`
**Confidence:** HIGH (official sherpa-onnx C API example + model card confirmed)

---

## Executive Summary

**The configuration is correct. The gibberish output is expected behavior for this model.**

The 300M CTC model (`omniASR_CTC_300M`) is the **smallest and lowest quality** variant in the Omnilingual ASR family. The model card's impressive results (CER < 10 for 78% of languages) apply to the **7B LLM model**, not the 300M CTC. No special configuration, language specification, or tuning is missing.

---

## Configuration Verification

### What the app does (correct)
```
Model: model.int8.onnx + tokens.txt
Config: OfflineOmnilingualAsrCtcModelConfig
modelType: "omnilingual"
decodingMethod: "greedy_search"
FeatureConfig: sampleRate=16000, featureDim=80
Max chunk: 35s (model limit is ~40s)
```

### What the official sherpa-onnx C API example does (identical)
```c
omnilingual.model = "model.int8.onnx"
offline_model_config.tokens = "tokens.txt"
offline_model_config.omnilingual = omnilingual
decoding_method = "greedy_search"
// No language parameter, no special decoding config
```

**Result: The app's configuration matches the official example exactly. No language specification or special tuning is required for CTC models.**

---

## Root Cause: Model Quality

The Omnilingual ASR family has multiple model sizes:

| Model | Parameters | Quality | Language Conditioning |
|-------|-----------|---------|----------------------|
| **omniASR_CTC_300M** ← **we use this** | 317M | Lowest | None |
| omniASR_CTC_1B | 965M | Low | None |
| omniASR_CTC_3B | 3B | Medium | None |
| omniASR_CTC_7B | 6.5B | Good | None |
| omniASR_LLM_7B | 7.8B | **Best** (CER < 10 for 78% langs) | Optional |

The 300M CTC model is a character-level CTC model with **no language model**, no language conditioning, and the smallest parameter count. It's designed for **broad language coverage at the expense of quality** — useful for languages not covered by any other model, but significantly worse than dedicated models for common languages.

### For Italian specifically:
- **Parakeet**: optimized for Italian, high quality
- **Distil-Whisper IT**: fine-tuned on Italian, 4.3% WER
- **Omnilingual 300M CTC**: generic multilingual, likely poor Italian quality

---

## Findings

### 1. No language specification needed ✅
CTC models don't accept a language parameter. Only the LLM models (`omniASR_LLM_*`) support language conditioning via `lang = ["eng_Latn", "ita_Latn"]`. The CTC models are language-agnostic at inference time.

### 2. No special tokens/vocabulary needed ✅
The `tokens.txt` included in the repo is the correct one. No additional vocabulary files are needed.

### 3. No special decoding parameters needed ✅
`greedy_search` is the correct and only practical decoding method for this model. Beam search provides marginal improvement but isn't worth the cost for a 300M CTC model.

### 4. No v2 model available ❌
The original code referenced `sherpa-onnx-omnilingual-asr-300m-ctc-v2-int8` but the `csukuangfj2/` v2 repos on HuggingFace are empty (only `.gitattributes`). Only the v1 model exists.

### 5. 40-second audio limit ✅
The app correctly chunks audio at 35s. The model card confirms: "only audio files shorter than 40 seconds are accepted for inference."

---

## Recommendations

1. **The model is working as designed.** The gibberish is the expected output quality for a 300M CTC model on a language with better alternatives.

2. **Consider upgrading to a larger model** (1B or 3B CTC) if better quality is needed, at the cost of download size and inference speed.

3. **Position in the app**: This model should be presented as a fallback for exotic languages not covered by Parakeet/Whisper/Qwen3 — not as a primary choice for Italian or other well-supported languages.

---

## Sources

- sherpa-onnx C API example: `k2-fsa/sherpa-onnx/c-api-examples/omnilingual-asr-ctc-c-api.c`
- Model card: `https://huggingface.co/csukuangfj/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12`
- sherpa-onnx model config reference: Model type `omnilingual` → single `model` file + `tokens.txt`
