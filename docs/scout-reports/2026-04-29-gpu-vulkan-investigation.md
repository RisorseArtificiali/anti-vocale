# GPU/Vulkan Acceleration Investigation — 2026-04-29

## Executive Summary

**Conclusion: GPU/Vulkan acceleration for on-device Whisper ASR is not feasible with current technology.** Every investigated approach shows GPU inference is equal to or slower than CPU on mobile devices. The root cause is architectural: mobile GPUs prioritize graphics rendering, not general-purpose compute.

## Approaches Investigated

### 1. sherpa-onnx GPU Backend
- **Status:** Not available on Android
- The `SHERPA_ONNX_ENABLE_GPU` CMake flag enables **NVIDIA CUDA only** (desktop/server)
- Maintainer (csukuangfj) confirmed in [issue #2227](https://github.com/k2-fsa/sherpa-onnx/issues/2227): "neither at present" (GPU/NPU on iOS/Android)
- The Android AAR ships with CPU + NNAPI providers only

### 2. ONNX Runtime WebGPU Execution Provider
- **Status:** Immature — 4x slower than CPU for ASR
- Uses Dawn library (Chrome's WebGPU), which translates to Vulkan on Android
- Testing with Qwen3-ASR 0.6B showed WebGPU EP was **4x slower** than CPU ([issue #27809](https://github.com/microsoft/onnxruntime/issues/27809))
- Key limitations:
  - No int8 support → forces CPU fallback with expensive CPU-GPU data copies
  - Plain ONNX models lack custom attention ops needed for GPU optimization
  - KV-cache/state transfers between CPU and GPU are unoptimized

### 3. ONNX Runtime NNAPI Execution Provider
- **Status:** Deprecated by Microsoft
- Had unpredictable performance per Microsoft's own comment in [issue #22973](https://github.com/microsoft/onnxruntime/issues/22973)
- Community fork by LemonCANDY42 added NNAPI with fallback policy (`nnapi → qnn → xnnpack → cpu`), not merged upstream

### 4. sherpa-ncnn + Vulkan (sibling project)
- **Status:** GPU is slower than CPU in real benchmarks
- [Issue #212](https://github.com/k2-fsa/sherpa-ncnn/issues/212): CPU RTF 0.4 vs GPU RTF >1.0
- [Issue #293](https://github.com/k2-fsa/sherpa-ncnn/issues/293): CPU recognized 30s audio in ~6s; Vulkan took 25s+
- Tested on Snapdragon 870 (Mi 10S)
- Also does **not support Whisper models** (uses Zipformer/Transducer)

### 5. llmedge (whisper.cpp + OpenCL/Vulkan)
- **Status:** Most mature attempt, but defaults to CPU
- Uses whisper.cpp with ggml tensor library
- GPU via OpenCL (preferred) or Vulkan (fallback)
- Even the project's own CMake defaults to `GGML_USE_CPU`
- Available as Maven dependency: `io.github.aatricks:llmedge:0.3.9`
- Would require migrating from ONNX to GGUF model format

## Expert Opinions

> "GPUs on handheld devices are designed more to reduce power consumption than to make things faster"
> — Dan Povey (Kaldi author)

> "neither at present" (in response to iOS/Android GPU/NPU support)
> — csukuangfj (sherpa-onnx maintainer)

> "NNAPI is deprecated, and always had unpredictable performance"
> — Microsoft (ONNX Runtime team)

## XNNPACK Status (Separate Tracking)

XNNPACK is a **CPU optimization** (not GPU) that could provide 1.2-2x speedup via ARM-optimized kernels. However:

- The current sherpa-onnx AAR was built **without** `--use_xnnpack` in the ONNX Runtime build
- sherpa-onnx code supports "xnnpack" as a provider string, but the binary silently falls back to CPU
- Enabling XNNPACK requires rebuilding ONNX Runtime for Android with `--use_xnnpack` flag, then rebuilding sherpa-onnx against it
- **Note:** XNNPACK primarily accelerates float32 ops; benefit for int8 quantized models may be limited
- Tracked in task-95 (Done, benchmarked CPU+NNAPI) and task-183 (To Do, re-run with resampling fix)

## Recommendations

1. **Close this task** — GPU/Vulkan is not viable for on-device ASR in 2026
2. **Monitor** ONNX Runtime WebGPU EP maturity ([issue #21917](https://github.com/microsoft/onnxruntime/issues/21917))
3. **Practical alternative:** Task-183 (XNNPACK benchmark) remains the best path for CPU-side optimization
4. **Revisit in 12 months** — mobile GPU compute is evolving; Qualcomm's NPU benchmarks for Distil-Whisper (English) suggest hardware acceleration may become viable

## References

| Topic | URL |
|-------|-----|
| sherpa-onnx GPU support question | https://github.com/k2-fsa/sherpa-onnx/issues/2227 |
| sherpa-onnx Vulkan/OpenCL question | https://github.com/k2-fsa/sherpa-onnx/issues/2754 |
| ONNX Runtime WebGPU EP tracking | https://github.com/microsoft/onnxruntime/issues/21917 |
| WebGPU EP 4x slower than CPU | https://github.com/microsoft/onnxruntime/issues/27809 |
| NNAPI deprecation | https://github.com/microsoft/onnxruntime/issues/22973 |
| sherpa-ncnn Vulkan slower than CPU | https://github.com/k2-fsa/sherpa-ncnn/issues/212 |
| sherpa-ncnn Vulkan benchmarks | https://github.com/k2-fsa/sherpa-ncnn/issues/293 |
| sherpa-onnx XNNPACK PR | https://github.com/k2-fsa/sherpa-onnx/pull/612 |
| llmedge project | https://github.com/Aatricks/llmedge |
