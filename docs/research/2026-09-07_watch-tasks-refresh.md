# Watch-task refresh: TASK-178, TASK-244, TASK-222 (2026-09-07)

Periodic refresh of three watch-tasks against live sources (HF Hub API, GitHub
REST API, vendor docs), pulled 2026-09-07 around 00:30 CEST. Every fact below
was read from the live API response this session unless labeled UNVERIFIED or
reported-by. Model sizes are sums of per-file sizes from the HF API
(`?blobs=true`), so they represent the on-disk download footprint.

## TASK-178: Moonshine (Useful Sensors)

What changed since the 2026-04 check at v0.0.59.

- The project moved house. `usefulsensors/moonshine` on GitHub now
  301-redirects to `moonshine-ai/moonshine` (last push 2026-08-31), and the HF
  org `usefulsensors` returns an empty model list. The live org is
  `moonshine-ai`, 32 repos.
- Rebranded as "Moonshine Voice": a voice-agent toolkit (STT + TTS + intent)
  with a `moonshine-voice` pip package and docs at
  moonshine-voice.readthedocs.io. Releases advanced from v0.0.59 (2026-04-20)
  to v0.1.5 (2026-08-24).
- New model generation: streaming models (tiny 34M, small 123M, medium 245M
  parameters) with cached encoder states, all MIT licensed. The legacy
  non-streaming non-English checkpoints keep the non-commercial Community
  license and are marked deprecated in favor of streaming replacements.
- Language list today, from the official available-models table: en, ar, de,
  es, ja, ko, tl, vi, zh, uk. German and Tagalog are new since April. Italian
  is still absent: no Italian repo in the complete moonshine-ai org listing
  (verified programmatically), no hit in a targeted `moonshine-italian`
  search, and no Italian-flavored repo in the first 100 results of a
  recency-sorted `moonshine` search.
- Sizes stay far below our range: 26M to 245M parameters, plus 1MB "micro"
  models for English, versus our 240-940MB bundles.
- The sherpa-onnx path is healthy: the master tree carries
  `c-api-examples/moonshine-c-api.c` and `moonshine-v2-c-api.c` plus an
  `export-moonshine-to-onnx` CI workflow, so an eventual Italian model would
  have the usual export route. If Italian ships it will presumably be a
  streaming MIT model.
- One engineering note relevant to our loop research: their docs say the most
  common hallucination pattern is endlessly repeating the last few words, and
  they guard it with a tokens-per-second heuristic that needs a manually
  raised threshold (13.0) for non-Latin scripts.

Verdict: KEEP WATCHING. No Italian yet; the project is very much alive (two
new languages in five months, a full MIT streaming family), so the watch
trigger stands unchanged.

RECOMMENDED BOARD ACTION: keep-open TASK-178; the next check only needs the
moonshine-ai org listing plus the available-models table, looking for an `it`
entry.

Sources:
- https://github.com/moonshine-ai/moonshine (releases; v0.1.5 published 2026-08-24)
- https://huggingface.co/moonshine-ai (org listing via HF API, 32 repos, no Italian)
- https://moonshine-voice.readthedocs.io/en/latest/models/available-models/ (language, size, WER/CER, license table)
- https://moonshine-voice.readthedocs.io/en/latest/ (MIT for streaming models in every language; Community license for legacy non-English)

Confidence: high (org listing and docs table read directly).

## TASK-244a: LocalAI-io Italian WER claim

What changed since the 2026-06-30 verification: nothing that meets any gate.

- The HF org renamed from `localai-org` to `LocalAI-io`; the old
  `localai-org/italian-asr` HF repo now answers 401. The code repo is
  `localai-org/italian-asr` on GitHub (`mudler/italian-asr` redirects there),
  last push 2026-05-24, 4 stars: dormant for about 3.5 months.
- Gate (a), CV25 WER for large-v3 and large-v3-turbo: still blank. The README
  still says benchmarks "will be added once we finish running the standalone
  eval pass", and the turbo model card still says "trained with --no-eval;
  final WER to be measured separately".
- Gate (b), real-audio WER on a public test set: still absent. The only
  real-audio evidence remains the 4+-word-loop counts on one private
  ~17-minute recording, with the audio explicitly not published.
- Gate (c), an ONNX/sherpa export: still zero. A grep of the full 118-repo
  LocalAI-io catalog finds no onnx or sherpa repo; formats remain CTranslate2
  int8 and GGML/GGUF only. Hard blocker for our sherpa-only runtime, as
  before.
