# Test Suite Hygiene Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Restore the unit-test suite to green by fixing the two stale tracked tests that break `compileDebugUnitTestKotlin`, then land the pending notification-test WIP batch.

**Architecture:** Two sequential chunks. Chunk 1 fixes the two stale tracked Parakeet tests so the **entire** unit-test source set compiles and passes — this unblocks every other test in the repo (currently the stale tests poison the whole batch). Chunk 2 runs, reviews, and commits the untracked notification-test WIP (which already compiles but can't be *run* until Chunk 1 lands).

**Tech Stack:** Kotlin, JUnit 4, AndroidX test, Gradle. **Build env:** the sandbox has no Android SDK — every `./gradlew` step runs *outside* the sandbox with `export JAVA_HOME="$HOME/.sdkman/candidates/java/21.0.11-tem"` (default `java` is JDK 25, which breaks Kotlin).

---

## Verified reference facts (read before editing — do NOT re-derive)

- **`ParakeetModel`** (`app/src/main/java/com/antivocale/app/transcription/ParakeetModelManager.kt:225`) — fields `path: String`, `sizeBytes: Long`. **No `isValid`.** `ParakeetModelManager.validateModelDirectory(dir): ParakeetModel?` returns `null` when invalid, non-null when valid → a non-null return *is* the validity proof.
- **`ParakeetModelManager`** also exposes: `REQUIRED_FILES: Set<String>` (4 files: encoder.int8.onnx, decoder.int8.onnx, joiner.int8.onnx, tokens.txt), `isValidModelPath(path): Boolean`, `validateModelDirectory(File): ParakeetModel?`.
- **`ModelViewModel.ParakeetUiState`** (`app/src/main/java/com/antivocale/app/ui/viewmodel/ModelViewModel.kt:99`) — current shape:
  `selectedVariant`, `downloadedVariants`, `variantDownloadStates: Map<Variant, VariantDownloadState>`, `modelPath: String?`, `showDownloadDialog`, `showDeleteDialog`, `variantToDelete`. **The flat download fields are GONE from here.**
- **`ModelViewModel.VariantDownloadState`** (`ModelViewModel.kt:115`) — `downloadState: DownloadState = Idle`, `downloadProgress: Float = 0f`, `isDownloading: Boolean = false`, `errorMessage: String? = null`, `partialDownload: DownloadState.PartiallyDownloaded? = null`. **This is where the flat fields moved.** It is a `data class` → trivially constructible in tests.
- **`ModelViewModel.UiState`** (`ModelViewModel.kt:187`) — `status`, `statusMessage`, `modelPath: String`, `modelName: String`. The auto-selection tests reference `modelName`/`modelPath` → **still valid, still compile.**
- **`DownloadState`** (`app/src/main/java/com/antivocale/app/data/download/DownloadState.kt`) — `Idle, CheckingAccess, Connecting, Downloading, Extracting, CopyingFiles, PartiallyDownloaded, Complete, Error, Cancelled`. All current; the stale tests' `DownloadState.*` references are correct.
- **Notification WIP tests already compile** (confirmed: the earlier parser-test run compiled the whole test source set successfully once only the 2 Parakeet files were set aside). Unknown whether they *pass* — Chunk 2 verifies.

---

## File Structure

**Modify (Chunk 1):**
- `app/src/test/java/com/antivocale/app/transcription/ParakeetModelManagerTest.kt` — drop the obsolete `model.isValid` assertion.
- `app/src/test/java/com/antivocale/app/ui/viewmodel/ParakeetDownloadStateTest.kt` — migrate the download-transition tests from the removed flat `ParakeetUiState` fields to `VariantDownloadState`; keep the still-valid reflection + auto-selection tests; fix the class KDoc.

**Commit (Chunk 2 — currently untracked):**
- `app/src/test/java/com/antivocale/app/service/InferenceServiceNotificationTest.kt` (5 @Test — includes the TASK-228.5 taskId-dedup test)
- `app/src/test/java/com/antivocale/app/service/ExtractionServiceNotificationTest.kt` (2 @Test)
- `app/src/test/java/com/antivocale/app/receiver/TaskerRequestReceiverNotificationTest.kt` (2 @Test)
- `app/src/test/java/com/antivocale/app/util/NotificationChannelHelperTest.kt` (11 @Test)

**Leave alone:** `app/src/test/java/com/antivocale/app/di/HiltModuleTest.kt.bak` (already disabled by `.bak` — out of scope).

---

## Chunk 1: Unblock the test suite (fix the 2 stale tracked tests)

### Task 1: Fix `ParakeetModelManagerTest.kt` — remove the obsolete `isValid` assertion

**Files:** Modify `app/src/test/java/com/antivocale/app/transcription/ParakeetModelManagerTest.kt`.

- [ ] **Step 1:** Open the file. Find the test `` `validateModelDirectory returns model when all files present` `` (≈ line 44-65). It currently asserts `assertTrue(model.isValid)`.
- [ ] **Step 2:** Remove the `assertTrue(model.isValid)` line. `ParakeetModel` has no `isValid`; the preceding `assertNotNull(model)` + `assertEquals(dir.absolutePath, model!!.path)` already prove validity. Keep `assertEquals(400L, model.sizeBytes)`.
- [ ] **Step 3:** Grep to confirm no other `isValid` reference remains in this file: `grep -n "isValid" app/src/test/java/com/antivocale/app/transcription/ParakeetModelManagerTest.kt` → expect no hits referencing `model.isValid` (the `isValidModelPath` calls are fine — that function exists).
- [ ] **Step 4: Commit.**
  ```bash
  git add app/src/test/java/com/antivocale/app/transcription/ParakeetModelManagerTest.kt
  git commit -m "test(parakeet): drop obsolete isValid assertion (validateModelDirectory non-null proves validity)"
  ```

### Task 2: Migrate `ParakeetDownloadStateTest.kt` from flat `ParakeetUiState` fields to `VariantDownloadState`

**Files:** Modify `app/src/test/java/com/antivocale/app/ui/viewmodel/ParakeetDownloadStateTest.kt`.

- [ ] **Step 1:** Update the class KDoc — replace "Parakeet download state" framing with "per-variant download state (`VariantDownloadState`)" since the UI state was refactored to a variant model.

- [ ] **Step 2: Keep these tests UNCHANGED** (they still compile and remain valuable):
  - `` `ParakeetUiState has no extraction-related dead fields` `` (reflection test — still valid).
  - The 4 auto-selection tests using `ModelViewModel.UiState(modelName=..., modelPath=...)` (≈ lines 164-215) — `UiState` still has these fields.

- [ ] **Step 3: Rewrite the download-transition tests** (≈ lines 20-160: defaults, idle→downloading, progress, complete, partial, clear-partial, error, cancel) to construct **`ModelViewModel.VariantDownloadState`** instead of `ParakeetUiState`. The fields are identical, so this is a 1-line constructor swap per test. Template:
  ```kotlin
  @Test
  fun `variant download state transitions from idle to downloading`() {
      val idle = ModelViewModel.VariantDownloadState()

      val downloading = idle.copy(
          isDownloading = true,
          downloadProgress = 0f,
          downloadState = DownloadState.Connecting("https://huggingface.co/...")
      )

      assertTrue(downloading.isDownloading)
      assertEquals(0f, downloading.downloadProgress)
  }
  ```
  Apply the same `ParakeetUiState(...)` → `VariantDownloadState(...)` swap to the progress / complete / partial / clear-partial / error / cancel tests. Drop any assertion on fields that only exist on `ParakeetUiState` (`modelPath`, `showDownloadDialog`, `showDeleteDialog`) from these transition tests — those belong on `ParakeetUiState`, not `VariantDownloadState`.

- [ ] **Step 4: Split the old "defaults" test.** The original `` `ParakeetUiState defaults to ...` `` mixed `ParakeetUiState` fields (`modelPath`, `showDownloadDialog`) with removed fields. Split into two:
  - One asserting `VariantDownloadState()` defaults (`isDownloading=false`, `downloadProgress=0f`, `downloadState==Idle`, `errorMessage=null`, `partialDownload=null`).
  - One asserting `ParakeetUiState()` defaults (`modelPath=null`, `showDownloadDialog=false`, `showDeleteDialog=false`, `selectedVariant=null`, `downloadedVariants` empty, `variantDownloadStates` empty).

- [ ] **Step 5: Verify no removed-field references remain.**
  ```bash
  grep -nE "state\.(isDownloading|downloadProgress|downloadState|errorMessage|partialDownload)" app/src/test/java/com/antivocale/app/ui/viewmodel/ParakeetDownloadStateTest.kt
  ```
  Every hit must be on a `VariantDownloadState` instance, not a `ParakeetUiState` instance.

### Task 3: Run the FULL unit-test suite → green; commit Chunk 1 remainder

**Run outside the sandbox:**
- [ ] **Step 1:** Compile the whole test source set (this is the gate that was broken repo-wide):
  ```bash
  export JAVA_HOME="$HOME/.sdkman/candidates/java/21.0.11-tem"
  ./gradlew :app:compileDebugUnitTestKotlin
  ```
  Expected: BUILD SUCCESSFUL (no `Unresolved reference` errors).
- [ ] **Step 2:** Run the two fixed test classes:
  ```bash
  ./gradlew :app:testDebugUnitTest --tests "*.ParakeetModelManagerTest" --tests "*.ParakeetDownloadStateTest"
  ```
  Expected: BUILD SUCCESSFUL, all tests pass.
- [ ] **Step 3: Run the FULL suite** (the real goal — nothing else regressed):
  ```bash
  ./gradlew :app:testDebugUnitTest
  ```
  Expected: BUILD SUCCESSFUL. Note any pre-existing failures unrelated to this work (e.g. `DownloadStateTest`, `LanguageFilterTest` only emit warnings — those are fine).
- [ ] **Step 4: Commit.**
  ```bash
  git add app/src/test/java/com/antivocale/app/ui/viewmodel/ParakeetDownloadStateTest.kt
  git commit -m "test(parakeet): migrate download-state tests to VariantDownloadState (UI-state refactor)"
  ```

---

## Chunk 2: Land the notification-test WIP batch

> Blocked by Chunk 1 (the WIP tests can't be *run* until the stale-Parakeet compile blockage is cleared). Already tracked under `feature/subtitle-extraction-228`; this batch is unrelated to subtitles and should be its own commit.

### Task 4: Run the notification WIP tests; fix any failures

**Files (currently untracked, compile-clean):** the 4 notification test files listed in File Structure.

- [ ] **Step 1:** Run all 4 classes:
  ```bash
  export JAVA_HOME="$HOME/.sdkman/candidates/java/21.0.11-tem"
  ./gradlew :app:testDebugUnitTest \
    --tests "*.InferenceServiceNotificationTest" \
    --tests "*.ExtractionServiceNotificationTest" \
    --tests "*.TaskerRequestReceiverNotificationTest" \
    --tests "*.NotificationChannelHelperTest"
  ```
- [ ] **Step 2:** If any fail, read the failure, fix the test (or the production code if the test surfaced a real bug — escalate per the receiving-code-review skill before changing production). Re-run until green. Record the final pass count.

### Task 5: Review + commit the batch

- [ ] **Step 1:** Sanity-scan each file: correct package, no TODO/stubs, assertions are meaningful (not tautological). The `InferenceServiceNotificationTest` includes the TASK-228.5 `taskId`-dedup assertion — confirm it genuinely exercises the dedup (duplicate `taskId` → processed once).
- [ ] **Step 2: Commit the batch** (explicit paths — do NOT `git add -A`, the working tree has unrelated untracked files):
  ```bash
  git add \
    app/src/test/java/com/antivocale/app/service/InferenceServiceNotificationTest.kt \
    app/src/test/java/com/antivocale/app/service/ExtractionServiceNotificationTest.kt \
    app/src/test/java/com/antivocale/app/receiver/TaskerRequestReceiverNotificationTest.kt \
    app/src/test/java/com/antivocale/app/util/NotificationChannelHelperTest.kt
  git commit -m "test(notifications): add notification channel/service/receiver unit tests (+ taskId dedup, TASK-228.5)"
  ```

---

## Notes for the implementer

- **Why Chunk 1 is load-bearing:** Gradle compiles the *entire* unit-test source set before running any test. One broken sibling test poisons the batch — that's why `--tests` filtering alone never unblocked the parser test during TASK-228. Fixing these two files restores every unit test in the repo.
- **Verify, don't assume:** the `isValid` removal and the `VariantDownloadState` migration are derived from the current source (read it; don't trust this doc blindly). If a field name differs from what's documented here, open `ModelViewModel.kt:99-130` and match the real declaration.
- **No SDK in sandbox:** every `./gradlew` step must run outside the sandbox with JDK 21. Inside the sandbox you can only read/grep/edit.
- **Scope discipline:** do not commit the unrelated untracked files (`docs/scout-reports/*`, `model-export/*`, `app/libs/classes.jar`, `*.pyc`, `HiltModuleTest.kt.bak`). Use explicit `git add <paths>`.
