# Audio Processing Speedup Research

**Date**: 2026-04-28
**Scope**: Exhaustive research into parallelization, streaming, early transcription, faster algorithms, and academic techniques for speeding up audio preprocessing across all model families (Whisper, Parakeet, Qwen3-ASR).
**Status**: Research only — no code changes.

---

## Executive Summary

The current pipeline processes audio through 7 stages sequentially before any transcription begins. Research identified **5 high-impact optimizations** (no new dependencies), **3 medium-impact model-level strategies**, and **2 watch-list items** that could transform the architecture when models mature.

**Key finding**: WhatsApp voice messages (the primary use case) are already 16kHz mono — the entire post-decode preprocessing pipeline can be **skipped entirely** for ~60% of inputs. For the remaining cases, the WAV round-trip and per-sample ByteBuffer allocations are the biggest bottlenecks, solvable with Kotlin-level refactoring alone.

---

## Current Pipeline Bottleneck Map

| Stage | Time (est.) | Bottleneck | Model-Specific? |
|-------|-------------|------------|-----------------|
| 1. MediaCodec decode | 50-200ms | Hardware-accelerated, not a bottleneck | No |
| 2. Stereo→mono | 200-1000ms | Per-sample ByteBuffer.wrap() allocations | No |
| 3. Resample to 16kHz | 200-1000ms | Per-sample ByteBuffer.wrap() allocations | No |
| 4. VAD (optional) | 50-200ms | Silero VAD via sherpa-onnx | No |
| 5. Chunking | <1ms | Simple byte array slicing | Yes (30s for Whisper/Qwen3, unlimited for Parakeet) |
| 6. WAV header creation | <1ms | 44-byte prepend | No |
| 7. Backend WAV parse→float | 50-100ms | WAV header strip + PCM→float conversion | No |

**Total preprocessing**: ~500-2300ms before transcription starts.

---

## Tier 1: High-Impact, Low-Effort Optimizations

### 1.1 Skip Preprocessing for 16kHz Mono Input (100% speedup for WhatsApp)

WhatsApp voice messages are **already 16kHz mono Opus**. Signal and Telegram use 48kHz mono. The code already extracts `inputSampleRate` and `inputChannels` from MediaFormat (lines 223-225).

| Platform | Sample Rate | Channels | Skip? |
|----------|-------------|----------|-------|
| WhatsApp | 16kHz | Mono | Skip entirely |
| Telegram/Signal | 48kHz | Mono | Downsample only |
| iMessage | 44.1kHz | Mono | Downsample only |
| AMR-WB (MMS) | 16kHz | Mono | Skip entirely |
| Voice recorder | 44-48kHz | Stereo | Full pipeline |

**Impact**: 100% elimination of post-decode processing for ~60% of inputs (WhatsApp). Zero new dependencies.

### 1.2 Fix Per-Sample ByteBuffer Allocations (10-50x faster processing)

`stereoToMono()` and `resampleAudio()` allocate a new ByteBuffer for **every single sample**. For a 60s stereo 48kHz clip: ~4.8M ByteBuffer objects, massive GC pressure.

Fix: wrap the entire array once as a ShortBuffer, access by index. No new dependencies.

### 1.3 Eliminate WAV Round-Trip (2 fewer data copies)

Current flow: `PCM → WAV ByteArray → WAV parse → FloatArray → sherpa-onnx`

Every sherpa-onnx backend does:
```kotlin
// AudioPreprocessor adds WAV header:
val wavBytes = createWavByteArray(pcmData)

// Backend immediately strips it:
val samples = WavUtils.parseWavToFloats(audioData)
stream.acceptWaveform(samples, 16000)
```

**sherpa-onnx `acceptWaveform(float[], sampleRate)` accepts any sample rate** and resamples internally using a Kaldi-derived sinc resampler (higher quality than our linear interpolation). We can pass raw floats at the native sample rate directly, eliminating both the WAV creation and manual resampling.

**Impact**: Eliminates 3 of 7 data copies. Also means we can drop manual resampling entirely — sherpa-onnx does it internally (and better).

### 1.4 Direct MediaCodec→FloatArray Conversion (merge 3 steps into 1)

