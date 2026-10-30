# Model Scout Report: 2026-10-29 (akhaliq/nemotron-3.5-transcribe-mini-64e, single-model follow-up)

**Scope:** single-model assessment of https://huggingface.co/akhaliq/nemotron-3.5-transcribe-mini-64e, requested the day after the dedicated Nemotron 3.5 Transcribe sweep (2026-10-28-nemotron35-transcribe-sweep.md). Sources: the model card (raw README, read 2026-10-29), the HuggingFace API file tree with LFS blob sizes (read 2026-10-29, `lastModified` 2026-10-29T06:04:47Z), and web search via tavily for third-party reaction. Baseline not re-derived: no relevant commits since the 2026-10-28 sweep recorded it (sherpa-onnx v2.0.1, catalog index-1.16.0.json).

## Executive Summary

The repo is the first smaller derivative of the 30B Nemotron Transcribe, filling exactly the gap yesterday's watch list flagged ("no smaller variant exists"). But it is a community REAP prune of the MoE experts, not an NVIDIA distill, and "mini" is relative to a 67.5GB teacher: the smallest usable speech artifact is still 10GB of backbone plus a 4.34GB perception encoder. Nothing changed for the phone (no ONNX, no sherpa family, AWQ W4A16 plus hybrid Mamba-2 MoE, far over every on-device bar). The remote-offload story improved: this is the first Nemotron Transcribe variant that plausibly serves from a consumer GPU instead of an H100, though the card's "10GB / fits a 12GB GPU" headline does not survive its own file listing (unique weights ~14.35GB; see the size audit below). Quality evidence is thin and self-reported: 300 short clips, en/de/es only, no Italian, 0 downloads at read time.

## Finding Card: akhaliq/nemotron-3.5-transcribe-mini-64e

- **Source:** https://huggingface.co/akhaliq/nemotron-3.5-transcribe-mini-64e (published 2026-10-29, 0 downloads, 10 likes at read time); demo Space akhaliq/nemotron-transcribe-mini; generated with the "ML Intern" tooling (badge in the card)
- **What it is:** a one-shot REAP prune (Router-weighted Expert Activation Pruning, arXiv:2510.13999) of nvidia/NVIDIA-Nemotron-3.5-Transcribe-30B-A4B. Routed experts cut 128 to 96 or 64, calibrated on ~2,500 multilingual FLEURS transcripts (8 languages), zero retraining or distillation. Audio encoder, speaker encoder, and CTC timestamp head untouched. Base-model metadata and OpenMDW-1.1 license inherited from the teacher
- **Variants on disk (API blob sums, verified 2026-10-29):**
  - fused-96e: 52.19GB (BF16, 96 experts)
  - fused-64e: 36.86GB (BF16, 64 experts)
  - fused-64e-w4a16-g64: 28.73GB directory, unique weights ~14.35GB (see audit)
  - backbone-64e-w4a16-g64: 10.03GB (text-only, no audio)
  - backbone-96e / backbone-64e: 48.48GB / 33.79GB (text-only BF16)
  - eval/ and scripts/ carry the raw transcripts and the exact WER, speed, and quantization scripts (good practice)
- **Languages:** 40 inherited unchanged per the card; the prune was calibrated on 8 FLEURS languages and evaluated on en/de/es only
- **Format:** NeMo SpeechLM safetensors; AWQ W4A16 (group 64) on the backbone with router, embeddings, lm_head, and Mamba scalars at full precision. NO ONNX, TFLite, or litertlm file anywhere in the tree (checked programmatically)
- **sherpa-onnx compatible:** no, same exclusion as the teacher (hybrid Mamba-2 Transformer MoE with MTP; none of the seven external import families apply)
- **Quality (card table, 300 clips FLEURS en/de/es + LibriSpeech test-clean, greedy):** teacher mean 4.12; 96e 4.15 (+0.7% rel); 64e 5.08 (+23% rel); 64e-W4A16 5.16 (+25% rel). The quantization itself is nearly free on top of pruning (+0.08 absolute over 64e BF16). Long-form at the claimed 1-hour limit is unmeasured (one ~90s clip only). Self-reported, no third-party replication found; no Italian row exists
- **Speed (card table, H200, vLLM greedy):** 64e-W4A16 mean latency 0.19s, single-stream RTFx 50.4, burst RTFx x8 171.3, versus teacher 0.24s / 39.3 / 142.1. The pruned 4-bit model is FASTER than the teacher
- **Score:** 4.0/10 as an on-device candidate (quality 2: no tradition-language evidence beyond en/de/es and not loadable anyway; size 2; on-device 1; breadth 4; importability 1; maintenance 3: single-artifact upload, reproducible scripts, 0 downloads)
- **Verdict:** NOT PHONE-RELEVANT; REMOTE-OFFLOAD CANDIDATE with a corrected hardware floor (16GB class minimum, 24GB comfortable, not the claimed 12GB)

