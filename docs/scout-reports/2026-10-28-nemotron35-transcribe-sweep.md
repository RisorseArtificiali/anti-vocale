# Model Scout Report: 2026-10-28 (Nemotron 3.5 Transcribe dedicated sweep)

**Scope:** dedicated sweep triggered by today's nvidia/NVIDIA-Nemotron-3.5-Transcribe-30B-A4B release. Five axes: (1) Nemotron 3.5 Transcribe family variants and ONNX plans, (2) Qwen3-ASR family, (3) new small audio-LLM/SALM models of the last ~2 months, (4) sherpa-onnx updates since v2.0.1, (5) vLLM serving compatibility for the remote-offload feature. Sources: HuggingFace API and raw model cards (all read 2026-10-28), GitHub API (sherpa-onnx releases/commits/issues/PRs, vLLM PRs, NeMo Speech docs), all URLs inline. Baseline re-verified: `.sherpa-version` pins **sherpa-onnx v2.0.1** (commit af2d8d81), LiteRT-LM **0.17.1** (`gradle/libs.versions.toml`), community catalog at `index-1.16.0.json` (33 entries), previous report 2026-10-24-769-models.md.

## Executive Summary

The 30B trigger model is server-class (1x H100 80GB, offline, no ONNX, no smaller variant announced): for the phone it matters only through the remote-offload lens, and there the news is good, vLLM merged native support with an OpenAI-compatible `/v1/audio/transcriptions` endpoint the same day (PR #64282, merged 2026-10-28). The two genuinely actionable on-device findings are elsewhere: **moondream/parakeet-ultra**, a post-trained drop-in replacement for our primary Parakeet TDT 0.6b v3 (same architecture, tokenizer and size, CC-BY-4.0) whose first Android CPU int8 ONNX export appeared today, and **nvidia/nemotron-labs-unified-asr-0.6b** (2026-10-27), one 0.6B checkpoint covering both offline and streaming across 25 European languages, with NVIDIA claiming it beats our two incumbents in their own modes. The runtime it needs (sherpa Parakeet Unified, PR #3575) is already in our v2.0.1 pin, but the multilingual checkpoint is degraded in it today (issue #4127, opened today, documents the two defects and a quantization recipe). Also new: litert-community shipped a device-proven Qwen3-ASR-1.7B `.litertlm` (2.7GB, over our bar but measured on our exact runtime version 0.17.1), LiquidAI released LFM3-Audio-2B with official ONNX exports, and a 100M streaming Sortformer diarizer landed on LiteRT with a Kotlin sample, directly relevant to the app's speaker pass.

## ASR Model Findings

### nvidia/NVIDIA-Nemotron-3.5-Transcribe-30B-A4B (the trigger)
- **Source:** https://huggingface.co/nvidia/NVIDIA-Nemotron-3.5-Transcribe-30B-A4B (released 2026-10-28, 338 downloads at read time)
- **Size:** 34B total / ~4.3B active (Lightning 30B-A3B MoE backbone with ~3.5B active + ~800M joint audio encoder + ~99M speaker encoder); BF16, FP8 and NVFP4 variants
- **Languages:** 40; card quality matrix (as far as the fetched page rendered): Leading for cs, de, en, fr, hi, it, ko, pl, pt, ru, sk, es (regional variants); Production-ready for bg, hr, da, et, fi, hu, ja, lv (remaining rows not captured)
- **Last updated:** 2026-10-28
- **Format:** NeMo SpeechLM checkpoint (model_type `nemo_speechlm`); NO ONNX export in the repo or on the roadmap found
- **sherpa-onnx compatible:** no. Hybrid Mamba-2-Transformer MoE with MTP and a DFlash2 diffusion drafter; far outside sherpa's supported architecture set
- **Import path:** none on device; remote-offload target only (see Framework/vLLM section)
- **Quality assessment:** card-captured Oct 12, 2026 numbers: multilingual Open ASR Leaderboard (7 languages) mean WER 4.62 vs Qwen3-ASR-1.7B 6.49; long-form 10.98 vs Parakeet-TDT-0.6B-v3 13.37; English 4.71 vs Qwen3-ASR-1.7B 4.94; multi-speaker cpWER 15.80 (AMI/CALLHOME/Mixer6/NOTSOFAR-1); Preference-ASR 7.56 default / 6.16 instructed. Vendor self-reported, no third-party replication yet
- **Score:** not scored (excluded on size)
- **Recommendation:** NOT PHONE-RELEVANT as a model; relevant as the flagship candidate for the experimental remote-offload feature
- **Effort:** n/a on device
- **Notes:** explicitly an OFFLINE model ("Internal audio chunking does not make it a real-time streaming ASR service"); speaker-attributed transcription with `<spk:N>` tags, contextual transcription via "Context terms:", speech translation, LID, audio QA. **Name-collision confirmed:** our streaming built-in is `nvidia/nemotron-3.5-asr-streaming-0.6b` (FastConformer RNNT); the new model shares the "Nemotron 3.5" prefix but is a different class (SALM speech LLM). Any docs/UI copy about "Nemotron 3.5" should disambiguate. Smaller variants: none announced, none teased in the card, none found under the nvidia org (searches `nemotron transcribe`, `nemotron salm`, author=nvidia + asr, read 2026-10-28). Community quantizations (`nativ-community/*-EA-{4,5,6,8}bit,mxfp4,mxfp8,nvfp4`, all 2026-10-28) remain 30B-class: 4-bit of 34B is ~17GB by arithmetic on the stated parameter count, far over any phone bar.

### nvidia/nemotron-labs-unified-asr-0.6b
- **Source:** https://huggingface.co/nvidia/nemotron-labs-unified-asr-0.6b (released 2026-10-27)
- **Size:** 600M (same class as our Parakeet TDT 0.6b: stock-int8 in the ~640MB range by family analogy; not yet exported)
- **Languages:** 25 European, same set as parakeet-tdt-0.6b-v3; tradition set covered: en de es fr it nl pl ru el fi uk (11 of 19)
- **Last updated:** 2026-10-27
- **Format:** NeMo `.nemo` only; sherpa export path exists for the English sibling (`scripts/nemo/parakeet-unified-en-0.6b/export_onnx_streaming.py`, merged with PR #3575 on 2026-05-09, hence present in our v2.0.1 pin); no published export of the multilingual checkpoint
- **sherpa-onnx compatible:** partially, today. The Parakeet Unified runtime loads it (issue #4127 author ran it), with two documented defects and a quantization caveat (details under Framework Updates)
- **Import path:** potential built-in catalog replacement for BOTH Parakeet TDT v3 (offline) and the Nemotron streaming entry; external-family route uncertain because the language prompt needs runtime support (the TRANSDUCER family's language wiring does not cover this model's prompt mechanism yet)
- **Quality assessment:** NVIDIA's own comparison (card figures): outperforms parakeet-tdt-0.6b-v3 offline and nemotron-3.5-asr-streaming-0.6b in most streaming scenarios at 160ms algorithmic latency. Per-language tables are in card FIGURES (images), not text, so per-language numbers are unverified. Language-ID prompt conditioning is new relative to our streaming model and directly addresses the Parakeet auto-LID latching problem (our research_parakeet_no_language_pin finding)
- **Score:** 8.4/10 (quality 4, size 5, on-device 4, breadth 4, importability 3, maintenance 5)
- **Recommendation:** WATCH, top priority. The single most strategic model in this sweep: one checkpoint could replace two built-ins and cut the catalog's streaming/offline duplication. Blockers: sherpa issue #4127 (left-context zero-padding corrupts normalization after endpointing resets: 52.1 WER with endpointing, 14.9 without, 8.0 in a NeMo-faithful loop; language prompt input not wired) and the unpublished multilingual export script
- **Effort:** medium-high (export script derivation + sherpa fixes + our per-language eval)
- **Notes:** streaming is BUFFERED, not cache-aware: left context recomputed every step, computationally heavier than our current cache-aware Nemotron streaming; the card itself recommends not dropping left context below 2s. Encoder initialized from parakeet-tdt-0.6b-v3; trained on Granary with v3's data composition

### moondream/parakeet-ultra (+ first Android ONNX export)
- **Source:** https://huggingface.co/moondream/parakeet-ultra (base model, known since our 2026-10-13 scout) and https://huggingface.co/abid1010/parakeet-ultra-onnx (NEW, 2026-10-28)
- **Size:** same as v3: 0.6B; the Android int8 export is encoder 690.5MB + decoder_joint 50.5MB + joint 5.1MB + tokens.txt (~747MB total, converter's SHA-256 table); optional Qualcomm Hexagon NPU builds (v73/v75/v79, 592.5MB each)
- **Languages:** same 25 European as v3 (same tokenizer, output conventions preserved)
- **Last updated:** export 2026-10-28
- **Format:** ONNX int8, TDT with duration head [0,1,2,3,4]; the exact file shape sherpa's nemo TDT packages use (decoder_joint + joint + tokens.txt)
- **sherpa-onnx compatible:** by shape yes (TDT family; our Transducer external family already carries a TDT fine-tune, Orukeet); the specific conversion is community-made and unvalidated by anyone we know
- **Import path:** built-in catalog replacement for the primary Parakeet TDT v3 entry (sha256-pinned like today's entries), or community catalog TRANSDUCER entry for A/B
- **Quality assessment:** the strongest published per-language evidence of any candidate in this sweep. Card tables (same files, Open ASR Leaderboard pipeline, September 2026 normalizers): FLEURS 25-language mean 9.55 vs v3's 11.62; **Italian 2.00 vs 2.61**; German 3.61 vs 4.13; Spanish 2.72 vs 3.12; French 4.32 vs 4.81; Ukrainian 4.75 vs 5.94; English Open ASR 7-set 5.80 vs 6.26; MUSAN noise 9-condition average 5.82 vs 6.72; TED-LIUM long-form 1.94 vs 2.71; and faster than v3 (9,743x vs 6,005x RTF on LibriSpeech test-clean, Photon GPU vs NeMo bf16). Vendor self-reported but per-language and same-pipeline, and our eval/ harness can verify every row. Note our shipped int8 v3 measured ~4.3 FLEURS it in our harness vs the card's 2.61 fp32-class row: absolute numbers are not comparable across pipelines, the DELTA is what matters
- **Score:** 9.2/10 (quality 5, size 5, on-device 4, breadth 4, importability 5, maintenance 4); the score is a ceiling pending conversion validation
- **Recommendation:** IMPORTABLE NOW, validate first: run the abid1010 int8 export through `eval/fleurs_bench.py` (it + a multilingual sample) and, if it holds, our real WhatsApp-clip eval against the shipped v3 int8. Quantization recipe is a known failure mode for this generation of models (see the #4127 lesson: the wrong recipe cost 2.9 WER on the unified model)
- **Effort:** low-medium (conversion exists; validation + catalog pinning + possible built-in swap)
- **Notes:** CC-BY-4.0, same as v3. Also newly converted today: lipik GGUF variants (no runtime in the app) and smdesai CoreAI (Apple). The 2026-10-13 scout flagged Ultra as headline; the on-device export is what converts that headline into an actionable candidate

### Qwen3-ASR family (0.6B shipped built-in; 1.7B now available three ways)
- **Source (official):** https://huggingface.co/Qwen/Qwen3-ASR-0.6B and https://huggingface.co/Qwen/Qwen3-ASR-1.7B (2026-01-30) with `-hf` variants (2026-07-22); no 4B ASR exists. Qwen3-ForcedAligner-0.6B (timestamps, 11 languages) also released
- **Languages (official card):** 30 languages + 22 Chinese dialects: zh en yue ar de fr es pt id it ko ru th vi ja tr hi ms nl sv da fi pl cs fil fa el hu mk ro. Tradition set: 17 of 19 (all except uk, he), including the languages Parakeet v3 lacks (hi fa ar vi ko zh tr)
- **New since the last scout, three artifacts:**
  1. **litert-community/Qwen3-ASR-1.7B** (2026-10-25, https://huggingface.co/litert-community/Qwen3-ASR-1.7B): `.litertlm` 2.69-2.70GB (int8 LM weights + fp16 24-layer encoder + int8 embedder), Apache-2.0. **Measured on LiteRT-LM 0.17.1, exactly our pinned version** (15s variant loads on 0.17.1 too; measured extensively on 0.18.0). Device-proven on Galaxy S26 and Pixel 8a via the Kotlin Conversation API (`litertlm-android`, our exact dependency). On-device FLEURS (50 clips/lang): English WER 4.35, Mandarin CER 6.19, Japanese CER 5.84, near-identical to the fp32 original. Forced language via a `language X<asr_text>` prefix item. Memory: S26 CPU peak 4.10GB (GPU OpenCL 2.41GB); Pixel 8a CPU is SLOWER than real time on the 30s file (8.39s for a 6.4s clip), GPU acceptable. Score 7.0/10 (quality 4, size 2, on-device 3, breadth 5, importability 3, maintenance 5). WATCH/evaluate: size is over our ~2GB bar (LLM-class, same class as Gemma E4B), and the app needs new wiring (the answer format `language <name><asr_text><transcript>` differs from the Gemma path; Qwen3-ASR is a built-in-only family, NOT one of the seven external import families, so a catalog entry is not possible today)
  2. **solavr/sherpa-onnx-qwen3-asr-1.7B-int8** (2026-09-13, https://huggingface.co/solavr/sherpa-onnx-qwen3-asr-1.7B-int8): mixed int8/FP32 ONNX for sherpa's offline Qwen3-ASR interface (the same interface our built-in 0.6B uses). Converter-verified: LibriSpeech-Long English aggregate WER 3.95 (RTF 0.743, 2 desktop threads), one ja clip; one repetition-loop regression fixed by keeping decoder self-attention in fp32. Apache-2.0. **4,191MB on disk, 3.4GB peak working set** (Windows reference): over our bar; decoder capped at 512 tokens (utterance-level). Score 5.4/10. WATCH behind the .litertlm arm
  3. Ecosystem burst (2026-10-25..28): fine-tunes (vi translation, Dutch, indic22, Arabic GRPO, Persian, Hebrew "Sabich"), QNN/RK/CoreML exports, GGUF. Signal: the 1.7B is becoming the community's fine-tune base. GGUF/QNN/RK/CoreML forms are all unusable in our runtime
- **Recommendation:** the .litertlm 1.7B is the highest-quality-per-runtime-risk multilingual upgrade that runs on our EXISTING LiteRT-LM dependency; treat as a possible flagship "large tier" rather than a default (size, CPU peak). The sherpa 1.7B waits for a slimmer export
- **What would change our mind (for the sherpa arm):** a fully-int8 export without fp32 attention projections and with quantized KV, landing under ~2.2GB peak with on-device proof

### LiquidAI LFM3-Audio-2B
- **Source:** https://huggingface.co/LiquidAI/LFM3-Audio-2B (release week 2026-10-27) and official ONNX: https://huggingface.co/LiquidAI/LFM3-Audio-2B-ONNX
- **Size:** ~2B (edge-targeted); int8 ONNX would land ~2GB class (not measured)
- **Languages:** 18 for ASR/TTS/translation (exact list not fully captured in scrape); the multilingual eval covers en es pt de fr it zh ja ko. **LiveASR streaming is English-only**; offline ASR via system prompt `Perform ASR in <language>.`
- **Format:** official ONNX sub-graphs (encoder, decoder, depth decoder, LiquidCodec tokenizer) with GroupQueryAttention CPU/WebGPU fallback instructions
- **sherpa-onnx compatible:** not yet; feature request k2-fsa/sherpa-onnx#4125 (open, 2026-10-28)
- **Import path:** none today (not a sherpa family; LiteRT route is the separate Omni variant at 3.23GB)
- **Quality assessment (vendor self-reported card table):** English ESB/Open ASR mean WER 6.23 (Voxtral Mini 5.51, Gemma4 E2B 8.26, E4B 7.82); multilingual FLEURS-9 mean 6.03, claimed better than Gemma4 E2B (8.08), E4B (6.54), Qwen2.5-Omni 3B (7.41), NVIDIA Audex 2B (6.40). No per-tradition-language breakdown
- **Score:** 5.8/10 (quality 3, size 2, on-device 3, breadth 3, importability 2, maintenance 5)
- **Recommendation:** WATCH. Right shape (official ONNX, 2B edge class, ASR+TTS+voice) but no runtime path in the app and unverified multilingual quality. License LFM Open License v1.0: non-commercial for orgs with revenue >= $10M (no problem for us, but restrictive for the community-catalog channel's redistribution assumptions, unexamined)
- **Effort:** high (would need sherpa integration (#4125) or a custom LiteRT/TFLite path plus eval)
- **Notes:** sibling litert-community/LFM3-Omni-2B-LiteRT (2026-10-28) runs audio-to-text on LiteRT-LM **0.18.1** (our pin is 0.17.1), 3.23GB, S26 GPU 27 tok/s; ASR quality unbenchmarked; flag: from the second message of a conversation LiteRT-LM 0.18.1 feeds a zero embedding for the previous stop token (upstream issue #3165 cited), one question per conversation avoids it

### Nemotron-3-Diarization-LiteRT (not ASR; app-relevant)
- **Source:** https://huggingface.co/litert-community/Nemotron-3-Diarization-LiteRT (2026-10-27)
- **Size:** 100M streaming Sortformer; 198.7MB encoder graph (fp16) + 2.1MB frontend + host-side state
- **Languages:** n/a (who-spoke-when, up to 8 speakers, one decision per 10ms frame)
- **Format:** two LiteRT graphs + Kotlin/Python host state code, Android sample included; OpenMDW-1.1
- **sherpa-onnx compatible:** n/a (separate LiteRT runtime)
- **Quality/perf:** FP32 speaker decisions identical to the reference; Galaxy S26 runs the whole network on the LiteRT CompiledModel GPU, real-time (171ms per 0.72s step, 2,915/2,915 nodes on one partition)
- **Score:** n/a (not an ASR candidate)
- **Recommendation:** spike candidate for the app's speaker pass (GH #83 lineage): on-device streaming diarization at ~201MB, Kotlin host code provided. Would complement (not replace) the current speaker-attribution path
- **Effort:** medium (new runtime seam next to sherpa; the app already carries LiteRT via LiteRT-LM)

### Checked and excluded (axis 3 and others)
- **nvidia/Nemotron-Labs-Audex-2B / Audex-30B-A3B** (2026-08-13/18): audio understanding + ASR + TTS siblings; **NVIDIA Oneway Noncommercial License** = hard blocker for redistribution in Play/F-Droid. NOT PHONE-RELEVANT (license, not size). https://huggingface.co/nvidia/Nemotron-Labs-Audex-2B
- **FluidInference/nemotron-3.5-transcribe-lite-coreml** (2026-10-28): the 30B's ~600M ASR encoder + CTC timestamp head WITHOUT the 30B LM, Apple CoreML/CoreAI only; "Lite" is the port's own name, not an NVIDIA release; card states the LLM-less transcript is "much rougher". Proves the encoder is extractable; no Android port, no license change (OpenMDW-1.1 carries over). NOT PHONE-RELEVANT (platform + quality)
- **ysdede/qwen3-asr-0.6b-onnx** (2026-10-25): asr.js-specific export, custom graphs, native streaming unsupported, explicitly "not a generic Transformers.js ONNX export". Not sherpa-shaped. Excluded
- **GGUF conversions** (lipik: parakeet-ultra/unified/v2/v3, kickvn/Hebrish Qwen3-ASR, handy-computer Cohere): no GGUF runtime in the app (TASK-639). Excluded as import candidates; tracked as ecosystem signal only
- **Peacockery/tajik-parakeet-tdt-110m** (2026-10-28): Tajik, outside the tradition set; noted for completeness
- **diarizeapp/parakeet-tdt-0.6b-v3-onnx(-qnn)**, **luweigen/parakeet-tdt-0.6b-v3-android**, **bjnortier/coreai-ht5-...** (all 2026-10-28): parallel export efforts of the model we already ship; no action

## LLM Model Findings

No new Gemma-family `.litertlm` of note this cycle beyond the above (litert-community/gemma-4-26B-A4B-it-litert-lm, 2026-10-28, is a 26B-class MoE text LLM, far over any phone bar; LFM3-Omni-2B-LiteRT is covered under ASR findings for its audio-input path). The LLM-side finding that matters is that litert-community has become a fast conversion channel for third-party audio models (Qwen3-ASR-1.7B, LFM3-Omni-2B), which is the realistic delivery vehicle for any future audio-LLM upgrade since it requires zero new runtime work.

## Language Coverage

Which tradition languages gained a candidate this cycle:

| Language(s) | New candidate | Evidence |
|---|---|---|
| it de es fr (+21 more EU) | parakeet-ultra (drop-in for the primary) | per-language FLEURS table, it 2.00 vs 2.61 (card, same-pipeline) |
| en de es fr it nl pl ru el fi uk (11) | nemotron-labs-unified-asr-0.6b | NVIDIA comparison figure (per-language numbers in images, unverified) |
| fa hi ar vi ko zh tr (+10 more) | Qwen3-ASR-1.7B (.litertlm) | official 30-language list; on-device FLEURS en/zh/ja only |
| en (+7 evaluated) | LFM3-Audio-2B | vendor table, FLEURS-9 mean 6.03 |

No per-language sweep was requested in this dedicated scope; no gap flags change from the 2026-10-24 report (uk: Moonshine/omnilingual/Orukeet; he: ivrit turbo; fi: Orukeet + the pending TASK-769 conversion plan; ar-EG: catalog pair, unchanged). No eval-harness run happened in this scout (no fresh WER from eval/ to record).

## Framework Updates

### sherpa-onnx
- **Current:** v2.0.1 (`.sherpa-version`, commit af2d8d81; `app/build.gradle.kts` SRCLIB PIN agrees; scripts/fetch-sherpa-aar.sh not re-read, sync script governs)
- **Latest:** v2.0.1 (2026-10-26). No newer release. Master activity since the tag (commits read through 2026-10-28T15:21Z):
  - #4084 (2026-10-27): fix modified_beam_search dropping speech for NeMo **TDT** models. Post-pin; relevant to any future TDT beam-search use (our shipped beam work is on the Nemotron transducer, a different path)
  - #4093 (2026-10-27): per-stream language candidates for **Whisper**. Post-pin; intersects our TranscriptionLanguagePolicy forced-decoding wiring (would need catalog/policy work to exploit, not a drop-in)
  - #4121 (2026-10-28): keep online result tokens in sync when dropping leading punctuation (online recognizer = our streaming backend's family). Post-pin
  - #4117 (2026-10-28): optional QNN HTP performance mode
  - #4120 (2026-10-28): VoxLock 1.2 speech-denoiser models
- **Unified-asr support status:** issue #4127 (open, 2026-10-28, https://github.com/k2-fsa/sherpa-onnx/issues/4127). The Parakeet Unified runtime (PR #3575, merged 2026-05-09, IN our pin) runs the multilingual checkpoint but: (1) `CopyWindowWithPadding` zero-fills left context and `NemoNormalizePerFeature` normalizes over the zeros, so after each endpointing `Reset()` whole leading utterances are deleted (FLEURS en WER 52.1 with endpointing / 14.9 without / 8.0 NeMo-faithful); (2) the unified path's encoder takes no language input (the cache-aware path has had `language_prompt_ids` since #3671, our streaming model uses it). The author's export bakes an English prompt into the graph. Quantization lesson with direct value for the Ultra conversion: the English script's recipe (dynamic QUInt8 encoder + QInt8 decoder/joiner) raised mean FLEURS WER over 11 languages from 11.3 to 14.2, while per-channel QInt8 on encoder MatMuls only, decoder/joiner fp32, matched fp32 (11.2)
- **v2.0.1 body relevant items we already ship:** #4097 Canary greedy decoder-positions fix (our CANARY family), #4092 librosa-style fbank for streaming NeMo CTC, #4085/#4101 VoxLock target-speaker voice isolation (a new denoiser family, potential future feature for voice-clip cleanup)
- **Migration effort:** none now. **Recommendation:** stay on v2.0.1; #4084/#4093/#4121 and the eventual #4127 fixes are the natural payload of the NEXT pin bump (recorded for that release train)

### vLLM (remote-offload axis 5)
- **Question:** does vLLM (0.32+) with nemo_speechlm expose an OpenAI-compatible `/v1/audio/transcriptions`?
- **Answer:** yes, as of vLLM main today. PR #64282 "[Model] Add Nemotron-3.5-Transcribe", MERGED 2026-10-28T15:49Z (https://github.com/vllm-project/vllm/pull/64282): registers `Nemotron3_5TranscribeForConditionalGeneration` (model_type `nemo_speechlm`), and per its own body "serves chat completions with audio and `/v1/audio/transcriptions`, and supports MTP and DFlash2 speculative decoding". Which release TAG first contains it is unverified (0.32.0 does not; it is post-0.32 main)
- **On 0.32.0 today** (the NeMo Speech plugin path, `VLLM_PLUGINS=nemo_speechlm`): serving is via chat completions with base64 audio content parts (the official card quick-start is literally an OpenAI-client `chat.completions.create` with `audio_url`), plus a custom middleware for CTC word timestamps (`--middleware nemo.collections.speechlm2.vllm.salm.ctc_serving.ctc_timestamp_middleware`). Doc: https://raw.githubusercontent.com/NVIDIA-NeMo/Speech/main/docs/source/speechlm2/vllm_serving.rst (install vLLM 0.32.0 pinned by NeMo's vllm extra; long-audio env vars `VLLM_MAX_AUDIO_DECODE_DURATION_S`, `VLLM_MAX_AUDIO_CLIP_FILESIZE_MB`; MTP/DFlash2 speculative options; CTC word-timestamp aligner contract)
- **Caveats from the card:** the release-day vLLM integration is "intended for model development and is expected to be roughly two times slower" than the NIM; deployment target is 1x H100 80GB
- **Conclusion for the experimental remote-offload feature:** a self-hosted Nemotron Transcribe server is a viable offload target TODAY via chat-completions on 0.32.0+plugin, and via the standard `/v1/audio/transcriptions` route once the release after 0.32.0 ships #64282. The endpoint contract is OpenAI-compatible, so an offload client can share the Whisper-API shape it likely already targets

### LiteRT-LM
- **Current:** 0.17.1 (`gradle/libs.versions.toml`)
- **Latest context:** litert-community files this week measure on 0.17.1 AND 0.18.x/0.18.1 (Qwen3-ASR-1.7B runs on our pin as shipped; LFM3-Omni-2B needs 0.18.1; upstream issue #3165 documents a multi-turn state quirk in 0.18.1). No release-tag sweep of the LiteRT-LM repo was done in this dedicated scope (HTML page not fetched; flag for the next full run)
- **Recommendation:** no bump for its own sake; 0.17.1 runs the Qwen3-ASR file, which is the only concrete upgrade target identified

### ONNX Runtime
Not checked in this dedicated scope (no release sweep; nothing in the sweep's findings depends on it). Deferred to the next full run.

## Landscape Developments

Two structural shifts stand out. First, the SALM (speech-LLM) wave is consolidating around server-class MoE models (Nemotron Transcribe 30B-A4B, Audex 30B-A3B) while the phone tier is being fed by a different lineage: post-trained small transducers (parakeet-ultra) and distilled/unified checkpoints (nemotron-labs-unified-asr-0.6b, parakeet-unified-en-0.6b), plus LFM3-Audio-2B as the first edge-class any-to-any with official ONNX. The phone-relevant frontier is no longer "convert the flagship", it is "the flagship's training recipe trickles into 0.6B checkpoints". Second, the conversion channels matured: litert-community shipped four phone-ready audio artifacts this week with device-measured benchmarks and Kotlin samples, meaning a new-model path that needs no sherpa work exists in practice, and community converters (abid1010, solavr, diarizeapp) are producing correctly-shaped sherpa/TDT exports within days of upstream releases. The #4127 quantization finding (per-channel encoder-only int8 or quality collapses) is the single most reusable technical lesson for every conversion we evaluate from here on.

## Prioritized Recommendations

| # | Action | Impact | Effort | Risk |
|---|--------|--------|--------|------|
| 1 | Validate abid1010/parakeet-ultra-onnx (eval/fleurs_bench.py it + multilingual sample, then real-clip eval vs shipped v3 int8); if it holds, plan the primary-model swap | high | low | medium (community conversion; int8 recipe risk) |
| 2 | Watch sherpa-onnx #4127; when the left-context and language-prompt fixes land, export nemotron-labs-unified-asr-0.6b and A/B it against BOTH incumbents (offline vs v3, streaming vs Nemotron) | high | medium | medium (buffered streaming compute cost unmeasured on ARM64) |
| 3 | Device trial of litert-community/Qwen3-ASR-1.7B on the RMX3853 (RAM headroom vs the S26's 4.10GB CPU peak); scope the LlmTranscriptionBackend wiring for the `language <name><asr_text>` answer format | med | medium | low (side-by-side with Gemma, no removal) |
| 4 | Record in the remote-offload design: vLLM exposes `/v1/audio/transcriptions` for Nemotron Transcribe as of #64282 (post-0.32 main); chat-completions fallback on 0.32.0+plugin | med | low | low |
| 5 | Batch the next sherpa pin bump on #4084 (TDT beam fix) + #4093 (Whisper per-stream languages) + #4121 (online punctuation sync) + #4127 fixes when they land | med | low | low |
| 6 | Spike Nemotron-3-Diarization-LiteRT (Kotlin sample, ~201MB) for the speaker pass | med | medium | medium (new runtime seam) |

## Watch List

- sherpa-onnx #4127 (unified-asr multilingual fixes) and the author's promised export script share
- sherpa-onnx #4125 (LFM3-Audio-2B native support) and LiquidAI's ONNX export evolution
- A NVIDIA-announced distill of the 30B Transcribe (none exists today; the card's "Best For: highest accuracy, customization" implies the 0.6B line stays separate)
- solavr's Qwen3-ASR-1.7B sherpa export: a slimmer full-int8 re-export under ~2.2GB
- The fine-tune burst on Qwen3-ASR-1.7B (Hebrew "Sabich", indic22, Arabic GRPO): potential community-catalog WHISPER-family substitutes are NOT possible (Qwen3-ASR is not an external family), but they signal where per-language upgrades will come from
- Flurin17/parakeet-tdt-0.6b-v3-swiss-german was touched 2026-10-28T18:12Z (file list unchanged: .nemo + assets; our catalog pins sha256 so weight drift would fail validation; cause of the touch undetermined)
- Cohere transcribe tracked item: base models unchanged; new community ONNX (rapidrabbit76, 2026-10-20) and Arabic SFT/RL fine-tunes (Cohere-AR org) appeared; still no sherpa-shaped artifact, no distilled variant
- VibeVoice sherpa issue #3106: unchanged (open, zero comments, checked 2026-10-28)
- litert-community org velocity: five phone-relevant additions in four days; re-scan every run

## Comparison with Previous Report (2026-10-24-769-models.md)

That report's subjects are untouched by this sweep: the Finnish candidate set (Finnish-NLP whisper-large-v3, RASMUS canary-v2, mozilla-ai turbo-fi) and the Nawah license question stand as written. One indirect reinforcement: #4127's quantization lesson lands on the same week as the fi conversion plan and should be applied to ANY int8 export we produce or adopt (per-channel encoder-MatMul-only, decoder/joiner fp32). The sherpa pin moved v2.0.0 to v2.0.1 between the two reports (TASK-668 consumption, already recorded in the release notes).

## Verdict Table

| Candidate | Verdict | One-line reason / what would change our mind |
|---|---|---|
| moondream/parakeet-ultra (abid1010 int8 ONNX) | **IMPORTABLE NOW** (pending validation) | sherpa TDT-shaped, ~747MB, CC-BY-4.0, published per-language gains incl it 2.61 to 2.00; validate the conversion in eval/ before any swap |
| nvidia/nemotron-labs-unified-asr-0.6b | **WATCH** | One checkpoint replaces offline+streaming incumbents; sherpa runtime degrades it today (#4127). Change: #4127 fixes released in a sherpa tag + a multilingual export script |
| litert-community/Qwen3-ASR-1.7B (.litertlm) | **WATCH** (evaluate) | Device-proven on our exact LiteRT-LM 0.17.1, 17/19 tradition languages, but 2.70GB and a new backend wiring. Change: maintainer accepts an LLM-class flagship tier, or a <=2GB quantization appears |
| solavr sherpa Qwen3-ASR-1.7B int8 | **WATCH** | Sherpa-loadable today but 4.2GB disk / 3.4GB RAM peak, over the bar. Change: a full-int8 export (no fp32 attention, quantized KV) under ~2.2GB with on-device proof |
| LiquidAI LFM3-Audio-2B | **WATCH** | Official ONNX, 2B edge class, but no app runtime path (#4125 open) and unverified multilingual quality. Change: sherpa support lands + a per-language eval shows it beating Qwen3-ASR-1.7B in our tradition set |
| litert-community/LFM3-Omni-2B-LiteRT | **WATCH** (low) | Runs on LiteRT-LM but 3.23GB, needs 0.18.1, ASR unbenchmarked. Change: an ASR benchmark + a size reduction |
| Nemotron-3-Diarization-LiteRT | **IMPORTABLE NOW** (non-ASR) | ~201MB, Kotlin sample, S26-proven; a diarization spike, not a transcription backend |
| nvidia/NVIDIA-Nemotron-3.5-Transcribe-30B-A4B (+ all quantizations) | **NOT PHONE-RELEVANT** | 34B/4.3B-active server model, H100-class, no ONNX, no smaller variant announced; relevant only as the remote-offload flagship (endpoint live in vLLM main) |
| nvidia/Nemotron-Labs-Audex-2B | **NOT PHONE-RELEVANT** | NVIDIA Oneway Noncommercial license blocks redistribution, regardless of size |
| FluidInference nemotron-3.5-transcribe-lite-coreml | **NOT PHONE-RELEVANT** | Apple CoreML only, encoder+CTC without the LLM ("much rougher" per its own card) |
| ysdede/qwen3-asr-0.6b-onnx | **NOT PHONE-RELEVANT** | asr.js custom graphs, explicitly not a generic export, streaming unsupported |
| All GGUF conversions (lipik, kickvn, Hebrish, handy-computer) | **NOT PHONE-RELEVANT** | No GGUF runtime in the app (TASK-639) |

## No Change

- sherpa-onnx releases: v2.0.1 remains the newest tag (matches our pin)
- Official Qwen3-ASR: no new sizes (no 4B); -hf variants from July unchanged
- Cohere transcribe base models; VibeVoice #3106
- Our built-in inventory and index-1.16.0.json: no new community-catalog-relevant sherpa-native multilingual artifact appeared in this window outside the candidates scored above
