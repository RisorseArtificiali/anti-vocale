# Research Report: Distil-Large-V3-IT Training Methodology

**Date**: 2026-05-17
**Scope**: Exhaustive investigation of bofenghuang's Whisper distillation methodology, public activity, and reproducibility
**Confidence**: High (cross-referenced across 4 parallel research agents, primary sources verified)

---

## Executive Summary

Bofeng Huang (ML Engineer at Zaion, Paris) is the leading individual contributor to multilingual Whisper distillation. He published **53 HuggingFace models** including the Italian `whisper-large-v3-distil-it-v0.2` used by Anti-Vocale. His methodology is well-documented across model cards, blog posts, and the official `huggingface/distil-whisper` repository (where he is a contributor). The training code is public, the pseudo-labeled dataset (189,000 hours, 7 languages) is published, and the approach is fully reproducible with one gap: the exact data filtering pipeline scripts are not shared.

---

## 1. Creator Profile

### Identity
- **Name**: Bofeng Huang
- **Role**: Machine Learning Engineer at **Zaion** (European AI company for customer relations)
- **Location**: Paris, France
- **Twitter/X**: `@bofenghuang1`
- **LinkedIn**: `linkedin.com/in/bofeng-huang-ab8107162/`
- **Medium**: `medium.com/@bofenghuang7`
- **GitHub**: `github.com/bofenghuang` (main repo: `vigogne`, 505 stars)

### Notable Achievements
- Won **1st prize** in both French and German tracks of the HuggingFace/Lambda Labs Whisper Fine-Tuning Event (Jan 2023)
- Co-authored `distil-whisper/distil-large-v3.5` (the flagship English Distil-Whisper) with Eustache Le Bihan, Steven Zheng, Vaibhav Srivastav
- Released the largest open-source multilingual ASR training dataset (189,000 hours)
- Member of "Whisper Multilingual Distillation" HuggingFace organization

### Infrastructure
All models trained on the **Jean-Zay supercomputer** at GENCI (French national computing center). The v3.5 English model used **64 H100 GPUs** over 3 days.

---

## 2. Complete Model Inventory

### 2.1 Whisper ASR Fine-Tuned Models (chronological progression)

| Model | Description | Key Detail |
|-------|-------------|------------|
| `whisper-small-cv11-french` | Fine-tuned on CV 11.0 | 1st prize winner |
| `whisper-medium-cv11-french` | Fine-tuned on CV 11.0 | WER: 9.03/8.54 |
| `whisper-medium-french` | 2,200h composite dataset | **Detailed Zaion blog post** |
| `whisper-large-v2-cv11-french` | Fine-tuned on CV 11.0 | WER: 8.05/7.67 |
| `whisper-large-v2-french` | Composite French dataset | Best V2-era model |
| `whisper-large-v3-french` | 2,500h French data (v0.1) | Custom case/punctuation restoration |

### 2.2 French Distilled Models (v0.1 — French-specific teacher)

| Model | Decoder Layers | Speedup | Notes |
|-------|---------------|---------|-------|
| `whisper-large-v3-french-distil-dec16` | 16 | 1.9x | **Outperforms original** |
| `whisper-large-v3-french-distil-dec8` | 8 | 3.0x | **Outperforms original** |
| `whisper-large-v3-french-distil-dec4` | 4 | 4.3x | |
| `whisper-large-v3-french-distil-dec2` | 2 | 5.8x | Draft model for speculative decoding |

### 2.3 Language-Specific Distilled Models (v0.2 — generic teacher)

| Model | Language | Raw Hours | Filtered Hours | Epochs |
|-------|----------|-----------|----------------|--------|
| `whisper-large-v3-distil-fr-v0.2` | French | 22,748 | 10,402 | 160 |
| **`whisper-large-v3-distil-it-v0.2`** | **Italian** | **11,235** | **6,509** | **100** |
| `whisper-large-v3-distil-de-v0.2` | German | — | — | — |

### 2.4 Multilingual Distilled Models

| Model | Languages | Notes |
|-------|-----------|-------|
| `whisper-large-v3-distil-multi4-v0.2` | EN/FR/ES/DE | Code-switching support |
| `whisper-large-v3-distil-multi7-v0.2` | EN/FR/ES/PT/IT/DE/NL | Below monolingual quality |
| `whisper-large-v3-distil-multi7-dec4` | 7 languages | 4 decoder layer variant |

