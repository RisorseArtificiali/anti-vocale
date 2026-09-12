# Research Report: "¡Muy bien!" x50 from bundled Whisper Small on Italian voice message

**Date**: 2026-09-02
**Depth**: exhaustive (adversarial verification included)
**Confidence**: HIGH on the verdict (not an export bug; known Whisper failure class), MEDIUM on the exact on-device trigger

## Executive Summary

The Whisper Small artifact we ship is byte-identical to the official k2-fsa export and behaves correctly on controlled Italian input. The reported output (one short Spanish phrase repeated until the transcript fills the decoder's token budget) is the canonical Whisper repetition-loop failure, which triggers when the language token is misdetected or the audio contains non-speech stretches. sherpa-onnx cannot mitigate it: for Whisper it supports only greedy decoding, with no temperature fallback, no compression-ratio check, and no no-speech guard. This is an inherent model/runtime limitation, not an export bug in what we propose.

## The incident

User report (task 998ca3af-db08-4490-a4d8-cb62ee6f9599): Whisper Small, 19.8 s Italian voice message, 79.8 s processing, status SUCCESS, output = "¡Muy bien!" repeated (paste shows 20 repetitions, user says ~50; the phrase is never spoken).

## Findings

### 1. What the app actually runs (verified from source)

- Catalog entry `whisper/small` (`app/src/main/assets/models_catalog.json:81`): files `small-encoder.int8.onnx`, `small-decoder.int8.onnx`, `small-tokens.txt` from `pantinor/sherpa-onnx-whisper-small`; flags `whisperTailPaddings=1000`, `chunkDurationSeconds=30`, `passLanguage=true`.
- `TranscriptionOrchestrator.kt:436-442`: the transcription-language preference (default `"auto"`, `PreferencesManager.DEFAULT_TRANSCRIPTION_LANGUAGE`) maps to `""` for Whisper. So by default Whisper Small runs with **language auto-detection**, not Italian pinned.
- `SherpaBackend.kt:472-486`: `task="transcribe"`, `tailPaddings=1000`, `decodingMethod="greedy_search"`, 16 kHz / 80 mel bins. VAD is OFF by default for Whisper (`DEFAULT_VAD_ENABLED=false`; `requiresVadAlignedChunking` is only forced for Canary and Gemma).
- 19.8 s audio is a single chunk (< 30 s), zero-padded by tail padding to ~29.8 s of mel frames.

### 2. Provenance: NOT our export (CONFIRMED, cryptographic)

All three shipped files are byte-identical to the official k2-fsa mirror `csukuangfj/sherpa-onnx-whisper-small`:

| File | SHA256 (both repos) |
|---|---|
| small-encoder.int8.onnx | `4cbe7b22fa9026b843b60a68640c747de05bafb1a11b57edc0e66c232d9f33a9` |
| small-decoder.int8.onnx | `acad50b5c782696e91b55914cc5ab4f756f1532f76e22aa6fc615f39fb69a8ee` |
| small-tokens.txt | `b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126` |

Our repo is a subset re-upload (int8 files only) of the official export. Whatever weaknesses the model has are k2-fsa/OpenAI weaknesses, not introduced by us. This kills the "bug di esportazione" hypothesis at the artifact level.

### 3. sherpa-onnx v1.13.5 decode path (read at tag v1.13.5 == srclib commit 3dc7c56)

- `offline-recognizer-whisper-impl.h:53-61`: **only `greedy_search` is accepted for Whisper**; any other decoding method exits the process.
- `offline-whisper-greedy-search-decoder.cc`: pure argmax loop. Termination: EOT token, or token budget `num_feature_frames/100*6` (6 tokens/s of audio, capped at 224), or text-context exhaustion. **No temperature fallback, no compression-ratio check, no average-logprob check, no no-speech threshold.**
- Language handling (`:53-71`): empty `language` runs `model_->DetectLanguage()` (argmax over language-token logits from the first decoder pass). A non-empty unknown language string would kill the process, so the app's `""`-for-auto mapping is the only correct way to request auto-detection.
- `tail_paddings` (`offline-recognizer-whisper-impl.h:107-128`): pads mel features with zeros to `num_frames + 1000` (10 s of silence), capped at 3000 frames. It exists to help EOT detection and matches our catalog value.

### 4. Quantitative signature of the user's transcript

For 19.8 s: budget = 19.8 x 6 = ~118 text tokens. "¡Muy bien!" tokenizes to 5 tokens (`¡`=24364, `M`=44, `uy`=7493, ` bien`=3610, `!`=0) plus separator, i.e. 5-7 tokens per repetition. 118 / 6 ≈ 20 repetitions. The user's paste shows exactly 20 repetitions before the cutoff; the "50 volte" does not fit the single-chunk budget and is read as emphasis. The signature (a loop that fills the entire token budget without ever emitting EOT) is the fingerprint of a runaway greedy loop, not of a corrupted model: a broken export produces garbage tokens or native crashes, not a fluent Spanish phrase repeated a budget-shaped number of times.

### 5. The failure class is documented at three levels

- **Primary source**: the Whisper paper (arXiv 2212.04356) states that beam search with 5 beams is used "to reduce repetition looping which happens more frequently in greedy decoding", with temperature escalation (0.0 to 1.0 in +0.2 steps) when average log-prob < −1 or gzip compression ratio > 2.4. None of that machinery exists in sherpa-onnx's Whisper path.
- **Peer-reviewed**: Koenecke et al., "Careless Whisper: Speech-to-Text Hallucination Harms" (FAccT 2024, arXiv 2402.08021): ~1% of transcriptions contain full hallucinated sentences; hallucinations concentrate on speakers with longer non-vocal durations (silence stretches inside the clip), across all Whisper sizes.
- **Engineering write-ups**: nyra-labs "Killing hallucinations" (repetition loops are a shallow autoregressive attractor; triggered by out-of-distribution audio; 0.91 correlation between distribution shift and hallucination rate per Atwany et al. 2025) and yage.ai's survey (silence + subtitle-prior training data explains invented phrases; recommended defenses: VAD preprocessing, temperature/compression-ratio thresholds, post-hoc repetition cleanup).

Why Spanish on Italian: with `language=""` the lang token comes from `DetectLanguage`, and whisper-small is a 244M-parameter model whose language head routinely confuses close Romance languages. Once `es` is conditioned, the decoder transcribes Italian acoustics through Spanish phonetics; under greedy decoding that out-of-domain condition is exactly the known loop trigger. Our own forced-`es` run (finding 6) shows the Spanish-garble half of this mechanism directly.

### 6. Empirical matrix (the exact shipped bytes, eval venv sherpa-onnx 1.13.5, CPU, greedy, tail 1000)

Synthetic 22.3 s Italian clip (espeak-ng, 16 kHz), plus an Opus-12k round-trip variant to mimic messenger codec, plus a variant with leading silence and trailing low-level noise:

| language | audio | detected | result |
|---|---|---|---|
| `""` (auto) | clean | it | correct transcription, 99 tokens, no loop |
| `""` (auto) | opus-12k | it | correct transcription, no loop |
| `it` | clean | it | identical to auto |
| `it` | opus-12k | it | identical to auto |
| `es` | clean | es | Spanish phonetic garble ("Chau, discribo para confirmar el apuntamiento..."), no loop |
| `es` | opus-12k | es | same garble, no loop |
| `""` (auto) | silence+noise | it | correct transcription, noise ignored |
| `es` | silence+noise | es | garble, no loop |

Interpretation: the artifact and the app's invocation are functionally correct (auto-detect lands on `it` and the transcription is clean in every auto/pinned cell). Forcing `es` reproduces the wrong-language garble, i.e. the conditioning half of the user's symptom. The runaway loop itself did not reproduce on synthetic audio; that is expected: the loop is audio-dependent (the user's real clip is unavailable; it lives on their device in the task record). The loop mechanism is established from source + literature; the specific trigger on that clip is not reproducible from here.

### 7. GitHub issue landscape

No issue in k2-fsa/sherpa-onnx reports Whisper repetition loops specifically (searched "whisper repetition", "whisper loop language", "hallucination"). Related entries show the pattern of the runtime leaving hallucination handling to callers: #3907 fixed Qwen3-ASR hallucinating on silent audio when language/hotwords are set; #3267 documents modified_beam_search hallucinations for NeMo TDT. Nothing adds decoding safeguards for Whisper. Independent sherpa-onnx consumers ship their own client-side filters (rpiv-voice: curated hallucinated-phrase set + repetition-loop detector + input energy floor, enabled by default), which corroborates that the runtime has no protection.

## Verdict

1. **Export bug in what we ship: REFUTED** (byte-identical official files; correct behavior on controlled input, auto and pinned).
2. **Cause class: CONFIRMED** as the documented Whisper repetition-loop hallucination under greedy-only decoding, with Spanish output explained by language misconditioning (misdetected or mis-triggered `es`). sherpa-onnx offers no mitigation path for this class.
3. **Exact trigger on the user's clip: UNVERIFIED** (audio not accessible; most plausible: DetectLanguage picked `es` on that specific recording, or the clip contained non-speech stretches that evoked the subtitle prior; both are documented triggers of the same loop).

## Mitigations (ranked; research only, nothing implemented)

1. **Language pinning**: the setting exists today (transcription-language preference, default "auto"). Pinning a concrete language removes the DetectLanguage coin-flip entirely: with `it` forced, Spanish output is impossible by construction (the lang token is forced before decoding). Cheapest, highest-leverage change: either default multilingual Whisper entries to the UI language when it is in the variant's language list, or surface the risk in the model description ("auto" on small models is unreliable).
2. **Enable VAD for Whisper**: strips silence/noise stretches, the other documented trigger; the app already has the toggle and VAD-aligned segmentation (off by default for Whisper). Memory note: VAD+Whisper segmentation was already researched in depth (2026-05; prompt-chaining strategy recommended).
3. **Post-hoc loop detection**: detect a runaway loop in the final text (an n-gram occupying a dominant share of positions, e.g. our top-trigram metric above) and either collapse it to one copy with a visible warning or re-run with the language pinned. Precedents: OpenAI's gzip compression-ratio check (threshold 2.4), rpiv-voice's detector, nyra's rewind-and-escape. There is no transcript post-processing seam today (TranscriptionCalibrator only models speed), so this would be a new, small seam in the result path.
4. **Model guidance for Italian**: our own 4-model benchmark (memory, 2026-05) already ranks Italian quality: distil-large-v3-it 4.3% WER (best), Parakeet 5.4% at 17x speed, Whisper turbo 6.3%. Whisper Small sits below all of those; users reaching for it on Italian get the weakest multilingual head in the catalog. A "best for Italian" hint already exists for distil; consider whether Small's description should set expectations.

## Confidence Assessment

- HIGH: artifact identity (3x SHA256); app config chain (read); sherpa decode behavior (source at the exact pinned tag, plus empirical lang output); existence and shape of the Whisper loop failure class (paper + FAccT study + engineering literature); quantitative budget match.
- MEDIUM: the specific on-device trigger (lang misdetection to `es` vs non-speech evoking the prior). Cannot be settled without the user's audio; both routes converge on the same unguarded decode loop.
- Not reproducible here: the loop itself on synthetic audio (expected; audio-dependent).

## Sources

1. k2-fsa/sherpa-onnx at v1.13.5 (commit 3dc7c56): `offline-whisper-greedy-search-decoder.cc`, `offline-recognizer-whisper-impl.h`, `offline-whisper-model.cc`, `offline-recognizer-whisper-impl.h` greedy-only check.
2. HuggingFace: `pantinor/sherpa-onnx-whisper-small`, `csukuangfj/sherpa-onnx-whisper-small` (blob sizes + SHA256 comparisons).
3. Radford et al., "Robust Speech Recognition via Large-Scale Weak Supervision", arXiv 2212.04356 (PDF, long-form decoding protocol).
4. Koenecke et al., "Careless Whisper: Speech-to-Text Hallucination Harms", FAccT 2024, arXiv 2402.08021.
5. nyra-labs, "Faster inference and mitigating hallucinations in CrisperWhisper 2.0" (nyra-labs.com/research/killing-hallucinations), citing Atwany et al. 2025 (arXiv 2502.12414) and Xu et al. 2022 (arXiv 2206.02369).
6. yage.ai, "Whisper's Broken Record" survey (2026-05-26), citing arXiv 2501.11378 for hallucinated-phrase frequency stats.
7. DeepWiki openai/whisper transcription pipeline (decode_with_fallback, thresholds).
8. @juicesharp/rpiv-voice npm package (sherpa-onnx consumer shipping client-side hallucination filter).
9. k2-fsa/sherpa-onnx issues #3907, #3267 (hallucination handling is caller-side in this runtime).

## Reproduction appendix

```bash
# artifact + provenance
curl -L "https://huggingface.co/pantinor/sherpa-onnx-whisper-small/resolve/main/<file>" | sha256sum
curl -L "https://huggingface.co/csukuangfj/sherpa-onnx-whisper-small/resolve/main/<file>" | sha256sum

# matrix (eval/.venv has sherpa-onnx 1.13.5)
espeak-ng -v it -s 155 -w it_raw.wav "<~20s of Italian>"; ffmpeg -i it_raw.wav -ar 16000 -ac 1 it_clean.wav
ffmpeg -i it_clean.wav -c:a libopus -b:a 12k -f ogg it.ogg && ffmpeg -i it.ogg -ar 16000 -ac 1 it_voicemsg.wav
eval/.venv/bin/python repro.py ""   it_clean.wav    # auto -> detected it, clean output
eval/.venv/bin/python repro.py es  it_clean.wav    # forced Spanish -> phonetic garble
```

Script and files used: `/tmp/whisper-small-repro/repro.py` (greedy, tail 1000, 4 threads, mirrors `SherpaBackend.initOffline`).
