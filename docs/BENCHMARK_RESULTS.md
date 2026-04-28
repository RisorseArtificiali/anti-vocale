# Device Benchmark Results

**Date**: 2026-04-28 | **Device**: Realme RMX3853 (Android 16) | **Audio**: 78s test file (~309KB OGG)

Methodology: each model group runs with a warmup pass (discarded), then each config runs once with the model already loaded. No force-stop between configs within a group.

## Parakeet TDT 0.6B (sherpa-onnx)

Single-pass CTC model — processes entire audio at once regardless of chunking settings.

| VAD | Progressive | Provider | Preprocess | Inference | **Total** |
|-----|-------------|----------|-----------|-----------|-----------|
| off | off | nnapi | 7.0s | 9.1s | **16.0s** |
| off | off | cpu | 9.2s | 9.9s | **19.0s** |
| off | on | nnapi | 9.0s | 7.5s | **16.6s** |
| off | on | cpu | 15.5s | 15.0s | **30.5s** |
| on | off | nnapi | 13.9s | 13.6s | **27.5s** |
| on | off | cpu | 21.7s | 9.9s | **31.5s** |
| on | on | nnapi | 10.6s | 9.4s | **20.1s** |
| on | on | cpu | 10.9s | 8.4s | **19.3s** |

**Best**: no VAD + no progressive + NNAPI = **16.0s**

VAD and progressive modes only add overhead for Parakeet since it already processes the full audio in a single pass. NNAPI is slightly faster than CPU for inference.

## Whisper Distil Large v3 IT

Autoregressive model with 30s max chunk duration. Two processing paths: pipeline (decode + inference overlap) and parallel (multi-chunk concurrent).

| VAD | Progressive | Provider | **Total** | TTFT | Path |
|-----|-------------|----------|-----------|------|------|
| off | off | nnapi | **37.8s** | 17.2s | pipeline |
| off | off | cpu | **34.9s** | 14.8s | pipeline |
| off | on | nnapi | **28.4s** | — | parallel |
| off | on | cpu | **30.6s** | — | parallel |
| on | off | nnapi | **29.9s** | — | parallel |
| on | off | cpu | **30.0s** | — | parallel |
| on | on | nnapi | **35.4s** | — | parallel |
| on | on | cpu | **36.0s** | — | parallel |

**Best**: pipeline + CPU = **34.9s** | parallel + NNAPI = **28.4s**

CPU outperforms NNAPI for Whisper on this device. The parallel path (no VAD + progressive) is the fastest overall at 28.4s because it overlaps chunk inference.

## Gemma 4 E4B (llm)

Could not benchmark. Android 16 kills the foreground service before the 4B parameter model finishes loading from cold start. This is a pre-existing FGS lifecycle issue in `InferenceService`, not related to the audio pipeline changes.

## Summary

| Model | Best Total | Best Config | Speedup vs Whisper |
|-------|-----------|-------------|-------------------|
| Parakeet TDT 0.6B | 16.0s | no VAD, no progressive, NNAPI | 1.8x |
| Whisper Distil IT | 28.4s | no VAD, progressive, NNAPI | baseline |
| Gemma 4 E4B | N/A | service killed before completion | — |

### Key takeaways

- **Parakeet is ~2x faster** than Whisper across all configs
- **CPU beats NNAPI for Whisper** (34.9s vs 37.8s in pipeline mode)
- **NNAPI beats CPU for Parakeet** (16.0s vs 19.0s single-pass)
- **VAD adds overhead for Parakeet** (single-pass CTC doesn't benefit from segmentation)
- **VAD gives marginal improvement for Whisper** (~5-8s preprocessing saved, but multi-chunk inference adds overhead)
- **Warm model matters**: cold-start Parakeet was ~25s, warm model ~16s (40% faster)
