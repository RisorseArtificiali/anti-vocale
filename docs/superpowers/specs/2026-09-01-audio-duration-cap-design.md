# Audio Duration Cap Redesign

Date: 2026-09-01
Status: approved design (sections 1-4), pending implementation plan
Origin: user report via email (Tim Veles, 2026-09-01 17:38), Anti-Vocale 1.11.1,
Pixel 9 Pro XL, Parakeet TDT: a 20+ minute ACR Phone recording is rejected with
"Audio exceeds 10 minute limit" while the model catalog promises
"Any length: longer audio is split automatically".

## Problem

`AudioPreprocessor` enforces `MAX_DURATION_SECONDS = 600` (line 45) on BOTH
decode paths, and a `MAX_FILE_SIZE_BYTES` 100MB guard (line 44) on both paths
before that, with the duration check on the whole-file path sitting AFTER the
full decode (line 127, downstream of `extractToMonoFloat` at line 122; the
streaming check is at line 267). The duration constant arrived with the initial
transcription commit (a32d4c6) as a memory safety valve for the whole-PCM-in-RAM
decode. The 1.11.1 streaming decode (TASK-416) removed that reason for the
streaming path, but the caps stayed: the catalog label (`model_limit_chunked`,
"Any length: longer audio is split automatically") now overpromises and users
with legitimate 20-60 minute recordings are blocked.

Path facts (verified 2026-09-01):

- STREAMING path (`prepareAudioStream`, used when VAD is OFF and the backend
  chunks; VAD defaults to off, so this is the Parakeet default): memory-bounded
  per chunk (in-session measurement on the RMX3853: 496s file, 46MB Dalvik
  peak). Emits chunks through an in-memory Channel; nothing is written to disk
  (the `cacheDir` parameter is unused on this path). A long-duration cap here
  is a practical valve (battery, expectations), not a memory necessity.
- WHOLE_FILE_PCM path (`prepareAudioForMediaPipe`, used when VAD is ON or the
  backend does not chunk): decodes the entire PCM into a FloatArray, 64KB per
  second of audio (16kHz mono float), with a 2x peak during the merge
  (AudioPreprocessor lines 513-516) and a further full copy through
  VadProcessor. 10 min = 38MB final, 30 min = 115MB, 1h = 230MB, peaks
  2-3x that. A cap here is load-bearing; a flat 600s is not tied to anything
  real and is arbitrary on high-RAM and cruel on low-RAM devices.
- Selection: `TranscriptionOrchestrator` line 681,
  `usePipeline = !vadEnabled && maxChunkDuration != null`.

## Decisions (settled with the maintainer, 2026-09-01)

1. Semantics: silent fix plus a per-request warning dialog above 30 minutes
   (no persistence, once per transcription request). No permanent user
   setting, no "unlimited" option (an unlimited toggle would reopen the
   1.10.0 OOM class on the VAD path and add UI almost nobody uses).
2. The cap depends on the DECODE PATH, never on the model. The model
   contributes only the time estimate shown in the dialog (a slow model is
   honestly warned about, never forbidden).
3. VAD path ceiling: derived from the binding memory constraint, not flat.
   The whole-file path's real peak is three copies of the PCM (the merge
   documents a 2x-of-final peak, final included, plus one more full copy
   through VadProcessor), and the binding constraint is the java heap
   (Runtime.maxMemory()), not system RAM. Budget =
   min(availMem x 0.25, maxHeap x 0.5) / (3 x 64KB/s), clamped to
   [600s, 7200s], fail-open to 600s when a reading is unavailable.
4. Streaming path ceiling: 7200s (2 hours), a documented practical valve.

## Design

### 1. AudioDurationPolicy (single source of truth)

New `app/src/main/java/com/antivocale/app/audio/AudioDurationPolicy.kt`,
pure Kotlin object, no Android imports:

