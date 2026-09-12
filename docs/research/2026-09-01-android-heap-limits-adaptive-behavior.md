# Research Report: Per-app heap limits across phones, runtime detection, and adaptive behavior

**Date**: 2026-09-01
**Depth**: exhaustive (pa:research; 17 searches/scrapes across 4 sub-domains + adversarial verification)
**Confidence**: HIGH (core mechanics and Android 17 changes confirmed against official sources; dataset absence and the 2027 Play date are MEDIUM)
**Context**: TASK-416 (decode-path Java-heap OOM on devices with a 256MB growth limit) is fixed and device-verified; this research asks whether per-model knowledge of memory limits would enable better per-device optimizations.

## Executive Summary

Per-model heap limits are NOT knowable in advance: no public database of `dalvik.vm.heapgrowthlimit` per phone exists, values are OEM build config (AOSP default 192m, overridden to 256m/384m by Samsung/OPPO-class OEMs), and the industry has converged on runtime capability detection instead of model recognition. Moreover the landscape just shifted: Android 17 introduces per-app TOTAL-memory caps scaled to device RAM (zRAM throttle, then kill), rolling out beyond Pixel over the coming year. The right move for anti-vocale is to adapt on runtime signals (`getMemoryClass`, `isLowRamDevice`, `totalMem`, and the new `MemoryLimiter:AnonSwap` exit reason), not on device-model lists; the app already does half of this (`TranscriptionMemoryPolicy`), and the highest-value additions are a startup ApplicationExitInfo check (mirroring our NNAPI crash-reset precedent), a "fits on this device" pre-download hint for catalog models, and using `am memory-limiter` to simulate 4GB-class devices in device gates.

## Findings

### 1. Where heap limits come from (mechanics)

- The per-app Java-heap limit is `dalvik.vm.heapgrowthlimit` (with `android:largeHeap` the app gets `dalvik.vm.heapsize` instead). `ActivityManager.getMemoryClass()` / `getLargeMemoryClass()` return exactly these, in MB [1][2][6].
- AOSP ships per-RAM-tier defaults in `build/phone-*-dalvik-heap.mk`: the 2GB-phone file sets `heapgrowthlimit=192m` / `heapsize=512m` [3]; the CURRENT master 4GB-phone file sets the same `heapgrowthlimit=192m` (`heapstartsize=8m`, `heapsize=512m`), via `PRODUCT_VENDOR_PROPERTIES` with the `?=` operator, i.e. explicitly OEM-overridable [4].
- Therefore the 256MB limit on the crashing Samsung flagships and the 384MB on our Realme test device are OEM bumps over the same 192m AOSP default. Physical RAM does NOT determine the Java limit (our S24-Ultra-class crasher had 12GB of RAM and a 256MB limit): the limit is a build-time decision, loosely correlated with RAM tier at best. This matches the TASK-416 field math: the old decode path needed ~222MB at 496s/48kHz, above the 192m AOSP default and the 256m Samsung limit, below the Realme's 384m.
- Historic values ranged far wider (64m on embedded/automotive units, 128m on old mid-rangers), so any Java-heap budget must assume the floor, not the flagship [3][5].

**Confidence: HIGH** (AOSP source files + ActivityManager source/docs).

### 2. Is there a public per-model dataset? (No)

- No maintained public database of per-model heap limits exists. The canonical Stack Overflow thread on the question [5] is answered with crowd-contributed `build.prop` dumps, not a dataset. Values surface only in: OEM device trees (mostly private), AOSP trees for Pixel/reference hardware [3][4], XDA/4PDA build.prop posts for specific models [7], and empirical reads (`adb shell getprop dalvik.vm.heapgrowthlimit`).
- What DOES exist per-model is RAM (not heap): Play Console's device catalog (RAM-tier device exclusion), GSMArena-class spec sheets, and Android vitals, which now segments memory metrics "across RAM class ranges" [8].
- Practical consequence: "know the limit in advance for phone X" is not achievable as a dataset play; it is achievable at runtime on the installed base (every app can read its own memoryClass in one line), or by mining your own field telemetry (log memoryClass + totalRam into Crashlytics and build the distribution yourself).

**Confidence: HIGH on absence** (multiple targeted searches across dev forums, AOSP, and tooling found no dataset; absence claims are inherently weaker than presence, hence the survey breadth).

### 3. The landscape changed: Android 17 per-app total-memory caps

