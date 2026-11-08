# TASK-970: Parakeet Ultra int8 (official sherpa-onnx export) vs Parakeet v3 stock-int8, 11-language FLEURS bench

**Date:** 2026-11-08 · **Scope:** the TASK-970 gate bench: Parakeet Ultra int8 (`csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-ultra-int8`, the official sherpa-onnx export published 2026-11-06) against our default Parakeet TDT 0.6b v3 stock-int8, on the established FLEURS 40-clip protocol across 11 tradition languages (the 8 mandatory plus fi, el, pt), plus the 11-clip real WhatsApp Italian set that carried the TASK-823 NO-GO. Nothing committed, pushed, or cataloged; the phone was not touched.

## TL;DR

**Ultra wins all 11 FLEURS languages**, average WER 9.95 vs 12.40 (2.45 absolute, ~20% relative), at the same payload size (0.67GB both) and the same decode speed (mean RTF 0.031 vs 0.033 on this harness). The verdict per TASK-964 is a clean **GO for the community catalog**: the shared-protocol gate is won outright with no losing cell, so the losing-entry domain-win exception never triggers. Two honest caveats ride along: on the 11-clip real WhatsApp Italian set the same official artifact still loses to v3 (same-day, same harness: WER 0.051 vs 0.041, CER 0.029 vs 0.016), so the app DEFAULT should stay v3 stock-int8 and the catalog row must carry both facts per the German precedent; and the device-validation pass (TASK-970 AC2) is still pending.

## Comparison table (FLEURS test, first 40 clips per language from the shared cache, jiwer-normalized WER, clip mode, sherpa-onnx 2.0.1, Apple M3 Pro, 4 threads, both models decoding identical clip sets)

| Language | Ultra int8 | v3 stock-int8 | Delta | Matrix v3 (2026-11-01) |
|---|---|---|---|---|
| it_it | 2.77 | 3.95 | -1.18 | 3.95 |
| en_us | 5.72 | 6.77 | -1.05 | 6.77 |
| de_de | 4.74 | 8.75 | -4.01 | 8.75 |
| fr_fr | 7.94 | 8.22 | -0.28 | 8.22 |
| es_419 | 3.12 | 3.63 | -0.51 | 3.63 |
| nl_nl | 9.83 | 11.82 | -1.99 | 11.82 |
| pl_pl | 11.60 | 13.36 | -1.76 | 13.36 |
| ru_ru | 7.98 | 8.71 | -0.73 | 8.71 |
| fi_fi | 14.48 | 20.66 | -6.18 | 20.66 |
| el_gr | 36.27 | 44.28 | -8.01 | 44.28 |
| pt_br | 4.98 | 6.25 | -1.27 | 6.25 |
| **Average (11)** | **9.95** | **12.40** | **-2.45** | 12.40 |

Ultra wins 11, ties 0, loses 0. The biggest gains are de (-4.01), fi (-6.18) and el (-8.01); the smallest is fr (-0.28). Both models are weak on Greek (36 and 44 WER; the worst quality-matrix cell for v3 too) and Ultra still takes it. Decode speed is the same class: mean RTF 0.031 (Ultra) vs 0.033 (v3 stock) across the 22 runs.

Validity checks, both passed: the fresh v3 stock runs reproduce the 2026-11-01 quality matrix (`eval/results/quality-matrix-2026-11-01.json`) bit-exactly in every one of the 11 languages, which pins the harness, clips and machine; and `audio_s` matches between the two models in every language, so both decoded identical clip sets.

For context, the repo README's own 300-clip FLEURS numbers (their harness, their normalization; not comparable to ours in absolute terms) point the same direction: Ultra de 5.20 / fr 7.48 / es 3.81 vs v3 de 6.84 / fr 8.69 / es 4.55.

## The real-audio countercheck (the TASK-823 tension, resolved)

