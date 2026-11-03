# TASK-953 French lane, distil tier: bench + convert (whisper-large-v3-french-distil-dec16)

**Date:** 2026-11-03 (same night, after the heavy-tier probe) · **Scope:** the lighter French candidate the heavy report recommended scouting: `bofenghuang/whisper-large-v3-french-distil-dec16` (MIT), decoder reduced from 32 to 16 layers and distilled from `bofenghuang/whisper-large-v3-french`. Same conversion pipeline, same 40-clip FLEURS fr_fr protocol, workspace `/var/tmp/fr2-lane` (the fr/fi/nl/de lanes untouched). Nothing was committed, pushed, published, or cataloged; the HF upload, catalog entry, and device pass stay gated on the maintainer.

## TL;DR

The distil WINS the whole lane outright: **6.54 WER vs Turbo's 8.59 (23.9% relative, clearing the ~20% fi precedent the heavy tier missed) and vs the heavy tier's 7.10** (it beats the model it was distilled from, by 7.9%), at a 1.35GB int8 payload (under the 1.4GB target, 24% smaller than the heavy) and RTF 0.942 measured under the same loaded-machine conditions where the heavy measured 2.415 (2.6x faster than the heavy; the same architecture class measured 1.465 idle on the fi lane, so the idle figure here is estimated around 0.6). Recommendation: **GO: the distil replaces the heavy tier as the French catalog candidate**, and the heavy lane's "qualified GO, below the fi bar" is superseded (the distil dominates it on every axis: WER, size, speed). Device pass and publication remain the gated remainder.

## Comparison table (FLEURS fr_fr test, 40 clips, jiwer-normalized WER, clip mode, sherpa-onnx 2.0.1, tail_paddings 1000, Apple M3 Pro, 4 threads)

| Model | WER | RTF | int8 download | Provenance |
|---|---|---|---|---|
| Whisper small (built-in) | 15.03 | 0.291 | ~0.36GB | quality-matrix-2026-11-01.json (2026-11-01 run, idle machine) |
| Whisper Turbo (built-in) | 8.59 | 0.43 | 1.04GB | quality-matrix-2026-11-01.json; THE BAR |
| whisper-large-v3-french (heavy tier) | 7.10 | 2.415 | 1.78GB | this night, loaded machine (`/var/tmp/fr-lane/bench-fr.json`, `eval/results/fleurs40_large-fr_20261103.json`) |
| **whisper-large-v3-french-distil-dec16 (candidate)** | **6.54** | **0.942** | **1.35GB** | this session (`/var/tmp/fr2-lane/bench-fr-distil.json`, copy at `eval/results/fleurs40_fr-distil_20261103.json`) |

Every row decodes the identical clip set (audio_s 447.5). The incumbent rows are the 2026-11-01 matrix measurements; the heavy and distil rows were both measured tonight on the unmodified `eval/fleurs_bench.py`.

**RTF caveat (same class as the heavy row):** the distil bench ran with load average ~15 falling to ~11 on this Mac (a concurrent lane agent and other session work); 0.942 is an upper bound, not a load-normalized figure. Cross-session scaling argues the idle figure sits around 0.6: tonight's distil-to-heavy wall ratio is 421.4s vs 1080.6s (0.390) under the same load, and the identical heavy architecture measured RTF 1.465 on the fi lane's idle machine (1.465 x 0.390 = 0.57); an independent scaling via the fi idle wall time gives ~0.72. The WER is decode-content and unaffected by load. Relative speed claims below use same-night loaded measurements only (distil vs heavy), never the matrix's idle turbo row.

