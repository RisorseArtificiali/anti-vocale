# Parakeet TDT hotword biasing: the vocab self-export path (TASK-994 follow-up)

Date: 2026-11-12. Question (maintainer): "non siamo in grado di ri-esportare noi?" - can we generate the missing bpe_vocab artifact ourselves instead of waiting for csukuangfj? Answer: YES, verified end-to-end on the desktop.

## The chain of facts (all verified, sources cited)

1. sherpa-onnx at our pin (v2.0.1, srclib af2d8d81) ships `scripts/nemo/generate_bpe_vocab.py` (contributor nullbio, 2026) - but it is sentencepiece-processor based and its example targets parakeet-tdt-0.6b-v2; we did NOT need it (see 3).
2. The original model repo `nvidia/parakeet-tdt-0.6b-v3` (HF) ships the full `.nemo` archive (2.5GB) alongside the safetensors; the archive is a plain tar containing `*_tokenizer.vocab` (text, 8192 lines, `piece<TAB>score`) and `*_tokenizer.model` (sentencepiece proto, 361KB).
3. Alignment check (the load-bearing invariant): the first 8192 entries of the csukuangfj export's `tokens.txt` match the sentencepiece vocab 1:1 by ID (0 mismatches); tokens.txt only appends `<blk>` at 8192, which the tokenizer never emits. Every piece ssentencepiece can encode therefore resolves in the decoder's symbol table.
4. ssentencepiece reads the TEXT vocab format, not the proto: passing the `.model` binary as bpe_vocab fails at construct ("Each line in vocab should contain two items"); the `.vocab` text works as-is (tab-separated accepted).
5. The HF `tokenizer.json` is a BPE-with-merges representation of the same 8192 tokens; the decoder's piece space (tokens.txt) is sentencepiece-style (`▁`-prefixed, 4172 pieces carry the marker).

## The experiment (eval venv sherpa-onnx 2.0.1, stock int8, carlo.wav)

Deliberately misspelled hotwords ("Castellasso", "Boncompani") with `modeling_unit=bpe`, `bpe_vocab=<the .nemo tokenizer.vocab>`, `decoding_method=modified_beam_search`:

- baseline: "...Carlo Boncompagni e vivo a Castellazzo Novarese..."
- hotwords_score 1.5 (sherpa default): "...Carlo Boncompani e vivo a Castellasso Novarese..."
- hotwords_score 3.0 and 6.0: same flip.

The biased spellings FLIPPED a correct decode at the DEFAULT score: the mechanism is live and strong on Parakeet TDT at our pin. This is simultaneously the proof and the caution - biasing toward correct spellings is the feature; biasing cannot distinguish correct from incorrect terms, so the dictionary's premise (user-maintained correct spellings) is load-bearing.

Artifacts kept (Mac, outside git): `~/data/hotwords-vocab/parakeet-tdt-0.6b-v3.bpe.vocab` (sha256 41130ff4...) and the tokenizer.model (eacec2b0...).

## What shipping it takes (not yet done; maintainer decision)

1. Host the 84KB vocab on the pantinor mirror; pin it in models_catalog.json as a fifth file for the stock and smoothquant variants (same tokenizer, same vocab).
2. App side: SherpaBackend passes `bpe_vocab` + `modeling_unit="bpe"` + `hotwords_file` (from PersonalDictionary) when the pinned file exists; decoding must switch to modified_beam_search while the dictionary is non-empty (greedy ignores hotwords; beam costs ~3x per the TASK-530 benchmark, so the switch should be dictionary-gated).
3. Same extraction path works for any NeMo export: streaming Nemotron's vocab would come from its own .nemo (separate tokenizer, separate probe).
4. The csukuangfj upstream ask becomes OPTIONAL (nice-to-have for everyone else), not a blocker.

Open tuning question for the device leg: the default score 1.5 already flips; whether real-world correct-spelling biasing needs a lower score (to avoid over-biasing) is measurable with the eval harness once wired.

## Family-by-family matrix (added 2026-11-12 after the maintainer's follow-up)

| Family | sherpa impl | Biasing mechanism | Artifact needed | Verdict |
|---|---|---|---|---|
| Parakeet TDT (stock + smoothquant) | offline nemo transducer | context graph, beam search | bpe.vocab from the .nemo | PROVEN (flip experiment) |
| GigaAM v3 | offline nemo transducer (same impl; our catalog modelType nemo_transducer) | same context graph | ai-sage/GigaAM-v3 ships tokenizer.model DIRECTLY (convert proto -> text vocab, one script); our mirror pantinor/gigaam-v3 is already ours | same mechanism as Parakeet; one desktop probe to confirm |
| Nemotron 3.5 streaming | ONLINE nemo transducer | per-stream createStream(hotwords) context graph (the AAR's createStream(String) IS the hotwords param) | nvidia/nemotron-3.5-asr-streaming-0.6b ships the .nemo (verified) | API present; extract + probe |
| Qwen3-ASR | offline qwen3-asr (audio-LLM) | hotwords ride the SYSTEM PROMPT of the chat template (comma CSV); Kotlin OfflineQwen3AsrModelConfig.setHotwords AND OfflineStream.setOption("hotwords") both exist in our AAR | NOTHING - no vocab, no beam switch (generation is the decode) | cheapest win; plumbing only |
| Whisper (all variants) | offline whisper | none (no hotwords, no initial_prompt wiring at this pin) | - | dead |
| SenseVoice / Canary / Moonshine / Dolphin (external families) | their impls | none | - | dead |
| External Transducer imports | generic transducer impl | context graph like built-ins | the IMPORT FORMAT would gain an optional bpe.vocab file (schema + copy plan + family docs) | possible follow-up |
| Gemma (LLM passes) | prompt rider | shipped (TASK-994) | - | shipped; audio-prompt path still unprobed |
| Whistle | native | shipped (TASK-858) | - | shipped |

Kotlin-side surface (verified by javap on our pinned AAR): OfflineRecognizerConfig.hotwordsFile/hotwordsScore, OnlineRecognizer.createStream(String) (hotwords), OfflineQwen3AsrModelConfig.hotwords, OfflineStream.setOption/getOption. Everything needed is already in the binary; the work is app wiring + vocab hosting + the transducer beam-search gating (dictionary-gated).
