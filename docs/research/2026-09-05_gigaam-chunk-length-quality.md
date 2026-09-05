# GigaAM v3: transcription quality vs single-pass length (TASK-448)

**Date**: 2026-09-05
**Question**: the app chunks GigaAM v3 at 180s (v1.11.2, chosen to stay under
the 5000-position rotary table). Sber's own package refuses single passes above
25s (LONGFORM_THRESHOLD). Is 180s actually acceptable quality?
**Confidence**: HIGH (six full lectures, one cliff-mapping file, one model, greedy decode, fixed segmentation)

## Executive summary

No. At 180s per pass the model still runs (no native error) but the transcript
is destroyed: macro WER 65.1% vs 10.7% at 25s across six 17-30 minute Russian
lectures. The degradation is gradual from ~60s and catastrophic by ~90-180s.
The catalog cap moves to 30s, measured indistinguishable from the 25s baseline.

## Setup

- Test set: HF dataset `dangrebenkin/long_audio_youtube_lectures` (apache-2.0),
  seven 17-30 minute Russian scientific lectures with reference transcripts
  (philology, philosophy, mathematics, politics, history, ML, medicine).
  Suggested by @Dum4G on #76.
- Model: GigaAM v3 int8 (pantinor/gigaam-v3 mirror), sherpa-onnx 1.13.5
  desktop, `model_type="nemo_transducer"` greedy, 8 threads.
- Segmentation: fixed chunks + the app's 1s tail pad, transcripts
  concatenated. Russian normalization (lowercase, punctuation stripped,
  ё→е), unit-cost Levenshtein WER/CER.
- Harness: `/tmp/gigaam-eval/run_eval.py` (session-local; numbers below).

## Results

Per-file (fixed chunk length is the only variable):

| lecture | domain | 25s WER | 180s WER |
|---|---|---|---|
| harvard | philosophy | 2.96% | 67.26% |
| zhirinovsky | politics | 7.68% | 54.81% |
| lankov | history | 9.17% | 56.87% |
| kolodezev | machine learning | 13.36% | 61.16% |
| savvateev | mathematics | 14.63% | 85.12% |
| zaliznyak | philology | 16.61% | 65.34% |
| **macro (6 files)** | | **10.74%** | **65.09%** |

Cliff mapping on harvard (23.5 min):

| chunk | WER | CER |
|---|---|---|
| 25s | 2.96% | 1.03% |
| 30s | 2.60% | 1.08% |
| 60s | 5.69% | 2.49% |
| 90s | 14.12% | 8.31% |
| 180s | 67.26% | 60.60% |

The seventh file (tuberculosis, 30 min, noisiest domain) could not complete:
CORRECTED 2026-09-05 (later session): the harness's CER was a quadratic Python
DP over ~25k chars (~6GB), OOM-killed by the kernel; NOT model behavior (the
first guess, "process died under load", was wrong). The fix pattern is
rapidfuzz's C++ Levenshtein (same unit-cost metric). Six files and the cliff
map are unambiguous either way.

Follow-up same day: the full segmentation landscape (fixed 25/30, VAD strip,
VAD-aligned no-strip) is measured in 2026-09-05_gigaam-segmentation-landscape.md;
all sub-30s schemes sit in a 10.7-11.5% band and Parakeet is flat on pass
length, so the cliff is GigaAM-specific and the 30s cap stands.

## Interpretation

- The rotary table (5000 positions = 200s) is only the CRASH boundary. The
  TRAINING boundary is 25s: beyond it the encoder operates on rotary positions
  it never saw in training, and attention degrades progressively. Sber's
  LONGFORM_THRESHOLD is a quality guard, not an arbitrary limit.
- 30s is indistinguishable from 25s on this data (2.60 vs 2.96 is within
  file-level noise) and 20% above the training max; 60s doubles the error,
  90s quintuples it.
- The v1.11.2 device runs (122/226/400s at 180s chunks) verified no native
  error only; the transcripts they produced were almost certainly garbage.
  Pre-1.11.2 single passes of ≤200s files had the same problem (no chunking
  at all), so no release ever shipped good GigaAM quality above ~60s.

## Decision

`chunkDurationSeconds` for gigaam moves 180 → **30** in the catalog. Effects:
- Streaming path: 30s fixed chunks (same as Whisper/Qwen3).
- VAD path: merge window becomes 28s (the historical Whisper window).
- Memory: much lower attention peak per pass.
- Speed: 6x more passes than 180s, but GigaAM RTF ~0.15 keeps it fast
  (a 30-min lecture decodes in ~4.5 min on the desktop at 8 threads).

## Sources

1. HF dataset card and parquet (7 lectures, apache-2.0).
2. Sber gigaam package: LONGFORM_THRESHOLD = 25*SAMPLE_RATE (gigaam/model.py).
3. This run's logs: WER/CER per file above; harness logic mirrors
   SherpaBackend's nemo_transducer config and the app's tail pad.
