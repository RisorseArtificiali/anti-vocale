# Audio Duration Cap Removal Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the flat 10-minute audio cap with path-aware ceilings (2h streaming valve, heap-derived VAD ceiling), enforced by a metadata pre-read, with a calibrated time-estimate warning dialog, a 2GB file bound, a storage pre-check, and localized errors.

**Architecture:** A pure `AudioDurationPolicy` object owns every constant and decision (ceiling, warn threshold, estimate tiering, dialog decision). Enforcement moves to a container-metadata pre-read at the start of both `AudioPreprocessor` prepare functions; the two legacy post-decode checks are deleted. The estimate comes from the existing `TranscriptionCalibrator` (device-measured) with `BackendDescriptor.rtfEstimate` as cold-start fallback. The dialog is a pre-start gate at the single interactive in-app dispatch site (`LogsViewModel.reTranscribeWithBackend`); every other flow is headless and never shows it. Errors carry localized strings via one branch in the orchestrator's `userFacingErrorMessage` (TASK-396 precedent).

**Tech Stack:** Kotlin, JUnit + kotlin.test (existing `app/src/test`), MediaExtractor metadata reads, StatFs for the storage check, DataStore-backed `TranscriptionCalibrator`.

**Spec:** `docs/superpowers/specs/2026-09-01-audio-duration-cap-design.md` (commit `e402197` on origin/main). Tracked as TASK-432, GitHub issue #73.

**Ground rules for every commit in this plan:** message carries the `Assisted-by: Claude <noreply@anthropic.com>` trailer plus a `Gates:` marker (or `gate-exempt:` with reason for pure-test commits). Tests run with `./gradlew :app:testPlayStoreDebugUnitTest --tests "<pattern>"`. Never use `./gradlew installDebug`; device installs go through `./scripts/install.sh`.

---

## File Structure

| File | Action | Responsibility |
|---|---|---|
| `app/src/main/java/com/antivocale/app/audio/AudioDurationPolicy.kt` | Create | All constants + pure decisions (ceiling, warn threshold, estimate tiering, dialog decision) |
| `app/src/main/java/com/antivocale/app/audio/AudioPreprocessor.kt` | Modify | Pre-read `validateDuration`, delete lines 127/267 checks, 2GB bound, parameterized `DurationTooLong` |
| `app/src/main/java/com/antivocale/app/transcription/TranscriptionOrchestrator.kt` | Modify | Expose memory readings, pass readings into prepare calls, localize `PreprocessingError` at catch site |
| `app/src/main/java/com/antivocale/app/transcription/BackendRegistry.kt` | Modify | `BackendDescriptor.rtfEstimate` field + per-backend values |
| `app/src/main/java/com/antivocale/app/util/SharedAudioHandler.kt` | Modify | Free-space pre-check before copy, `CopyResult.OutOfSpace` |
| `app/src/main/java/com/antivocale/app/receiver/ShareReceiverActivity.kt` | Modify | Handle `OutOfSpace` with the localized message |
| `app/src/main/java/com/antivocale/app/ui/dialogs/LongAudioWarningDialog.kt` | Create | The pre-start warning dialog (Compose `AlertDialog`) |
| `app/src/main/java/com/antivocale/app/ui/viewmodel/LogsViewModel.kt` | Modify | Gate `reTranscribeWithBackend` (the ONLY interactive in-app transcription start) on the dialog decision |
| `app/src/main/java/com/antivocale/app/ui/tabs/LogsTab.kt` | Modify | Render `LongAudioWarningDialog` from view-model state (a `MutableStateFlow`, so Confirm/Cancel survive recomposition) |
| `app/src/main/res/values/strings.xml` + 10 locale `values-*/strings.xml` | Modify | New error + dialog strings |
| `app/src/test/java/com/antivocale/app/audio/AudioDurationPolicyTest.kt` | Create | Policy unit tests |
| `app/src/test/java/com/antivocale/app/audio/AudioPreprocessorTest.kt` | Modify | Update for pre-read semantics + 2GB bound |
| `FAQ.md` | Modify | Long-audio entry |

Dialog surface census (verified 2026-09-01): `LogsViewModel.reTranscribeWithBackend` (called from `LogsTab.kt:304` inside `RetranscribeDialog`) is the ONLY interactive in-app transcription start; the spec's "MainActivity flow" physically lives here. It is the only gated site. Every other `InferenceService` start is headless by nature and left ungated: `ShareReceiverActivity:371` (share), `TaskerRequestReceiver:104` + `TaskerTrampolineActivity:29` (Tasker), `NotificationActionReceiver:85` (notification retry), `BenchmarkActivity:65`, `SubtitleChoiceTimeoutWorker`, and the cancel dispatch at `LogsTab.kt:121` (ACTION_CANCEL_TASK, not a start).

---

## Chunk 1: Policy + enforcement (Tasks 1-3)

### Task 1: AudioDurationPolicy (pure, TDD)

**Files:**
- Create: `app/src/main/java/com/antivocale/app/audio/AudioDurationPolicy.kt`
- Test: `app/src/test/java/com/antivocale/app/audio/AudioDurationPolicyTest.kt`

- [ ] **Step 1: Write the failing tests**