- One content shift: the README now headlines a VibeVoice ASR fine-tune
  (Qwen2-based, with diarization) as their loop-robustness winner. That
  reinforces that the loop claim concerns their GGUF/CT2 artifacts, not
  anything our runtime could load.

Verdict: KEEP WATCHING. The "hallucinated repetition on our models" claim
remains unverified, and the source project is now also unattended.

RECOMMENDED BOARD ACTION: keep-open TASK-244 part (a) with the three gates
unchanged; the cheapest trigger to watch is any push to
localai-org/italian-asr after 2026-05-24.

Sources:
- https://github.com/localai-org/italian-asr (README benchmark sections; last push 2026-05-24)
- https://huggingface.co/LocalAI-io (118-repo catalog via HF API; zero onnx/sherpa repos)
- https://huggingface.co/LocalAI-io/whisper-large-v3-turbo-it (card: --no-eval, final WER to be measured separately)

Confidence: high.

## TASK-244b: Qwen3-ASR-1.7B sherpa export

Headline: the news is in the sherpa runtime, not in the 1.7B artifact.

- Official export: still only the 0.6B.
  `csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25` measures 982MB
  (decoder 755.9 + encoder 182.5 + frontend 44.1). No csukuangfj 1.7B repo
  exists.
- Issue #3535 is still open, but on 2026-08-31 csukuangfj replied "Please
  upload one. We can put a link to your huggingface repo in the doc". The path
  to a doc-linked 1.7B artifact is now an invited community upload; none is
  published yet.
- Community 1.7B int8 exports do exist:
  `ilmina/qwen3-asr-1.7b-sherpa-onnx` (2026-05-17) and
  `thieunv/sherpa-onnx-qwen3-asr-1.7B-int8` (2026-07-23). Both measure about
  2.36GB (decoder 2037.5 + encoder 314.2 + frontend 48.1), both show 0
  downloads, both unverified quality. That is 2.4x our 0.6B artifact and well
  above our largest bundle; on mid-range phones this is not a realistic
  download or memory proposition (see
  docs/research/2026-09-01-android-heap-limits-adaptive-behavior.md).
- Load-bearing find: sherpa-onnx PR #3873, "Align Qwen3-ASR whisper features
  to the centered-STFT convention", merged 2026-08-31. eolivelli's analysis
  in #3535: the whisper-style feature frontend centered every mel frame 5 ms
  late relative to the extractor Qwen3-ASR was trained against; the 1.7B
  collapses into repetition loops on marginal inputs because of it, and the
  official 0.6B is measurably degraded too (reported-by eolivelli: mean CER
  on the 0.6B's own test_wavs improves from 0.2228 to 0.2109 with the fix,
  which also adds a degenerate-repetition guard). Whisper models are
  unaffected.
- Version math against our pin: `.sherpa-version` is v1.13.5 (released
  2026-08-11). The fix merged 2026-08-31 and first shipped in v1.13.7
  (2026-09-01). Every build we ship today therefore runs the Qwen3-ASR path
  with the 5 ms misalignment and without the loop guard.
- Nothing newer than 1.7B from Qwen: the Qwen org lists only Qwen3-ASR-0.6B
  and Qwen3-ASR-1.7B (plus the -hf variants).

Verdict: the 1.7B watch itself is KEEP WATCHING (no official artifact;
community exports are 2.36GB and unverified). The runtime fix is READY TO
EVALUATE against our existing 0.6B: a quality-relevant sherpa bump we are
currently missing.

RECOMMENDED BOARD ACTION: next-concrete-step: bump the sherpa pin from
v1.13.5 to v1.13.7 (all three sync points: `.sherpa-version`,
`SHERPA_ONNX_VERSION` in `scripts/fetch-sherpa-aar.sh`, the SRCLIB PIN
comment in `app/build.gradle.kts`) and re-run `eval/run_baseline.py` on
Qwen3-ASR 0.6B to confirm the CER and loop improvement; keep the 1.7B half of
TASK-244 open on the official-export trigger.