Card context (claims, not our measurements, author's normalization of numbers-to-text and punctuation stripping): the card reports FLEURS fr_fr 5.03, Common Voice 13 7.18, MLS 3.57, VoxPopuli 8.76, African Accented French 3.90 for dec16 (https://huggingface.co/bofenghuang/whisper-large-v3-french-distil-dec16). Our 6.54 on the stricter jiwer protocol sits proportionally above their FLEURS figure exactly as the heavy's 7.10 did.

## What was run this session

1. Checkpoint download (HF sha 16bd0218) and tokenizer verification against the heavy lane's copies.
2. The conversion pipeline (era-matched toolchain, identical to fi/fr heavy) in `/var/tmp/fr2-lane`.
3. Validation: three-way agree-check, sherpa probes, tiled probe, timestamps, fp32-vs-int8 divergence.
4. The bench: `eval/.venv/bin/python eval/fleurs_bench.py --langs fr_fr --clips 40 --kind whisper --encoder .../large-fr-distil-encoder.int8.onnx --decoder .../large-fr-distil-decoder.int8.onnx --tokens .../large-fr-distil-tokens.txt` (WER 6.54, RTF 0.942, wall 421.4s).

## Conversion record

**Source:** `bofenghuang/whisper-large-v3-french-distil-dec16` (MIT, sha 16bd02185bdaa6b00fb4b0deb46e47ac1b754b8e). Architecture per config.json: full large-v3 encoder (32 layers), HALF-DEPTH decoder (16 layers), 1280 d_model, 20+20 heads, 128 mels, 1,121,790,720 parameters. HF safetensors 4.49GB fp32 (sha256 7b8b42616a5b926db657007a005e0b736cc704d2df90fbc6cf3586f207804fee); the repo also ships the author's openai-format `original_model.pt` (2.25GB fp16, sha256 afef6e50cefac6cc9b5c7804ab71dd0e6e561b88a5c23586a4118e2ecd70fea2), used as the independent agree-check reference.

**Toolchain (era-matched, unchanged from fi/fr heavy):** torch 2.3.1, onnx 1.16.2, onnxruntime 1.18.1, numpy 1.26.4, openai-whisper 20250625, transformers 4.44.2; export via the patched copy of sherpa's `export-onnx-with-attention.py` (srclib af2d8d81, v2.0.1) carried into `/var/tmp/fr2-lane/export-scripts` (fr-lane's copies untouched). Export shape identical to fi/fr heavy: -attn decoder (4 outputs incl. `cross_attention_weights`), per-channel int8 on the decoder, MatMul-only int8 on the encoder, 128-mel branch, `large` external-data branches.

**Tokenizer verification (the fi landmine, re-checked for this checkpoint):** `vocab.json`, `added_tokens.json`, and `merges.txt` are BYTE-IDENTICAL to the heavy lane's files (sha256 6788c80b / 3c51f66c / 2df2990a; the heavy lane verified those byte-identical to stock `openai/whisper-large-v3`). The `<|yue|>`-at-50358 layout confirmed directly (`<|0.00|>` is 50365 in this repo, the HF +1 shift). Same fix applied unchanged: token_embedding rows permuted into openai id order, dropping `<|yue|>` (n_vocab 51866 to 51865).

**Half-depth decoder (the one architecture delta, handled by the toolchain as expected):** the conversion script's key mapping is layout-driven, so 16 decoder layers needed no code change; `dims` came out `n_mels 128, n_audio_ctx 1500, 32+16 layers, n_vocab 51865` and the export traced cleanly (`Cross-attention weights shape [1, 160, 3, 1500]`). The one place depth shows up mechanically: `n_alignment_heads` is 160 (openai-whisper's default last-half set, layers 8..15 all 20 heads) because the distilled checkpoint ships no curated alignment-head list (its `generation_config.json` has none, unlike the heavy repo); same known deviation class as fi/fr, affecting timestamp head count only, not WER. A second depth effect worth recording: the exported ENCODER graph embeds the decoder's cross-attention k/v projections (the TensorCache design), so the encoder int8 file is also 52.5MB smaller than the heavy's (714MB vs 767MB); the file sizes decompose exactly as the architecture predicts.

**Conversion sanity (run and read this session):** encoder positional embedding std 0.6760 (the sinusoid), decoder positional std 0.0092 (the learned table; both copied verbatim from the checkpoint); decoder layer indices 0..15 contiguous.

**One repo-layout deviation (handled, documented):** the distil's safetensors OMITS `proj_out.weight` entirely, unlike the heavy repo which ships it explicitly. That is transformers' tied-weight serialization (tied weights are skipped), so the omission itself is the tying evidence; openai-whisper computes logits from the token embedding, which is exactly the tied behavior. Verified empirically: the agree-check's A==C leg (below) matches the author's own openai-format export verbatim, which is impossible if the projection handling were wrong.

**Agree-check (same three-leg form as the heavy lane):** on two FLEURS fr probes, greedy. Our permuted `large-fr-distil.pt` matches the author's own `original_model.pt` VERBATIM on both probes; the HF transformers pipeline agrees verbatim on probe0 and differs on probe1 by one word-form ("de leur sport" vs "de leurs sports"), the same pipeline decode-path difference the heavy lane saw. The verbatim A==C match also closes the tied-projection question above.