- Android 17 (stable since June 2026) introduces app memory limits based on the device's TOTAL RAM, enforced against anonymous memory. Escalation is progressive: first forced zRAM swapping (CPU overhead, jank), then process termination [8][9].
- Field detection: `ApplicationExitInfo` with `REASON_OTHER` and a description containing the literal string `MemoryLimiter:AnonSwap`; plus trigger-based profiling (`TRIGGER_TYPE_ANOMALY`, and `TRIGGER_TYPE_OOM` from API 35's ProfilingManager) to capture heap dumps at the moment of the breach [8][9][10].
- Rollout: introduced on Pixel with Android 17; per the official Android Developers Blog (19 Aug 2026) "an increasing number of manufacturers will leverage the Android per-app memory limits across their portfolio of device RAM configurations from 4GB to 16GB+" over the coming year; apps that exceed limits "will be slowed down and may be terminated" [9]. Press reporting puts a Play-policy adaptation deadline at February 2027 [11] (MEDIUM: single secondary source, exact enforcement date not in the official post).
- New measurement surface: Android vitals gained a "Memory Usage (Anonymous RSS + swap)" metric with RAM-class segmentation, and Firebase Crashlytics 20.1.0 adds debug data for OOMs and memory-limiter kills [9].
- Testing: `am memory-limiter ignore|manual|status` adb subcommands let you impose or disable the cap on any enforcing device [8]; in other words, a 12GB test phone can simulate a 4GB-class budget.
- The exact cap formula is not published; Google states the limits "focus on memory leaks and other outliers" and anticipates "minimal impact on the vast majority of app sessions" [8][10].

**Confidence: HIGH** (official behavior-change doc + official blog, cross-verified; formula/date details MEDIUM).

### 4. What comparable on-device AI apps do (the adaptive-behavior survey)

- The dominant pattern is a RUNTIME BUDGET derived from device RAM, not a model list. A detailed on-device-AI practitioner writeup (React Native + whisper.rn + llama.cpp): "I use 60% of device RAM as a hard budget. Warn at 50%, block at 60%"; calculate whether a model fits BEFORE download (file size x ~1.5 for activations/KV), show RAM requirements next to every model, filter out models that will not fit, offer multiple model sizes (Tiny/Base/Small) as an explicit user trade-off, and "test on real mid-range devices, not just your flagship" because the OOMs are "random crashes you can't reproduce in development since your test device has 12GB" [12]. That last sentence is a verbatim echo of our TASK-416 morning discussion.
- Engine benchmarking confirms the model-class x RAM-class interaction is real: WhisperKit CoreML OOMs on 4GB iOS devices for anything above Whisper Tiny while smaller sherpa-onnx models run fine; model+engine choice spans 31MB (Whisper Tiny GGML) to 1.8GB (Qwen3-ASR) with RTF from 0.05 to 3.5 on Android [13]. sherpa-onnx (our engine) is the fastest Android engine measured (51x faster than whisper.cpp on the same model) [13].
- Platform-side adaptive signals used in the wild: `isLowRamDevice()` (ro.config.low_ram, Android Go, typically the under-2GB class) for feature gating [14]; `onTrimMemory`/`ComponentCallbacks2` for cache eviction [1]; Google's games-focused Memory Advice API (androidx.games:games-memory-advice, NDK, OOM prediction) exists but is games-specific and its beta guidance is deprecated; large-scale apps treat OOM-kill prediction as a monitoring/ML problem (Netflix) [15], which for us maps to Crashlytics/vitals rather than an on-device predictor.
- Nobody surveyed branches on `Build.MODEL` for memory behavior. Model-specific hacks are considered a maintenance trap; the portable proxies are memoryClass / RAM class / low-RAM flag, which is exactly what the platform exposes and what Play vitals segments on.

**Confidence: HIGH** on the pattern (multiple independent practitioners + platform docs); the React Native writeup is a single (detailed, internally consistent) source for the specific 50/60% numbers.

### 5. What this means for anti-vocale specifically

- Device-model recognition would buy nothing: the two limits that can kill us (Java growth limit, Android 17 anon-RAM cap) are functions of build config and RAM class, both readable at runtime in one line each. We already adapt on RAM (`TranscriptionMemoryPolicy` derives external-model chunk caps from RAM, fail-open); that is the correct layer, and TASK-416's fix removed the only Java-heap cliff in the decode path (peak ~2x final 16kHz size, sample-rate independent).
- The exposure that remains is NATIVE anonymous memory (ONNX session arenas, Gemma/LiteRT working set) under the Android 17 cap on 4GB-class devices. Post-fix, our biggest lever is model choice, and the industry pattern (fit-check before download + visible RAM requirements + size tiers as user choice) fits our external-model catalog UI directly.
- Concrete candidate follow-ups (research only; for the backlog, not implemented here):
  1. Startup `ApplicationExitInfo` check for `REASON_OTHER` + `MemoryLimiter:AnonSwap` (and REASON_LOW_MEMORY), mirroring the NNAPI crash-reset precedent: after such a kill, offer or auto-reset to a smaller model or a shorter duration cap on next launch.
  2. Catalog "fits on this device" hint: compare entry sizeBytes x ~1.5 against a RAM-class budget before import (the RN 50/60% pattern; we already hold model records with sizeBytes).
  3. Device-gate tooling: use `am memory-limiter manual <pid> <limit>` on the RMX3853 to gate transcription runs under a simulated 4GB-class budget, closing the "12GB test phone proves nothing" gap for the kill class (the Java-heap class is already covered by the 192m AOSP-default math).
  4. Crashlytics 20.1.0 upgrade + watch the new vitals RSS+swap metric ahead of the reported Feb-2027 Play enforcement [9][11].
  5. Optionally log memoryClass + totalRam into crash breadcrumbs to build our own per-device-class field distribution: the dataset that does not exist publicly.

## Confidence Assessment

- HIGH: mechanics of heapgrowthlimit/memoryClass [1][2][3][4]; Android 17 per-app memory limits, detection string, adb test commands [8][9]; runtime APIs available (ApplicationExitInfo, isLowRamDevice, MemoryInfo) [8][14]; the fit-check + model-size-tier adaptive pattern in on-device AI apps [12][13].
- MEDIUM: Feb-2027 Play adaptation date [11] (secondary source); exact cap formula and thresholds (unpublished); "users cannot raise the cap" (consistent with docs, not explicitly stated); the specific 50%/60% budget numbers [12] (one practitioner's policy, not a standard).
- LOW/unverified: per-model distribution of heapgrowthlimit in the wild (no dataset, only anecdotal build.prop dumps); whether OEMs that raise growthlimit (like our 384m Realme) will also raise the Android-17 caps.

## Sources

1. developer.android.com/reference/android/app/ActivityManager (getMemoryClass / getLargeMemoryClass / isLowRamDevice / MemoryInfo) + ActivityManager.java source at android.googlesource.com
2. "Everything you need to know about Memory Leaks in Android", proandroiddev.com/d7a59faaf46a
3. AOSP `build/phone-xhdpi-2048-dalvik-heap.mk` (android-4.2.2_r1): 2G phone, growthlimit 192m, heapsize 512m
4. AOSP `build/phone-xhdpi-4096-dalvik-heap.mk` (master): 4G phone, growthlimit ?=192m via PRODUCT_VENDOR_PROPERTIES
5. Stack Overflow 5350465, "Android heap size on different phones/devices and OS versions" (crowd build.prop data; no dataset)
6. Stack Overflow 2630158, "Detect application heap size in Android"
7. XDA Forums and 4PDA build.prop dumps (190m growthlimit on one mid-tier, 64m on an automotive unit)
8. developer.android.com/about/versions/17/behavior-changes-all (App memory limits; MemoryLimiter:AnonSwap; am memory-limiter)
9. Android Developers Blog, "Preparing your app for broader memory limits", 19 Aug 2026 (android-developers.googleblog.com/2026/08/app-broader-memory-limits.html)
10. stora.sh/blog/2026-04-25-android-17-memory-limits-guide (practitioner deep dive; cross-checked against [8][9])
11. Tom's Hardware, "Google clamps down on Android app RAM usage ... developers have until February 2027" (tomshardware.com)
12. r/LocalLLaMA, "Everything I learned building on-device AI into a React Native app" (reddit.com/r/LocalLLaMA/comments/1renuky)
13. VoicePing, "Offline Speech Transcription Benchmark Research" (voiceping.net/en/blog/research-offline-speech-transcription-benchmark): 16 STT models x 9 engines, memory and RTF on Android and iOS
14. source.android.com low-RAM configuration doc + Magisk disable-low-ram module README (ro.config.low_ram semantics, under-2GB class)
15. Netflix TechBlog, "Formulating Out of Memory Kill Prediction on the Netflix App as a Machine Learning Problem"; androidx.games games-memory-advice releases