```kotlin
package com.antivocale.app.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AudioDurationPolicyTest {

    // 3 * 64KiB/s peak budget; the admission-formula denominator
    private val denom = 3L * 64 * 1024L

    @Test
    fun `streaming ceiling is the 2h valve regardless of memory`() {
        assertEquals(7200L, AudioDurationPolicy.ceilingSeconds(
            AudioDurationPolicy.DecodePath.STREAMING, 1L shl 30, 256L * 1024 * 1024))
        assertEquals(7200L, AudioDurationPolicy.ceilingSeconds(
            AudioDurationPolicy.DecodePath.STREAMING, null, null))
    }

    @Test
    fun `whole-file ceiling is heap-bound on typical devices`() {
        // 512MB heap, RAM plentiful: budget = min(ram/4, heap/2) = 256MiB
        val ram = 8L * 1024 * 1024 * 1024
        val heap512 = 512L * 1024 * 1024
        val expected = (heap512 / 2) / denom   // 1365s = ~22.8 min
        assertEquals(expected, AudioDurationPolicy.ceilingSeconds(
            AudioDurationPolicy.DecodePath.WHOLE_FILE_PCM, ram, heap512))
        // 256MB heap -> 682s = ~11.4 min
        val heap256 = 256L * 1024 * 1024
        assertEquals((heap256 / 2) / denom, AudioDurationPolicy.ceilingSeconds(
            AudioDurationPolicy.DecodePath.WHOLE_FILE_PCM, ram, heap256))
    }

    @Test
    fun `whole-file ceiling is RAM-bound on a low-RAM device with a big heap`() {
        val ram = 512L * 1024 * 1024        // ram/4 = 128MiB
        val heap = 512L * 1024 * 1024       // heap/2 = 256MiB; RAM binds
        assertEquals((ram / 4) / denom, AudioDurationPolicy.ceilingSeconds(
            AudioDurationPolicy.DecodePath.WHOLE_FILE_PCM, ram, heap))
    }

    @Test
    fun `clamp bounds hold on both sides`() {
        val tiny = AudioDurationPolicy.ceilingSeconds(
            AudioDurationPolicy.DecodePath.WHOLE_FILE_PCM, 128L shl 20, 128L shl 20)
        assertEquals(AudioDurationPolicy.VAD_MIN_SECONDS, tiny)
        val huge = AudioDurationPolicy.ceilingSeconds(
            AudioDurationPolicy.DecodePath.WHOLE_FILE_PCM, 32L shl 30, 4L shl 30)
        assertEquals(AudioDurationPolicy.VAD_MAX_SECONDS, huge)
    }

    @Test
    fun `fail-open on unreadable memory is the 600s floor`() {
        assertEquals(600L, AudioDurationPolicy.ceilingSeconds(
            AudioDurationPolicy.DecodePath.WHOLE_FILE_PCM, null, 512L shl 20))
        assertEquals(600L, AudioDurationPolicy.ceilingSeconds(
            AudioDurationPolicy.DecodePath.WHOLE_FILE_PCM, 8L shl 30, null))
        assertEquals(600L, AudioDurationPolicy.ceilingSeconds(
            AudioDurationPolicy.DecodePath.WHOLE_FILE_PCM, 0L, 0L))
    }

    @Test
    fun `estimate tiering prefers the device calibration`() {
        // 2+ samples: measured value wins even when slower than the fallback
        val measured = 250f   // ms per second of audio (RTF 0.25)
        assertEquals(measured, AudioDurationPolicy.resolveEstimateMsPerSec(measured, 2, 1000f / 15f))
        assertEquals(measured, AudioDurationPolicy.resolveEstimateMsPerSec(measured, 7, 1000f / 15f))
        // <2 samples: family fallback (rtf 15 => 1000/15 ms per second)
        assertEquals(1000f / 15f, AudioDurationPolicy.resolveEstimateMsPerSec(measured, 1, 15f))
        assertEquals(1000f / 15f, AudioDurationPolicy.resolveEstimateMsPerSec(null, 0, 15f))
    }

    @Test
    fun `warn decision truth table`() {
        val ceiling = 7200L
        val est = 1000f / 15f   // Parakeet cold: 45 min audio -> ~3 min estimate
        // below threshold: no dialog
        assertFalse(AudioDurationPolicy.warnDecision(1700L, ceiling, est, true).showDialog)
        // above threshold + dialog-capable: dialog, estimate rounded UP to the minute
        val d = AudioDurationPolicy.warnDecision(2700L, ceiling, est, true)
        assertTrue(d.showDialog); assertEquals(3L, d.estimateMinutes)
        // headless: never a dialog
        assertFalse(AudioDurationPolicy.warnDecision(2700L, ceiling, est, false).showDialog)
        // above ceiling: NO dialog (the pre-read refusal carries the message)
        assertFalse(AudioDurationPolicy.warnDecision(8000L, ceiling, est, true).showDialog)
    }

    @Test
    fun `estimate rounds up to the minute`() {
        // 31 min audio at RTF 15 => 124s = 2.07 min -> 3
        assertEquals(3L, AudioDurationPolicy.warnDecision(
            1860L, 7200L, 1000f / 15f, true).estimateMinutes)
    }
}
```

- [ ] **Step 2: Run to verify failure**

Run: `./gradlew :app:testPlayStoreDebugUnitTest --tests "com.antivocale.app.audio.AudioDurationPolicyTest"`
Expected: compile error (unresolved `AudioDurationPolicy`).

- [ ] **Step 3: Write the policy**

