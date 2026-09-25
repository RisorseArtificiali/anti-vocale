# vocaphone feature mining (TASK-656)

Date: 2026-09-25. Input: the maintainer's link (the hopsayer fork) redirected to the live upstream VocaHQ/vocaphone. Method: /pa:research exhaustive, six parallel research agents, adversarial spot-verification of the load-bearing claims directly on their source (their AndroidManifest has zero send-filters; their SherpaModelCatalog ids match the reported catalog). Full report with sources: `claudedocs/research_vocaphone_2026-09-25.md`.

## What it is (and is not)

vocaphone is keyboard-first live dictation for iPhone and Android (AGPL-3.0, 8 weeks old, 6 human contributors, weekly Android releases, on Google Play in beta). It transcribes microphone audio into the text field you are typing in. It has no share-sheet ingress, no file import, no audio playback (successful dictations delete their audio), no transcript editing, no export formats, no summaries, no diarization, and no automation surface on Android (no receivers, no providers, no Tasker surface; their only exported components are the launcher and the IME). The same engine we ship (sherpa-onnx 1.13.8) plus whisper.cpp on Android and WhisperKit on iOS. The optional VocaGateway is a separate AGPL Python server with heavier models and llama.cpp-based transcript cleanup; the phone works fully offline without it.

Consequence for us: the message-transcription pipeline (share ingestion, History, exports, summaries, signature, Tasker surface) is uncontested by them on Android. The overlap is the model catalog and the engine discipline, which is where the adopted candidates come from.

## Adopted (maintainer decision 2026-09-25: candidates 2, 3, 4, 5)

| Candidate | Evidence in their repo | Tracked as |
|---|---|---|
| 2. Startup sweep of orphaned external-import dirs | LocalModelManager.deleteRetiredModelFiles | GH #117, TASK-657 |
| 3. Device-tier RAM budget as a second axis of the memory math | DeviceProfile (CONSTRAINED..FLAGSHIP, modelRamBudgetGB) | GH #118, linked into TASK-631 |
| 4. Bounded recovery for chunks that decode empty | SherpaEmptyChunkRecovery + incremental session | GH #119, linked into TASK-521 |
| 5. Measured accuracy from eval, in the stats surface (maintainer constraint: NEVER inline in the model list; Parakeet v3 alone covers 25 languages) | WER numbers in code comments beside catalog pins | GH #120, TASK-658 |

## Declined (same decision: 1, 6, 7)

1. Retired-model migration table. Our catalog has retired models (the GGUF removal), but the picker already resolves saved paths through SherpaModelManager and the external platform carries its own records; the table's value here is lower than in their single-catalog world. Revisit if a future catalog removal strands users.
6. Script-aware rule-based punctuation tables (15 scripts). Our punctuation pass is the Gemma path; a rule-based twin is a second punctuation owner with a different output contract. Revisit only if a no-LLM fast path becomes a goal.
7. Honesty-prose pattern for remote modes. No remote mode exists or is planned; the pattern is recorded here for the day one appears ("self-hosted is not on-device" is the wording shape).

## Worth stealing that they do not have a file for

Their catalog comments carry measured WER beside each pin, their language picker ceiling is built only from what shipped models cover (with the Cantonese large-v3-only carve-out documented), and their release notes name the device a fix was tried on. All three are habits, not features; the adopted #5 carries the first one into our world.
