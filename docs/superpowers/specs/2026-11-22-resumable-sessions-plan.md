# Resumable transcription sessions, implementation plan (TASK-1037, GH #190)

Planned by the fable pane on 2026-11-22 (read-only pass; nothing edited while planning).
Execution phases live in the backlog task; this file is the durable design of record.

## Corrections to the session's missing-list

1. "Audio-offset persistence next to the partial text" is the wrong home. savePartialTranscriptionState is one global DataStore slot, text only, throttled to 5 s. It also feeds the LogsViewModel "interrupted transcription" dialog through the 15 s staleness gate. It cannot hold several paused sessions and cannot be written atomically with an offset. The plan uses a Room chunk table instead, and session runs do not arm that seed at all.
2. The decoder cannot start mid-file, and this is the largest missing piece. AudioPreprocessor.prepareAudioStream opens a MediaExtractor and decodes from zero; there is no seekTo on that path.
3. Global-offset stitching is nearly free on the pipelined path. processPipelinedAudio derives cue times from the running decodedSeconds. Seeding that counter with the resume offset makes cues global by construction. The speaker-label collector is the opposite case: it needs the whole sample timeline in RAM, so a resumed run cannot label speakers.
4. A remaining-time estimate is entirely absent on this path. startGlobalProgressTimer and formatEta are called only from processParallelChunks. formatEta also returns hardcoded English, so the new surface needs its own localized strings.
5. "Self-checkpoint and re-enqueue between stints" should not be built as stated. With a checkpoint per chunk, a kill loses at most one chunk. Stopping the service and re-enqueueing from the background would walk into the API 31+ start restriction that InferenceEnqueue's fallback exists for. A running session would become a "tap to continue" notification. The plan keeps one continuous service lifetime; a stint boundary survives only as an optional queue-fairness yield in phase 7.
6. Three further gaps: SharedAudioHandler.cleanupOldFiles deletes filesDir/shared_audio files after 24 h, which would destroy a paused session's source; recordCalibration divides total audio by one stint's wall time, so a resumed run would poison the per-model calibration; logQueued always inserts a row, so a resume under the same task id would duplicate it.
7. Confirmed: chunks are independent on every path. SherpaBackend creates a fresh stream per transcribeAudio call, including the Nemotron online recognizer. InferenceService is specialUse, so the Android 15 dataSync six-hour budget does not apply to it.

## Design decisions

**Scope.** Sessions exist only on the pipelined path (VAD off or the TASK-450 fallback, a chunking backend, no forced VAD-aligned chunking). They engage when the audio is longer than a new AudioDurationPolicy.SESSION_MIN_SECONDS, recommended equal to WARN_THRESHOLD_SECONDS (30 min). The loop stays one loop with a nullable checkpoint sink, so there is no second code path. Tasker and Open Transcribe origins do not get sessions in this work and keep the 2 h valve.

**Data model.** Two new Room tables and no new LogEntity column.
- transcription_sessions, keyed by taskId: identity (file path, file size and mtime fingerprint, frozen backendId, model path, language pin, prompt, source, source package, sender); progress (state, total samples if known, accumulated compute ms); bookkeeping (failed, blank and retried counters, a crash-at-offset counter, created and updated timestamps).
- session_chunks, keyed by (taskId, startSample): endSample, text, cues JSON, confidence, detected language.
- The checkpoint is one chunk-row insert. The resume offset is the end of the contiguous prefix of chunk rows, so text and offset cannot drift apart.
- Offsets are in 16 kHz output samples, not chunk indexes. TranscriptionMemoryPolicy may tighten the chunk size differently on each resume, and that law stays respected.
- The logs row gains a new status value, PAUSED. The alternative (ERROR plus a failureContext class, the suspended-run precedent) would force a "not really an error" carve-out at every error surface.
- LogEntity.kt:97 does Status.valueOf(status) and would crash on the new value, so the enum and every when on status move together.

**Backup codec.** Session tables are deliberately excluded from the backup: they describe in-flight work bound to a local file. BackupHistoryCodec must export a PAUSED row as a partial ERROR row with its text, so an archive never carries a status the importing build cannot parse. No new column means the four-places rule is not triggered; the status mapping gets its own round-trip test.

**Resume request shape.** A new factory AudioRequestIntent.resumeSession(context, taskId) carries only the task id and a resume flag. The service reads everything else from the session row, so the intent cannot disagree with persisted state, and it rides the trampoline fallback unchanged. It goes through InferenceEnqueue.start. The existing inFlightTaskIds guard makes a double tap idempotent.

**State machine.**

