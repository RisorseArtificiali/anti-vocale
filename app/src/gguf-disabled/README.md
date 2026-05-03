# GGUF/llama-bro Feature (Disabled)

This directory contains the Gemma 4 GGUF inference code, disabled because
llama-bro does not yet support the Gemma 4 GGUF architecture.

## To Re-enable

1. Move all files back to their original locations:
   ```
   java/com/antivocale/app/llm/         → app/src/main/java/com/antivocale/app/llm/
   java/com/antivocale/app/transcription/ → app/src/main/java/com/antivocale/app/transcription/
   test/com/antivocale/app/transcription/ → app/src/test/java/com/antivocale/app/transcription/
   ```

2. Uncomment the llama-bro dependency in `app/build.gradle.kts`

3. Uncomment the GGUF DI bindings in `TranscriptionModule.kt`

4. Uncomment the GGUF section in `ModelTab.kt` (search for `GgufDownloadSection`)

5. Uncomment the `ShareGemma` alias in `AndroidManifest.xml`

6. Uncomment the Gemma entry in `ShareTargetManager.TARGETS` and `ShareReceiverActivity`

7. Uncomment `loadGgufBackend()` body in `TranscriptionOrchestrator.kt`

8. Uncomment GGUF methods in `ModelViewModel.kt` (`useGgufModel`, `deleteGgufModel`)

9. Uncomment GGUF download branch in `ExtractionService.kt`

10. Uncomment the llama-bro keep rule in `app/proguard-rules.pro`

Or build with: `./gradlew -Penable.gguf installDebug`