Sources:
- https://github.com/k2-fsa/sherpa-onnx/issues/3535 (eolivelli analysis 2026-08-12; csukuangfj reply 2026-08-31)
- https://github.com/k2-fsa/sherpa-onnx/pull/3873 (merged 2026-08-31T06:29Z)
- https://github.com/k2-fsa/sherpa-onnx/releases (v1.13.5 2026-08-11, v1.13.6 2026-08-18, v1.13.7 2026-09-01)
- https://huggingface.co/csukuangfj2/sherpa-onnx-qwen3-asr-0.6B-int8-2026-03-25
- https://huggingface.co/ilmina/qwen3-asr-1.7b-sherpa-onnx and https://huggingface.co/thieunv/sherpa-onnx-qwen3-asr-1.7B-int8 (sizes via HF API)

Confidence: high on all API-read facts; the CER numbers are as reported by
the PR author, so secondhand, labeled reported-by.

## TASK-222: Cohere Transcribe ASR backend

What Cohere Transcribe is today, verified.

- A 2B-parameter dedicated ASR model, Apache-2.0, 14 languages including
  Italian (en, de, fr, it, es, pt, el, nl, pl, vi, zh, ar, ja, ko), Conformer
  encoder plus a lightweight Transformer decoder; no automatic language ID,
  no timestamps, no diarization (docs.cohere.com/docs/transcribe).
- Distribution is both cloud and open weights: a hosted API (free tier with
  rate limits; Model Vault for production) plus downloadable weights at
  CohereLabs/cohere-transcribe-03-2026. The HF original is gated behind an
  access request, but the license tag is apache-2.0.
- The on-device path exists and is sherpa-native:
  `csukuangfj2/sherpa-onnx-cohere-transcribe-14-lang-int8-2026-04-01` is
  publicly downloadable and unchanged since 2026-04-02, and sherpa-onnx
  master carries first-class support (`kotlin-api-examples/test_offline_cohere_transcribe.kt`
  and a CI test script), so it loads through the same OfflineRecognizer our
  app already uses.
- Measured footprint: 2.89GB total (encoder.int8.onnx.data 2731.5MB +
  decoder.int8.onnx 153.3MB + encoder.int8.onnx 3.1MB). The task note's
  2.65GB figure does not match the current file listing; 2.89GB is what the
  API reports today. That is roughly 3x our largest curated model
  (Distil-IT at 938MB).
- Ecosystem churn since the task was written: many community quantizations
  now exist (ONNX int8 and int4, CoreML 4/6/8-bit, GGUF, oQ4 through oQ8),
  but only the csukuangfj2 export is sherpa-shaped. An Arabic specialist
  variant (cohere-transcribe-arabic-07-2026) also appeared; irrelevant to
  Italian.
- The June claim that it ranks #1 on the Open ASR Leaderboard at 5.42% avg
  WER was not re-checked this round: UNVERIFIED.

Positioning, stated plainly. The expected close reason (a cloud API breaks
the local-only promise, F-Droid distribution, the rejected-cloud-ASR
precedent) does not apply to the artifact we would actually use: the weights
are Apache-2.0 and run fully on-device through sherpa. Integrating the Cohere
cloud API would indeed violate the no-network promise, but nothing forces
that route. The genuine blocker is the one the 2026-08-24 triage already
identified: 2.89GB does not fit the curated download catalog, and the
external-models platform already gives users this model today with zero app
changes.

Verdict: CLOSE WITH REASON. Close not on the cloud-conflict premise (that
premise is factually wrong for the on-device artifact) but because the
footprint is 3x our largest curated model, the sherpa export has been frozen
since April, and the external-import path already covers the demand. Reopen
triggers: a sherpa-native int4 or sub-1GB quantization, or a smaller
multilingual Cohere Transcribe release.

RECOMMENDED BOARD ACTION: close TASK-222 (too large for the curated catalog,
already usable via external import); spend no benchmark time on a 2.89GB
artifact.

Sources:
- https://docs.cohere.com/docs/transcribe (model facts, language list, Apache-2.0, API and Vault availability)
- https://huggingface.co/CohereLabs/cohere-transcribe-03-2026 (API metadata: license apache-2.0, gated access, 14 language tags)
- https://huggingface.co/csukuangfj2/sherpa-onnx-cohere-transcribe-14-lang-int8-2026-04-01 (file sizes via HF API; 14-language README)
- sherpa-onnx master tree: kotlin-api-examples/test_offline_cohere_transcribe.kt, .github/scripts/test-offline-cohere-transcribe.sh

Confidence: high on all facts above; leaderboard rank UNVERIFIED this round.
