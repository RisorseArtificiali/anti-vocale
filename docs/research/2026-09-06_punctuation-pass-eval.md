# TASK-278: punctuation-pass eval baseline (2026-09-06)

Baseline report for the LLM punctuation pass shipped in TASK-276, produced by
`eval/postprocess_score.py`. It scores the pass the way the app runs it:
same prompt composition, same guards, on real Italian voice-message text.

## The pass under test

Source of truth read from the app (all paths under
`app/src/main/java/com/antivocale/app/`):

- `transcription/TranscriptionOrchestrator.kt` (`applyPunctuationPass`, ~line
  395): mode/model/text gates, backend swap to the LLM, guards, raw fallback.
- `transcription/PunctuationPolicy.kt`: `needsPunctuation` (fewer than one
  terminal per 40 words = needs the pass, `MIN_LENGTH_CHARS = 12`),
  `withinContextLimit` (12000 chars), `acceptablePolish`
  (`polished.length >= original.length * 0.4`), `effectivePrompt`
  (user override else localized default).
- `transcription/ChunkPromptPolicy.kt`: `finalPrompt` composes
  `"$instruction\n\nTranscript:\n$transcript"`.
- Instruction: `punctuation_default_prompt` from `res/values-it/strings.xml`
  (line 436) because the eval set is Italian and an Italian-locale device
  resolves to that resource.
- The LLM call: `LlmTranscriptionBackend.generateText` is a passthrough to
  `LlmManager.generateText`, which sends the prompt as a single user message
  with no system prompt.

**Sampler finding (correction to the task brief).** The brief assumed the
pass runs greedy like `AUDIO_CONVERSATION_CONFIG` (topK=1, temperature=0).
The code says otherwise: `generateText` reads the main conversation, which is
created with `DEFAULT_CONVERSATION_CONFIG` (topK=40, topP=0.95,
temperature=0.8, `LlmManager.kt`); only the audio path uses the greedy
config. Both configurations were therefore measured: greedy (temperature 0,
top_p 1.0) and the app-faithful chat sampler (temperature 0.8, top_p 0.95).
OpenAI-compatible endpoints have no top_k parameter, so top_k=40 is not
reproduced for the chat config; that is a known small gap (labeled guess:
top_k 40 vs no top_k changes sampling little at top_p 0.95).

## Method

Per sample:

1. Reference = the punctuated raw-ASR transcript (see provenance below).
2. Input = reference stripped of punctuation and casing, keeping apostrophes
   and intra-word hyphens (`l'acqua` stays). This simulates the
   non-punctuating ASR model the pass exists for; feeding the punctuated
   reference verbatim would be skipped by `needsPunctuation`.
3. Prompt = `final_prompt(IT instruction, input)`, sent as one user message.
4. Polished = endpoint reply, trimmed, then through the app guards
   (`acceptablePolish`, blank fallback). A rejected polish keeps the input,
   exactly like the orchestrator.

Metrics (normalization mirrored from `eval/run_baseline.py` `normalize_it`:
lowercase, punctuation dropped, apostrophes become spaces, accents kept):

- **WER raw / polished**: edit distance vs the reference tokens. WER raw is
  0.0 by construction (the input derives from the reference by stripping),
  so the polished WER isolates exactly the content the pass itself changed.
- **Punctuation F1**: mark-type token F1. Text becomes an alternating
  sequence of lowercase surface words and mark items (`M:comma` for `, ; :`,
  `M:term` for `. ! ? …`; runs of same-class marks count once; leading marks
  dropped). Ref and polished sequences are aligned with
  `difflib.SequenceMatcher`; matched mark items are TP, unmatched marks in
  the polished text are FP, unmatched marks in the reference are FN. A
  comma-where-the-ref-has-a-period counts as both FP and FN.
- **Content preservation**: `difflib.SequenceMatcher` ratio on normalized
  word lists, input vs polished; samples below 0.98 are flagged.

## Eval set and provenance

- 11 real Italian voice messages (spontaneous speech, mixed speakers),
  durations 2.1 s to 40.6 s, 5 to 98 words. Audio and transcripts live in
  gitignored `eval/clips/` and `eval/transcripts/` (PII: never commit).
  Clip ids are real file names, so this report aliases them to s01 to s11;
  the id map lives in gitignored `eval/results/clip_id_map.txt`.
