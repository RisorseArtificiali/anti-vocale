# Research Report: Extending GigaAM v3 to long-audio (20-40+ min) transcription for Anti-Vocale

**Date**: 2026-09-05
**Depth**: exhaustive (primary-source web research + new empirical measurements)
**Confidence**: HIGH overall (per-finding levels below)

## Executive summary

No action on the model side is needed or available: every GigaAM variant is trained and validated on short segments, and Sber's own long-form solution is exactly ours (segment, decode per segment, concatenate). New measurements run this session on the six-lecture Russian benchmark show that once chunks stay at or under 30s, the segmentation scheme barely matters: fixed 25s, fixed 30s, whisperX-style VAD-aligned windows, and our VAD strip-and-merge all land in a 10.7 to 11.5% macro WER band, far from the 65% collapse at 180s. The quality ceiling is the model, not our chunking. The practical package: keep GigaAM v3 e2e_rnnt, optionally drop the catalog chunk from 30s to 25s (the only segmentation change with a consistent, if small, quality direction), keep VAD as the opt-in speed feature it already is, and do not invest in overlap-merge, model swap, or fine-tuning for quality.

## New empirical results (this session, strongest evidence)

Run on the same TASK-448 benchmark (six 17-30 min Russian lectures from `dangrebenkin/long_audio_youtube_lectures`, same refs and normalization, GigaAM v3 e2e_rnnt int8 via sherpa-onnx 1.13.5 Python, greedy, 1s tail pad, 6 threads, desktop, contended). The VAD modes mirror `VadProcessor.kt` and `AudioPreprocessor.mergeVadSegments` exactly (silero int8, threshold 0.5, 250ms min silence/speech, 20s max speech, 200ms pads, merge to cap-2). Harness: session-local (`/tmp/gigaam-r2/run_seg.py`, `run_cut.py`).

| lecture | fixed25 (TASK-448 doc) | fixed30 | vadcut30 | vad28 | vad23 | fixed180 (doc) |
|---|---|---|---|---|---|---|
| zaliznyak (philology, reverb) | 16.61 | 17.77 | 18.42 | 18.56 | 20.30 | 65.34 |
| harvard (philosophy) | 2.96 | 2.60 | 2.19 | 2.05 | 2.14 | 67.26 |
| savvateev (mathematics) | 14.63 | 16.02 | 15.13 | 14.83 | 15.33 | 85.12 |
| zhirinovsky (politics) | 7.68 | 7.85 | 6.98 | 6.86 | 7.27 | 54.81 |
| lankov (history) | 9.17 | 8.99 | 9.20 | 9.10 | 9.44 | 56.87 |
| kolodezev (ML) | 13.36 | 13.79 | 12.78 | 14.12 | 14.84 | 61.16 |
| **macro WER** | **10.74** | **11.17** | **10.78** | **10.92** | **11.49** | **65.09** |

- `fixed30`: current app catalog behavior (blind 30s windows). `vad28`: the app's VAD path with cap 30. `vad23`: VAD path as it would behave with a 25s cap. `vadcut30`: NEW variant, whisperX-style alignment only (windows packed onto VAD silence gaps by span, silence inside windows kept, nothing stripped).
- Reproduction anchor: fixed30 harvard is 2.60%, identical to the TASK-448 cliff map, so the re-extracted dataset and harness match the prior session bit-for-bit in outcome.
- Findings: (1) all sub-30s schemes sit within per-file noise of one another; no segmentation refinement buys a material win. (2) fixed 25s beats fixed 30s on 4 of 6 files (macro 10.74 vs 11.17), the only comparison with a consistent direction. (3) silence stripping is quality-neutral on average (vad28 10.92 vs vadcut30 10.78) but file-specific: it strips 8 to 23% of audio and the reverb-heavy zaliznyak regresses under every VAD variant, including no-strip alignment, so short acoustic context, not lost content, is what hurts that file. (4) VAD cuts decode volume by 8 to 23%, a genuine battery and speed win.