```kotlin
package com.antivocale.app.audio

/**
 * Single source of truth for audio-duration ceilings and the long-audio
 * warning decision (spec: docs/superpowers/specs/2026-09-01-audio-duration-cap-design.md).
 *
 * Pure Kotlin, no Android imports: memory readings enter as parameters so the
 * whole policy is JVM-testable.
 */
object AudioDurationPolicy {

    /** Streaming path: practical valve, not a memory constraint. */
    const val STREAMING_MAX_SECONDS = 7200L

    /** Whole-file path clamp floor; also the fail-open value. */
    const val VAD_MIN_SECONDS = 600L

    /** Whole-file path clamp ceiling (same 2h as streaming, reached only with a huge heap). */
    const val VAD_MAX_SECONDS = 7200L

    /** 16kHz mono FloatArray bytes per second of audio. */
    const val PCM_BYTES_PER_SECOND = 64 * 1024L

    /** Peak copies budgeted: merge peaks at 2x-of-final (final included) plus the VAD copy. */
    const val PCM_PEAK_COPIES = 3L

    enum class DecodePath { STREAMING, WHOLE_FILE_PCM }

    data class WarnDecision(
        val showDialog: Boolean,
        /** Estimated compute time, rounded UP to the minute. */
        val estimateMinutes: Long,
        /** True when the estimate came from the cold-start fallback (fewer than 2 calibration samples). */
        val isRough: Boolean,
    )

    /**
     * WHOLE_FILE_PCM budgets the binding constraint. The decoded FloatArray lives
     * in the dalvik heap (no largeHeap in the manifest), so the heap, not system
     * RAM, caps large arrays: budget = min(availRam/4, maxHeap/2) over 3 PCM
     * copies, clamped to [VAD_MIN_SECONDS, VAD_MAX_SECONDS]. Either reading null
     * or <= 0 fails open to the floor, matching the pre-1.12 flat cap.
     */
    fun ceilingSeconds(path: DecodePath, availableRamBytes: Long?, maxHeapBytes: Long?): Long {
        if (path == DecodePath.STREAMING) return STREAMING_MAX_SECONDS
        val ram = availableRamBytes ?: return VAD_MIN_SECONDS
        val heap = maxHeapBytes ?: return VAD_MIN_SECONDS
        if (ram <= 0L || heap <= 0L) return VAD_MIN_SECONDS
        val budgetBytes = minOf(ram / 4L, heap / 2L)
        return (budgetBytes / (PCM_PEAK_COPIES * PCM_BYTES_PER_SECOND))
            .coerceIn(VAD_MIN_SECONDS, VAD_MAX_SECONDS)
    }

    /** Advisory dialog above 30 minutes. */
    fun warnThresholdSeconds(): Long = 1800L

    /**
     * Estimate tiering: the on-device calibration (2+ samples) wins even when
     * slower than the family fallback, because optimism is the failure mode.
     */
    fun resolveEstimateMsPerSec(calibratedMsPerSec: Float?, sampleCount: Int, fallbackRtf: Float): Float =
        if (sampleCount >= 2 && calibratedMsPerSec != null && calibratedMsPerSec > 0f) calibratedMsPerSec
        else 1000f / fallbackRtf

    /**
     * No dialog when duration exceeds the ceiling: the pre-read refusal already
     * carries the actionable message, and a dialog there would promise a
     * transcription that is then refused.
     */
    fun warnDecision(
        durationSeconds: Long,
        ceilingSeconds: Long,
        estimateMsPerSec: Float,
        dialogCapable: Boolean,
        calibrated: Boolean = true,
    ): WarnDecision {
        val show = dialogCapable &&
            durationSeconds in (warnThresholdSeconds() + 1) until ceilingSeconds
        if (!show) return WarnDecision(false, 0L, !calibrated)
        val minutes = kotlin.math.ceil(durationSeconds * estimateMsPerSec / 1000f / 60f)
        return WarnDecision(true, minutes.toLong(), !calibrated)
    }
}
```

- [ ] **Step 4: Run to verify pass**

Run: `./gradlew :app:testPlayStoreDebugUnitTest --tests "com.antivocale.app.audio.AudioDurationPolicyTest"`
Expected: PASS, 8 tests.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/antivocale/app/audio/AudioDurationPolicy.kt \
        app/src/test/java/com/antivocale/app/audio/AudioDurationPolicyTest.kt
git commit -m "feat(audio): AudioDurationPolicy pure ceiling + warn decisions (TASK-432)

Gates: test-first (AudioDurationPolicyTest 8 green); /simplify + code-review
deferred to the consolidated diff at task 8 per plan.

Assisted-by: Claude <noreply@anthropic.com>"
```

### Task 2: Memory readings helper

**Files:**
- Create: `app/src/main/java/com/antivocale/app/audio/MemoryReadings.kt`
- Modify: `app/src/main/java/com/antivocale/app/transcription/TranscriptionOrchestrator.kt:457-464`

- [ ] **Step 1: Create the helper (extraction, not duplication)**

Move the body of the orchestrator's private `availableMemoryBytes` (lines 457-464, including the string-overload comment: unit-test Context fakes return generic objects from `getSystemService`, and the class-based overload crashes them) into an object next to the policy, and add the heap reading:

```kotlin
package com.antivocale.app.audio

import android.app.ActivityManager
import android.content.Context

/** One owner for the memory readings [AudioDurationPolicy] consumes. */
internal object MemoryReadings {
    /** ActivityManager.MemoryInfo.availMem, or null when the service is unavailable. */
    fun availableRamBytes(context: Context): Long? {
        val info = ActivityManager.MemoryInfo()
        // String overload + safe cast: unit-test Context fakes return generic
        // objects from getSystemService, and the class-based overload's
        // implicit checkcast crashes them.
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return null
        am.getMemoryInfo(info)
        return info.availMem
    }

    /** Dalvik heap limit for this process (no largeHeap in the manifest). */
    fun maxHeapBytes(): Long = Runtime.getRuntime().maxMemory()
}
```

In `TranscriptionOrchestrator`, replace the private `availableMemoryBytes(context)` body with `MemoryReadings.availableRamBytes(context) ?: 0L` (its existing call site at the chunk-cap tightening treats 0 as "measurement unavailable"), delete the duplicate implementation, and add the import.

- [ ] **Step 2: Compile + existing suite still green**

Run: `./gradlew :app:testPlayStoreDebugUnitTest --tests "com.antivocale.app.transcription.*"`
Expected: PASS (no behavior change; the reading is identical).

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/antivocale/app/audio/MemoryReadings.kt \
        app/src/main/java/com/antivocale/app/transcription/TranscriptionOrchestrator.kt
git commit -m "refactor(audio): extract MemoryReadings, one owner for policy inputs (TASK-432)

Gates: behavior-preserving extraction; transcription unit suite green.

Assisted-by: Claude <noreply@anthropic.com>"
```

### Task 3: Pre-read enforcement in AudioPreprocessor

**Files:**
- Modify: `app/src/main/java/com/antivocale/app/audio/AudioPreprocessor.kt` (constants 44-45, error class 90-99, whole-file check 127-130, streaming check 267-270, internal delegation at 227, `validateInputFile` 612-618)
- Modify: `app/src/main/java/com/antivocale/app/transcription/TranscriptionOrchestrator.kt` (prepare call sites ~703 and ~1080 pass readings)
- Modify: 9 orchestrator test files (mockk stub updates, listed in Step 5)
- Modify: `app/src/test/java/com/antivocale/app/audio/AudioPreprocessorTest.kt` (compile fix only; this harness is plain JUnit4 and MediaExtractor is a JVM stub under `isReturnDefaultValues`, so no behavioral duration test is observable here)
- Modify: `app/src/androidTest/.../AudioPreprocessorInstrumentedTest.kt` (the behavioral duration tests; this is where MediaExtractor is real)

