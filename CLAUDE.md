# Anti-Vocale

Android application written in Kotlin for transcribing voice messages locally on-device.

## Project Info
- **GitHub:** `RisorseArtificiali/anti-vocale` (fork: `paoloantinor/anti-vocale`)
- **Language:** Kotlin
- **Platform:** Android

## Development
- Default branch: `main`
- Git protocol: SSH
- **Commit provenance:** every commit message carries an `Assisted-by: Claude <noreply@anthropic.com>` trailer (maintainer decision 2026-08-29, alongside the AI-assisted disclosure in README/CONTRIBUTING). Append it to every commit this agent creates.
- **adb path:** `~/Android/Sdk/platform-tools/adb`
- **Build & install on device:** `./scripts/install.sh` (ALWAYS use this — never `./gradlew installDebug`)
- **Device:** Realme RMX3853 (Android 16, wireless debugging, paired once and persistently connected). It shows up in `adb devices` automatically, with a long mDNS serial like `adb-b51d20e6-XDR829 (2)._adb-tls-connect._tcp`. Do NOT run `adb disconnect` (it breaks the existing connection and `adb connect ip:port` will not re-establish it on a stale/rotated port). To target it, pass the serial to `-s` exactly as `adb devices` prints it; you can capture it with `D=$(adb devices | sed -n 's/^\(.*_adb-tls-connect\._tcp\)[[:space:]]*device$/\1/p')` and then `adb -s "$D" ...` (the serial contains spaces: an awk `$1` capture truncates it and adb reports "device not found"). The IP port (e.g. 192.168.20.174:40079) rotates and is irrelevant for commands. If the device ever drops off entirely, the user re-enables wireless debugging on the phone; otherwise no user input is needed.

@import docs/BUILD.md

## Key Identifiers

- **Package:** `com.antivocale.app`

## Project Structure

- `app/src/main/java/com/antivocale/app/` — Main source
  - `audio/` - Audio input domain: `AudioPreprocessor` (decode/chunking/VAD), `AudioDurationPolicy` (ALL duration ceilings + the long-audio warn decision, TASK-432: streaming 2h valve, whole-file heap-derived), `MemoryReadings` (one owner of platform memory reads), `PreprocessingErrorMessages` (localized error mapping)
  - `transcription/` — Transcription backends + model managers:
    - `SherpaBackend` (ONE sherpa-onnx engine; all built-in models are bundled-catalog entries: Parakeet TDT via OfflineRecognizer, Whisper via OfflineRecognizer, Qwen3-ASR via OfflineRecognizer, Nemotron 3.5 via OnlineRecognizer — the only streaming backend, GigaAM v3 Russian via OfflineRecognizer; per-entry `SherpaModelManager`/`SherpaModelDownloader` handle discovery + download)
    - `ExternalSherpaBackend` (user-imported external models, via OfflineRecognizer; dynamic BackendRegistry descriptors, `external:` prefix routing; ShareExternal family alias with chooser; families: Transducer/Whisper/CTC/SenseVoice/Canary)
    - `ModelFamilySupport` (per-family copy plans, metadata validation, sherpa config shared by external imports)
    - `LlmTranscriptionBackend` (Gemma via LiteRT-LM)
    - `OrphanedModelDirCleaner` reclaims stranded old-version dirs at startup.
  - `ui/` — Compose UI screens and view models
  - `receiver/` — Broadcast receivers + share-target aliases (ShareReceiverActivity)
  - `data/` — Preferences, ShareTargetManager, download infrastructure
  - `util/` — `CrashReporter` (flavor-split), `TranscriptFileSaver` (SAF auto-save), `AppNotificationChannel`, etc.
- `app/src/playStore/` — playStore-flavor source set: `CrashReporter` (Firebase-backed), `AndroidManifest.xml` (Firebase service suppression)
- `app/src/fdroid/` — fdroid-flavor source set: `CrashReporter` (logcat-only no-op). Firebase-free build for F-Droid.
- `app/libs/` — Prebuilt AAR (sherpa-onnx, NOT committed since v1.8.3): run `./scripts/fetch-sherpa-aar.sh` once after cloning, or Gradle fails resolving the runtime classpath
- `.sherpa-version` — Marker file at the repo root (tag + srclib commit hash of the pinned sherpa-onnx). When bumping the sherpa version, update ALL THREE sync points: this file, `SHERPA_ONNX_VERSION` in `scripts/fetch-sherpa-aar.sh`, and the `SRCLIB PIN` comment in `app/build.gradle.kts`. The F-Droid recipe's `sherpa_onnx` srclib pin must match the commit listed here (issue #38).
- `docs/` — Build guides, research notes, scout reports
- `scripts/` — Build/install helpers (`install.sh`)
- `eval/` — Desktop eval harness (`run_baseline.py`: WER/CER/loops via sherpa-onnx Python; `smoke_nemotron.py`: model validation). Uses `eval/.venv` with sherpa-onnx 1.13.3 Python.
- `fastlane/` — Store listing metadata (en-US + it-IT) for F-Droid
- `metadata/` — F-Droid build recipe (`com.antivocale.app.yml`)

