# TASK-968 bench leg: Fritter v1.2 offline int8 vs Whisper Turbo on FLEURS en_us

**Date:** 2026-11-07 · **Scope:** the desktop quality verdict for the already-approved Fritter listing. Fritter v1.2 offline int8 (author Daniel Rosehill, `danielrosehill/Fritter` on HF, described as a Parakeet TDT 0.6B v2 fine-tune for technical vocabulary) benched against Whisper Turbo on the shared FLEURS en_us protocol. Workspace `/var/tmp/fritter-bench`; nothing committed or pushed from this leg; the app source and the phone were untouched.

## TL;DR

Fritter does not lose the shared benchmark, so TASK-964's losing-entry rule (a loss on the shared benchmark requires a compensating domain win) never triggers. On the same 40 clips, same night, same harness: **Fritter 5.37 WER vs Turbo 5.72** (6.1% relative, a narrow aggregate edge; per-clip it is 10 Fritter wins, 8 Turbo wins, 22 ties), at **RTF 0.026 vs 0.241 back-to-back** (9.3x faster decode, Parakeet-stock speed class) and a 661MB int8 payload vs Turbo's 1.04GB (36% smaller). The honest verdict is: **comparable on general speech with a small aggregate win, decisively cheaper to run; the technical-vocabulary claim (author-reported 7.53 to 4.01 WER, single-speaker) is untested by this corpus** because FLEURS en_us carries no technical vocabulary and no validated in-house tech-vocab eval set exists. An improvised handful of technical sentences is not a validated set, so the claim stays untested rather than confirmed or refuted.

## Comparison table (FLEURS en_us test, 40 clips, jiwer-normalized WER, clip mode, sherpa-onnx 2.0.1, 4 threads, Apple M3 Pro)

| Model | WER | RTF | int8 download | Provenance |
|---|---|---|---|---|
| parakeet-stock-int8 (built-in) | 6.77 | 0.037 | ~0.66GB | quality-matrix-2026-11-01.json (2026-11-01) |
| Whisper Turbo (built-in) | 5.72 | 0.29 | 1.04GB | quality-matrix-2026-11-01.json; the WER bar |
| Whisper Turbo (fresh, same night) | 5.72 | 0.241 | 1.04GB | this session, back-to-back with Fritter (`eval/results/fleurs40_turbo-en_20261107.json`) |
| **Fritter v1.2 int8 (candidate)** | **5.37** | **0.026** | **0.66GB** | this session (`eval/results/fleurs40_fritter_20261107.json`) |

Every row decodes the identical clip set (audio_s 371.5). The fresh Turbo run reproduces the 2026-11-01 matrix WER (5.72) exactly, which cross-checks that the protocol, clip set, and normalization are unchanged; its role is the same-load RTF companion for Fritter.

