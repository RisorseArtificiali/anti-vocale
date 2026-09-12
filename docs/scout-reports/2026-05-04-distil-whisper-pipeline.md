# Scout Report: Distil-Whisper Italian Training Pipeline

**Date**: 2026-05-04
**Scope**: Distillation methodology, datasets, and reusability for other models

## Model

- **Page**: https://huggingface.co/bofenghuang/whisper-large-v3-distil-it-v0.2
- **ONNX export**: https://huggingface.co/pantinor/sherpa-onnx-whisper-distil-large-v3-it
- **Author**: Bofeng Huang (co-author of official distil-large-v3.5)
- **Paper**: arXiv:2311.00430 — "Robust Knowledge Distillation via Large-Scale Pseudo Labelling"

## Distillation Method

- **Teacher**: `openai/whisper-large-v3` (1550M params, 32 decoder layers)
- **Student**: 2-layer decoder (756M params = 49% of teacher), encoder frozen from teacher
- **Training**: 100 epochs, KL divergence + cross-entropy loss ("patient teacher" approach)
- **Pseudo-labelling**: Teacher generates transcripts → filtered by WER < 20%
- **Data augmentation**: SpecAugment, 30-second packed segments, 50% with timestamps
- **Compute**: Jean-Zay supercomputer at GENCI/IDRIS

## Datasets (6,508 hours filtered from 11,235h raw)

| Dataset | Filtered (h) | Notes |
|---------|-------------|-------|
| YODAS-it000 | 953 | YouTube-sourced |
| YODAS-it100 | 2,666 | YouTube-sourced |
| YODAS-it101 | 2,276 | YouTube-sourced |
| Common Voice | 233 | Mozilla curated |
| MLS | 234 | LibriSpeech multilingual |
| VoxPopuli | 58 | EU parliament |
| mTEDx | 89 | TED talks |

## Public Resources

- **Training code**: https://github.com/huggingface/distil-whisper/tree/main/training
  - `run_pseudo_labelling.py`, `create_student_model.py`, `run_distillation.py`, `run_eval.py`
  - Language-agnostic, documented with Hindi example
- **Pseudo-labelled data (189K hours, 7 languages)**: https://huggingface.co/datasets/bofenghuang/stt-pseudo-labeled-whisper-large-v3-multilingual
  - Covers: EN, FR, ES, PT, IT, DE, NL
  - All WER-filtered, packed into 30-second segments

## Existing Distilled Variants by Bofeng Huang

- `whisper-large-v3-distil-it-v0.2` — Italian
- `whisper-large-v3-distil-multi4-v0.2` — EN+FR+ES+DE
- `whisper-large-v3-distil-multi7-v0.2` — 7 languages
- `distil-large-v3.5` (official, co-authored) — English only

## Reusability for Other Models

The **datasets** (audio + transcript pairs) are fully model-agnostic — any ASR model can be fine-tuned on them:
- The 189K-hour pseudo-labelled dataset (7 languages) can directly fine-tune Parakeet, Gemma, Qwen3-ASR, etc.
- The pipeline concepts (pseudo-labelling → WER filtering → training) apply universally
- The specific distillation scripts are Whisper-architecture-specific but the data preparation steps are not

The **distillation pipeline** is Whisper-specific (frozen encoder + 2-layer decoder). For other architectures:
- The pseudo-labelling step can use any high-quality model as the labeler
- WER filtering is model-agnostic
- SpecAugment and data packing strategies transfer directly
- Only the training loop needs architecture-specific adaptation
