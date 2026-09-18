# TASK-366 step 1: small-class monolingual ASR candidates, eval + smoke (2026-08-21)

Environment: eval/.venv, sherpa-onnx 1.13.5 python, CPU (4 threads), ffmpeg.
Test clips: 10 per language, FLEURS (google/fleurs, CC-BY-4.0), 16 kHz mono WAV.
- de / fr / ru: validation split via the HF datasets-server rows API (signed wav URLs).
- es: es_419 test split parquet (validation shard exceeds the rows-API 300 MB scan
  limit), decoded from embedded WAV bytes, parquet deleted after extraction.

Manifests: `eval/smallclass/<lang>/manifest.tsv` (path TAB transcript), clips alongside.
Runner: `eval/smallclass/run_eval.py` (raw output incl. per-clip ref/hyp in `results.json`).
Models: `eval/smallclass/models/<repo-name>/` (int8 variants where shipped).

WER = Levenshtein on normalized lowercase words; RTF = decode time / audio duration.

| Model | Lang | Size | Load | WER | RTF |
|---|---|---|---|---|---|
| bookbot/sherpa-onnx-zipformer-streaming-robust-es-v0 (int8, OnlineRecognizer.from_transducer) | es | 26 MB | OK | n/a (phoneme output, see below) | 0.15 |
| wanderer51/sherpa-onnx-whisper-tiny-de (int8, OfflineRecognizer.from_whisper, lang=de) | de | 59 MB | OK, decode FAIL | n/a | n/a |
| csukuangfj/sherpa-onnx-zipformer-ru-int8-2025-04-20 (OfflineRecognizer.from_transducer) | ru | 70 MB | OK | 14.8 % | 0.05-0.14 (varies run to run) |
| csukuangfj/sherpa-onnx-streaming-zipformer-fr-kroko-2025-08-06 (fp32 only, OnlineRecognizer.from_transducer) | fr | 68 MB | OK | 22.5 % | 0.12-0.15 |
| fr-kroko (cross-check on German clips) | de | - | OK | 99.5 % (expected; French-only) | 0.15 |
| csukuangfj streaming-zipformer-es-kroko (fp32) | es | 148 MB | OK | **6.8 %** | 0.13 |
| csukuangfj streaming-zipformer-de-kroko (fp32) | de | 67 MB | OK | **6.3 %** | 0.08 |
| csukuangfj streaming-zipformer-en-kroko (fp32) | en | 67 MB | OK | 17.6 % | 0.09 |

## Notes

- **ES bookbot**: its tokens.txt is a 37-entry phoneme inventory (a, e, ʝ, ɾ, θ, ...,
  no space token), so the model emits unspaced phonemic strings, e.g.
  `elusoadekwadodelosblogspwedeempodeɾaɾalosalumnospa...` for
  "el uso adecuado de los blogs puede empoderar a los alumnos...". Phonetically the
  outputs track the references well, but orthographic WER is not computable against
  FLEURS refs. For an app this model would need a phoneme-to-grapheme step or
  phoneme-based scoring; flagged as NOT directly usable as-is.
- **DE whisper-tiny-de**: model loads, but every decode throws inside onnxruntime
  (`Non-zero status code ... Slice node '/Slice_2' ... Starts must be a 1-D array`,
  offline-recognizer-whisper-impl.h:DecodeStream) and returns empty text. tail_paddings
  up to 3000 did not help; only int8 files are shipped in the repo. Treat as
  incompatible with sherpa-onnx 1.13.5 desktop/runtime as-is. DE remains UNCOVERED by a
  working candidate.
- **RU zipformer int8**: best result of the set. 14.8 % WER, RTF comfortably below
  realtime.
- **FR kroko**: works, 22.5 % WER. License is CC-BY-SA (must be noted for any app
  integration; also only fp32 files exist, 68 MB). Not German-capable (99.5 % on de).
- RTF on this host is noisy across runs (ru showed 0.05-0.14); treat as order-of-magnitude
  "well below realtime on desktop CPU", phone numbers will be smaller-core-bound.

## 2026-08-21 second follow-up: DE resolved by de-kroko (6.3%), EN marginal

de-kroko is the best small-class result so far (6.3% WER on the German FLEURS set,
67 MB fp32): it fills the DE gap left by the broken whisper-tiny-de export. en-kroko
at 17.6% is not competitive for English (Parakeet is the default there). All kroko
models share CC-BY-SA and fp32-only caveats.

## 2026-08-21 follow-up: ES resolved by es-kroko

bookbot-ES is skipped as a recommendation (phoneme-only output; see notes above; both
the regular and the "ort" variant ship phoneme inventories). The working Spanish
small-class candidate is csukuangfj's streaming-zipformer-es-kroko: ORTHOGRAPHIC BPE
tokens, **WER 6.8%** on the same 10 FLEURS clips, RTF 0.13. Caveats: fp32 only
(148 MB; no int8 shipped), CC-BY-SA (license review before any in-app recommendation).

## 2026-09-18 TASK-550: FA covered by Shenava Koochik v1.5 (15.9%)

shenava-koochik-v1.5-rnnt int8 (NeMo FastConformer RNNT, Apache-2.0, ~130 MiB) is
the first Persian-capable catalog entry. Loaded via OfflineRecognizer.from_transducer
with model_type="nemo_transducer" (required: the NeMo decoder has no vocab_size
metadata). fa clips fetched from the fa_ir validation parquet (the datasets-server
rows API 500s on that config; same method as es).

| Model | Lang | Size | Load | WER | RTF |
|---|---|---|---|---|---|
| shenava-koochik-v1.5-rnnt (int8) | fa | 130 MB | OK | 15.9 % | 0.04 |

Persian measurement caveats (the number is honest but pessimistic):
- FLEURS fa references join words with ZWNJ (U+200C) where the model emits plain
  spaces; treating ZWNJ as a word separator gives 13.3% on the same clips.
- The residual is dominated by digits: the references write Persian numerals
  (۷۰, ۴۱) where the model emits number words (fa_010 alone scores 62% from this).
RTF 0.04 = ~28x realtime on 4 desktop threads. Desktop-validated only; the catalog
entry ships pending the on-device import + RTL render pass.
