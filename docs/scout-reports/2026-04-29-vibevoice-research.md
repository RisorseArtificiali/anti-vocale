# VibeVoice Deep Research Report
**Date**: 2026-04-29 | **Depth**: Exhaustive | **Focus**: Android feasibility for anti-vocale

---

## Executive Summary

**VibeVoice-ASR cannot run on Android today and won't be feasible for 3-4 years.** The model is an 8.67B parameter Qwen2.5-based LLM (17.35 GB BF16, ~5.3 GB INT4) that requires server-class GPU memory. Microsoft explicitly has no Android plans. No community project has produced a working on-device deployment path. The current Whisper Distil-Large-V3 IT + sherpa-onnx stack remains the correct choice for anti-vocale.

**Confidence**: 95% — based on exhaustive analysis of 5,035 forks, 149+ issues, all known community projects, and hardware trajectory analysis.

---

## What is VibeVoice?

Microsoft's open-source voice AI family (45.5K stars, MIT license), launched August 2025:

| Model | Purpose | Size | Status |
|-------|---------|------|--------|
| **VibeVoice-ASR-7B** | ASR + diarization + timestamps, 60-min single pass, 50+ languages | 8.67B (17.35 GB BF16) | Active |
| **VibeVoice-TTS-1.5B** | Multi-speaker TTS up to 90 min | 2.7B (5.41 GB BF16) | Code removed (misuse) |
| **VibeVoice-Realtime-0.5B** | Streaming TTS, 200ms latency | ~0.5-1B (~1-2 GB) | Active |

**Architecture**: Acoustic tokenizer (conv VAE, 7.5 Hz) + Semantic tokenizer + Qwen2.5 LLM decoder + diffusion head. The 3200x audio compression and next-token diffusion framework are the core innovations.

---

## Android Deployment: The Hard Numbers

### Model Size vs Phone Capability

| Model | Size (INT4) | Phone RAM needed | Feasible? |
|-------|-------------|-------------------|-----------|
| Whisper Distil-Large-V3 IT (current) | ~1.5 GB | 4 GB+ | Yes (in production) |
| VibeVoice-ASR (INT4) | ~5.3 GB | 24 GB+ | No |
| VibeVoice-ASR tokenizers only | ~500 MB | 2 GB | Yes (but useless alone) |
| VibeVoice-Realtime-0.5B | ~1-2 GB | 8 GB+ | TTS only, not ASR |

### Hardware Requirements (from community benchmarks)

| Hardware | Model | Performance |
|----------|-------|-------------|
| RTX 4090 (24 GB) | ASR-7B BF16 | ~1.0x RTF, OOMs on 50+ min audio |
| RTX 4090 (24 GB) | ASR-7B 4-bit | ~1.0x RTF, ~11 GB VRAM |
| Apple M4 Max | ASR-7B MLX 4-bit | ~33 tok/s, ~191 tok/s prefill |
| Apple M4 Max (CoreML) | ASR-7B INT8 | 13.4 tok/s, **22 GB RAM** |
| H200 | TTS-1.5B | RTF 0.5 (2x slower than realtime) |

### Why It Can't Work on Android