Companion measurement (main session): Parakeet TDT on the same benchmark is FLAT on pass length (harvard 4.42/4.74/5.15% at 25/60/180s; zaliznyak 16.53/15.99/17.80%): the quality cliff is GigaAM-specific (rotary-position training distribution), not a general long-pass phenomenon, and our per-model chunk caps are the right architecture.

Side finding (corrects the TASK-448 doc narrative): the seventh file "could not complete on the desktop" was not model behavior. The harness's CER built a quadratic Python DP over ~25k characters (~625M cells, ~6GB) and was OOM-killed. Fixed with `rapidfuzz.distance.Levenshtein` (same unit-cost metric, C++ implementation) in the session harness.

## Findings by research question

### 1. Model side: better-suited GigaAM variants or successors

- GigaAM v3 ships five ASR variants (`ssl`, `ctc`, `rnnt`, `e2e_ctc`, `e2e_rnnt`); we ship the best end-to-end one. On Sber's 10-set evaluation, v3_rnnt averages 8.3% and v3_ctc 9.1%, while our e2e_rnnt averages 11.2% after de-punctuation (the price of built-in punctuation, casing, and numeral normalization). HIGH confidence.
- No long-form variant exists. `LONGFORM_THRESHOLD = 25 * SAMPLE_RATE` still guards `transcribe()` in the current `model.py`; the supported long-form path `transcribe_longform` segments with VAD (now pyannote segmentation-3.0) then decodes each segment batched. HIGH.
- Training segment budget: the official fine-tuning utils filter the dataset at `--max_duration 20.0` seconds by default; the paper says models are "trained on relatively short audio segments (e.g., up to 30 seconds)" and explicitly notes a full-context model trained on short fragments "might struggle when presented with a 2-minute audio file", which is precisely our measured cliff. HIGH.
- The InterSpeech 2025 paper describes chunkwise attention with dynamic chunk size sampling that would enable streaming fine-tunes (receptive field "approximately a minute"), but no streaming GigaAM was ever released. HIGH.
- Successor: GigaAM Multilingual (2026/06): 220M/600M charwise CTC on 2M hours, best-in-class Russian WER (600M: CV 5.1, FLEURS 3.0, internal 6.0, beating Whisper-large-v3 on Russian). Also a short-segment model; CTC only, no punctuation, and the 600M is not phone-practical. The 220M (about 220MB int8) is a plausible future catalog addition, not a GigaAM v3 replacement. MEDIUM.

### 2. Segmentation side: evidence for cut alignment

- whisperX (Interspeech 2023) is the primary quantification: on TED-LIUM, VAD cut-and-merge with the merge threshold equal to the training duration improved WER from 10.52 to 9.70 (7.8% relative), while batched sliding windows without VAD collapsed to 78.78 WER. Two caveats for us: it is Whisper-specific (hallucination during silence drives much of the gain; transducers emit nothing in silence), and our fixed-30 concat is the mild variant, not the catastrophic one. HIGH for the numbers, MEDIUM for transfer.
- Sber's and sherpa's own long-form pipelines are VAD-segment-then-decode, the architecture we already have. sherpa's reference binary for exactly our model class is `sherpa-onnx-vad-with-offline-asr` with `--model-type=nemo_transducer` plus silero VAD. HIGH.
- Our measurements above are the direct answer for GigaAM: aligning cuts to silence recovers roughly 0.2 to 0.6 points on clean files, gives it back on reverb-heavy ones, and does not change the macro meaningfully. HIGH (own data).

### 3. Overlap and merge strategies