### 2.5 Collaborative Release

| Model | Notes |
|-------|-------|
| `distil-whisper/distil-large-v3.5` | English flagship, bofenghuang co-authored |

### 2.6 Other Notable Models
- `parakeet-tdt-0.6b-v3-hybrid` — NVIDIA Parakeet experiment (recently uploaded)
- `vigogne-*` family — French LLM instruction-following/chat models (LLaMA, Mistral, Llama 3)
- `flan-t5-large-dialogsum-fr` — French dialogue summarization
- `gemma-3-27b-it` — recent Gemma 3 upload

### 2.7 Published Datasets

| Dataset | Size | Description |
|---------|------|-------------|
| `stt-pseudo-labeled-whisper-large-v3-multilingual` | **24.1 TB**, 189,000h | 7 languages, WER<20% filtered |
| `mt-bench-french` | 80 samples | French MT-Bench evaluation |
| `magpie-fr` | 6.93M | French AI-generated responses |
| `asr-dummy` | 101 samples | Demo dataset (FR/DE/IT/CS) |

---

## 3. Training Methodology: The Complete Pipeline

### 3.1 Overview: Two Approaches

Bofenghuang used two distinct approaches across his work:

**Approach A (v0.1 — French): Fine-tune then Distill**
1. Fine-tune Whisper-Large-V3 on curated language-specific data
2. Distill from the fine-tuned model (reducing decoder layers)
3. Result: dec16/dec8 variants outperform the original model

**Approach B (v0.2 — Italian/French/German/Multilingual): Direct Distillation**
1. Use generic `openai/whisper-large-v3` as teacher (no language-specific fine-tuning)
2. Build large pseudo-labeled dataset via teacher inference + WER filtering
3. Distill with "patient teacher" approach (long training + aggressive augmentation)
4. Result: 2-layer decoder, optimized for target language

The Italian model uses **Approach B**.

### 3.2 The Four-Stage Pipeline (Approach B)

#### Stage 1: Data Collection and Pseudo-Labeling

**Italian data sources (11,235 hours raw):**

| Dataset | Hours (Raw) | Hours (Filtered <20% WER) | Description |
|---------|-------------|---------------------------|-------------|
| mcv (Common Voice) | 250 | 233 | Crowdsourced read speech |
| mls (Multilingual LibriSpeech) | 247 | 234 | Audiobook chapters |
| voxpopuli | 74 | 58 | European Parliament proceedings |
| mtedx | 94 | 89 | TED talks |
| yodas-it000 | 1,447 | 953 | Semi-supervised YouTube |
| yodas-it100 | 4,930 | 2,666 | Semi-supervised (larger) |
| yodas-it101 | 4,193 | 2,276 | Semi-supervised (largest) |
| **Total** | **11,235** | **6,509** | |

**Pseudo-labeling process:**
1. Concatenate short audio clips into ~30-second segments (preserving same speaker)
2. Run `openai/whisper-large-v3` inference on all segments (greedy decoding)
3. Normalize both pseudo-labels and ground truth using Whisper normalizer
4. Compute WER between pseudo-labels and ground truth
5. Discard segments with WER > 20% threshold

**Script**: `run_pseudo_labelling.py` from `huggingface/distil-whisper`

```bash
python run_pseudo_labelling.py \
  --model_name_or_path "openai/whisper-large-v3" \
  --dataset_name "facebook/multilingual_librispeech" \
  --dataset_config_name "italian" \
  --language "it" \
  --batch_size 32
```

