# OmniVoice-Studio feature mining (TASK-589)

Date: 2026-09-22
Source: github.com/paoloantinori/OmniVoice-Studio (fork of debpalash/OmniVoice-Studio)
@ e4c1ef0, the project we run on the LAN (mac.lan:3900 backs ccgram voice
transcription). Note: the TASK-589 reference URL (pantinor/omnivoice) was stale;
the repo lives at the fork above (task updated).
Method: exhaustive dimension-by-dimension mining by a research subagent over the
clone at /tmp/research-mining/omnivoice; citations spot-checked locally. The
"translation verdict" for our app is the synthesis of the subagent's findings with
our load-path recon (TASK-303/304 session notes), which the miner could not see.

OmniVoice-Studio is a self-hostable Tauri studio: Python FastAPI backend, React
frontend, 14 TTS and 11 ASR engines. Its transcription surface (dictation, dubbing
prep, subtitles) is the part that maps to Anti-Vocale.

## Ingestion and batch

- URL ingest via yt-dlp WITH caption harvesting: existing platform subtitles seed
  the transcript, skipping ASR entirely (`backend/schemas/requests.py:191-205`,
  `backend/services/dub_pipeline.py:960-1043`). The yt-dlp download half is
  NOT-APPLICABLE (server-side, network-first), but subtitle-seeding as a transcript
  source is a NEW-CANDIDATE (a .srt/.vtt handed to the app becomes the transcript;
  pairs with GH #92).
- SRT/VTT import with a lenient parser (`backend/services/srt_parser.py:7-18`).
  ADAPTABLE, ties GH #92 (import alongside export).
- Batch queue with per-job stage progress `{stage, percent}` and serial consumption
  (`backend/api/routers/batch.py:37-48`, `:101-103`): the progress payload shape and
  serial lane map onto our queue/WorkManager model (ties GH #72).
- Incremental re-render by content hash: only changed segments re-run
  (`backend/services/incremental.py:4-13`). ADAPTABLE: re-transcribe only edited
  History entries.
- Crash-resume manifests for long jobs (`backend/services/longform_resume.py:4-11`):
  the idea of checkpointed long-file transcription is a candidate for our long-audio
  path.

## Output and speakers

- Exports json/text/verbose_json/srt/vtt with word timings
  (`backend/api/routers/openai_compat.py:509-511`, builders `:605-629`): the SRT/VTT
  serializers are ~30 lines each and portable (GH #92).
- Sentence-boundary subtitle segmentation with short-segment merging
  (`backend/services/segmentation.py:105-141`, `:262`): pure logic, portable (GH #92).
- Three-tier speaker assignment with graceful degradation: pyannote overlap-weighted
  diarization, then inline ASR speaker turns, then silence-gap heuristic
  (`segmentation.py:465-535`). We ship sherpa diarization (GH #83); the transferable
  bit is the tiered fallback plus honest labeling.
- Speaker-aware re-splitting: segments straddling two speakers re-split at the word
  nearest the boundary so one subtitle never mixes voices
  (`segmentation.py:689-700`). ADAPTABLE post-processing for our diarized rows
  (GH #83).
- Optional speaker-count hint, clamped 1-20 and forced onto the diarizer because
  auto-detect collapses multi-speaker clips (`dub_core.py:564-604`). ADAPTABLE as a
  per-recording setting.
- Diarized transcript editing UI: search, per-speaker filter, inline edit, merge
  (`frontend/src/components/DubSegmentTable.jsx:76-92`, `:198-203`). NEW-CANDIDATE
  for a History review mode.

## Refinement (the richest vein for us)

- **Two-tier dictation cleanup.** Tier 1 deterministic: word runs of 6+ repeats
  collapsed plus a character-level pass for multi-word loops (unit bounded at 60
  chars) (`backend/services/refinement.py:88-93`, `:103-122`). Tier 2 optional
  local-LLM pass: disfluency removal, self-correction handling ("no wait",
  "scratch that"), technical-term preservation, dictated punctuation; few-shot as
  chat turns so small models do not echo them (`:201-244`, `:266-302`). Tier 1 is
  FEASIBLE-AS-IS for us (pure regex); tier 2 is the GH #72 shape via LiteRT-LM.
- **Hard wall-clock budget on the LLM pass** (4 s default): on timeout the raw
  transcript ships and the UI is told the LLM failed (`refinement.py:41-56`,
  `:369-439`). FEASIBLE-AS-IS discipline for any on-device LLM post-pass.
- Rule-based text polish on every final: leading capital, terminal punctuation,
  dangling separator, idempotent (`backend/services/text_polish.py:1-27`).
- Per-skill LLM registry: six named consumption points, each independently
  toggleable, disabled = exact no-LLM path (`backend/services/llm_skills.py:5-30`).
  Clean gating pattern for our LLM features.
- Map-reduce summaries/chapters: NOT_FOUND anywhere; their one LLM transcript
  contract explicitly says "Do not summarize" (`refinement.py:216`). GH #97 remains
  ours to design.

## Queueing and model management

- Single GPU lane with queue-position introspection ("2 jobs ahead") and cooperative
  cancel (`backend/core/job_queue.py:113-148`). ADAPTABLE (GH #72): single-lane +
  position reporting is the right shape for on-device RAM limits.
- **Offline sherpa models with live partials via utterance windowing**: the buffer
  holds only the uncommitted utterance, re-decoded every ~800 ms, committed when the
  trailing ~0.6 s fall below an RMS floor, then dropped; per-partial cost bounded by
  one utterance (`capture_ws.py:670-690`, constants `:421-422`, gate `:776-785`).
  FEASIBLE-AS-IS and exactly the fast-pass half of GH #43 (dual-model).
- Warm shared recognizer + idle reaper + session lease so nothing unloads
  mid-sentence (`backend/services/asr_backend.py:2740-2777`; the comment carries
  the measured 16 GB M2 postmortem: 6.2 GB idle baseline was the killer, not
  spikes). RECONCILIATION: we already ship this lifecycle (NativeKeepAlive idle
  unload + beginWork/endWork work brackets, see the Voxscribe note). Not a new
  candidate; the parts we lack are only the post-OOM breadcrumb (name the load
  that tipped the device) and eviction-on-load of other resident engines.
- Silent-model demotion: a model that loads but decodes nothing while the session
  heard real speech is auto-demoted; user re-selection clears it
  (`sherpa_dictation.py:392-459`, clearing at `dictation.py:140-144`).
  NEW-CANDIDATE: self-healing guard for catalog models that decode garbage on some
  devices.
- Compute-type fallback chain (fp16, int8_fp16, int8) instead of crashing
  (`asr_backend.py:199-207`). ADAPTABLE for NNAPI/provider hiccups.
- Endpoint detection tuned to 1.0/0.6 s trailing-silence rules, env-overridable
  (`sherpa_dictation.py:62-76`): tuning reference for our streaming backend.
- Engine quick-switch from the status bar (CHANGELOG, unreleased): ADAPTABLE as a
  quick-settings tile.

## Integrations and UX

- MCP server + per-agent voice bindings (`backend/mcp_server.py`, `mcp_bindings.py`):
  NOT-APPLICABLE as a server; the reverse is a candidate (Anti-Vocale as MCP client
  feeding transcripts to the user's agents).
- Floating dictation pill: unfocused always-on-top overlay, per-utterance live paste
  (`frontend/src-tauri/src/lib.rs:562-565`, `CaptureWidget.jsx:130-131`): the concept
  proof for GH #79; on Android this is accessibility-overlay territory (Play-policy
  care needed).
- AEC for dictate-over-playback (NLMS + Geigel double-talk detection,
  `backend/services/aec.py:1-24`, protocol `capture_ws.py:11-19`): NEW-CANDIDATE
  adjacent to GH #74 (transcribe a voice note while other audio plays, without
  transcribing the app's own output). Note sherpa-onnx has no AEC primitive; this
  would be a separate DSP component.
- Honest memory panel (resident models + free/total RAM + breadcrumb) and a
  diagnostics suite with scrubbed bundles (`core/diagnose.py`, `error_journal.py`):
  NEW-CANDIDATES (debug/diagnostic surface; pairs with our CrashReporter).
- LAN offload of long-file refinement to an OmniVoice box (retry/deadline shape at
  `worker/scheduler.py:239-242`): runner-up idea for GH #97, opt-in and clearly
  labeled, keeps the offline-first contract.

NOT_FOUND (explicit): summarization of any kind, hotwords, Telegram, webhooks,
folder watching, cloud-drive ingestion, cron/scheduling, OAuth.

## Shortlist (value x on-device feasibility, reconciled)

1. Hallucination-loop collapse tier 1 (pure regex, `refinement.py:88-122`), GH #72.
2. Utterance-windowed live partials from offline sherpa models
   (`capture_ws.py:670-690`), GH #43 fast pass.
3. Bounded LLM refinement with timeout + honest fallback status
   (`refinement.py:41-56`, `:369-439`), GH #72/#97 via LiteRT-LM.
4. Silent-model demotion after observed empty decodes
   (`sherpa_dictation.py:392-459`), NEW (reliability).
5. Dictation pill (unfocused overlay + per-utterance commit), GH #79,
   Play-policy sensitive.
6. SRT/VTT import + sentence-aware segmentation (`srt_parser.py:7-18`,
   `segmentation.py:105-141`), GH #92.
7. Speaker re-split at word boundaries + tiered fallback (`segmentation.py:465-535`,
   `:689-700`), GH #83.
8. Post-OOM breadcrumb + resident-models memory panel, NEW (diagnostics).

Issue mapping note: 1-3 and 8 map to existing tracking issues (#72, #43, #97);
4, 8, and the AEC candidate have no issue yet and are proposed for new tracking
issues pending maintainer approval.