**Validation (all run and read this session):**
- sherpa int8 decode of the probes matches the .pt decodes; `OfflineRecognizer.from_whisper` in eval/.venv, app decode shape (tail_paddings 1000).
- Tiled 60s probe: the 30s window decodes 71 words across 4 sentences; no premature EOT (the per-tensor-int8 failure mode).
- fp32 vs int8 word divergence: 0.00% on the 19-word probe AND 0.00% on the 71-word tiled window (better than the heavy's 0.00%/1.39%).
- Timestamps (the -attn contract): 34/34 tokens carry real DTW timestamps, monotonically increasing.
- Encoder metadata `version 2`, `n_mels 128`, `n_text_layer 16`, `model_type whisper-large-fr-distil`; decoder has the 4 outputs including `cross_attention_weights`.

**Artifacts** (workspace `/var/tmp/fr2-lane`, kept for the gated remainder; the fp32 graphs and HF checkpoint also live there):

| File | Bytes | sha256 |
|---|---|---|
| `export-run/large-fr-distil-encoder.int8.onnx` | 714,129,993 | f6a83a5d2bf66f10dee0a573e99d6795937e84a4495a9e8ef5e354a3daaf96ea |
| `export-run/large-fr-distil-decoder.int8.onnx` | 639,822,942 | c3af005bc01a263830434fbf5d7a3c7999323bd13f822e749334f1179e84c062 |
| `export-run/large-fr-distil-tokens.txt` | 816,730 | b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126 (byte-identical to the standard whisper tokens pinned in arabic.json and in the fi/fr heavy entries) |

Total int8 payload 1,354,769,665 bytes (1.35GB): 0.762x the heavy tier's 1.78GB (24% smaller), 1.30x Turbo's 1.04GB. Estimated device footprint delta vs the heavy (estimate, must be re-proven): the fi device leg measured a 705MB RAM load delta for the 1.78GB payload; scaling by payload size puts this one around 540MB, still invasive on an 8GB device, and RTF on the RMX3853 should land roughly half the heavy's ~3.1 CPU figure since the encoder is unchanged but the decoder halves.

**Toolchain deviations from the fr heavy lane:** none in the pipeline. Operational only: (1) the export ran under `python -I` with the script dir injected into `sys.path` explicitly (isolated mode strips it; the fr lane ran without `-I`), (2) the conversion script reads the head counts from `config.json` instead of hardcoding 20, and (3) the tied-projection check accepts the distil's tied-by-omission safetensors layout as described above.

## GO/NO-GO

**GO: the distil is the French catalog candidate; the heavy tier is superseded.** The verdict rule's condition (a) is met outright: 6.54 matches-and-beats the heavy tier's 7.10, and condition (b) is met with room (well below the ~8.2 band, payload 1.35GB under the 1.4GB target, 2.6x the heavy's decode speed under the same-night load). Beyond the rule, the margin vs Turbo is 23.9% relative, clearing the ~20% fi precedent the heavy tier missed: this candidate is simultaneously better than every measured incumbent AND cheaper than the heavy in every cost dimension. License MIT, conversion lane the proven whisper one, agree-check verbatim against the checkpoint author's own export, int8 divergence 0.00%. What is NOT yet proven is the device leg (the estimate above says invasive-but-less-than-heavy; the RMX3853 pass must confirm before any catalog copy). If the maintainer declines the lane anyway, no lighter French whisper fine-tune of this quality class was identified tonight; the lane would close as declined rather than NO-GO on evidence.

## Gated remainder (needs the maintainer)

1. HF upload (publish the three pinned files above, e.g. `pantinor/sherpa-onnx-whisper-large-v3-french-distil-dec16`).
2. Community-catalog entry (french.json) and catalog plumbing; decide the heavy-tier disposition (this report argues do not publish the heavy: dominated by the distil on every axis).
3. Device validation pass on the RMX3853 (RAM delta and RTF; the heavy's 705MB/~3.1 figures bound it from above).

Session artifacts: bench JSON copied to `eval/results/fleurs40_fr-distil_20261103.json`; workspace scripts and logs under `/var/tmp/fr2-lane/` (convert_to_whisper_pt.py, agree_check.py, validate_sherpa.py, shas.txt, logs/).