**RTF caveat:** load average was 6.2 rising to 9.2 during the two runs (other session work on this Mac). Both candidate rows were measured back-to-back under that same load, so the 9.3x relative figure is fair; the absolute RTFs are load-inflated upper bounds (the matrix's Turbo 0.29 was measured with 3 concurrent workers on 2026-11-01). WER is decode-content and unaffected by load.

**Per-clip shape of the win** (both recognizers decoded all 40 clips again through the same harness functions): Fritter better on 10 clips, Turbo better on 8, identical on 22; mean per-clip WER 5.64 vs 5.94. The aggregate edge is real but narrow and traded clip-by-clip, not a uniform improvement; on this evidence "comparable, slightly ahead" is the honest wording, not "better".

## Download and pin verification

Source: schema-1 manifest at `https://models.carrotcakeai.com/asr/fritter/latest/manifest.json` (version "Fritter v1.2", run r30-general, revision 9836ee57f85abc0c9af0343ffd61528f322da8c3, licence CC-BY-NC-4.0, base_url the same CDN path). The four files sherpa's transducer shape needs were downloaded into `/var/tmp/fritter-bench/`; every byte size matched the manifest exactly, and `shasum -a 256` verified all four pins before any decode ran:

| File | Bytes | sha256 (computed == manifest) |
|---|---|---|
| encoder.int8.onnx | 652,282,303 | b1924e35604a5dd4c4f10867d5109cb894729768041e7861431b53773520be9f |
| decoder.int8.onnx | 7,257,753 | d79eac8bba592a6efd1343591c0724648bf1a1ffa035bad4699e349501a1a600 |
| joiner.int8.onnx | 1,739,080 | 37b5e174ee6b4b9e8f48a770d01bb172c3a83cef1bb7e28f3a012a282ac5876e |
| tokens.txt | 9,384 | ec182b70dd42113aff6c5372c75cac58c952443eb22322f57bbd7f53977d497d |

`bpe.vocab` was skipped: sherpa's 4-file NeMo transducer constructor never reads it, and the bench ran to completion without it. The manifest also lists GGUF variants (F16/Q8_0/Q4_K_M), irrelevant to the sherpa path.

## What was measured (model identity observations)

The encoder int8 is 652,282,303 bytes, within 0.1% of our built-in Parakeet TDT stock encoder int8 (652,184,281 bytes): same architecture class, fine-tuned weights. The head is where Fritter departs from stock: decoder 7.26MB and joiner 1.74MB against stock's 11.8MB and 6.4MB, driven by a rebuilt output vocabulary. `tokens.txt` is 1,025 lines (a 1024-piece vocab plus `<blk>`), character/piece-level with pieces like `▁th`, against stock's 128k BPE. ONNX metadata (read from the files): `model_type EncDecRNNTBPEModel`, `vocab_size 1024`, `feat_dim 128`, `subsampling_factor 8`, `pred_rnn_layers 2`, `pred_hidden 640`, comment "Only the transducer branch is exported". One recorded observation, not adjudicated: the author describes the base as Parakeet TDT 0.6B v2, while feat_dim 128 is the v3-family mel config in our catalog (the v2 entry is 80); the metadata above is what was actually measured and none of it changes the bench numbers.

## What was run this session

1. Manifest fetch; four-file download; byte-size check; `shasum -a 256` pin verification (table above).
2. A 2-clip smoke through the unmodified harness (entry mode) confirming the recognizer builds on the `nemo_transducer` path and decodes sensible English.
3. Turbo bench, then Fritter bench, back-to-back on the same clips: `eval/.venv/bin/python eval/fleurs_bench.py` at 424fcb43, raw-file mode for Turbo, entry mode (a local entry JSON carrying `family TRANSDUCER` + `modelType nemo_transducer`, mirroring how the harness benches every Parakeet transducer) for Fritter.
4. A per-clip comparison pass (both recognizers rebuilt, all 40 clips decoded again, per-clip jiwer WER) to characterize the win's shape.

## Verdict and listing context

**Comparable on general speech, narrowly ahead in aggregate, and dramatically cheaper: GO-consistent with the maintainer's approval to list.** Fritter beats the Turbo bar it was benched against (5.37 vs 5.72) while decoding 9.3x faster and downloading 36% smaller, and it also beats the built-in Parakeet stock cell (6.77) on this set. Because it does not lose the shared benchmark, TASK-964's compensating-domain-win requirement does not apply. What this bench does NOT establish is the author's technical-vocabulary claim (7.53 to 4.01 WER single-speaker on his tech-sentence material): FLEURS en_us contains no technical vocabulary, no in-house tech-vocab eval set exists, and an improvised handful of sentences would not be a validated instrument. Catalog copy should claim general-English quality on the level measured here, not the tech-vocab figure.

**License and import context:** CC-BY-NC-4.0 (manifest licence field, matching the HF source). That is why Fritter is a URL-import community-catalog entry and not a bundled model: the app side landed in 030df6a8 (schema-1 CDN-manifest import dialect; the catalog entry's entryUrl IS the live publisher manifest, languages `["en"]`, with the Nemotron-based Fritter Streaming as a separate streaming entry). One dependency worth restating for future re-benches: the manifest is a floating `latest` URL, so a re-run must re-verify the pins against the manifest fetched that day; the revision pinned here is r30-general / 9836ee57.

## Artifacts

- Bench JSONs: `eval/results/fleurs40_fritter_20261107.json`, `eval/results/fleurs40_turbo-en_20261107.json`.
- Workspace: `/var/tmp/fritter-bench/` (manifest.json, the four verified model files, the harness entry.json, both raw bench JSONs); per-clip script at `/var/tmp/fritter-bench-scripts/perclip.py` (its output lives only in this report's per-clip paragraph).
