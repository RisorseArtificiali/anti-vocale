# F-Droid MR !46215: root cause of the fdroid build failure (2026-08-20)

Corrects the earlier "runner timeout" diagnosis: pipeline 2772641710 did NOT
time out. The `fdroid build` job failed in ~16 min with
"compared built binary to supplied reference binary but failed".

## Evidence

Failed-job artifact (tmp/com.antivocale.app_371.apk) vs our v1.10.0 reference
release APK (app-fdroid-armeabi-v7a-release.apk), armeabi-v7a:

| lib | ref | built | verdict |
|---|---|---|---|
| libsherpa-onnx-jni.so | 3,414,016 | 3,414,016 | byte-identical |
| libonnxruntime.so | 15,027,384 | 15,027,368 | ref == k2-fsa release AAR byte-for-byte (incl. malloc_hook sym); built (srclib) lacks malloc_hook/.plt |
| libllm_inference_engine_jni.so | 19,038,796 | 19,038,776 | ref == Maven tasks-genai 0.10.33 raw bytes (unstripped); built is STRIPPED (shstrtab lacks __lcxx_override/.plt) |

## Root causes (two independent, both on OUR side)

1. **sherpa provenance**: our release APKs embed the k2-fsa PREBUILT release
   AAR (fetch-sherpa-aar.sh -> releases/download/v1.13.5/sherpa-onnx-1.13.5.aar).
   The fdroid recipe builds the sherpa AAR from the srclib (same tag 3dc7c569)
   with build-android-armv7-eabi.sh; k2-fsa's release CI builds its AAR
   differently, so the bundled libonnxruntime.so differs by provenance
   (the srclib-assembled copy lacks the malloc_hook/.plt symbols the release
   AAR carries). libsherpa-onnx-jni.so happens to match (deterministic compile),
   libonnxruntime.so does not.
2. **NDK-dependent stripping**: our release CI runs without an NDK, so AGP
   ships the Maven tasks-genai .so RAW. The fdroid buildserver has NDK r27c
   (recipe `ndk: r27c`), so AGP strips every .so with r27c llvm-strip ->
   byte/layout differences on libllm_inference_engine_jni.so.

## Fix plan

For 1.10.0 (already tagged): rebuild the three reference APKs exactly per the
recipe path (srclib-built sherpa AAR + NDK r27c present), sign with the release
key, upload under NEW asset names (never clobber the existing canonical
assets), and ask licaon-kter to repoint the recipe `binary:` URLs.

For future releases (repo changes):
- pin `ndkVersion = "27.3.13750724"` (r27c, matching the recipe) in
  app/build.gradle.kts so AGP strips identically everywhere;
- make the release flow produce the F-Droid reference APKs from the
  srclib-built sherpa AAR (not fetch-sherpa-aar.sh), or make BOTH paths use
  one byte-reproducible AAR source;
- keep the three-point sherpa pin in sync (.sherpa-version,
  fetch-sherpa-aar.sh, SRCLIB PIN comment) as already documented.

## Note
The fork pre-validation (pipeline 2774020785) passed "check apk" because both
sides of that comparison were built the same way on the same runner; it never
compared against the GitHub-hosted release binaries the way fdroiddata CI does.