- The state-carrying reference is NeMo's Buffered Transducer Inference (8s chunks plus 1s symmetric context, mid-buffer token selection, predictor state kept across buffers). It requires online-style state APIs that sherpa's OfflineRecognizer does not expose. MEDIUM-HIGH.
- The offline analogue, offset-carrying overlapping windows with word-level stitching, is implementable: `OfflineRecognitionResult` in sherpa 1.13.5 exposes `timestamps`, `tokens`, `words`, and `durations` (verified with a probe). HIGH.
- Measured gains for RNNT specifically: no published isolated number exists; whisperX's 7.8% relative on Whisper is the nearest proxy. Given our flat plateau across all sub-30s schemes, the expected gain for GigaAM is below the noise floor of our benchmark. MEDIUM.
- Conclusion: overlap-merge is a solved-in-principle, low-payoff build. Not recommended now; the timestamps finding keeps it cheap to revisit (word-level timing for subtitles would use the same machinery).

### 4. sherpa-onnx capabilities we are not using

- Releases 1.13.6 and 1.13.7 contain nothing for GigaAM long-form (a Flutter VAD+ASR example, a Qwen3-ASR silent-audio hotword hallucination fix, online NeMo transducer logprobs). Nothing argues for a version bump on this account. HIGH. (Side note: the 1.13.7 Qwen3 fix may matter for our Qwen3 backend separately.)
- Hotwords (contextual biasing) are transducer-only and require `modified_beam_search` plus a `cjkchar`/`bpe` modeling unit. Our e2e vocab is a 1024-unit hybrid (subwords plus punctuation tokens), which sherpa's hotword tokenizer cannot encode. Dead end without upstream work. MEDIUM-HIGH.
- Practical gates: `chunkDurationSeconds > 0` already makes the catalog's `maxAudioDurationSeconds: 200` UI-inert for gigaam (`ModelAudioLimit.kt` routes to ChunkedAnyLength), so 40-minute inputs are accepted; total duration is governed by the AudioDurationPolicy ceilings. HIGH (read in code).

### 5. Alternative Russian models for long audio

Third-party benchmark (alphacephei, maintained by the Vosk author, updated 2025-09; average WER over 11 Russian test sets): GigaAM2 CTC+LM 8.42, GigaAM2 RNNT 8.64, Vosk 0.54 10.69 to 11.02, T-One streaming 12.79, Whisper Podlodka Turbo 13.78, NeMo ru FastConformer 13.95, Whisper large-v3 16.21, Vosk small streaming 14.67.

| option | size | ru quality | long-audio story | verdict |
|---|---|---|---|---|
| GigaAM v3 e2e_rnnt (ours) | 326MB int8 | best in class | 30s chunks, punctuation native | keep |
| GigaAM v3 ctc / v2 ctc+LM | ~250MB | comparable | same segmentation need | no quality case for a swap |
| GigaAM Multilingual 220M ctc | ~220MB int8 | near-SOTA ru | short-segment too; no punctuation | future catalog candidate |
| Vosk small ru | 45MB | poor (22-32% open sets) | streaming, unlimited | only if streaming matters; big quality loss |
| Whisper (bundled) | 358MB/988MB | 16% class | 30s chunks, hallucination-prone | already a user fallback |
| Qwen3-ASR (bundled, 938MB) | bundled | ru listed, no lecture evidence | 30s chunks | no evidence it beats GigaAM on ru |
| SenseVoice | any | no Russian | n/a | dead end, verified |

No swap improves Russian long-audio quality at mobile size. HIGH for the numbers, LOW for Qwen3-ASR ru (unmeasured on this domain).

### 6. Fine-tuning GigaAM ourselves on longer segments

- Sber ships fine-tuning code (2026/04): `train_utils` with CTC/RNNT/SSL heads, PyTorch Lightning, activation checkpointing, bf16, staged encoder freezing. Full fine-tuning, not LoRA. `--max_duration` is a plain dataset filter, so training on 30-60s segments is a one-flag change. HIGH.
- Data: the supervised mix is public (Golos, Common Voice ru, Russian LibriSpeech, SOVA). At hobby scale a length-extension fine-tune is technically feasible. MEDIUM.
- Precedent: none published. Expected value is low for us: the cliff is rotary-position extrapolation, we do not want longer single passes anyway (O(n^2) memory), and it would make us owners of a non-official artifact against the byte-identical-official-artifact policy. MEDIUM.

