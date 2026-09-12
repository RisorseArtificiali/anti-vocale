# Research Report: Cactus Compute — Edge Inference Engine

**Date**: 2026-05-18
**Scope**: Cactus inference framework for on-device AI, relevance to Anti-Vocale
**Confidence**: High (primary sources: GitHub repo, official docs, HuggingFace blog, InfoQ, YC page)

---

## Executive Summary

**Cactus** (YC S25) is a cross-platform, open-source AI inference engine built in C++ specifically for mobile devices and wearables. It supports **LLM text generation, speech-to-text, vision, embeddings, and tool calling** through a single SDK. For Anti-Vocale, the key finding is that Cactus supports **Whisper, Moonshine, and Parakeet** ASR models with NPU acceleration and a Kotlin SDK — but it uses a proprietary `.cact` model format, not ONNX or GGUF, making it a **competing runtime** rather than a drop-in replacement for sherpa-onnx. The hybrid cloud fallback for transcription is novel but not relevant to our offline-first use case.

---

## What Is Cactus?

| Attribute | Detail |
|-----------|--------|
| **Company** | Cactus Compute, Inc. — Y Combinator S25 |
| **Founders** | Roman Shemet, Henry Ndubuaku (met via YC co-founder matching, London) |
| **GitHub** | `cactus-compute/cactus` — 4.7k stars, 726 commits, 376 forks |
| **License** | Open source (core engine), free tier + paid for hybrid features |
| **Free for** | Students, educators, non-profits, small businesses |
| **Current version** | v1.7 (beta) |
| **Written in** | C++ with custom ARM SIMD kernels |
| **Website** | cactuscompute.com |

### Three-Layer Architecture

```
┌─────────────────────┐
│   Cactus Engine     │ ← OpenAI-compatible APIs (C/C++, Swift, Kotlin, Flutter, RN, Python, Rust)
└─────────────────────┘   Chat, vision, STT, RAG, tool calling, cloud handoff
         │
┌─────────────────────┐
│   Cactus Graph      │ ← Zero-copy computation graph ("PyTorch for mobile")
└─────────────────────┘   Custom models, RAM-optimized, lossless weight quantization
         │
┌─────────────────────┐
│   Cactus Kernels    │ ← ARM SIMD kernels (Apple, Snapdragon, Exynos)
└─────────────────────┘   Custom attention, KV-cache quantization, chunked prefill
```

---

## ASR / Speech-to-Text Support

### Supported ASR Models
Cactus `cactus_transcribe()` API supports three model families:

1. **Whisper** — OpenAI Whisper family (all sizes). Uses 16-bit PCM input at 16kHz.
2. **Moonshine** — Useful Sensors' lightweight ASR (27MB-245MB). English-focused with some community language variants.
3. **Parakeet** — NVIDIA Parakeet family (TDT, CTC variants).

### STT API Features
- File-based transcription (WAV) and raw PCM buffer input
- Optional streaming callback for real-time transcription
- Built-in **Silero VAD** (v1.7+) for voice activity detection
- Language detection with optional VAD pre-filtering
- NPU acceleration on Apple Neural Engine; Qualcomm NPU planned
- Hybrid cloud fallback: routes noisy audio to cloud, clear audio stays on-device

### ASR Model Format
Cactus uses a **proprietary `.cact` format** (since v1), not GGUF or ONNX. Models must be converted to `.cact` for use. Cactus provides download/conversion tooling.

---

## LLM / Text Generation Support

### Supported Models
Any GGUF-compatible model from HuggingFace: Qwen, Gemma, Llama, DeepSeek, Phi, Mistral, SmolLM, SmolVLM, InternVLM, Jan Nano, etc. Models are converted to `.cact` format.

### Quantization
FP32 → FP16 → INT8 → INT4 → **down to 2-bit** quantization supported.

### Benchmarks (from official sources)
| Device | Model | Precision | Speed |
|--------|-------|-----------|-------|
| M4 Pro (Mac) | 1.2B | INT4 | 100 tok/s decode |
| iPhone 17 Pro | 1.2B | INT4 | 48 tok/s decode |
| Android (varies) | Gemma 3 270M | INT4 | Real-time capable |

### Notable LLM Features
- **Tool calling** (MCP-compatible)
- **Auto RAG** (built-in retrieval-augmented generation)
- **Embedding models** support
- **Vision/multimodal** inference (SmolVLM, InternVLM)
- Model versioning and **OTA updates** (push new models without app update)
- **Zero-copy memory mapping** — 10x lower RAM usage, near-instant model loading

---

## SDKs and Platforms

| SDK | Status | Notes |
|-----|--------|-------|
| **Kotlin Multiplatform** | Available (v1.0.2-beta) | Android + iOS via KMP |
| **Flutter** | Available | Most mature SDK |
| **React Native** | Available | Strong community adoption |
| **Swift** | Minimal | iOS devs can use KMP bindings |
| **Python** | Available | Shared lib via FFI |
| **C/C++** | Available | Core engine, full API |
| **Rust** | Available | Bindings to core engine |

### Android Kotlin Integration
```kotlin
// build.gradle.kts
implementation("com.cactuscompute:cactus:1.0.2-beta")

// Init in Activity
CactusContextInitializer.initialize(this)

// Use
val cactusLM = CactusLM()
cactusLM.init(model = "qwen3-0.6", params = CactusInitParams(...))
```

---

## Hybrid Cloud Fallback