| From | Event | To | Row status |
|---|---|---|---|
| none | long audio accepted on the pipelined path | ACTIVE | PROCESSING |
| ACTIVE | user Pause (flag honoured at the next chunk boundary) | PAUSED_USER | PAUSED |
| ACTIVE | process death, found at cold start | PAUSED_SYSTEM (carries the classifier verdict) | PAUSED |
| PAUSED_* | Resume | ACTIVE | QUEUED then PROCESSING |
| ACTIVE | last chunk checkpointed | COMPLETING (post-passes) | PROCESSING |
| COMPLETING | process death | PAUSED_SYSTEM; resume skips decode and reruns post-passes | PAUSED |
| COMPLETING | logSuccess | session rows deleted | SUCCESS |
| any | Cancel or Discard | session rows deleted, assembled text kept as partial | ERROR, isPartial |
| PAUSED_* | file missing or changed, model gone, or expiry | closed as partial with an honest reason | ERROR, isPartial |

Rules that hold across the table:
- Pause is cooperative. A native decode cannot be interrupted, so the UI shows "Pausing..." until the in-flight chunk lands.
- A held empty chunk (the TASK-664 ladder) is never checkpointed. The resume offset is the start of the earliest unresolved chunk, and that chunk is decoded again.
- The early preview and the chunk-0 logic key on the global offset being zero.
- A paused stint leaves processPipelinedAudio through a SessionPaused sentinel that processRequestInner handles before any error surface. Because the stint never returns success, every post-pass runs exactly once, at the true end. That covers punctuation, summary and the ChunkPromptPolicy final pass.
- A resumed run prefills text, cues and counters from the chunk rows, so the function still returns the full TranscriptionResult.

**Remaining time.** A pure function in a new transcription/SessionProgress.kt: remaining audio seconds times measured milliseconds per audio second. The measurement is the session's own compute time over its decoded audio. Until two chunks are measured it is seeded from AudioDurationPolicy.resolveEstimateMsPerSec, the warn dialog's source. Output is coarse by design: minutes below one hour, five-minute steps above. It is persisted with the session so a paused row can show it.

**UX shape.**
- Running notification: title "Transcribing, long recording"; text "1:12:00 of 3:40:00, about 2 h 50 min left"; a determinate progress bar; actions Pause and Cancel. The chunk prev/next actions are dropped for session runs, because that notification already uses its three action slots.
- Paused notification: fixed id 2505, the next free id under the reserved-range contract. It reads "Paused at 1:12:00 of 3:40:00", with a Resume action when exactly one session is paused, otherwise a count that opens History. A system pause names the cause from the classifier verdict and keeps the battery-exemption link of the suspension notification.
- History row, running: progress, the same remaining-time line, a Pause button.
- History row, paused: a distinct paused icon and label, the position and remaining estimate, Resume and Discard. The text so far renders through CappedTranscriptText unchanged.
- Warn dialog: above the session threshold it gains one sentence saying the run can be paused and resumes after an interruption.
- Settings: none in this work, so no subpage is needed. If a second entry ever appears, it gets a hub row and subpage per the rule.

## Phases

**Phase 0: spikes, no production code.** Measure MediaExtractor.seekTo accuracy and decode-and-discard speed on the RMX3853 for m4a, mp3, Ogg Opus, wav and flac at a three-hour offset. Measure the logs row size limit: write a row sized like a 6 h and a 12 h transcript, read it through getAll and through the backup codec. Read the two-pass refinement path and decide whether session runs exclude it. Decide whether the phase-7 yield re-queues inside the drain loop or through InferenceEnqueue. Ends in: numbers recorded in the task, and a chosen SESSION_MAX_SECONDS. Does not: change any behaviour.

**Phase 1: start-offset decode.** Files: audio/AudioPreprocessor.kt (a startOffsetSamples parameter on prepareAudioStream; the header reports the total and the offset), plus a pure helper for the discard arithmetic. Baseline is decode-and-discard from zero, exact by construction. Seek is added only if phase 0 shows it sample-accurate for a format. Ends in: a JVM test of the arithmetic, and a device proof that decoding from offset N yields the same samples as a full decode sliced at N for each format. Does not: touch the orchestrator.

**Phase 2: session store.** Files: new data/local/TranscriptionSessionEntity.kt, SessionChunkEntity.kt, SessionDao.kt; AppDatabase.kt (version 17, MIGRATION_16_17); the Hilt module; LogEntry.Status.PAUSED with every status dispatch site (HistoryTab.kt, LogsViewModel.kt, the DAO status lists); BackupHistoryCodec.kt export mapping. failAllNonTerminal already lists only QUEUED, PROCESSING and PENDING, so PAUSED rows survive the cold-start sweep without a change; pin that with a test. Ends in: a migration test, DAO tests for the contiguous-prefix offset, and the codec round-trip test. Does not: create any session at runtime.