TASK-823 (2026-10-13) found both community Ultra int8 conversions (Alexqwqw, leogaudin) regressing identically on the 11-clip real WhatsApp Italian set (WER 0.062 vs v3's then-0.031) and sharpened the verdict to "the regression is in the weights"; the official csukuangfj export was named the clean retest. That retest ran today, same-day and same-harness for both models (sherpa 2.0.1, `run_baseline.py` driven in-process with a temporary backend entry, no repo edit):

| Backend | mean WER | mean CER | per-clip |
|---|---|---|---|
| v3 stock-int8 | 0.041 | 0.016 | baseline |
| Ultra official (csukuangfj) | 0.051 | 0.029 | 5 worse, 5 tied, 1 better |
| Ultra Alexqwqw (2026-10-28, same venv) | 0.056 | 0.027 | for reference |

The official export regresses less than the community ones but still loses on real audio. Three independent artifacts, one of them the sherpa-onnx project's own CI, now agree: the real-audio regression is weight behavior, not conversion damage, and it coexists with a read-speech (FLEURS) win everywhere. This is a domain tradeoff the post-training made, and the report row should say so. Side observation, not load-bearing: v3's own real-set number drifted 0.031 (2026-09-29, sherpa 1.13.8) to 0.041 (today, 2.0.1); the same-day A/B above is the valid comparison.

## Model metadata and pin verification

Source: `csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-ultra-int8` (repo sha `402e1190ce85b9793118ee223db21fb0d12f52db`, published 2026-11-06). This is the official export: built by the sherpa-onnx project's own workflow (k2-fsa/sherpa-onnx#4163), not a third-party conversion. Weights: Moondream's parakeet-ultra, a post-training of nvidia/parakeet-tdt-0.6b-v3, CC-BY-4.0; the ONNX export code is Apache-2.0. Same TDT 0.6b architecture and tokenizer as our v3 (25 EU languages). The encoder metadata carries `tdt` in its url field, so sherpa loads the TDT joiner correctly; loads and decodes fine on our pinned sherpa-onnx 2.0.1 (smoke-tested before the matrix).

All files downloaded into `/var/tmp/ultra-bench/model/` and verified against the HF API before the run:

| File | Bytes | sha256 | Verification |
|---|---|---|---|
| encoder.int8.onnx | 652,282,345 | `b43072e1b721f18a3df03fcfeb763ff7c4debdf29843598a3aba2f703e2780a3` | matches HF LFS pin |
| decoder.int8.onnx | 11,845,274 | `1fab98fe6c12aded87d2da66272cc9e148d0a0044ce3850a12fe56302ec4a922` | matches HF LFS pin |
| joiner.int8.onnx | 6,355,277 | `8a71aaccfdba3d451775507a889afd2257d2e754463fd81095a8c8f290f924c2` | matches HF LFS pin |
| tokens.txt | 93,939 | `d58544679ea4bc6ac563d1f545eb7d474bd6cfa467f0a6e2c1dc1c7d37e3c35d` | git-blob sha1 `fc43e1c723e262df60b70e1919614417162d1fe2` matches HF tree; byte-identical to the v3 stock tokens (the TASK-823 pin) |

Total int8 payload 670,576,835 bytes vs v3 stock's 670,384,833: the same size class.

Documented limitations that matter to us (from the repo README): whole-clip decoding degrades badly on long audio (their stress test: a 184.6s clip at 37.65% WER whole vs 9.54% split at silences into ~15s chunks, with +2335MB vs +196MB RSS); they recommend silence-split 15-20s chunks, never fixed windows; and very quiet input hits a frontend issue tracked upstream (k2-fsa/sherpa-onnx#4024).

## Verdict (TASK-964 rule)

The acceptance rule says the shared-protocol FLEURS check is the gate, and a losing entry ships only with a domain win plus distinct value. Ultra does not need the exception: it wins the gate outright, 11 of 11 languages, ~20% relative average, at equal size and speed. **GO: create the community-catalog entry** (TASK-970 AC2) with the device-validation pass, and record the real-audio caveat in the report row so the choice stays with the user, exactly as the German Large-v3 precedent carries both numbers. The app default recommendation remains v3 stock-int8: for the real voice-note domain our own 11-clip set says v3 is still better, and FLEURS is read speech, not WhatsApp audio. Swapping the built-in default would be a separate maintainer decision with real-audio evidence required first.

## Import path (for the AC2 entry, when approved)

Direct catalog entry from the csukuangfj repo, no conversion (already sherpa-shaped): family TRANSDUCER, modelType `nemo_transducer`, streaming flag false (the offline NeMo export carries none of the chunk keys; the TASK-720 gate), four files with the sha256 and size pins exactly as verified above, languages the 25 EU set from the README. One open decision for the entry author: the Transducer family declares no family-level chunk cap (only Canary does) and the built-in parakeet runs 60s windows, but the README's own long-audio stress result argues this model wants tighter, silence-aligned windows; a long-voice-note probe during the device pass should settle whether the entry needs a tighter chunk declaration or VAD-aligned chunking.

## Artifacts

Bench JSONs (22, one per model per language, house metadata format): `eval/results/fleurs40_ultra_{it_it,en_us,de_de,fr_fr,es_419,nl_nl,pl_pl,ru_ru,fi_fi,el_gr,pt_br}_20261108.json` and `eval/results/fleurs40_v3stock_{...}_20261108.json`. Workspace `/var/tmp/ultra-bench/`: `model/` (verified download), `entry-ultra.json` (the harness entry used), `run_matrix.sh`, `analyze.py`, `logs/` (per-run harness output), `realset-results/` (the 11-clip countercheck CSV and summary). Harness: unmodified `eval/fleurs_bench.py` at commit a347c798, driven by CLI, entry mode; the v3 stock leg used `eval/models/parakeet-tdt-0.6b-v3-int8/bench-entry.json`.