Instead of: `MediaCodec ByteBuffer → ByteArray → stereoToMono → resample → WAV → parse → FloatArray`

Do: `MediaCodec ByteBuffer → mono FloatArray (single pass)`

### 1.5 Pipeline Preprocessing with Transcription

Start transcribing chunk 1 the moment it's decoded, while still decoding/preprocessing chunk 2+. Turns sequential `preprocess(all) → transcribe(all)` into `preprocess(chunk) || transcribe(chunk)`.

**Impact**: For a 3-min voice message, first text appears ~2s earlier instead of ~5s.

---

## Tier 2: Model-Specific Strategies

### 2.1 Whisper (distil-large-v3, turbo)

**Streaming**: Architecturally impossible at the model level. Whisper's encoder requires the full 30s mel spectrogram. All "streaming Whisper" implementations (WhisperStreaming, whisper.cpp stream) work at the application level with sliding windows.

**Parallel chunk transcription**: With `condition_on_previous_text=False`, all 30s chunks are independent and can be transcribed in any order/parallel. Already partially implemented (semaphore of 2).

**Encoder feature caching**: For overlapping chunks, mel spectrogram features for the overlapping region could be cached. sherpa-onnx doesn't expose this, but faster-whisper does it. Estimated 5-10% overall speedup.

**Skip resampling**: IMPOSSIBLE. 16kHz is hardcoded into Whisper's mel filterbank (`N_FFT=400, HOP_LENGTH=160` at 16kHz). Feeding 44.1kHz audio produces garbage because the mel filter bank maps wrong frequency bins.

**Speculative decoding**: Distil-Whisper was designed as a speculative decoding pair for Whisper large-v3, yielding 2x speedup with identical outputs. Requires running two models concurrently (memory concern on Android). NOT available in sherpa-onnx.

### 2.2 Parakeet (TDT 0.6B)

**Model type clarification**: The app uses `parakeet-tdt-0.6b-v3-int8`, which is a **Token-and-Duration Transducer (TDT)**, not CTC. It uses `OfflineRecognizer` with `modelType = "nemo_transducer"`.

**Streaming potential**: TDT models are theoretically streamable (transducer architecture supports incremental decoding). sherpa-onnx has `OnlineRecognizer` with full NeMo CTC streaming support (cache-aware streaming with `cache_last_channel`, `cache_last_time`, `cache_last_channel_len`). However:
- No streaming Parakeet TDT models exist (all are offline-trained)
- No streaming Italian Parakeet models exist (only English streaming NeMo CTC)
- Exporting current Parakeet with `cache_support: True` would likely degrade quality (trained without chunked attention)

**Practical path**: The TDT model already handles unlimited-length audio in a single pass (`maxChunkDurationSeconds = null`). It's 17.6x faster than Whisper. The bottleneck for Parakeet is not transcription speed but preprocessing.

**Quantization**: Already INT8. INT4 quantization could provide additional 2-3x speedup but no INT4 Parakeet models exist. The comprehensive benchmark paper (arXiv:2604.14493) shows INT4 k-quant reduces similar models from 2.47GB to 0.67GB with WER within 1%.

### 2.3 Qwen3-ASR (0.6B)

**Architecture**: Encoder-decoder (seq2seq), similar to Whisper. Uses 128-bin Whisper-style mel spectrograms (not 80-bin as configured in the backend — sherpa-onnx overrides internally). Has a conv_frontend (3 layers of stride-2 convolution) before the transformer encoder.

**Streaming**: NOT supported and fundamentally cannot be adapted (same reason as Whisper — encoder-decoder with cross-attention over full sequence).

**Critical limitation**: `max_total_len = 512` caps prompt + audio tokens + generated text. Audio >30s causes truncation.

**Quality**: Worst of the four models for Italian (12.2% WER vs 4.3% for Distil IT, 5.4% for Parakeet). Unfavorable speed/accuracy position — Parakeet is 6.8x faster with 2.3x lower WER. Its only advantage is 52-language auto-detection.