- The curated TASK-243 set does not exist on disk (checked the repo, old
  worktree, /tmp, scratch), so references were generated with
  `--transcribe`: parakeet-tdt-0.6b-v3 int8, greedy, the app's default model,
  run in `eval/.venv` (sherpa-onnx 1.13.5 python). These are **ASR
  pseudo-references, not human-verified truth**. Consequences:
  - absolute WER vs human truth is not measured here;
  - the WER delta (the AC-relevant number) is unaffected, because reference
    and pass-input derive from the same source, so any delta is purely the
    pass's own edit;
  - punctuation F1 is a lower bound on quality vs human punctuation: the
    reference marks are parakeet's own (sometimes idiosyncratic on
    disfluent speech), which shows in the two longest clips.

## Results

Backend: the Mac's oMLX `http://mac.lan:8000/v1`, model
`gemma-4-26b-a4b-it-4bit` (real endpoint, not mock). Every sample passed the
app gates and the polish was applied on all 11 (no collapse, no blank, no
error) in both configurations.

Aggregate (pasted from the run output):

```
# greedy, temperature 0.0, top_p 1.0
Aggregate over 11 samples (11 pass-applied):
  WER raw 0.0% | polished 0.3% | delta 0.3%
  punct micro P/R/F1 62.3%/66.2%/64.2% (TP 43 FP 26 FN 22)
  content ratio mean 0.9965, min 0.9730, flagged 1/11

# app chat sampler, temperature 0.8, top_p 0.95 (what the device runs)
Aggregate over 11 samples (11 pass-applied):
  WER raw 0.0% | polished 0.1% | delta 0.1%
  punct micro P/R/F1 65.2%/66.2%/65.6% (TP 43 FP 23 FN 22)
  content ratio mean 0.9990, min 0.9888, flagged 0/11
```

| Config | mean WER raw | mean WER polished | delta | punct F1 micro | content min | flagged |
|---|---|---|---|---|---|---|
| greedy (t=0) | 0.0% | 0.3% | +0.3 pp | 64.2% | 0.9730 | 1/11 |
| app chat sampler (t=0.8) | 0.0% | 0.1% | +0.1 pp | 65.6% | 0.9888 | 0/11 |

Per sample (WER polished vs reference; punct F1 of the polished text; the
unpolished input scores F1 0.000 on every sample by construction, that is
what the pass is for):

| clip | words | greedy WER | greedy F1 | greedy content | chat WER | chat F1 | chat content |
|---|---|---|---|---|---|---|---|
| s01 | 5 | 0.0000 | 1.000 | 1.0000 | 0.0000 | 1.000 | 1.0000 |
| s02 | 18 | 0.0000 | 1.000 | 1.0000 | 0.0000 | 1.000 | 1.0000 |
| s03 | 89 | 0.0112 | 0.690 | 0.9888 | 0.0112 | 0.815 | 0.9888 |
| s04 | 19 | 0.0000 | 0.800 | 1.0000 | 0.0000 | 0.800 | 1.0000 |
| s05 | 22 | 0.0000 | 0.500 | 1.0000 | 0.0000 | 0.500 | 1.0000 |
| s06 | 54 | 0.0000 | 0.364 | 1.0000 | 0.0000 | 0.200 | 1.0000 |
| s07 | 98 | 0.0000 | 0.439 | 1.0000 | 0.0000 | 0.439 | 1.0000 |
| s08 | 37 | 0.0270 | 0.875 | 0.9730 FLAG | 0.0000 | 1.000 | 1.0000 |
| s09 | 17 | 0.0000 | 1.000 | 1.0000 | 0.0000 | 0.750 | 1.0000 |
| s10 | 16 | 0.0000 | 0.667 | 1.0000 | 0.0000 | 0.667 | 1.0000 |
| s11 | 23 | 0.0000 | 1.000 | 1.0000 | 0.0000 | 1.000 | 1.0000 |

### What the two WER deltas actually are

Word-level diff of the two drifting samples (the only content edits in the
whole run; single words, no additions or deletions):