**Phase 3: checkpoint and kill recovery (the floor).** Files: TranscriptionOrchestrator.kt (the sink in processPipelinedAudio, the prefill, the SessionPaused handling, a logQueued skip for resume, per-stint recordCalibration, compute-time accumulation, no seed arming for session runs); SuspendedRunRecovery.kt (a row with a session becomes PAUSED_SYSTEM before the generic sweep); AudioRequestIntent.kt, InferenceService.kt, NotificationActionReceiver.kt (the resume arm); SharedAudioHandler.kt (the cleanup skips files owned by a live session); ResultNotificationFactory.kt (the paused notification and its id-table entry); TestSpiOps.kt and docs/testing-spi.md (session state and resume ops). Resume validates the file fingerprint and the frozen model; a mismatch closes the session as partial with a stated reason. A crash-loop guard counts deaths at the same offset; on the second native-crash verdict the chunk is recorded as failed and skipped. Ends in: an orchestrator test with a fake backend where resumed output equals uninterrupted output, text and cues; ReservedNotificationIdContractTest; a device proof (am kill mid-run on a 40-minute file, resume, compare with an uninterrupted run). Does not: add Pause, an estimate, or raise the valve.

**Phase 4: user Pause.** Files: InferenceService.kt (ACTION_PAUSE_TASK added to isControlDelivery, promotion first, retireIfIdle afterwards, conditional stopSelf(lastStartId)); the notification builders; HistoryTab.kt and LogsViewModel.kt; strings in all locales. Ends in: order tests in the InferenceServicePromotionGateTest style for pause on a live run, on a dead process and on an unknown task; the strings battery; a device proof of pause then resume. Does not: pause non-session runs.

**Phase 5: remaining time.** Files: new transcription/SessionProgress.kt; the orchestrator emission; TranscriptionListener.kt if the existing progress callback cannot carry it; the notification and row rendering; plural strings. Ends in: JVM tests for the derivation and the rounding, and a device check that the estimate converges on a one-hour file. Does not: replace formatEta on the parallel path.

**Phase 6: lift the valve.** Files: AudioDurationPolicy.kt (a session-capable streaming ceiling equal to the measured SESSION_MAX_SECONDS; shouldFallBackToStreaming and warnDecision consume it); the three ceilingSeconds call sites; the 2 h error string; the warn dialog copy; README, FAQ.md and the #73 wording. Add a free-space pre-check and an expiry for old paused sessions. Ends in: policy tests, and a device run of a file longer than three hours with at least one forced kill and one user pause. Does not: change the whole-file ceiling, the 2 GB file bound, or the Models tab labels (TASK-1036).

**Phase 7, optional: queue fairness.** A session yields at a chunk boundary when other requests are waiting, then re-enters the queue. Without it, a four-hour session blocks automatic voice notes for its whole length. Ends in a drain-order test.

**Out of scope for the whole plan:** paged rendering of long transcripts (TASK-1034), sessions for VAD, whole-file, Tasker and Open Transcribe requests, speaker labels on resumed runs, and resuming under a different model.

## Risks and the gotcha each reopens

| Risk | Gotcha it reopens | Mitigation |
|---|---|---|
| A multi-hour result approaches the 2 MB cursor window | The TASK-506 data-dependent bricking | Phase 0 measures it; SESSION_MAX_SECONDS is chosen from the number |
| SessionPaused reaches an error consumer | False error notification, Tasker error reply, silent-model demotion | Handled at one site ahead of all failure arms; pinned by test |
| The DataStore seed stays armed for a session | The TASK-602 false "interrupted" dialog | Session runs never arm it; test |
| Per-stint timing recorded against whole-file audio | Calibration poisoning, wrong warn-dialog estimates | Calibration is recorded per stint |
| A chunk that crashes the engine every time | The NNAPI crash loop of issue #26 | The crash-at-offset counter |
| Model or language changes between stints | Mixed-model transcripts; the TASK-545 misattribution lesson | Frozen identity in the session; resume refuses on mismatch |
| The source file is cleaned or replaced | The dead Retry action of TASK-684 | Cleanup exemption plus the fingerprint check |
| Inexact seek on VBR or Ogg | Duplicated or dropped words at the join; the GH #91 duration lesson | Decode-and-discard baseline |
| The Pause delivery revives a dead process | The stranded foreground notification of TASK-978 | Control-delivery classification plus retireIfIdle; order tests |
| The new status reaches an old parser | Status.valueOf crash on restore | Export mapping in the codec |
| A new notification id collides | Silent replacement under the reserved-range contract | Id 2505 added to the table and the contract test |
| Speaker labels expected on long audio | TASK-597 retention | Labels skipped on resumed runs, with the reason in ProcessingContext |

One fact in this plan is not verified by reading code: whether a startForegroundService call from an app that already holds a foreground service is exempt from the background-start restriction. The plan avoids depending on it.

END-OF-PLAN