- [ ] **Step 1: Update the error class (parameterized ceiling, 2GB bound)**

```kotlin
sealed class PreprocessingError(message: String) : Exception(message) {
    data object FileNotFound : PreprocessingError("Audio file not found")
    data object FileTooLarge : PreprocessingError("Audio file exceeds 2GB limit")
    data object InvalidFormat : PreprocessingError("Unable to determine audio format")
    data class DurationTooLong(val ceilingSeconds: Long, val path: AudioDurationPolicy.DecodePath) :
        PreprocessingError("Audio exceeds ${ceilingSeconds / 60} minute limit on this path")
    data object DurationUnknown : PreprocessingError("Could not determine audio duration")
    data class ConversionFailed(val reason: String) : PreprocessingError("Conversion failed: $reason")
    data class ChunkFailed(val chunkIndex: Int, val reason: String) : PreprocessingError("Chunk $chunkIndex failed: $reason")
    data object NoAudioTrack : PreprocessingError("No audio track found in file")
}
```

Replace `MAX_FILE_SIZE_BYTES = 100 * 1024 * 1024` with `MAX_FILE_SIZE_BYTES = 2L * 1024 * 1024 * 1024` and delete `MAX_DURATION_SECONDS` entirely.

- [ ] **Step 2: Add the pre-read and delete the legacy checks**

New validation, called at the START of both `prepareAudioForMediaPipe` (right after `validateInputFile`) and `prepareAudioStream` (same position). Both prepare functions gain two parameters `availableRamBytes: Long?` and `maxHeapBytes: Long?` (defaulted to null, which fails open, so unrelated test callers compile):

```kotlin
/**
 * Metadata pre-read enforcement (spec: the old post-decode check at line 127
 * could not enforce a heap-derived ceiling, because the full PCM was already
 * resident when it fired). Reads container duration only, no decode. Missing
 * metadata fails OPEN, matching the legacy whole-file behavior where a bad
 * KEY_DURATION read as 0.
 */
private fun validateDuration(
    inputPath: String,
    path: AudioDurationPolicy.DecodePath,
    availableRamBytes: Long?,
    maxHeapBytes: Long?,
) {
    val duration = getAudioDuration(inputPath)
    if (duration <= 0.0) return
    val ceiling = AudioDurationPolicy.ceilingSeconds(path, availableRamBytes, maxHeapBytes)
    if (duration > ceiling) {
        Log.e(TAG, "Audio too long: ${duration}s > ${ceiling}s ceiling on $path")
        throw PreprocessingError.DurationTooLong(ceiling, path)
    }
}
```

Delete the whole-file post-decode check (`if (duration > MAX_DURATION_SECONDS) ... throw PreprocessingError.DurationTooLong`, old lines 127-130) and ONLY the four closing lines of the streaming in-thread check (the `if (totalDurationSeconds > MAX_DURATION_SECONDS) { channel.close(...); return@Thread }` block, old lines 267-270; KEEP the `durationUs`/`totalDurationSeconds` assignments above it, which lines 274 and 281 still use for `expectedChunks` and the Header). In `prepareAudioForMediaPipe` call `validateDuration(inputPath, DecodePath.WHOLE_FILE_PCM, availableRamBytes, maxHeapBytes)`; in `prepareAudioStream` call it with `DecodePath.STREAMING` before the decode thread starts.

