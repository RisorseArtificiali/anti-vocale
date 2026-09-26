# Live partials (utterance-windowed) and silent-model demotion: design notes

TASK-673 and TASK-675. Reference: Omnivoice (read from the Mac checkout,
2026-09-26 evening): capture_ws.py:937-955 (the ~800ms live-window
re-decode for uncommitted utterances) and sherpa_dictation.py:513-540
(demote_model: persisted silent-models list, idempotent, never breaks
the session, cleared by re-selection).

## TASK-673: utterance-windowed live partials

Their shape: the live window (speech since the last committed utterance)
is re-decoded every ~800ms for a partial; when an utterance commits
(polished, pasted), it is decoded once more and flushed as final.

Our mapping:
- We already ship progressive display on STREAMING models (Nemotron); the
  gap is OFFLINE models (Parakeet/Whisper): today they deliver only at the
  end. The offline equivalents of their live window are our VAD chunks:
  each completed VAD chunk is a committed utterance.
- Design: during a multi-chunk offline transcription, after chunk N
  completes, re-decode NOTHING: instead surface the delivered per-chunk
  texts as partials (they already are: chunks carry text as they
  finish). The NEW work is the uncommitted-tail partial: the chunk still
  accumulating (up to 30s of speech) stays invisible until it closes. An
  800ms-style re-decode of the open chunk would need a second decode of
  growing audio on the SAME backend: with sherpa offline models the cost
  is quadratic (the tail re-decodes from scratch every tick) and the
  decode competes with the chunk itself for the model. DECISION for tier
  1: per-chunk partials only (already shipped); tier 2 candidates:
  a) decode the open chunk on a SECOND backend instance (memory cost),
  b) Nemotron-style streaming hybrid for the tail only.
  Their ~800ms cadence maps to OUR per-chunk delivery, not to a timer.

## TASK-675: silent-model demotion

Their shape: a model that produced no text while an RMS gate proved real
speech is recorded in a persisted demoted list (idempotent, never
raises); auto-selection skips it; picking it again manually clears the
entry; a fallback recognizer must CONFIRM speech before a demotion is
safe ("the RMS gate can fire on fan noise").

Our mapping:
- Trigger: our silent-decode case is NoTranscriptionProduced on a row
  where VAD stripped nothing (speech was present by RMS/energy): the
  same two-signal shape (their fallback recognizer confirm maps to our
  VAD/speech-presence check).
- Storage: a persisted demoted-models list keyed by backend id
  (PreferencesManager string-set preference; no schema change).
- Effect: auto-selection and the recommended card skip demoted models;
  manual selection clears the entry (the "give it another chance" path)
  and shows an honest one-line reason in the Model tab.
- Safety: demote only after N silent decodes with speech present
  (N=2 within a session), never on the first occurrence (a wrong file
  share must not demote a good model), and never on remote/LLM backends
  (empty is meaningful there).
- No automatic downloads (their constraint too: the fallback must not
  trigger a fetch).

## Anti-goals for both
No LLM anywhere in tier 1; no background decoding; no new permissions;
the offline-first contract stays (both features are on-device).