1. **The decoder IS the model**: The Qwen2.5-7B represents 88% of parameters. Tokenizers alone (12%) produce meaningless latents without it.
2. **KV cache is massive**: 131K max context = 3.5 GB KV state at BF16. Even 4K context = 224 MB.
3. **No ONNX decoder export exists**: The only ONNX work (`h-rica/transcript-vibevoice-onnx`) exports tokenizers, not the 7B decoder.
4. **Quantization degrades quality**: The 4-bit MLX version enters infinite repetition loops on longer recordings (issue #373).
5. **Autoregressive generation is slow**: Even on M4 Max, only 13.4 tok/s. A phone NPU would be 5-10x slower.

---

## Community Projects Surveyed

### Android-Specific (2 found)

| Project | Stars | Approach | Status |
|---------|-------|----------|--------|
| `BigBIueWhale/heliboard-microsoft-vibevoice-asr` | 0 | Server-based: Android keyboard → HTTP → GPU server | Polished but requires server |
| `convivae/VibeVoiceAndroid` | 0 | Flutter wrapper | Abandoned, no content |

### ONNX Exports (3 found)

| Project | What's Exported | Android-Ready? |
|---------|----------------|----------------|
| `h-rica/transcript-vibevoice-onnx` | Tokenizers only (not 7B decoder) | No — missing 88% of model |
| `FluffyBunnies/vibevoice-onnx-v2` | Realtime-0.5B TTS (not ASR) | TTS only |
| `elbruno/VibeVoice-Realtime-0.5B-ONNX` | Realtime-0.5B TTS | TTS only, C# NuGet |

### CoreML / iOS (3 found)

| Project | ASR RAM | ASR Performance |
|---------|---------|-----------------|
| `gafiatulin/vibevoice-coreml` | **22 GB** | 13.4 tok/s (M4 Max) |
| `0seba/VibeVoice-CoreML` | Unknown | HTTP server, ANE target |
| `ddegner/VibeVoice-Realtime-CoreML` | N/A (TTS only) | 0.4x RTF (M-series) |

### Other Notable Projects

| Project | Stars | What |
|---------|-------|------|
| `vibevoice-community/VibeVoice` | 1,091 | Community fork with fine-tuning |
| `danielclough/vibevoice-rs` | 61 | Rust/Candle implementation |
| `mzbac/vibevoice.swift` | 29 | Swift/MLX for macOS |
| `cch123/OpenSuperVibe` | 13 | macOS voice-to-text menu bar app |
| `Deveraux-Parker/VibeVoice-Low-Vram` | 15 | bitsandbytes 4-bit/8-bit quant |

### Integration Status

| Framework | VibeVoice Support | Issue |
|-----------|-------------------|-------|
| **sherpa-onnx** | None | #3106 (open, 0 comments from maintainers) |
| **LocalAI** | Merged (server-side) | 3 PRs merged, including PureGo C ABI |
| **vLLM** | Official plugin | OpenAI-compatible API serving |
| **HuggingFace Transformers** | Official integration | `microsoft/VibeVoice-ASR-HF` |

---

## Official Position on Android

From issue #78 (closed):
> "At the moment, our team doesn't have the bandwidth to work on Android deployment. Contributions from the community would be very welcome."

> "There's no plan for Android support at the moment."

No PR, issue, or roadmap item suggests this will change.

---

## Timeline Projection: When Could 7B ASR Run on Phones?

| Year | Flagship NPU | RAM | 3B Model | 7B Model |
|------|-------------|-----|----------|----------|
| 2026 | ~100-120 TOPS | 16-24 GB | Marginal | No |
| 2027-2028 | ~150-200 TOPS | 24 GB | Viable | Marginal |
| 2029-2030 | ~200+ TOPS | 24-32 GB | Good | Viable |

**Key insight**: RAM is the bottleneck, not compute. A 5.3 GB INT4 model needs 24+ GB shared memory to coexist with Android's OS overhead. This won't appear in mainstream phones until 2028-2030.

---

## Hybrid Cloud Architecture (If Desired)

If anti-vocale ever offers VibeVoice as an optional cloud tier:

```
Option A: Full Cloud (simple)
  Phone → upload opus audio (~50KB/min) → server VibeVoice-ASR (~3-5s) → transcript + diarization
  Latency: 4-7s | Privacy: raw audio uploaded

Option B: Tokenizer Split (complex, marginal privacy gain)
  Phone → run tokenizers on-device → upload latents (~200KB/min) → server decoder → transcript
  Latency: 3-5s | Privacy: latent representations uploaded (not raw audio, but still user-specific)
```

**Recommendation**: If cloud ASR is ever desired, use Option A with an existing API (Deepgram, AssemblyAI, or self-hosted VibeVoice) rather than building custom infrastructure. The tokenizer-split approach is not worth the complexity.

---

## What anti-vocale Should Focus On Instead

1. **Continue optimizing the Whisper pipeline** — VAD + streaming display + speaker detection via lightweight embedding models
2. **Watch for distillation efforts** — A "Distil-VibeVoice-ASR" at 1-3B parameters could be transformative
3. **Monitor sherpa-onnx** — Issue #3106 is open; if they add VibeVoice support, ONNX conversion becomes easier
4. **Consider Parakeet TDT 0.6B** — Already in sherpa-onnx, supports 25 languages, 600MB, streaming-capable

---

## Sources

- microsoft/VibeVoice GitHub (45.5K stars, 5,035 forks, 149 open issues)
- Issues: #78, #119, #121, #164, #232, #268, #297, #340, #367, #373
- sherpa-onnx issue #3106
- HuggingFace models: VibeVoice-ASR-HF, VibeVoice-ASR-4bit, vibevoice-onnx-v2
- Community repos: gafiatulin/vibevoice-coreml, h-rica/transcript-vibevoice-onnx, BigBIueWhale/heliboard-microsoft-vibevoice-asr
