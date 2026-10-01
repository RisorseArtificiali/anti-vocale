# Upstream PR draft: convert Ort::Exception to a Java exception at the sherpa-onnx JNI boundary

Task TASK-502, issue k2-fsa/sherpa-onnx#3987, patch
`sherpa-jni-exception-barrier-2026-10-01.patch` (branch
`jni-convert-ort-exception-to-java`, commit 0664be2, cloned at
v1.13.8). Status: ready for the maintainer's publish decision.

## PR title

jni: convert Ort::Exception during recognizer construction to a Java
exception

## PR body

The four recognizer factories (OfflineRecognizer and OnlineRecognizer,
newFromFile and newFromAsset) construct the recognizer with no
exception barrier. When the model file fails to parse, Ort::Exception
crosses the JNI boundary, reaches std::terminate, and aborts the whole
Android process. The Java try/catch around the constructor never sees
the exception, so one corrupt file kills the app on every load attempt.

We see this in production (an on-device transcription app shipping the
1.13.8 AAR): Crashlytics reports

```
terminating due to uncaught exception of type Ort::Exception
#00 abort (libc)
#01..#10 libsherpa-onnx-jni.so
#11 art_jni_trampoline
#13 com.k2fsa.sherpa.onnx.OfflineRecognizer.<init>
```

after onnxruntime logs `Load model from ... failed: Protobuf parsing
failed`. Report: #3987.

This wraps the four constructions in `try { } catch (const
std::exception &e)`, logs, and converts the failure to a Java exception
with `env->ThrowNew`, returning 0 exactly like the existing config-error
paths in the same functions. `Ort::Exception` derives from
`std::exception` (onnxruntime_cxx_api.h: `struct Exception :
std::exception`), so the catch covers it without including onnxruntime
headers in the JNI translation units, and it also covers any other
std::exception raised during construction. The `FindClass` + `ThrowNew`
+ `DeleteLocalRef` sequence follows the existing JNI convention in
speaker-embedding-manager.cc and wave-reader.cc.

Repro: take a working model directory, corrupt the encoder bytes while
keeping the file length (an interrupted write or bit rot on device),
then construct the recognizer. Before: process abort with the stack
above. After: a catchable `java.lang.Exception` whose message carries
the onnxruntime parse error.

Verification: configured with `-DSHERPA_ONNX_ENABLE_JNI=ON` (macOS
arm64, Release) and built the `sherpa-onnx-jni` target to completion at
v1.13.8 plus this patch.

Scope note: other JNI factories (keyword spotting, VAD, offline TTS,
speaker embedding) have the same unguarded construction. This PR fixes
the two recognizer families where we have production evidence; happy to
extend the same barrier to the rest if you prefer one pass.

## Our-side linkage

- Issue: https://github.com/k2-fsa/sherpa-onnx/issues/3987 (filed
  2026-09-25; open, no comments as of 2026-10-01)
- Our load-time gates that cover the ingress paths meanwhile:
  TASK-479 (catalog models) and TASK-482 (external imports, 2026-10-01)
- Runtime confirmation on a device is pending the next phone window;
  the repro recipe above is the exact procedure.

## Published (maintainer approval 2026-10-01)

https://github.com/k2-fsa/sherpa-onnx/pull/4014 (base master, head
paoloantinori:jni-convert-ort-exception-to-java). Body passed the
unslop audit before posting; "Fixes #3987" links the issue.
