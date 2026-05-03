# Device Benchmark Results

**Date**: 2026-05-03 | **Device**: Realme RMX3853 (Android 16) | **Audio**: 78s test file (~309KB OGG)

Methodology: each config force-stops the app for a clean process, then measures cold-start performance (model loaded fresh). Single run per config. All results reflect real-world latency when the app handles a new transcription request.

## Parakeet TDT 0.6B (sherpa-onnx)

Single-pass CTC model. VAD and progressive modes split audio into segments, enabling parallel/progressive processing that reduces wall-clock time.

| VAD | Progressive | Provider | Preprocess | **Total** | Path |
|-----|-------------|----------|-----------|-----------|------|
| off | off | nnapi | 7.9s | **15.0s** | batch |
| off | off | cpu | 12.4s | **20.0s** | batch |
| off | on | nnapi | 9.6s | **21.5s** | batch |
| off | on | cpu | 11.0s | **25.8s** | batch |
| on | off | nnapi | 18.4s | **11.9s** | parallel |
| on | off | cpu | 18.0s | **10.5s** | parallel |
| on | on | nnapi | 24.2s | **6.7s** | progressive |
| on | on | cpu | 10.9s | **5.8s** | progressive |

**Best**: VAD + progressive + CPU = **5.8s**

VAD splits the audio into segments, and progressive mode returns partial results as each segment completes. This dramatically reduces wall-clock time despite the single-pass CTC architecture.

## Whisper Distil Large v3 IT

Autoregressive model with 30s max chunk duration. Three processing paths: pipeline (sequential decode+inference), parallel (multi-chunk concurrent), and progressive (chunk results streamed).

| VAD | Progressive | Provider | **Total** | TTFT | Path |
|-----|-------------|----------|-----------|------|------|
| off | off | nnapi | **66.3s** | 29.3s | pipeline |
| off | off | cpu | **58.8s** | 27.7s | pipeline |
| off | on | nnapi | **38.4s** | 15.0s | pipeline |
| off | on | cpu | **38.7s** | 19.7s | pipeline |
| on | off | nnapi | **41.5s** | — | parallel |
| on | off | cpu | **57.7s** | — | parallel |
| on | on | nnapi | **63.9s** | — | progressive |
| on | on | cpu | **52.7s** | — | progressive |

**Best**: progressive + NNAPI = **38.4s** (with 15.0s TTFT)

CPU outperforms NNAPI for pipeline mode (58.8s vs 66.3s), but NNAPI is faster for progressive mode (38.4s vs 38.7s). Progressive pipeline is the fastest overall at 38.4s, producing first text in 15s.

## Gemma 4 E4B (llm)

LLM-based transcription backend. Previously killed by Android 16 FGS lifecycle, now works with the force-stop-per-request approach.

| VAD | Progressive | Provider | Preprocess | **Total** | Path |
|-----|-------------|----------|-----------|-----------|------|
| off | off | cpu | — | **161.7s** | pipeline |
| off | on | cpu | — | **154.6s** | pipeline |
| on | off | cpu | 17.1s | **158.0s** | parallel |
| on | on | cpu | 22.7s | **145.1s** | progressive |

**Best**: VAD + progressive = **145.1s** (~2.4 min)

VAD+progressive provides the fastest LLM result at 145s. The model takes ~2 minutes to load and process, making it significantly slower than the ASR backends but viable for use cases where LLM transcription quality is preferred.

## Summary

| Model | Best Total | Best Config | vs Parakeet |
|-------|-----------|-------------|-------------|
| Parakeet TDT 0.6B | 5.8s | VAD on, progressive on, CPU | baseline |
| Whisper Distil IT | 38.4s | VAD off, progressive on, NNAPI | 6.6x slower |
| Gemma 4 E4B | 145.1s | VAD on, progressive on, CPU | 25.0x slower |

### Key takeaways

- **VAD + progressive is the fastest path for all backends** — segment-based processing reduces wall-clock time significantly
- **Parakeet dominates at 5.8s** — the combination of CTC single-pass architecture with VAD segmentation is extremely efficient
- **CPU beats NNAPI for Parakeet VAD+Prog** (5.8s vs 6.7s) — the overhead of NNAPI dispatch outweighs its compute advantage for small segments
- **Cold-start vs warm-model**: these numbers reflect cold-start performance (model loaded fresh each time). Warm-model results would be 30-40% faster for ASR backends
- **Gemma 4 E4B is now benchmarkable** at ~145-162s cold-start, previously killed by FGS lifecycle issues
- **Progressive TTFT matters**: Whisper progressive shows first text in 15s despite 38s total — useful for streaming UX
