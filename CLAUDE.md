# Anti-Vocale

Android application written in Kotlin for transcribing voice messages locally on-device.

## Project Info
- **GitHub:** `RisorseArtificiali/anti-vocale` (fork: `paoloantinor/anti-vocale`)
- **Language:** Kotlin
- **Platform:** Android

## Development
- Default branch: `main`
- Git protocol: SSH
- **adb path:** `~/Android/Sdk/platform-tools/adb`
- **Build & install on device:** `./scripts/install.sh` (ALWAYS use this — never `./gradlew installDebug`)
- **Device:** Realme RMX3853 (Android 16, connects via wireless debugging at `telefonopaolo:35685`)

@import docs/BUILD.md

## Key Identifiers

- **Package:** `com.antivocale.app`

## Project Structure

- `app/src/main/java/com/antivocale/app/` — Main source
  - `transcription/` — Transcription backends (Whisper/sherpa-onnx, LiteRT-LM)
  - `ui/` — Compose UI screens and view models
  - `receiver/` — Broadcast receivers (Tasker integration)
  - `model/` — Model management and configuration
- `app/libs/` — Prebuilt AARs (sherpa-onnx, tracked via Git LFS)
- `docs/` — Build guides, research notes, scout reports
- `scripts/` — Build/install helpers (`install.sh`)

## Skills

- **`/model-scout [scope]`** -- Scout HuggingFace, GitHub releases, and the ASR landscape for new models, framework updates, and techniques that could improve on-device transcription. Scopes: `full`, `asr`, `llm`, `frameworks`, `parakeet`, `whisper`, `qwen`. Reports saved to `docs/scout-reports/`.

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

### Pre-Release R8 Audit Procedure

Before every release, run this audit to catch R8 stripping issues:

1. **Find all JNI/native dependencies** — scan `app/build.gradle.kts` for native library dependencies (AARs with `.so` files, JNI bridges)
2. **Cross-reference with proguard-rules.pro** — every native library package MUST have a `-keep class` entry
3. **Check for stale rules** — if a library was replaced (e.g., `de.kherud.llama` → `com.suhel.llamabro`), update the keep rule to match the new package
4. **Verify dynamically-registered classes** — classes registered via Hilt multibinding, map lookups, or string-based instantiation need keep rules. The existing `com.antivocale.app.transcription.**` rule covers backend classes
5. **Audit command**: `grep -E 'import (com\.|de\.|org\.)' app/src/main/java/ -rh | sed 's/.*import //' | sed 's/\..*//' | sort -u` — compare output against keep rule packages

**Known native libraries and their keep rule packages**:
| Library | Keep Package | Notes |
|---------|-------------|-------|
| sherpa-onnx | `com.k2fsa.sherpa.onnx.**` | ONNX inference via JNI |
| LiteRT-LM | `com.google.ai.edge.litertlm.**` | Gemma inference via JNI |
| llama-bro | `com.suhel.llamabro.**` | GGUF inference via llama.cpp JNI |

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