## Size audit: the "10GB, fits a 12GB consumer GPU" claim fails its own file listing

The card headline says "From 67.5GB to 10GB" and the layout section calls fused-64e-w4a16-g64 a "10GB servable 4-bit speech model" that "fits a 12GB consumer GPU". The API blob sizes say otherwise:

- The directory ships 28.73GB because it carries the weights in two layouts: `awq_model.safetensors` (10,014,396,088 bytes, byte-count-identical to the standalone 10.03GB backbone) plus `perception.safetensors` (4.34GB), AND an HF-style sharded copy `model-00001-of-00002.safetensors` (10.01GB) + `model-00002-of-00002.safetensors` (4.34GB) referenced by `model.safetensors.index.json`
- The 10.01GB figure is the W4A16 TEXT BACKBONE alone; the speech model additionally needs the 4.34GB perception encoder. Unique servable weights are approximately 14.35GB
- 14.35GB of weights cannot fit 12GB of VRAM before KV cache and activations; the card's own vLLM command (`--gpu-memory-utilization 0.9 --max-model-len 8192`, no offload flags) loads everything on GPU. Realistic floor is a 16GB card, comfortable at 24GB
- The BF16 variants are internally consistent (96e at 52.19GB and 64e at 36.86GB track the expert counts against the 67.5GB teacher), so the 12GB line looks like a backbone-only arithmetic slip, not deception

## Remote-offload implications (the actionable axis)

Yesterday's report recommendation #4 recorded that vLLM serves the teacher via the NeMo plugin (0.32.0, chat completions with base64 audio) or the merged #64282 transcriptions endpoint (post-0.32 main), on 1x H100 80GB. This derivative moves the floor to the consumer-GPU class:

- The card's verified vLLM recipe serves fused-64e-w4a16-g64 on vllm==0.32.0 + the NeMo Speech plugin, exactly the stack already recorded for the teacher; an offload client written against the teacher's endpoint shape works against this unchanged
- scripts/ includes `wer_killswitch_sglang.py` alongside the vLLM one, so an SGLang serving path is at least explored by the author
- The cost: +25% relative WER versus the teacher on their own eval (4.12 to 5.16 mean), thin eval, community artifact with no adoption yet. For a self-hosted offload tier where the phone's own models remain the primary path, that trade (H100 to a 16-24GB card already in the house) is reasonable to SPIKE, not adopt
- 96e is the quality-preserving option (4.15 mean, within noise of the teacher) but at 52GB it saves nothing meaningful on hardware

## Relation to yesterday's watch list

- "A NVIDIA-announced distill of the 30B Transcribe (none exists today)": still true. This is a community expert-prune with zero retraining, not a distill, and the smallest SPEECH artifact remains 10GB-class; no phone-tier model appeared
- The method is the transferable signal: REAP one-shot pruning kept 96 of 128 experts at +0.03 mean WER, calibrated on one FLEURS pass. Nothing we ship is MoE, so there is no direct application today, but it is the likely template for every future MoE speech release that outlives its flagship
- Reinforced lesson (third time this week): read the file listing, not the headline. The quantization-recipe lesson from sherpa #4127 and the CoreML "lite" naming trap from yesterday now have a sibling: the "10GB" claim counts the backbone only

## Verdict Table (delta over 2026-10-28 report)

| Candidate | Verdict | One-line reason |
|---|---|---|
| akhaliq/nemotron-3.5-transcribe-mini-64e (W4A16) | NOT PHONE-RELEVANT / OFFLOAD-SPIKE | 14.35GB unique weights, no ONNX or sherpa family, AWQ+Mamba MoE; first consumer-GPU-servable Nemotron Transcribe (16GB+ real floor, not the claimed 12GB), +25% rel WER vs teacher on a thin en/de/es eval |

## No Change

- Everything else in the 2026-10-28 verdict table stands: parakeet-ultra validation remains the top on-device action; sherpa-onnx v2.0.1 remains the newest tag; no new litert-community artifacts appeared relevant to this scope (not re-swept; single-model scope)
