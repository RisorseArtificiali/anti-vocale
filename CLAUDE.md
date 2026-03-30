# Anti-Vocale

Android application written in Kotlin for transcribing voice messages locally on-device.

## Project Info
- **GitHub:** Private repo at `paoloantinori/voice_message_reader` (being renamed to `anti-vocale`)
- **Language:** Kotlin
- **Platform:** Android

## Development
- Default branch: `main`
- Git protocol: SSH
- **adb path:** `~/Android/Sdk/platform-tools/adb`
- **Build & install on device:** `./gradlew installDebug`
- **Device:** Realme RMX3853 (Android 16, connects via wireless debugging)

@import docs/BUILD.md

## Release Checklist: New Models / Native Libraries / Architectures

Whenever integrating a new model, native library, JNI bridge, or supporting a new CPU architecture, **always** verify ProGuard/R8 rules before shipping a release build:

1. **Check `app/proguard-rules.pro`** — does the new code have JNI reflection, `@Keep` annotations, or dynamically-loaded classes that R8 could strip?
2. **Add keep rules** for any new native-facing classes:
   ```proguard
   -keep class com.antivocale.app.<new_package>.** { *; }
   ```
3. **Build a release APK** (`./gradlew assembleRelease`) and test on a real device — debug builds don't apply R8, so JNI crashes only surface in release.
4. **Key symptom**: model or native component works in debug but crashes immediately in release → almost always an R8 stripping issue.

**Context**: The distil-large-v3 Whisper model crashed on the v1.1.1 Play Store release because R8 stripped Kotlin metadata and transcription backend classes needed for JNI reflection. The fix was adding keep rules for `*Annotation*/InnerClasses/Signature`, `com.antivocale.app.transcription.**`, and `@androidx.annotation.Keep`.

<!-- BACKLOG.MD MCP GUIDELINES START -->

<CRITICAL_INSTRUCTION>

## BACKLOG WORKFLOW INSTRUCTIONS

This project uses Backlog.md MCP for all task and project management activities.

**CRITICAL RESOURCE**: Read `backlog://workflow/overview` to understand when and how to use Backlog for this project.

- **First time working here?** Read the overview resource IMMEDIATELY to learn the workflow
- **Already familiar?** You should have the overview cached ("## Backlog.md Overview (MCP)")
- **When to read it**: BEFORE creating tasks, or when you're unsure whether to track work

The overview resource contains:
- Decision framework for when to create tasks
- Search-first workflow to avoid duplicates
- Links to detailed guides for task creation, execution, and completion
- MCP tools reference

You MUST read the overview resource to understand the complete workflow. The information is NOT summarized here.

</CRITICAL_INSTRUCTION>

<!-- BACKLOG.MD MCP GUIDELINES END -->