Cactus v1.7 introduced **hybrid inference**:
- On-device model handles simple/standard requests (NPU-accelerated)
- Cloud frontier models handle complex or noisy requests
- Confidence-based routing: model evaluates its own certainty in real-time
- For transcription: clear audio → on-device, noisy audio → cloud
- Claims **80%+ of production inference can stay on-device**
- Privacy mode: lock to on-device only (HIPAA-friendly, GDPR-compliant)

---

## Comparison with Anti-Vocale's Current Stack

| Aspect | sherpa-onnx (current) | LiteRT-LM (current) | Cactus |
|--------|----------------------|---------------------|--------|
| **Primary focus** | ASR (speech recognition) | LLM (Gemma multimodal) | General-purpose (LLM + STT + Vision) |
| **Model format** | ONNX (.onnx) | .litertlm | .cact (proprietary) |
| **ASR models** | Whisper, Parakeet, Qwen3-ASR | None (uses LLM for audio) | Whisper, Moonshine, Parakeet |
| **LLM models** | None | Gemma 3N, Gemma 4 E2B/E4B | Qwen, Gemma, Llama, DeepSeek, Phi, Mistral |
| **NPU acceleration** | No (CPU-only via ONNX Runtime) | Yes (GPU/NPU/CPU auto) | Apple Neural Engine; Qualcomm planned |
| **Quantization** | int8 (model-dependent) | Framework-managed | FP32 → 2-bit |
| **Android Kotlin** | AAR (JNI) | Maven dependency | Maven dependency (KMP) |
| **Open source** | Yes (Apache 2.0) | Partial (Google-managed) | Yes (core engine) |
| **Maturity** | Production-stable, v1.13.2 | Production (Google) | Beta (v1.7), YC startup |
| **Offline-first** | Yes | Yes | Yes (with optional cloud fallback) |
| **Italian ASR** | Distil-Large-V3-IT (excellent) | Via Gemma audio (unverified) | Whisper models (but no .cact conversion of our custom model confirmed) |
| **Custom model support** | Any ONNX ASR | Google-managed models | Models they've pre-converted |
| **Community** | Large (k2-fsa) | Large (Google) | Growing (4.7k stars) |

---

## Relevance Assessment for Anti-Vocale

### Potential Benefits
1. **Kotlin SDK** — cleaner integration than sherpa-onnx's AAR + JNI approach
2. **NPU acceleration** — could improve inference speed on supported devices
3. **Unified runtime** — could potentially replace both sherpa-onnx AND LiteRT-LM
4. **OTA model updates** — push new models without app updates
5. **Tool calling** — could enable AI features beyond transcription
6. **Moonshine support** — very lightweight ASR option (27MB) for low-end devices

### Significant Concerns
1. **Proprietary .cact format** — we'd need our Distil-Large-V3-IT model converted. No evidence that custom fine-tunes can be easily converted. This is a dealbreaker unless they support user-provided ONNX/GGUF conversion.
2. **Beta maturity** — v1.7 beta, YC startup. Production risk for a Play Store app.
3. **No Italian Whisper quality evidence** — they support Whisper generically, but no benchmarks for Italian ASR quality. Our sherpa-onnx pipeline is battle-tested with 4.3% WER on Italian.
4. **Dual runtime complexity** — migrating from sherpa-onnx to Cactus would be a full rewrite of our transcription pipeline, with no guarantee of equivalent quality.
5. **Licensing ambiguity** — free for small businesses, but hybrid features are paid. Per-monthly-active-device pricing could become expensive.
6. **Qualcomm NPU not yet supported** — only Apple Neural Engine has NPU acceleration. Most Android devices would run CPU-only (same as sherpa-onnx).
7. **Loss of sherpa-onnx ecosystem** — we'd lose access to k2-fsa's model zoo, streaming support, and the Parakeet TDT optimizations we already have.

### Verdict: **Watch, don't switch**

Cactus is an impressive project solving real mobile AI deployment problems, but it's **not the right fit for Anti-Vocale today**:

- Our ASR quality depends on a custom Italian fine-tune (Distil-Large-V3-IT) that would need conversion to .cact format — no evidence this is supported or straightforward
- sherpa-onnx is production-stable and purpose-built for ASR; Cactus is a general-purpose engine in beta
- The main advantage (NPU acceleration) isn't available on Android/Qualcomm yet
- The hybrid cloud fallback contradicts our offline-first philosophy

**Re-evaluate if**: Cactus adds Qualcomm NPU support, supports user-provided ONNX model conversion, and reaches v2.0 stable release with proven Italian ASR benchmarks.

---

## Sources

- GitHub: `github.com/cactus-compute/cactus` (4.7k stars)
- Website: `cactuscompute.com`
- Docs: `cactuscompute.com/docs/v1.7`
- HuggingFace blog: `huggingface.co/blog/rshemet/cactus-on-device-inference`
- InfoQ: `infoq.com/news/2025/12/cactus-on-device-inference/`
- Y Combinator: `ycombinator.com/companies/cactus`
- Cactus Engine FFI API: `github.com/cactus-compute/cactus/blob/main/docs/cactus_engine.md`
- Comparison pages: `cactuscompute.com/compare/cactus-vs-llama-cpp`, `cactus-vs-mlc-llm`
- Medium tutorial (Kotlin): `medium.com/@mobiledevpro/hands-on-with-on-device-ai-for-pdf-text-summarization-android-app-cactus-compute-kotlin-library-34330bce8ff4`
- Koog Edge (Cactus integration): `github.com/lemcoder/koog-edge`