**Why pseudo-labels over ground truth:**
- Consistent formatting across all datasets (punctuation, casing, numbers)
- Sequence-level distillation signal (student learns teacher's output distribution)
- Mitigates ground-truth labeling errors
- Higher WER threshold (20%) used for non-English vs. English (10%) — reflecting higher baseline WER

#### Stage 2: Student Model Initialization

**Script**: `create_student_model.py` from `huggingface/distil-whisper`

```bash
python create_student_model.py \
  --teacher_checkpoint "openai/whisper-large-v3" \
  --encoder_layers 32 \
  --decoder_layers 2 \
  --save_dir "./distil-large-v3-it-init"
```

**What it does:**
- Copies the **entire 32-layer encoder** from the teacher (no modification)
- For the decoder, selects **maximally-spaced layers**: from 32 layers → 2 layers (copies layers 1 and 32)
- The encoder is then **frozen** during training (only decoder is trained)

**Rationale for freezing encoder:** The encoder handles acoustic feature extraction and Whisper's robustness to noise, accents, and different audio distributions. Ablation in the Distil-Whisper paper (Table 13) shows freezing achieves nearly identical performance to full fine-tuning while being ~10x more compute-efficient.

#### Stage 3: Training (Distillation)

**Script**: `run_distillation.py` from `huggingface/distil-whisper`

```bash
accelerate launch run_distillation.py \
  --model_name_or_path "./distil-large-v3-it-init" \
  --teacher_model_name_or_path "openai/whisper-large-v3" \
  --train_dataset_name "path/to/pseudo_labelled_data" \
  --language "it" \
  --task "transcribe" \
  --freeze_encoder \
  --freeze_embed_positions \
  --per_device_train_batch_size 32 \
  --learning_rate 1e-4 \
  --lr_scheduler_type "constant_with_warmup" \
  --warmup_steps 500 \
  --dtype "bfloat16" \
  --gradient_checkpointing \
  --predict_with_generate \
  --do_train --do_eval
```

**Training supports three modes:**
- **Mode A** — Fine-tune on ground truth (for weak baselines, <1,000h data)
- **Mode B** — Fine-tune on pseudo-labels (the primary mode used for Italian)
- **Mode C** — KL divergence distillation (matches teacher's token-level probability distribution)

**Italian-specific configuration:**

| Parameter | Value | Notes |
|-----------|-------|-------|
| Epochs | 100 | French used 160; English v3.5 used 80 |
| Data augmentation | Aggressive | SpecAugment (time/frequency masking on mel spectrogram) |
| Timestamp probability | 50% | Higher than default 20%, ensures timestamp quality |
| Previous-context probability | 20% | Kept low — 2-layer decoder not expected to excel at this |
| Encoder | Frozen | Not updated during training |
| Decoder layers | 2 | Copied from layers 1 and 32 of teacher |
| Precision | bfloat16 | On A100/H100 GPUs |

**"Patient Teacher" approach** (from Beyer et al., CVPR 2022, arXiv:2106.05237):
- The teacher and student receive **identical inputs** (same augmentation)
- Longer training schedules than typical fine-tuning — eval WER continued decreasing throughout 100 epochs
- More aggressive data augmentation is possible without degrading distillation signal
- SpecAugment for audio augmentation
- BPE dropout for text regularization (used in v3.5, likely also in v0.2)

#### Stage 4: Evaluation and Export

Evaluation uses `run_eval.py` with both short-form (single utterance) and long-form (30s+ audio) benchmarks. For mobile deployment, the model is exported to ONNX via `sherpa-onnx/scripts/whisper/export-onnx.py` with int8 quantization.

---

## 4. Comparative Analysis: Italian vs French vs English

| Metric | Italian v0.2 | French v0.2 | English v3.5 |
|--------|-------------|------------|--------------|
| Raw data (hours) | 11,235 | 22,748 | 196,000 |
| Filtered data (hours) | 6,509 | 10,402 | 98,000 |
| Filter retention rate | 57.9% | 45.7% | 50.0% |
| WER filter threshold | 20% | 20% | 10% |
| Epochs | 100 | 160 | 80 |
| Decoder layers | 2 | 2 | 2 |
| Teacher | whisper-large-v3 | whisper-large-v3 | whisper-large-v3 |
| Timestamp prob. | 50% | 50% | ~20% |
| Prev-context prob. | 20% | 20% | ~20% |
| GPUs | Jean-Zay cluster | Jean-Zay cluster | 64x H100 |
| Training time | — | — | 3 days |

---

## 5. Published Resources for Reproduction

### 5.1 Training Code
- **Repository**: https://github.com/huggingface/distil-whisper (`training/` directory)
- **Scripts**: `run_pseudo_labelling.py`, `create_student_model.py`, `run_distillation.py`, `run_eval.py`
- bofenghuang is a listed contributor to this repository

### 5.2 Training Data
- **189,000-hour multilingual dataset**: `bofenghuang/stt-pseudo-labeled-whisper-large-v3-multilingual`
  - Covers EN, FR, ES, PT, IT, DE, NL
  - Already pseudo-labeled by Whisper-Large-V3
  - WER < 20% filtered
  - 24.1 TB total size
  - Can apply stricter filters (e.g., WER < 10%) for higher quality

### 5.3 Blog Posts with Step-by-Step Guides
- **Medium**: "What I Learned from Whisper Fine-Tuning Event" (Jan 2023)
  - URL: `medium.com/@bofenghuang7/what-i-learned-from-whisper-fine-tuning-event-2a68dab1862`
  - Covers: data preparation, audio augmentation (Audiomentations), text normalization, training config
  - Full notebook available at `github.com/bofenghuang/community-events`
- **Zaion Blog**: "Notre retour d'experience sur le Whisper Fine-Tuning Event" (Feb 2024)
  - URL: `zaion.ai/en/notre-retour-dexperience-sur-le-whisper-fine-tuning-event-2/`
  - More detailed version of the Medium article
  - Won 1st prize in French and German tracks

### 5.4 Papers Referenced
1. **Distil-Whisper** (arXiv:2311.00430) — Gandhi, von Platen, Rush — Core methodology
2. **"Knowledge distillation: A good teacher is patient and consistent"** (arXiv:2106.05237) — Beyer et al. — Patient teacher concept
3. **SpecAugment** (arXiv:1904.08779) — Park et al. — Data augmentation
4. **BPE-Dropout** (arXiv:1910.13267) — Regularization technique

---

## 6. Related Work and Ecosystem

### 6.1 Papers Extending Distil-Whisper

| Paper | Venue | Innovation |
|-------|-------|------------|
| **uDistil-Whisper** (arXiv:2407.01257) | NAACL 2025 | Label-free data filtering using proxy models, uncertainty quantification, synthetic speech, and multimodal embeddings |
| **Multilingual DistilWhisper** | Telecom Paris | Language-specific gated modules on whisper-small, 35.2% boost with only 14h training data |
| **"To Distill or Not to Distill?"** | ACL 2024 | Student models can beat teacher in multi-dialectal settings |
| **Distil-Whisper-NURC-SP** (arXiv:2409.15350) | Sep 2024 | Portuguese-specific distillation, single A100 80GB |
| **"Adapting Whisper for Padding-Free Inference"** | ICASSP 2026 | Encoder attention masking + KD |

### 6.2 Fine-Tuning Repositories

| Repo | Approach | Notes |
|------|----------|-------|
| `Theodb/ASR-whisper-finetuning` | LoRA fine-tuning on Fleurs | Reduced WER from 77.4% to 45.7% on Quebec French |
| `fengredrum/finetune-whisper-lora` | LoRA for Cantonese/Mandarin | 9.63% CER on Common Voice |
| HuggingFace official guide | Full fine-tuning | `huggingface.co/blog/fine-tune-whisper` |

### 6.3 ONNX Export Pipelines

| Tool | Notes |
|------|-------|
| `sherpa-onnx/scripts/whisper/export-onnx.py` | Official, supports all Whisper/distil variants, int8 quantization |
| HuggingFace Optimum | `ORTModelForSpeechSeq2Seq.from_pretrained(model_id, export=True)` |
| Custom torch.onnx.export | Without KV-caching is 4x slower than PyTorch |

---

## 7. Reproducibility Assessment

### What IS reproducible
- Training scripts (public in `huggingface/distil-whisper`)
- Training data (189,000 hours published as HuggingFace dataset)
- Model architecture (encoder copy + 2-layer decoder)
- Pseudo-labeling pipeline (documented with exact script commands)
- Evaluation methodology (documented with benchmarks)
- Step-by-step fine-tuning guide (Zaion blog + notebook)

### What is NOT shared
- The exact **data filtering pipeline** scripts (quality filtering beyond WER threshold)
- The **case/punctuation restoration** pipeline (used customized HuggingFace Speechbox + whisper-large-v2-cv11-french)
- Exact **hyperparameters** for the Italian run (learning rate schedule, batch size, warmup — deferred to the repo but not specified per-language)
- The **audio augmentation configuration** (SpecAugment parameters, which frequency/time masks)

### Reproducibility Gap: Data Filtering

The filtering pipeline is the most critical missing piece. bofenghuang mentions:
- Removing audio-transcription mismatches (language or content)
- Removing poorly segmented utterances
- Removing missing words in scripted speech
- Excluding >10% of data in French v0.1
- This filtering "significantly reduced hallucination"

The WER threshold filter (20%) handles obvious mismatches but not subtle quality issues. The additional manual filtering likely accounts for the gap between a naïve reproduction and bofenghuang's results.

### Practical Difficulty Estimate

| Step | Difficulty | Time | Cost |
|------|-----------|------|------|
| Collect data from HuggingFace datasets | Low | Hours | Free |
| Pseudo-label with whisper-large-v3 | Medium | Days on GPU | $50-200 |
| WER filtering | Low | Hours | Free |
| Additional quality filtering | High | Weeks of iteration | Manual effort |
| Initialize student model | Low | Minutes | Free |
| Train 100 epochs | Medium | Days on multi-GPU | $500-2000 |
| Evaluate on benchmarks | Low | Hours | Free |
| Export to ONNX + int8 | Low | Hours | Free |

---

## 8. Applying This to New Models

### Strategy: Distilling Other Architectures for Italian

The Distil-Whisper methodology is specific to the Whisper architecture (encoder-decoder transformer with 30-second windows). To apply similar principles to other ASR architectures:

**For Qwen3-ASR 1.7B:**
- Qwen3-ASR uses a different architecture (CASA encoder + transformer decoder with conv frontend)
- The pseudo-labeling approach transfers: run teacher inference → filter by WER → train student
- But `create_student_model.py` cannot be used directly (it assumes Whisper architecture)
- Would need architecture-specific student initialization
- `ilmina/qwen3-asr-1.7b-sherpa-onnx` (published 2026-05-17) already provides an ONNX export

**For Parakeet TDT:**
- Parakeet uses a transducer architecture (not encoder-decoder)
- Knowledge distillation for transducers is less studied than for Whisper
- The pseudo-labeling + WER filtering approach still applies for data curation
- NVIDIA has not published distillation scripts for Parakeet

### Language Transfer Trick
From the Distil-Whisper training README: initialize the student from `distil-whisper/distil-large-v3` instead of raw Whisper. This gives the decoder a warm start that already knows how to be compact. Then use the target-language teacher for pseudo-labeling.

---

## 9. Sources

### Primary Sources
- `huggingface.co/bofenghuang/whisper-large-v3-distil-it-v0.2` — Italian model card
- `huggingface.co/bofenghuang/whisper-large-v3-distil-fr-v0.2` — French v0.2 model card
- `huggingface.co/bofenghuang/whisper-large-v3-french` — French fine-tune model card
- `huggingface.co/bofenghuang/whisper-large-v3-french-distil-dec16` — French distil model card
- `github.com/huggingface/distil-whisper` — Training scripts
- `arxiv.org/abs/2311.00430` — Distil-Whisper paper
- `arxiv.org/abs/2106.05237` — Patient teacher paper
- `medium.com/@bofenghuang7/what-i-learned-from-whisper-fine-tuning-event-2a68dab1862` — Fine-tuning blog
- `zaion.ai/en/notre-retour-dexperience-sur-le-whisper-fine-tuning-event-2/` — Zaion blog

### Secondary Sources
- `arxiv.org/abs/2407.01257` — uDistil-Whisper (NAACL 2025)
- `arxiv.org/abs/2409.15350` — Distil-Whisper-NURC-SP (Portuguese)
- `github.com/Theodb/ASR-whisper-finetuning` — LoRA fine-tuning
- `huggingface.co/datasets/bofenghuang/stt-pseudo-labeled-whisper-large-v3-multilingual` — Training dataset
- LinkedIn posts by bofenghuang (Dec 2023, Apr 2025)
- HuggingFace profile: `huggingface.co/bofenghuang`