THIRD CALLER: the internal delegation at `AudioPreprocessor.kt:227` (`prepareAudioStream`'s VAD branch forwarding to `prepareAudioForMediaPipe`) must forward `availableRamBytes`/`maxHeapBytes` too, or the inner call silently enforces WHOLE_FILE_PCM with null readings (fails open to the 600s floor) after the outer call already admitted under the streaming ceiling. It is unreachable through the orchestrator today (the pipeline call site hardcodes `enableVad = false`), but forward the readings anyway so the two layers cannot disagree when that changes.

- [ ] **Step 3: Orchestrator passes the readings**

At the `prepareAudioForMediaPipe` call (~line 703) and inside `processPipelinedAudio`'s `prepareAudioStream` call (~line 1080), add:

```kotlin
availableRamBytes = MemoryReadings.availableRamBytes(context),
maxHeapBytes = MemoryReadings.maxHeapBytes(),
```

- [ ] **Step 4: Fix the JVM test compile break, move behavior to androidTest**

In `AudioPreprocessorTest.kt`, `DurationTooLong` changes from `data object` to a data class with two required parameters: the message test at lines 41-44 stops compiling. Update it to construct `DurationTooLong(600, AudioDurationPolicy.DecodePath.STREAMING)` and assert the formatted message. Do NOT attempt fixture-based duration tests in this file: it is plain JUnit4 and `MediaExtractor` is a JVM stub under `testOptions.unitTests.isReturnDefaultValues = true` (build.gradle.kts:161-166), so `getAudioDuration` always returns 0.0 there and no duration behavior is observable.

The behavioral tests (an 11-minute file with permissive readings passes the whole-file path; an above-7200s file throws `DurationTooLong(7200, path)`; a valid-KEY_DURATION-but-corrupted-track container throws `DurationTooLong`, not `NoAudioTrack`, proving the pre-read runs before decode) go into the EXISTING instrumented file `app/src/androidTest/.../AudioPreprocessorInstrumentedTest.kt`, where MediaExtractor is real. They run at device-gate time (Task 8 uses the same fixtures); a connectedAndroidTest run is optional if the device is attached.

- [ ] **Step 5: Update the ~23 mockk stub blocks across 9 orchestrator test files**

The orchestrator now passes two extra args (`availableRamBytes` is null with a mocked Context, `maxHeapBytes` is always non-null). A mockk stub recorded on a subset of named args does NOT match a call carrying more args: the stub misses, the relaxed mock returns a child mock of `PreprocessingResult`/`Flow`, and the suites explode. Add `availableRamBytes = any(), maxHeapBytes = any()` to every `every { ... prepareAudioForMediaPipe(...) }` / `prepareAudioStream(...)` stub block in:

- `TranscriptionOrchestratorTestBase.kt:119` (`stubPreprocessing`, used by every subclass)
- `TranscriptionOrchestratorAudioTest.kt` (11 sites)
- `VadTest.kt:66`, `ParallelTest.kt:49+87`, `InterimThrottleTest.kt:66`
- `ChunkRetryTest.kt:65/75/95`, `PipelineProgressiveTest.kt:70`, `ChunkOrderingTest.kt:50`
- `LlmChunkPromptTest.kt:76+99`

- [ ] **Step 6: Run BOTH suites (the transcription package is the real gate)**

Run: `./gradlew :app:testPlayStoreDebugUnitTest --tests "com.antivocale.app.audio.*" --tests "com.antivocale.app.transcription.*"`
Expected: PASS (audio suites plus every orchestrator suite above; the audio-only filter would hide exactly the breakage Step 5 exists to catch).

- [ ] **Step 7: Commit**

```bash
git add app/src/main/java/com/antivocale/app/audio/AudioPreprocessor.kt \
        app/src/main/java/com/antivocale/app/transcription/TranscriptionOrchestrator.kt \
        app/src/test/java/com/antivocale/app/ app/src/androidTest/
git commit -m "feat(audio): metadata pre-read enforcement, 2GB bound, remove flat 10-min cap (TASK-432)

Gates: audio + transcription unit suites green (mockk stubs updated for the
new readings args); behavioral duration tests moved to androidTest where
MediaExtractor is real; consolidated /simplify + code-review at task 8.

Assisted-by: Claude <noreply@anthropic.com>"
```

Note on discipline: Tasks 2 and 3 deviate from strict test-first (Task 2 is a behavior-preserving extraction; Task 3's JVM-observable surface is a compile fix, with the behavioral tests living in androidTest). That is deliberate: the JVM harness cannot observe MediaExtractor behavior, and inventing a shadow for it would test the shadow.

---

## Chunk 2: Estimates, dialog, i18n, storage (Tasks 4-7)

### Task 4: BackendDescriptor.rtfEstimate

**Files:**
- Modify: `app/src/main/java/com/antivocale/app/transcription/BackendRegistry.kt` (data class ~54, `catalogDescriptor` ~155, `llmDescriptor` ~184, `descriptorFor` ~206)
- Test: `app/src/test/java/com/antivocale/app/transcription/BackendRegistryTest.kt`

- [ ] **Step 1: Add the field with KDoc**

In `BackendDescriptor`, after `isStreaming`:

```kotlin
/**
 * Cold-start speed estimate: audio-seconds processed per compute-second.
 * Used ONLY when TranscriptionCalibrator has fewer than 2 samples for this
 * model on this device (AudioDurationPolicy.resolveEstimateMsPerSec tiers).
 * The conservative default of 1f overestimates time, never underestimates.
 */
val rtfEstimate: Float = 1f,
```

- [ ] **Step 2: Set per-backend values**

In `catalogDescriptor`, before the `BackendDescriptor(...)` return:

```kotlin
// Cold-start RTF: Parakeet TDT is roughly 15x real time on a mid-range SoC;
// the other offline sherpa families cluster around 4x. Calibrator samples
// replace these after two runs on the actual device.
val rtf = if (entry.id == BuiltInBackendIds.PARAKEET) 15f else 4f
```

and pass `rtfEstimate = rtf` in the constructor call. In `llmDescriptor` pass `rtfEstimate = 1f` explicitly with the same comment style. In `descriptorFor(record)` pass `rtfEstimate = 4f`: external families get the conservative cluster default, because the catalog Parakeet exception cannot be detected from `ModelFamily` alone.

- [ ] **Step 3: Test + commit**

Add to `BackendRegistryTest`: the PARAKEET descriptor reports 15f, every other static descriptor reports a value in 1f..15f, and external descriptors report 4f.

Run: `./gradlew :app:testPlayStoreDebugUnitTest --tests "com.antivocale.app.transcription.BackendRegistryTest"`
Expected: PASS.

```bash
git add app/src/main/java/com/antivocale/app/transcription/BackendRegistry.kt \
        app/src/test/java/com/antivocale/app/transcription/BackendRegistryTest.kt
git commit -m "feat(registry): BackendDescriptor.rtfEstimate cold-start fallback (TASK-432)

Gates: BackendRegistryTest green.

Assisted-by: Claude <noreply@anthropic.com>"
```

### Task 5: Error strings + mapper (i18n)

**Files:**
- Modify: `app/src/main/res/values/strings.xml` and the 10 locale files under `app/src/main/res/values-{de,es,fr,hi,it,pl,pt-rBR,ru,tr,uk}/strings.xml`
- Create: `app/src/main/java/com/antivocale/app/audio/PreprocessingErrorMessages.kt`
- Modify: `app/src/main/java/com/antivocale/app/transcription/TranscriptionOrchestrator.kt` (catch sites)

- [ ] **Step 1: Add ALL the strings (none of the nine exist today; base first, then all 10 locales, same translation flow used for the 1.11.x strings)**

The two conversion strings carry placeholders, or `getString(res, args)` silently drops its arguments:

```xml
<string name="error_audio_too_long_streaming">Audio longer than 2 hours is not supported.</string>
<string name="error_audio_too_long_vad">With VAD enabled the maximum length depends on your device\'s memory (here: %1$d minutes). Turn VAD off, or use a chunked model, for long recordings.</string>
<string name="error_file_too_large">Audio files larger than 2GB are not supported.</string>
<string name="error_file_not_found">Audio file not found</string>
<string name="error_invalid_format">Unable to determine audio format</string>
<string name="error_no_audio_track">No audio track found in file</string>
<string name="error_duration_unknown">Could not determine audio duration</string>
<string name="error_conversion_failed">Conversion failed: %1$s</string>
<string name="error_chunk_failed">Chunk %1$d failed: %2$s</string>
```

- [ ] **Step 2: The mapper**

Check which plain-error strings already exist: `grep -n "error_file_not_found\|error_invalid_format\|error_no_audio_track\|error_duration_unknown" app/src/main/res/values/strings.xml`. Reuse what exists, add what is missing (same 10 locales, current English text as the base), then:

```kotlin
package com.antivocale.app.audio

import android.content.Context
import com.antivocale.app.R

/**
 * Localized user-facing text for PreprocessingError (TASK-396 OOM precedent:
 * the notification and the broadcast reply must carry localized advice, not
 * the sealed class's English).
 */
object PreprocessingErrorMessages {
    fun localize(context: Context, error: PreprocessingError): String = when (error) {
        is PreprocessingError.DurationTooLong -> when (error.path) {
            AudioDurationPolicy.DecodePath.STREAMING ->
                context.getString(R.string.error_audio_too_long_streaming)
            AudioDurationPolicy.DecodePath.WHOLE_FILE_PCM ->
                context.getString(R.string.error_audio_too_long_vad, error.ceilingSeconds / 60)
        }
        is PreprocessingError.FileTooLarge -> context.getString(R.string.error_file_too_large)
        is PreprocessingError.FileNotFound -> context.getString(R.string.error_file_not_found)
        is PreprocessingError.InvalidFormat -> context.getString(R.string.error_invalid_format)
        is PreprocessingError.NoAudioTrack -> context.getString(R.string.error_no_audio_track)
        is PreprocessingError.DurationUnknown -> context.getString(R.string.error_duration_unknown)
        is PreprocessingError.ConversionFailed -> context.getString(R.string.error_conversion_failed, error.reason)
        is PreprocessingError.ChunkFailed -> context.getString(R.string.error_chunk_failed, error.chunkIndex, error.reason)
    }
}
```

- [ ] **Step 3: Wire at the REAL localization seam: `userFacingErrorMessage`**

The orchestrator's two `PreprocessingError` catches (lines 716-717 and 1171-1172) only `return Result.failure(e)`; no catch forwards a message. The user-facing text is built later, in `processRequest`'s `result.fold` `onFailure`, through the companion `userFacingErrorMessage(context, error)` (lines 76-99), which currently drops `PreprocessingError` into the generic `else -> R.string.transcription_failed` branch. THE EDIT IS ONE BRANCH there:

```kotlin
is PreprocessingError -> PreprocessingErrorMessages.localize(context, error)
```

placed before the generic `else`, alongside the existing special cases (the OOM branch is the precedent). That one branch localizes every consumer downstream: the notification via `InferenceService.onError` and the Tasker broadcast reply.

- [ ] **Step 4: Lint the locales, run the suite, commit**

Run: `./gradlew :app:lintPlayStoreDebug`
Expected: no missing-translation error for the new keys (the 1.11.x lint gate treats new-base-only strings as errors).

Run: `./gradlew :app:testPlayStoreDebugUnitTest`
Expected: PASS (full suite).

```bash
git add app/src/main/res/ app/src/main/java/com/antivocale/app/audio/PreprocessingErrorMessages.kt \
        app/src/main/java/com/antivocale/app/transcription/TranscriptionOrchestrator.kt
git commit -m "feat(i18n): localized preprocessing errors incl. per-path duration ceiling (TASK-432)

Gates: full unit suite + resource lint green.

Assisted-by: Claude <noreply@anthropic.com>"
```

### Task 6: Storage pre-check on the import path

**Files:**
- Modify: `app/src/main/java/com/antivocale/app/util/SharedAudioHandler.kt` (new `CopyResult.OutOfSpace`, check inside `copyToAppStorage` ~line 69)
- Modify: `app/src/main/java/com/antivocale/app/receiver/ShareReceiverActivity.kt` (~line 221 result handling)
- Create: `app/src/test/java/com/antivocale/app/util/SharedAudioHandlerStorageTest.kt`

- [ ] **Step 1: Size the source and check free space before writing**

In `copyToAppStorage`, after the extension validation and before creating the output file:

```kotlin
// Pre-copy storage gate (spec: with the 2GB sanity bound, a near-full device
// would otherwise hit ENOSPC mid-copy). Source size via AssetFileDescriptor;
// unknown size fails open (the copy itself will error visibly).
val neededBytes = try {
    context.contentResolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
} catch (e: Exception) { -1L }
if (neededBytes > 0 && !hasFreeSpace(android.os.StatFs(context.filesDir.path).availableBytes, neededBytes)) {
    return CopyResult.OutOfSpace(neededMb(neededBytes))
}
```

with the decision extracted as a pure internal function so it is JVM-testable (the ContentResolver/StatFs plumbing itself stays inspection-verified, the fallback the spec allows):

```kotlin
/** True when the target storage can hold the source plus margin (10% + 32MB). */
internal fun hasFreeSpace(availableBytes: Long, neededBytes: Long): Boolean =
    availableBytes >= neededBytes + neededBytes / 10L + 32L * 1024 * 1024L

internal fun neededMb(neededBytes: Long): Int =
    ((neededBytes + neededBytes / 10L + 32L * 1024 * 1024L) / (1024L * 1024L)).toInt()
```

Add the variant to `CopyResult`:

```kotlin
data class OutOfSpace(val neededMb: Int) : CopyResult()
```

- [ ] **Step 2: Handle it in ShareReceiverActivity**

At the `when (result)` following the line-221 call, add a branch surfacing `getString(R.string.error_storage_full, result.neededMb)` (the Activity's own `getString`, the idiom the existing branches use) exactly the way `UnsupportedFormat` surfaces today: `showErrorToast` + `cleanup()` + `finish()`.

Add the string (base + 10 locales):

```xml
<string name="error_storage_full">Not enough free space to import this file (needs about %1$d MB).</string>
```

- [ ] **Step 3: Test the pure decision (the spec's storage verification)**

The device cannot be practically filled during the gate, so the spec allows "code inspection plus a unit test": the margin math gets a JVM test, the two plumbing lines (AssetFileDescriptor size read, StatFs query) are inspection-verified in the device-gate review.

```kotlin
package com.antivocale.app.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SharedAudioHandlerStorageTest {
    @Test fun `admits when free space covers source plus margin`() {
        assertTrue(SharedAudioHandler.hasFreeSpace(
            availableBytes = 500L * 1024 * 1024, neededBytes = 100L * 1024 * 1024))
    }
    @Test fun `rejects when free space misses the margin`() {
        // exactly source size, no margin: must refuse
        assertFalse(SharedAudioHandler.hasFreeSpace(
            availableBytes = 100L * 1024 * 1024, neededBytes = 100L * 1024 * 1024))
        // source + 10% but not the flat 32MB floor
        assertFalse(SharedAudioHandler.hasFreeSpace(
            availableBytes = 115L * 1024 * 1024, neededBytes = 100L * 1024 * 1024))
    }
    @Test fun `neededMb reports source plus both margins`() {
        assertEquals(100 + 10 + 32, SharedAudioHandler.neededMb(100L * 1024 * 1024))
    }
}
```

Run: `./gradlew :app:testPlayStoreDebugUnitTest --tests "com.antivocale.app.util.SharedAudioHandlerStorageTest"`
Expected: PASS, 3 tests.

- [ ] **Step 4: Compile, full suite, commit**

Run: `./gradlew :app:testPlayStoreDebugUnitTest`
Expected: PASS.

```bash
git add app/src/main/java/com/antivocale/app/util/SharedAudioHandler.kt \
        app/src/main/java/com/antivocale/app/receiver/ShareReceiverActivity.kt \
        app/src/test/java/com/antivocale/app/util/SharedAudioHandlerStorageTest.kt \
        app/src/main/res/
git commit -m "feat(receiver): free-space pre-check before importing shared audio (TASK-432)

Gates: full unit suite + SharedAudioHandlerStorageTest green.

Assisted-by: Claude <noreply@anthropic.com>"
```

### Task 7: The warning dialog at the in-app dispatch site

**Files:**
- Create: `app/src/main/java/com/antivocale/app/ui/dialogs/LongAudioWarningDialog.kt`
- Modify: `app/src/main/java/com/antivocale/app/audio/AudioDurationPolicy.kt` (add `decodePathFor`)
- Modify: `app/src/main/java/com/antivocale/app/transcription/TranscriptionOrchestrator.kt` (line 681 refactor)
- Modify: `app/src/main/java/com/antivocale/app/ui/viewmodel/LogsViewModel.kt` (`reTranscribeWithBackend` ~line 327; constructor gains `audioPreprocessor` + `transcriptionCalibrator`)
- Modify: `app/src/main/java/com/antivocale/app/ui/tabs/LogsTab.kt` (render the dialog alongside the existing `retranscribeTarget` state, ~line 304 context)
- Modify: `app/src/main/res/values/strings.xml` + 10 locales

- [ ] **Step 1: Strings**

```xml
<string name="dialog_long_audio_title">Long audio</string>
<string name="dialog_long_audio_message">This audio lasts %1$d minutes. With %2$s it will take about %3$d minutes. You will get a notification when it is done.</string>
<string name="dialog_long_audio_roughly">Rough estimate: this device is still calibrating for the selected model.</string>
<string name="dialog_long_audio_continue">Continue</string>
<string name="dialog_long_audio_cancel">Cancel</string>
```

- [ ] **Step 2: The path-selection rule becomes policy (single source)**

Add to `AudioDurationPolicy` and refactor the orchestrator's line-681 expression to call it, so the gate can never drift from the enforcement path selection:

```kotlin
/** Mirrors TranscriptionOrchestrator's usePipeline rule: streaming needs VAD
 *  off AND a chunking backend; everything else decodes whole-file PCM. */
fun decodePathFor(vadEnabled: Boolean, maxChunkDurationSeconds: Int?): DecodePath =
    if (!vadEnabled && maxChunkDurationSeconds != null) DecodePath.STREAMING
    else DecodePath.WHOLE_FILE_PCM
```

Add a policy test for it (VAD off + chunk cap = STREAMING; VAD on = WHOLE_FILE; null cap = WHOLE_FILE).

- [ ] **Step 3: The Compose dialog**

```kotlin
package com.antivocale.app.ui.dialogs

@Composable
fun LongAudioWarningDialog(
    durationMinutes: Int,
    estimateMinutes: Long,
    isRough: Boolean,
    modelDisplayName: String,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancel,
        title = { Text(stringResource(R.string.dialog_long_audio_title)) },
        text = {
            Column {
                Text(stringResource(
                    R.string.dialog_long_audio_message,
                    durationMinutes, modelDisplayName, estimateMinutes))
                if (isRough) {
                    Text(stringResource(R.string.dialog_long_audio_roughly),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) {
            Text(stringResource(R.string.dialog_long_audio_continue)) } },
        dismissButton = { TextButton(onClick = onCancel) {
            Text(stringResource(R.string.dialog_long_audio_cancel)) } },
    )
}
```

- [ ] **Step 4: The pre-start gate at the single interactive dispatch site**

The gate runs BEFORE `ContextCompat.startForegroundService` and is purely advisory: enforcement already lives in the preprocessor pre-read. `reTranscribeWithBackend` is the ONLY gated site (see the dialog-surface census in the File Structure section); the share, Tasker, notification-retry, benchmark, and subtitle dispatches stay untouched.

Constructor first: `LogsViewModel` injects only `transcriptionBackendManager`, `logDao`, `preferencesManager`, and `backendRegistry` today. ADD `audioPreprocessor: AudioPreprocessor` (`@Inject constructor()`, no module needed) and `transcriptionCalibrator: TranscriptionCalibrator` (Hilt-provided at `AppModule.kt:55`).

The gate, wrapping the existing dispatch in `viewModelScope.launch` (`getEstimate` is suspend; `getAudioDuration` does metadata IO, so run it on `Dispatchers.IO`):

```kotlin
// dialogCapable is true here by construction: this is the interactive in-app
// flow. Every headless dispatch site never calls the gate.
val duration = withContext(Dispatchers.IO) { audioPreprocessor.getAudioDuration(filePath) }
val vadEnabled = // read the same preference the orchestrator reads for this job
val ceiling = AudioDurationPolicy.ceilingSeconds(
    AudioDurationPolicy.decodePathFor(vadEnabled, maxChunkDurationFor(backendId)),
    MemoryReadings.availableRamBytes(context), MemoryReadings.maxHeapBytes())
val profile = transcriptionCalibrator.getEstimate(backendId, modelPath)
val estimate = AudioDurationPolicy.resolveEstimateMsPerSec(
    profile?.msPerSecondOfAudio, profile?.sampleCount ?: 0, descriptor.rtfEstimate)
val decision = AudioDurationPolicy.warnDecision(
    duration.toLong(), ceiling, estimate, dialogCapable = true,
    calibrated = profile?.hasEstimate == true)
if (!decision.showDialog) dispatch()   // below threshold or over ceiling: straight to the service
else pendingLongAudioWarning.value = LongAudioWarning(durationMinutes, estimateMinutes, isRough, displayName)
// LogsTab renders LongAudioWarningDialog while pendingLongAudioWarning != null;
// Confirm -> clear state + dispatch(), Cancel -> clear state. Once per request,
// no persistence.
```

`maxChunkDurationFor(backendId)` does NOT exist today: the derivation lives in the orchestrator (lines 667-684, backend `maxChunkDurationSeconds` plus memory tightening). Do NOT duplicate the cap table: add a small query there (for example an internal `fun effectiveChunkCap(backendId, context)` on the orchestrator, or expose the pre-tightening backend cap via the registry) and call it from the gate.

For the dialog plumbing, mirror the pattern this exact surface already uses: `LogsTab` keeps Compose-local `remember` state (`retranscribeTarget`, `showClearDialog`) that renders `RetranscribeDialog`. Add an analogous `pendingLongAudioWarning` state (a `MutableStateFlow` in the view model rendered from LogsTab, so Confirm/Cancel survive recomposition).

- [ ] **Step 5: Full suite + assemble + commit**

Run: `./gradlew :app:testPlayStoreDebugUnitTest && ./gradlew assembleFdroidDebug`
Expected: both green.

```bash
git add app/src/main/java/com/antivocale/app/ app/src/main/res/
git commit -m "feat(ui): long-audio warning dialog with calibrated estimate (TASK-432)

Gates: full unit suite + assembleFdroidDebug green; device gate at task 8.

Assisted-by: Claude <noreply@anthropic.com>"
```

---

## Chunk 3: Verification and ship (Task 8)

### Task 8: Gates, device verification, docs

**Files:**
- Modify: `FAQ.md`
- Fix whatever the review gates surface; no other source changes expected.

- [ ] **Step 1: /simplify on the consolidated diff**

Invoke `/simplify` with the argument: the full TASK-432 diff (`git diff e402197..HEAD`). Apply accepted findings; file rejected trivial ones in TASK-423.

- [ ] **Step 2: /review-local at maximum rigor**

Invoke `/review-local` (5 reviewers + confidence scoring). Fix everything scoring >= 80. Do NOT push before this passes (project rule: review and simplify before device, before push).

- [ ] **Step 3: Build and install on the RMX3853**

```bash
./gradlew assemblePlayStoreDebug && ./scripts/install.sh
```

Expected: install of `com.antivocale.app.debug` (the suffix trap: debug builds install alongside and never overwrite the user's real app).

- [ ] **Step 4: Device gate (the spec's battery)**

Record first: `D=$(adb devices | sed -n 's/^\(.*_adb-tls-connect\._tcp\)[[:space:]]*device$/\1/p')` then `adb -s "$D" shell getprop dalvik.vm.heapgrowthlimit`. This calibrates the expected VAD ceiling before the runs; the refusal message prints the derived ceiling, so a mismatch self-diagnoses.

Fixtures: a 60-minute file, one just under the derived VAD ceiling, one above 2 hours, one above 2GB. Generate the WAV ones with `ffmpeg -f lavfi -i "sine=frequency=440:duration=3600" -ar 16000 -ac 1 fx60.wav` (mono s16 WAV, so MediaExtractor derives KEY_DURATION from data size). The 2GB fixture can be ANY container with a fat payload (pad a WAV with trailing junk bytes): the size bound fires before any duration logic, and a genuine 2GB of 16kHz mono would be ~18 hours of audio and slow to generate. Deliver per the device playbook (`/data/local/tmp` plus `run-as cp`, explicit receiver intents with `-n`).

1. 60-min VAD OFF (streaming, Parakeet): completes; transcript sane (no truncation, no repetition); no OOM in logcat.
2. 60-min VAD ON: REFUSED with the localized VAD message naming the derived ceiling; logcat shows the pre-read firing with no decode start (enforcement-before-decode proof).
3. Just-under-ceiling file with VAD ON: completes.
4. Above-2h file: refused with the streaming message.
5. Above-2GB file: refused with the 2GB message.
6. In-app retranscribe of the 60-min file: dialog appears once per request (Cancel aborts; retry shows it again), estimate matches the calibrator profile after 2 prior runs. Share-flow AND Tasker-broadcast dispatch of the same file: NO dialog in either, both proceed to notification (explicit `-n` receiver intent for the Tasker path).
7. A 2h streaming file survives screen-off and delivers the completion notification; record battery drain (no wakelock added).
8. Storage gate: verified by the SharedAudioHandlerStorageTest unit tests (Task 6) plus inspection of the two plumbing lines during this review; filling the physical device is out of scope per the spec's fallback.

- [ ] **Step 5: FAQ.md entry + final commit**

Add the long-audio entry (2h valve, VAD memory dependence, turn-VAD-off advice) mirroring the existing FAQ style. Commit any gate fixes and the FAQ together with the full gates marker.

- [ ] **Step 6: Push + housekeeping**

Declare gates in the transcript, then `git push origin main`. Close TASK-432 (device-verified evidence in the final summary). Prepare the issue-#73 reply and the email to Tim Veles, but send NEITHER without explicit user approval (project rule: no external comments without approval; replies are 3-4 short sentences with links).

---

## Out of scope (do not pull in)

- Streaming the VAD path itself (TASK-424).
- `largeHeap` in the manifest (deferred to TASK-424's unification).
- Any user-facing duration setting or unlimited mode.
- RTF calibration automation for the fallback constants.