## What we should do next (decision package)

1. Keep GigaAM v3 e2e_rnnt as the Russian backend. No model swap available improves on it at 326MB.
2. Optional one-line quality tweak: catalog `chunkDurationSeconds` 30 to 25 for gigaam. Paired evidence says fixed 25s wins on 4 of 6 files, macro 10.74 vs 11.17; 25s is also Sber's own threshold. Cost: about 20% more decode passes. This is a within-noise improvement; ship it only if the cost is acceptable.
3. Keep VAD exactly where it is: an opt-in setting that trades nothing on quality for 8-23% less decoding. Do NOT force it gigaam-wide the way Canary requires it.
4. Do not build: overlap-merge, pyannote-style segmentation, rotary-table re-export.
5. Watch, do not act: GigaAM Multilingual 220M CTC as a future catalog entry (model-scout cadence covers this).
6. The file-7 exclusion in the TASK-448 doc was a harness CER O(n^2) OOM, not model failure (amended in the doc).

## Confidence assessment

- HIGH: all Sber-source facts (25s threshold, pyannote longform, train max_duration 20s, variant table, WER tables, fine-tuning tooling); the empirical matrix (deterministic greedy decode, reproduction anchor matched, only segmentation varied); sherpa capabilities and hotword vocab mismatch; whisperX table numbers; SenseVoice language coverage.
- MEDIUM: the 25s-vs-30s direction (4 of 6 files, inside the per-file noise band); overlap-merge expected gain; fine-tune feasibility; GigaAM Multilingual 220M as a candidate.
- LOW: Qwen3-ASR Russian quality on lectures.

## Sources

1. ai-sage/GigaAM-v3 model card: https://huggingface.co/ai-sage/GigaAM-v3
2. salute-developers/GigaAM README + evaluation.md + train_utils/README.md: https://github.com/salute-developers/GigaAM
3. Sber model.py at main: https://raw.githubusercontent.com/salute-developers/GigaAM/main/gigaam/model.py
4. GigaAM paper, Interspeech 2025: https://arxiv.org/abs/2506.01192
5. GigaAM Multilingual model card and paper: https://huggingface.co/ai-sage/GigaAM-Multilingual , https://arxiv.org/abs/2607.10371
6. WhisperX paper (VAD Cut and Merge ablation): https://arxiv.org/abs/2303.00747
7. NeMo Buffered Transducer Inference tutorial: https://github.com/NVIDIA/NeMo/blob/stable/tutorials/asr/Buffered_Transducer_Inference.ipynb
8. sherpa-onnx hotwords docs: https://k2-fsa.github.io/sherpa/onnx/hotwords/index.html
9. sherpa-onnx NeMo transducer docs: https://k2-fsa.github.io/sherpa/onnx/pretrained_models/offline-transducer/nemo-transducer-models.html
10. sherpa-onnx releases 1.13.6/1.13.7: https://github.com/k2-fsa/sherpa-onnx/releases
11. alphacephei Russian ASR benchmark 2025: https://alphacephei.com/nsh/2025/04/18/russian-models.html
12. T-One model card: https://huggingface.co/t-tech/T-one
13. Vosk models page: https://alphacephei.com/vosk/models
14. SenseVoice repo: https://github.com/QwenAudio/SenseVoice
15. istupakov/gigaam-v3-onnx: https://huggingface.co/istupakov/gigaam-v3-onnx
16. Local: TASK-448 docs (2026-09-03_gigaam-rotary-pos-limit.md, 2026-09-05_gigaam-chunk-length-quality.md) and this session's Parakeet comparison.