## Architecture Gotchas

**Build flavors: playStore vs fdroid.** Two product flavors (`flavorDimensions += "store"`):
- `playStore` — includes Firebase Crashlytics + Analytics (scoped via `"playStoreImplementation"`). Firebase plugins applied conditionally based on `gradle.startParameter.taskNames` containing "Fdroid".
- `fdroid` — Firebase-free. `CrashReporter` is a logcat-only no-op. No `google-services.json` needed.
- Same `applicationId` (`com.antivocale.app`) for both — users can switch stores.
- Build commands: `./gradlew assemblePlayStoreDebug`, `./gradlew assembleFdroidRelease`, etc.
- `./gradlew assembleDebug` is ambiguous (must specify a flavor).

**Unit tests:** `./gradlew :app:testPlayStoreDebugUnitTest` (CI runs the fdroid flavor, `testFdroidDebugUnitTest`: same shared suite, because the playStore debug build carries the `.debug` applicationIdSuffix, which the Firebase google-services.json has no client for). That suffix is a standing trap: the debug package is `com.antivocale.app.debug`, NOT `com.antivocale.app` (the user's real installed app). Whatever touches package ids, shares, or notifications: verify which of the two you are driving.

**Adding a transcription backend → start from BackendRegistry (TASK-254..324 migrated the dispatch sites).** Add a `BackendDescriptor` in `transcription/BackendRegistry.kt` (backend id, ModelType, share alias, preference accessors, display-name derivation). The registry's KDoc carries the live checklist of what consumes it and what legitimately remains separate. **The registry is NO LONGER stateless**: it takes `ExternalModelStore` + `ExternalModelRecordsProvider` as constructor params; hand-built instances create duplicate collectors and racing read-modify-write domains. Since the migrations:
- `ActiveModelRepository` (active model name/path), `TranscriptionOrchestrator` (backend loading + saved-path lookup), `ShareTargetManager`/`ShareReceiverActivity` (share targets and alias resolution) all dispatch through the registry.
- `SettingsViewModel` collects `ActiveModelRepository` (the old dual-state root smell is gone); `ModelViewModel`'s file-validity check keys on the descriptor's ModelType (its benchmark-config when and other BACKEND_ID constant uses are documented in the registry KDoc).
- Deliberately separate: `ExtractionService.ModelType` stays the persistence/bookkeeping enum (its download dispatch carries no registry data); the manifest `activity-alias` names stay literal strings (pinned by `BackendRegistryTest`); `PreferencesManager` is the data source the descriptors delegate to; `TranscriptionModule`'s `@IntoSet` DI registration is its own concern.
- The disabled GGUF backend (`"gemma4_gguf"`) has NO descriptor: its literal id is matched explicitly at the fallback sites (orchestrator, repository, ModelViewModel). If it is ever re-enabled, give it a BACKEND_ID constant and a descriptor instead.
- After adding a backend, still `grep -rE "BACKEND_ID|gemma4_gguf" app/src/main` to confirm the GGUF fallback sites and any constant uses are coherent.

## External-Models Platform (v2a)

User-imported sherpa-onnx models (Transducer / Whisper / CTC / SenseVoice / Canary families) as first-class backends. Per-family invariants that bite: chunk caps are family-declared (Whisper 30s, Canary 10s) and tightened at request time by `TranscriptionMemoryPolicy` (RAM-derived; floor = min(30, cap); fail-open on unreadable memory); `TranscriptionBackend.requiresVadAlignedChunking` forces VAD-aligned segmentation regardless of the user toggle (Canary, and Gemma since TASK-370); `ModelFamilySupport.featureDim` is per-family mel bands (80 default, 128 Canary: a wrong count fails the native load or decodes garbage, GH #68-adjacent lesson). Key components:
- `ExternalModelStore` (`data/`): JSON-serialized records in one DataStore key; single source of truth
- `ExternalModelRecordsProvider`: StateFlow seam for the registry's synchronous `backends` getter
- `BackendRegistry`: composes static descriptors + dynamic external descriptors (`external:<id>` prefix)
- `ExternalSherpaBackend`: one configurable engine, routed by the `external:` prefix in `TranscriptionBackendManager`
- `ExternalModelImporter` (`data/`): single pipeline for folder/URL/entry-JSON imports with SHA-256 pins
- `ShareExternal` manifest alias: family-level share target opening a chooser (resolved before the subtitle branch)
- `CustomTransducerMigrator`: one-shot migration in `BridgeApplication.onCreate` (runCatching, marker-first)

Reference docs: `docs/external-models.md` (user-facing import formats, schema).

Gotchas:
- `ExternalModelStore` has NO `@Inject` (defaulted lambda params break Dagger; AppModule provider instead)
- The `external:` prefix is intercepted BEFORE the registry lookup in the orchestrator (cold-start race)
- `buildCopyPlan` role matching: encoder/decoder by keyword, joiner also matches "joint" (GigaAM), tokens prefers rnnt-hinted and ctc-free `.txt` files

**Notification ids are a reserved-range contract.** The result allocator owns every id from 3000 up; fixed and banded ids (foreground 1001/1003, download band 2001..2100, Tasker 2201..2300, share-choice 2401..2500) stay below it. The table lives on `ResultNotificationFactory.RESULT_NOTIFICATION_ID_BASE` and `ReservedNotificationIdContractTest` enforces it. A new notification id outside the contract silently replaces another notification.

**Process-lifetime coroutines use the injected `@ApplicationScope`** (`di/ApplicationScope.kt`, no dispatcher on the scope: launch sites pass their own). Never hand-build a scope for process-lifetime work; the four hand-built ones drifted (one lost the CrashReporter handler) and were consolidated in TASK-438.

**Language wiring is catalog data with a named owner:** `TranscriptionLanguagePolicy` resolves preference x per-variant `preferUiLanguage` flag x UI locale. The untouched default is the "system" sentinel (follows the app language on flagged variants, TASK-434: whisper small only); explicit "Auto-detect" keeps model-side detection. The benchmark site resolves through the same policy: change the mapping there, never at a call site.

**Debug-build test SPI:** the `TEST_SPI` broadcast reads/writes app state for device-driven tests (`docs/testing-spi.md`; ops get/set/records/help). Always invoke with `-n` (implicit shell broadcasts are silently dropped on the RMX3853) and launch the app once after a fresh install. Release builds contain no receiver.

## NNAPI crash recovery (issue #26)

NNAPI is available on ALL devices including MediaTek. If a native crash occurs while NNAPI is the selected provider, `MainActivity` auto-resets the preference to CPU on the next launch (leveraging `NativeCrashDetector`). Users see one crash, then the app falls back safely.

## Skills

- **`/model-scout [scope]`** -- Scout HuggingFace, GitHub releases, and the ASR landscape for new models, framework updates, and techniques that could improve on-device transcription. Scopes: `full`, `asr`, `llm`, `frameworks`, `parakeet`, `whisper`, `qwen`. Reports saved to `docs/scout-reports/`.

## Backlog label taxonomy

Task labels use a closed slash-namespaced vocabulary (migrated 2026-08-23 by `scripts/migrate-labels.py`); do not invent new labels:
- `kind/` (exactly ONE per task): feature, enhancement, bug, refactor, test, performance, chore, research, docs, breaking
- `area/` (one or two): ui, transcription, downloads, release, i18n, platform, settings, notifications, build, reliability, data, receiver, ci
- `model/` (optional, only for model-specific tasks): parakeet, whisper, gemma, qwen, nemotron, gigaam, sherpa, external
Never encode priority, milestone, or issue links in labels (dedicated fields exist for those).

## GitHub issue triage

External issue reports get answered on GitHub; anything we commit to goes into a tracking issue first, then a Backlog task. Live threads (2026-08-30): #69 (SenseVoice + Omnilingual catalog candidates), #70 (curated Model-tab profiles, community input). FAQ.md at the root mirrors the public answers; keep it in sync when these land.

## Release Checklist: New Models / Native Libraries / Architectures

Whenever integrating a new model, native library, JNI bridge, or supporting a new CPU architecture, **always** verify ProGuard/R8 rules before shipping a release build:

1. **Check `app/proguard-rules.pro`** — does the new code have JNI reflection, `@Keep` annotations, or dynamically-loaded classes that R8 could strip?
2. **Add keep rules** for any new native-facing classes:
   ```proguard
   -keep class com.antivocale.app.<new_package>.** { *; }
   ```
3. **Build a release APK** (`./gradlew assemblePlayStoreRelease` or `assembleFdroidRelease`) and test on a real device — debug builds don't apply R8, so JNI crashes only surface in release.
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