```kotlin
object AudioDurationPolicy {
    const val STREAMING_MAX_SECONDS = 7200       // practical valve (2h)
    const val VAD_MIN_SECONDS = 600              // fail-open floor
    const val VAD_MAX_SECONDS = 7200             // clamp ceiling (2h)
    const val PCM_BYTES_PER_SECOND = 64 * 1024L  // 16kHz mono FloatArray
    const val PCM_PEAK_COPIES = 3L               // final + 2x merge peak + VAD copy
    const val RAM_BUDGET_FRACTION = 0.25         // share of available RAM
    const val HEAP_BUDGET_FRACTION = 0.5         // share of the max dalvik heap

    enum class DecodePath { STREAMING, WHOLE_FILE_PCM }

    fun ceilingSeconds(path: DecodePath, availableRamBytes: Long?,
                       maxHeapBytes: Long?): Long

    fun warnThresholdSeconds(): Long = 1800      // dialog above 30 min

    // cold-start RTF resolution: device calibration if it exists, else family
    fun resolveEstimateMsPerSec(calibratedMsPerSec: Float?, sampleCount: Int,
                                fallbackRtf: Float): Float

    // pure decision for the UI layer: should a dialog be shown, what estimate
    // (NO dialog when duration > ceilingSeconds: the pre-read refusal carries
    // the actionable message, and a dialog there would promise a transcription
    // that is then refused)
    fun warnDecision(durationSeconds: Long, ceilingSeconds: Long,
                     estimateMsPerSec: Float, dialogCapable: Boolean): WarnDecision
}
```

`ceilingSeconds` for WHOLE_FILE_PCM budgets the BINDING constraint, which is
the java heap, not system RAM (no `largeHeap` in the manifest; a FloatArray
lives in the dalvik heap, whose per-device limit `Runtime.maxMemory()` caps
at roughly 192-512MB regardless of installed RAM):

```
budget = min(availableRamBytes * 0.25, maxHeapBytes * 0.5)
seconds = clamp(budget / (3 * PCM_BYTES_PER_SECOND), 600, 7200)
```

With 3 copies budgeted: a 512MB heap derives ~23 minutes, a 256MB heap
~11 minutes, and plentiful system RAM cannot lift the ceiling past what the
heap can hold (which is honest: the VAD path's real fix is turning VAD off,
exactly what the error message says). Either reading null/unreadable
resolves to 600 (fail-open). Both values enter as parameters, so the policy
is unit-testable without Robolectric. No device gets worse than today:
heaps of 256MB and up get 11-23 minutes instead of the flat 10.

Readings: system RAM via `TranscriptionOrchestrator.availableMemoryBytes`
(private, line 457, ActivityManager.MemoryInfo.availMem; extracted into a
shared helper or made internal: one owner); max heap via `Runtime.maxMemory()`
(a pure JVM call, no Context needed). The orchestrator passes both into the
policy call and the preprocessor validation.