- `s03` (both configs): a first-person-plural verb was rewritten to
  third-person plural (`volevamo` to `volevano`). This is a meaning change:
  who performs the action flips. Exactly the silent-rewrite class TASK-278
  exists to catch.
- `s08` (greedy only): an adjective's number agreement was corrected
  (`chiara` to `chiare`). Grammatical fix, not a meaning change.

### The 0.98 content flag has a blind spot

`s03` drifted by one word in 89, ratio 0.9888, which is above the 0.98
threshold: the flag catches one-word drift on shorter clips (`s08`,
1 in 37) but misses it on clips longer than about 50 words. If single-word
meaning drift must never slip through, the flag needs a length-aware
threshold (for example flag any nonzero word-level diff) or the report must
list per-sample word diffs; the per-sample CSV carries the verbatim
ref/input/polished from which those diffs are derived.

### Second model variant (QAT), partial service

The Mac also serves `gemma-4-26B-A4B-it-QAT-MLX-4bit`. Two warm runs
completed 10/11 and 8/11 samples; the rest failed with server-side
`HTTP 507 Insufficient Storage` while oMLX juggled the two 26B weights
(single-shot probes succeed, sequential batch runs trip it). Partial
aggregates: greedy 10/11 applied, WER delta +1.0 pp, punct F1 64.6%;
chat 8/11 applied, WER delta +0.9 pp, punct F1 46.7%. The harness's
error-to-raw guard handled every failure exactly as the app would (raw text
delivered, WER unaffected). These numbers are reported for completeness;
they are not a clean per-model comparison and were not used for the verdict.

## Verdict against TASK-276 AC2 ("WER must not degrade")

Strictly read, the pass does degrade WER, by a small amount: mean +0.3 pp
greedy, +0.1 pp with the chat sampler the device actually runs; worst single
sample +2.7 pp (one word in 37). Samples with exactly zero content change:
9 of 11 greedy, 10 of 11 chat; both configs keep per-sample token
preservation at or above 0.973. Whether "+0.1 pp mean, one meaning-flipping
verb in 11 real clips" satisfies "must not degrade" is a tolerance call for
the maintainer; the harness now measures it per sample, and the flagged
drift classes are the concrete evidence to decide with. The data here does
not support a stronger statement either way (11 pseudo-referenced samples,
stronger model than the device one).

## Caveats (honest list)

- **Pseudo-references**: ground truth is parakeet's own output, so absolute
  WER says nothing about ASR quality; only the delta (the pass's edit) is
  meaningful, and the input WER 0.0 is by construction.
- **Model mismatch**: the device runs Gemma 4 E2B/E4B via LiteRT-LM
  (`litert-community/gemma-4-E2B-it-litert-lm`, `data/ModelDownloader.kt`);
  this eval used the Mac's gemma-4-26b-a4b-it, a much larger same-family
  model. Numbers are an upper bound on the on-device pass; the E2B vs E4B
  comparison from the task text needs an on-device or device-faithful
  backend (the harness takes `--endpoint`/`--model` for exactly that).
- **Punct F1 vs pseudo-marks**: the reference marks are parakeet's, at times
  arbitrary on disfluent speech (visible on the two longest clips, F1 0.20
  to 0.44); against human punctuation the F1 would differ.
- **top_k not reproduced** for the chat-sampler config (OpenAI-compatible
  API has no top_k); see the sampler finding above.
- **Sample size**: 11 clips, directional only, same caveat as
  `eval/README.md`.
- **Mock mode exists** (`--mock`) and was validated end-to-end, but every
  number in this report comes from the real endpoint.

## Reproduce

```bash
cd eval
.venv/bin/python postprocess_score.py --transcribe          # build pseudo-refs from clips/
set -a; . ~/.config/litellm/zai-router.env; set +a          # OMLX_API_KEY
.venv/bin/python postprocess_score.py --temperature 0.0 --top-p 1.0
.venv/bin/python postprocess_score.py --temperature 0.8 --top-p 0.95
.venv/bin/python postprocess_score.py --mock                # pipeline check, no network
```

Outputs land in gitignored `eval/results/` (per-sample CSV with transcripts,
markdown report). Scoring functions are importable and side-effect-free for
unit tests (`from postprocess_score import punct_items, punct_prf, ...`).