**Preprocessing**: Uses 128-bin mel features (more expensive than Parakeet's standard 80-bin). The conv_frontend is a mandatory neural network that cannot be bypassed.

---

## Tier 3: Academic / Emerging Techniques

### 3.1 Self-Speculative Decoding (arXiv:2603.11243) — HIGH FEASIBILITY

Uses CTC encoder output as a draft model to accelerate autoregressive decoder inference. Three-step procedure:
1. Compute CTC output. If frame entropies below threshold, accept as final.
2. If entropy high, verify CTC hypothesis in single LLM forward pass.
3. If verification fails, resume AR decoding from accepted prefix.

**Results**: 4.4x speedup with 12% relative WER increase.

**Anti-Vocale relevance**: HIGH — the app has both Parakeet (CTC) and Whisper (AR decoder). Parakeet CTC output could draft for Whisper decoder. Requires custom ONNX orchestration at Kotlin level. Memory concern on Android.

### 3.2 Moonshine — WATCH ITEM

Purpose-built on-device ASR with:
- No zero-padding (huge win for short voice messages)
- Built-in streaming with cached encoder states
- 5-100x faster than Whisper at comparable accuracy
- Native ONNX + sherpa-onnx support
- Sliding-window self-attention (bounded latency)

**Blocker**: No Italian STT model. Supported languages: ar, es, en, ja, ko, vi, uk, zh. Very active development (v0.0.59 as of 2026-04-20). Italian may be coming soon.

### 3.3 Comprehensive On-Device Benchmark (arXiv:2604.14493)

Evaluates 50+ configurations of Whisper, Parakeet TDT, Qwen3-ASR, Nemotron on CPU with quantization strategies. Key finding: INT4 k-quant reduces models to ~27% of original size with <1% WER increase. NVIDIA Nemotron Speech Streaming identified as strongest real-time candidate.

### 3.4 Early-Exit Encoders — LOW FEASIBILITY

Papers: Splitformer (arXiv:2506.18035), Spiralformer (arXiv:2510.00982). Require custom model training with exit classifiers. Existing Whisper/Parakeet models don't have early-exit branches. 30-50% speedup for easy audio.

### 3.5 Neural Audio Compression — VERY LOW FEASIBILITY

Using EnCodec/DAC tokens as ASR input. No established technique. Would require training a new encoder from scratch.

### 3.6 Feature Caching — LOW EFFORT, MODERATE IMPACT

Pre-compute mel spectrogram once for full audio, slice per chunk instead of recomputing. Standard optimization in faster-whisper. 5-10% overall speedup. sherpa-onnx may support providing pre-computed features directly.

---

## Proposed Optimized Pipeline

### Current (7 copies, sequential):
```
MediaCodec → PCM ByteArray → stereoToMono → resample → WAV → parse WAV → FloatArray → sherpa-onnx
```

### Proposed (2 copies, streaming-capable):
```
MediaCodec ByteBuffer
  ↓ [Copy 1: single-pass mono + PCM→float conversion]
FloatArray (native sample rate)
  ↓ [Copy 2: JNI copy in acceptWaveform()]
sherpa-onnx (internal sinc resampling + mel extraction + inference)
```

**Expected reduction**: ~10MB → ~2MB temporary allocations per 30s clip. Preprocessing time drops from 500-2300ms to ~50-200ms for non-WhatsApp, 0ms for WhatsApp.

---

## Backlog Candidates (Priority Order)

1. **Skip preprocessing for 16kHz mono input** — detect format, bypass stereo-to-mono + resample
2. **Replace per-sample ByteBuffer with ShortBuffer** — eliminate GC pressure
3. **Remove WAV round-trip** — pass FloatArray directly to backends, let sherpa-onnx resample internally
4. **Direct MediaCodec→FloatArray** — single-pass conversion from decode output
5. **Pipeline preprocessing with transcription** — producer-consumer chunk streaming
6. **Feature caching across overlapping chunks** — pre-compute mel once, slice per chunk
7. **Self-speculative decoding** — Parakeet CTC drafts for Whisper decoder (research prototype)
8. **Moonshine Italian** — watch for Italian STT support, benchmark when available
9. **INT4 quantization** — evaluate for all models per arXiv:2604.14493 techniques