ENFORCEMENT (the review's blocking point): a post-decode check cannot enforce
a RAM-derived ceiling, because the whole-file path would already hold the
full PCM when it fires. Enforcement therefore moves to a metadata pre-read at
the START of both prepare functions, BEFORE any decode: the preprocessor
gains `validateDuration(inputPath, path, availableRamBytes)` which reads the
container duration via the existing `getAudioDuration` (AudioPreprocessor
line 747, MediaExtractor KEY_DURATION, no decode) and throws the per-path
error above the policy ceiling. The whole-file check (line 127, post-decode)
and the streaming check (line 267, mid-thread metadata) are both REMOVED:
the unified pre-read at function start supersedes them, and keeping them
would only re-implement the policy worse.
The orchestrator's metadata read for the warning dialog uses the same
`getAudioDuration`; the dialog is advisory, the pre-read is the enforcement
point.

FILE-SIZE GUARD (the review's second blocking point): `MAX_FILE_SIZE_BYTES`
100MB fires before any duration logic and makes the 7200s streaming valve
unreachable for exactly the files it exists for (a 2h AAC at 128kbps is
~115MB; WAV containers far more). The flat size guard's original purpose was
a decode-cost proxy, which the path-aware duration policy now owns. It is
REPLACED by a generous absolute sanity bound of 2GB (protects MediaExtractor
patience and filesystem reality), message i18n'd with the others.

### 2. Per-model estimate: calibrator first, descriptor fallback

The app ALREADY measures per-model speed on the actual device:
`TranscriptionCalibrator` (`data/TranscriptionCalibrator.kt`) records
ms-per-second-of-audio per `backendId__dirName` after every transcription
(rolling average from totals; confidence NONE below 2 samples, LOW at 2,
HIGH at 3+), the orchestrator already reads it for chunk progress
(TranscriptionOrchestrator line 929) and records after each run (lines 1252,
1326), and PerformanceStatsDialog displays it as RTF.

The dialog estimate therefore uses, in order:

1. `TranscriptionCalibrator.getEstimate(backendId, modelPath)` when
   `hasEstimate` (2+ samples on THIS device): device-measured, correct by
   construction, and self-calibrating on low-spec SoCs.
2. Otherwise the cold-start fallback: `BackendDescriptor.rtfEstimate: Float`
   (audio-seconds per compute-second), default `1.0f` (conservative:
   overestimates time). Initial family values: Parakeet TDT `15f`, other
   sherpa offline families `4f`, Gemma `1f`; external models inherit by
   family. As samples accumulate the fallback stops being consulted.

`resolveEstimateMsPerSec` in the policy is the pure tiering function.
Dialog wording hedges by confidence: "circa N min" always, rounded UP; with
LOW confidence (exactly 2 samples) the wording adds "roughly". The
descriptor constants stay a TODO-tracked manual first pass; the calibrator
makes their accuracy non-critical.

### 3. Dialog, error messages, i18n

Flow: before starting transcription, `TranscriptionOrchestrator` reads the
duration via `getAudioDuration` (metadata only). If duration is above
`warnThresholdSeconds()`, `AudioDurationPolicy.warnDecision` decides; where a
dialog-capable surface exists the UI shows an AlertDialog: duration, model
name, estimated time ("this audio lasts 45 min; with Parakeet about 3 min"),
buttons Cancel / Continue. The estimate comes from the calibrator-first
resolution of section 2, rounded up, hedged ("circa"). The dialog text does
NOT promise screen-on behavior (InferenceService is a foreground service
without a wakelock; transcription continues with the screen off and
completion arrives by notification): the wording is "you will get a
notification when it is done". Once per transcription request, no persistent
flag.

Which flows show the dialog is keyed on listener capability, not on an
enumeration (all four orchestrator entry points, verified 2026-09-01):

- MainActivity in-app transcription: dialog-capable, DIALOG shown.
- ShareReceiverActivity (the reporter's ACR / Files path): dispatches through
  InferenceService with no dialog surface (toast only): NO dialog, proceed;
  the completion notification carries the result.
- TaskerRequestReceiver (ACTION_PROCESS_REQUEST): headless, NO dialog,
  proceed.
- SubtitleChoiceTimeoutWorker: headless, NO dialog, proceed.

The decision function takes `dialogCapable` as a parameter so adding an entry
point later is a one-line call-site choice, not a policy change.

Error messages move from the hardcoded English strings in the
`PreprocessingError` sealed class to string resources, with per-path
actionable text:

- Streaming valve: "Audio longer than 2 hours is not supported."
- WHOLE_FILE_PCM: "With VAD enabled the maximum length depends on your
  device's memory (here: N minutes). Turn VAD off, or use a chunked model,
  for long recordings."
- File sanity bound: "Audio files larger than 2GB are not supported."
- Storage: "Not enough free space to import this file (needs about N MB)."

The storage check is a PRE-COPY gate on the import path: shared files are
copied into app storage by `SharedAudioHandler.copyToAppStorage`
(ShareReceiverActivity line 221) before preprocessing; with the 2GB sanity
bound, a near-full 32GB device would otherwise hit ENOSPC mid-copy. The
check compares source size plus margin against free bytes on the target
storage and fails with the message above before writing anything.

New strings land in `values/strings.xml` plus the 10 maintained locales
(de, es, fr, hi, it, pl, pt-rBR, ru, tr, uk; all already carry
`model_limit_chunked`).

The catalog label `model_limit_chunked` stays as is: "Any length" becomes true
within the documented 2-hour valve.

### 4. Testing and verification

Unit tests (JVM, no device):

- `AudioDurationPolicy`: heap-binding derivation (512MB heap -> ~23 min,
  256MB heap -> ~11 min), RAM-binding cases where RAM is the smaller budget
  (a low-RAM device with a proportionally large heap), clamp bounds both
  sides, fail-open on either null reading, streaming constant, warn
  threshold, and `warnDecision` truth table (below threshold, above
  threshold dialog-capable, above threshold headless, above ceiling).
- Estimate resolution: `resolveEstimateMsPerSec` tiering (calibrated value
  with 2+ samples wins; below 2 samples falls back to the family constant;
  calibrated value also wins when SLOWER than the fallback, since optimism
  is the failure mode), and estimate math rounding up to the minute.
- Per-path error message selection: each `PreprocessingError` case (and the
  storage pre-check) maps to the right resource id (also guards the i18n
  move).
- Existing `AudioPreprocessor` tests updated: duration validation is now a
  pre-read (line-127/267 checks removed), file-size bound at 2GB.

Device gate (RMX3853, wireless, mDNS serial as per project rules):

- Synthetic 60-minute file with VAD OFF (streaming path): completes, RAM
  peak logging, transcript sanity (no truncation, no repetition).
- The same file with VAD ON: EXPECTED TO BE REFUSED with the actionable
  heap-derived message (a 60-min VAD admission would need a ~1.4GB heap;
  this device reports far less via Runtime.maxMemory()), and a file JUST
  UNDER the derived ceiling with VAD ON completes. Record
  `adb shell getprop dalvik.vm.heapgrowthlimit` first so the expected
  ceiling is known before the run (the refusal message prints it anyway:
  a mismatch self-diagnoses). The refusal IS the honest-behavior test, not
  a failure.
- A file above 7200s: refused by the metadata pre-read BEFORE any decode,
  correct per-path message.
- A file above 2GB: refused with the sanity-bound message.
- Storage: an import on a simulated-full cache (or verified by code
  inspection plus a unit test if filling the device is impractical) fails
  with the storage message BEFORE copying.
- Dialog: appears once per request above 30 min in the MainActivity flow,
  never below, Cancel aborts cleanly; the estimate shown matches the
  calibrator profile for the active model after 2+ prior runs; share and
  Tasker flows proceed without a dialog and complete.
- Valve behavior at the top: a 2h file on streaming starts and survives
  screen-off (foreground service, notification on completion); battery drain
  recorded and reported, no wakelock added.

FAQ.md gains the long-audio entry; the reporter (Tim) gets the reply agreed
in-session, referencing the fix.

## Out of scope

- Streaming the VAD path itself (TASK-424 stream/disk research stays the
  deeper unification; this design makes the two-path ceiling honest instead).
- Any user-facing duration setting or unlimited mode (rejected above).
- RTF calibration automation for the FALLBACK constants (the calibrator
  makes them non-critical; they stay a manual TODO on the descriptors).
- Adding `largeHeap` to the manifest (a bigger heap would raise the VAD
  ceiling but tax every low-RAM device app-wide; the honest answer is
  "turn VAD off", and revisiting this belongs to the TASK-424 unification).
- Wakelock or keep-screen-on for long sessions (the foreground service
  already outlives screen-off; adding one is a separate decision with
  battery evidence).

## Open items for the implementation plan

- The exact shape of the shared memory-reading helper (extract
  `availableMemoryBytes` from TranscriptionOrchestrator vs make it internal;
  `Runtime.maxMemory()` needs no helper): either is fine, one owner is the
  requirement.
- How the dialog's Continue suspends the orchestrator start in the
  MainActivity flow (pre-start gate vs listener suspension): a UI-layer
  choice during implementation; the decision function is already pure.
