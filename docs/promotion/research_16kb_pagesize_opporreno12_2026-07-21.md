# Research Report: 16KB Page Size Crash Investigation (Oppo Reno 12)

**Date**: 2026-07-21
**Depth**: exhaustive
**Confidence**: HIGH (refutation)

## Executive Summary

**The 16KB-page-size hypothesis for the Oppo Reno 12 crash is REFUTED by direct evidence.** All 34 `.so` files in the built APK (sherpa-onnx, LiteRT, AndroidX) are already 16KB-aligned at the ELF level (`PT_LOAD align = 0x4000`). The Oppo Reno 12 almost certainly uses 4KB pages (only the Pixel 9a on Android 16+ ships with 16KB pages today). The actual crash cause is elsewhere — most likely ColorOS foreground-service killing, OEM sideload restrictions, or a MediaTek-specific native issue. A logcat from the device is needed to diagnose.

## Findings

### 1. Our .so files are ALL 16KB-aligned (REFUTES the hypothesis)

Verified by `readelf -lW` on every `.so` in the sherpa-onnx AAR (v1.12.34, v1.13.3, v1.13.4) and in the built APK. All show `PT_LOAD align = 0x4000` (= 16384 = 16KB). Zero misaligned. [Source: direct mechanical check on our artifacts this turn]

### 2. AGP 8.10.0 handles 16KB zip-alignment automatically

AGP ≥ 8.5.1 applies 16KB zip-alignment to uncompressed native libs. Our build has no `useLegacyPackaging` override. The APK passed `zipalign -c -P 16 4`. [Source: developer.android.com/guide/practices/page-sizes]

### 3. The Oppo Reno 12 is almost certainly NOT a 16KB-page device

Google's official docs list exactly one device shipping with 16KB pages: **Pixel 9a (Android 16+)**. The Reno 12 (MediaTek Dimensity 7300) shipped on Android 14 / ColorOS 14.1 with 4KB pages. Even if the user updated to ColorOS 16, no OEM outside Google has flipped kernel page size to 16KB. [Source: developer.android.com/guide/practices/page-sizes]

### 4. Upstream sherpa-onnx / ONNX Runtime issues are NOT our problem

- sherpa-onnx#2641: fixed their Flutter `libonnxruntime4j_jni.so` — we don't ship that library. Our `libsherpa-onnx-jni.so` is independently confirmed aligned.
- onnxruntime#24902, #25859: Microsoft's official Android AAR had alignment issues — we use sherpa's prebuilt, which is verified clean.

### 5. What's actually likely causing the crash (tangential, needs logcat to confirm)

1. **ColorOS aggressive foreground-service killing** — most common cause of "works on Snapdragon, dies on ColorOS" for audio apps. ColorOS kills foreground services even with a proper notification.
2. **OEM sideloading restrictions** — Oppo/Realme package installers sometimes partially-install sideloaded APKs, breaking `.so` extraction.
3. **MediaTek NEON/DSP code path bug** — would show as `SIGSEGV`/`SIGILL` in logcat with a specific native PC.
4. **16KB backcompat shim misidentification** — speculative; no supporting evidence found.

### 6. The ONE diagnostic that settles everything

```bash
adb logcat *:E AndroidRuntime:E DEBUG:E
```

A single crash capture from the Reno 12 narrows it to: (a) `UnsatisfiedLinkError`/`dlopen failed` (native load), (b) `SIGSEGV`/native abort (MediaTek bug), (c) silent kill (ColorOS FGS policy), or (d) something else.

## Confidence Assessment

| Finding | Confidence | Basis |
|---------|-----------|-------|
| .so files are 16KB-aligned | HIGH | Direct readelf on all 34 files in our actual APK |
| 16KB is NOT the cause | HIGH | Alignment confirmed + device likely not 16KB-page |
| ColorOS FGS killing is the likely cause | MEDIUM | Industry pattern, not verified against this specific crash |
| Need logcat to diagnose | HIGH | Without crash signature, every cause is a guess |

## Verification Commands

```bash
# Check ELF alignment of every .so in the AAR
unzip -q sherpa-onnx.aar -d out
for so in out/jni/arm64-v8a/*.so; do readelf -lW "$so" | grep LOAD; done
# Each line should end in "0x4000"

# Check the built APK
~/Android/Sdk/build-tools/35.0.0/zipalign -c -v -P 16 4 app-debug.apk

# Confirm the device's actual page size
adb shell getconf PAGE_SIZE
# prints 4096 or 16384
```

## Sources

1. https://developer.android.com/guide/practices/page-sizes — Android 16KB page size guide
2. https://android-developers.googleblog.com/2025/05/prepare-play-apps-for-devices-with-16kb-page-size.html — Play Store requirement blog post
3. https://github.com/k2-fsa/sherpa-onnx/issues/2641 — sherpa-onnx 16KB alignment issue (Flutter wrapper, not our library)
4. https://github.com/microsoft/onnxruntime/issues/24902 — ONNX Runtime 16KB alignment issue
5. https://github.com/microsoft/onnxruntime/issues/25859 — ORT 1.22.2 still missing fix
6. https://githubissues.com/microsoft/onnxruntime/21837 — "no production Android devices available today" with 16KB
