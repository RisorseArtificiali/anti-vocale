# Model catalog

Every model Anti-Vocale can transcribe with, on one page: download sizes, supported languages, speed notes, and links to the original models behind our ports. The same information drives the in-app model picker, but this page is meant to be browsed from a desktop before you commit to a download on your phone.

Bundled models are downloaded on demand inside the app. Community models are one-tap imports (**Model tab > Advanced > Import from catalog**) and the list grows without app updates. Any other sherpa-onnx model can also be imported by URL or from a folder ([import formats](external-models.md)).

Speed context for everything below: measured by us on an Italian voice-message test set with the desktop eval harness (2026-05), your device and your language will differ. The in-app benchmark tab measures the models you have installed on your own hardware. Quality context by language: the same harness family runs every bundled model on FLEURS clips per language, and the [per-language quality matrix](quality-matrix.md) shows which model leads where.

## Bundled models

### Parakeet TDT 0.6B v3 (default)

Transducer (NVIDIA NeMo, TDT decoding). The app default since 1.14.0: the stock int8 variant, device-measured at 4.48% WER on our Italian clip set (the smoothquant variant, the default through 1.13.x, measures 6.28% through the same chain), 220MB lighter and about 25% faster. On the internal desktop benchmark set Distil edged it 4.3% vs 5.4% WER; on the device itself (11 real voice clips through the app's own audio chain) the int8 variant leads everything at 4.48%, with SmoothQuant at 6.28% and Distil at 6.8%.

- Variants: int8 (640 MB), SmoothQuant (862 MB)
- Languages: 25 European, including Italian, German, French, Spanish, English, Russian, and the Nordics, Baltics, and most of Central Europe
- Audio limit: 400 s per segment (a hard cap in NVIDIA's checkpoint), split automatically for longer input
- Original model: [nvidia/parakeet-tdt-0.6b-v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3)
- Downloads from: [csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8) (int8), [pantinor/parakeet-tdt-0.6b-v3-smoothquant](https://huggingface.co/pantinor/parakeet-tdt-0.6b-v3-smoothquant) (SmoothQuant)

### Whisper (OpenAI, encoder-decoder)

Four sizes of the classic. Whisper Turbo is the strong multilingual all-rounder at 6.3% WER on our Italian set; medium and small trade accuracy for download size; the Distil variant below is the Italian specialist.

| Variant | Size | Languages | Original |
|---|---|---|---|
| turbo | 1037 MB | 99 | [openai/whisper-large-v3-turbo](https://huggingface.co/openai/whisper-large-v3-turbo) |
| medium | 949 MB | 99 | [openai/whisper-medium](https://huggingface.co/openai/whisper-medium) |
| small | 378 MB | 99 | [openai/whisper-small](https://huggingface.co/openai/whisper-small) |
| distil-large-v3-it | 984 MB | Italian | [bofenghuang/whisper-large-v3-distil-it-v0.2](https://huggingface.co/bofenghuang/whisper-large-v3-distil-it-v0.2) |

- Audio limit: 30 s per segment, split automatically
- Distil-large-v3-it scored the best Italian accuracy on our internal desktop benchmark set (4.3% WER); on the device corpus (11 real clips, app chain) it lands at 6.8%, behind Parakeet int8 (4.48%), at roughly a seventeenth of Parakeet's speed
- Downloads from our mirrors: [turbo](https://huggingface.co/pantinor/sherpa-onnx-whisper-turbo), [medium](https://huggingface.co/pantinor/sherpa-onnx-whisper-medium), [small](https://huggingface.co/pantinor/sherpa-onnx-whisper-small), [distil-it](https://huggingface.co/pantinor/sherpa-onnx-whisper-distil-large-v3-it)

### Qwen3-ASR 0.6B

Encoder-decoder, int8 export. Per the model card its 52 entries are 30 languages plus 22 Chinese dialects, not 52 independent languages.

- Size: 938 MB; audio limit 30 s per segment, split automatically
- Original model: [Qwen/Qwen3-ASR-0.6B](https://huggingface.co/Qwen/Qwen3-ASR-0.6B)
- Downloads from: [pantinor/sherpa-onnx-qwen3-asr-0.6b-int8](https://huggingface.co/pantinor/sherpa-onnx-qwen3-asr-0.6b-int8)
- On our Italian set it was the weakest of the four we benchmarked (12.2% WER); it is here for language coverage and comparison, and the in-app benchmark can tell you if it earns its slot on your languages

### Nemotron 3.5 ASR (streaming)

The only streaming model: it shows partial results while audio is still being processed. Online decoder, 1120 ms chunking, int8. It lists 40 language-locales per NVIDIA's card, and the tiers matter: 19 transcription-ready, 13 broad-coverage, 8 adaptation-ready that need fine-tuning before they transcribe (32 work out of the box).

- Size: 640 MB; no fixed audio cap (it streams)
- Decoding: beam search, tuned for accuracy over speed (TASK-874/530, measured on device: about 1 point of WER better than the faster greedy path, at roughly 1.5x its processing time; both stay under real time)
- Original model: [nvidia/nemotron-3.5-asr-streaming-0.6b](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b)
- Downloads from: [csukuangfj2/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-1120ms-int8-2026-06-11](https://huggingface.co/csukuangfj2/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-1120ms-int8-2026-06-11)

### GigaAM v3

Russian specialist transducer from SberDevices.

- Size: 326 MB; audio limit 200 s per segment (a rotary positional table in the NeMo export), split automatically at 30 s (quality, not the crash limit: measured degradation above ~60 s per pass)
- Original model: [ai-sage/GigaAM-v3](https://huggingface.co/ai-sage/GigaAM-v3)
- Downloads from: [pantinor/gigaam-v3](https://huggingface.co/pantinor/gigaam-v3)

### Whistle (Cactus)

Tiny English-first engine from Cactus Compute: the smallest bundled model by far, built for fastest startup and instant first text rather than accuracy. A single `whistle.cact` file, no companion assets. ARM64 devices only (other ABIs and the fdroid flavor build the stub form under `-PwhistleEngineDisabled`).

- Size: 17 MB; 7 languages (English first)
- Original model: [Cactus-Compute/whistle](https://huggingface.co/Cactus-Compute/whistle)
- Kotlin side: `WhistleNative` in `transcription/` (TASK-853); the JNI symbol contract is pinned by `WhistleJniSymbolSetTest`

### Gemma (LLM)

A full language model rather than a dedicated transcriber: the only bundled model that can also summarize, reformat, and post-process the transcript, driven by a customizable prompt in Settings. Runs via LiteRT-LM; see the [user guide](user-guide/) for prompting.

## Community catalog

One-tap imports from the in-app catalog. Where we publish our own sherpa-onnx re-export of someone else's fine-tune, both links are listed: the original model is the one to read for training data, benchmarks, and credit; our mirror is just the packaging the app downloads.

**When an entry may ship despite losing on the shared benchmark.** The gate for a per-language entry is our FLEURS check against Whisper Turbo. An entry that loses there still ships when two things hold: it wins on a published benchmark of its own training domain, and it brings a value the incumbent does not (a different training distribution, a lighter payload, streaming). The German full fine-tune is the precedent: 7.48 on our FLEURS de against turbo's 6.01, but 3.00 on the author's Common Voice 15 German test, and a training distribution no turbo variant has; the catalog keeps both entries and the row states the two numbers, so the choice stays with the user. An entry that loses on the shared benchmark and has neither domain evidence nor a distinct value does not ship.

| Language | Catalog entry (searchable name in the app) | Size | Original model | Our mirror |
|---|---|---|---|---|
| Arabic (dialectal) | Whisper Large v3 Turbo Arabic Dialectal (sherpa int8) | ~1.0 GB | [oddadmix/whisper-large-v3-turbo-arabic-dialectal-v2](https://huggingface.co/oddadmix/whisper-large-v3-turbo-arabic-dialectal-v2) (sherpa export: [dmouayad](https://huggingface.co/dmouayad/sherpa-onnx-whisper-large-v3-turbo-arabic-dialectal-v2)) | [pantinor/whisper-arabic-dialectal-sherpa](https://huggingface.co/pantinor/whisper-arabic-dialectal-sherpa) |
| Arabic (dialectal, light) | Whisper Small Arabic Dialectal v2 (sherpa int8) | ~358 MB | [oddadmix/whisper-small-arabic-dialectal-v2](https://huggingface.co/oddadmix/whisper-small-arabic-dialectal-v2) | [pantinor/sherpa-whisper-small-arabic-dialectal-v2](https://huggingface.co/pantinor/sherpa-whisper-small-arabic-dialectal-v2) |
| Hebrew | Whisper v3 Turbo Hebrew int8 (sherpa, ivrit-ai fine-tune) | ~1.0 GB | [ivrit-ai/whisper-large-v3-turbo](https://huggingface.co/ivrit-ai/whisper-large-v3-turbo) | [pantinor/whisper-large-v3-turbo-hebrew-sherpa](https://huggingface.co/pantinor/whisper-large-v3-turbo-hebrew-sherpa) |
| German | Whisper v3 Turbo German int8 (sherpa, primeline fine-tune) | ~1.0 GB | [primeline/whisper-large-v3-turbo-german](https://huggingface.co/primeline/whisper-large-v3-turbo-german) (WER 2.6% on their German eval mix) | [pantinor/whisper-large-v3-turbo-german-sherpa](https://huggingface.co/pantinor/whisper-large-v3-turbo-german-sherpa) |
| German (full fine-tune) | Whisper Large v3 German (sherpa, primeline fine-tune) | ~1.7 GB | [primeline/whisper-large-v3-german](https://huggingface.co/primeline/whisper-large-v3-german) (the full large-v3 fine-tune; their Common Voice 15 test WER 3.00%, our FLEURS de check: 7.48 vs turbo's 6.01, so this is the pick for speech closer to its training domain) | [pantinor/sherpa-onnx-whisper-large-v3-german](https://huggingface.co/pantinor/sherpa-onnx-whisper-large-v3-german) |
| German (Swiss) | Whisper v3 Turbo Swiss German int8 (sherpa, Flurin17 fine-tune) | ~1.0 GB | [Flurin17/whisper-large-v3-turbo-swiss-german](https://huggingface.co/Flurin17/whisper-large-v3-turbo-swiss-german) | [pantinor/whisper-large-v3-turbo-swiss-german-sherpa](https://huggingface.co/pantinor/whisper-large-v3-turbo-swiss-german-sherpa) |
| Finnish | Whisper Large v3 Finnish int8 (sherpa, Finnish-NLP fine-tune) | ~1.78 GB | [Finnish-NLP/whisper-large-finnish-v3](https://huggingface.co/Finnish-NLP/whisper-large-finnish-v3) (full large-v3 fine-tune, 1.54B params; our FLEURS fi check: 7.09 WER vs turbo's 9.05, at 1.7x the download and ~3.5x the decode time) | [pantinor/sherpa-onnx-whisper-large-finnish-v3](https://huggingface.co/pantinor/sherpa-onnx-whisper-large-finnish-v3) |
| French | Whisper Large v3 French Distil int8 (sherpa, bofenghuang distil) | ~1.35 GB | [bofenghuang/whisper-large-v3-french-distil-dec16](https://huggingface.co/bofenghuang/whisper-large-v3-french-distil-dec16) (the 16-decoder-layer distil of [the French large-v3 fine-tune](https://huggingface.co/bofenghuang/whisper-large-v3-french), MIT; our FLEURS fr check: 6.54 WER vs turbo's 8.59 and the full fine-tune's 7.10, at roughly 1.3-2.2x turbo's decode time) | [pantinor/sherpa-onnx-whisper-large-v3-french-distil](https://huggingface.co/pantinor/sherpa-onnx-whisper-large-v3-french-distil) |
| German (light) | Kroko Community Zipformer German (streaming, CC-BY-SA 4.0, Banafo / kroko.ai) | ~67 MB | [Banafo / kroko.ai](https://kroko.ai) | [csukuangfj/sherpa-onnx-streaming-zipformer-de-kroko-2025-08-06](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-de-kroko-2025-08-06) |
| Spanish (light) | Kroko Community Zipformer Spanish (streaming, CC-BY-SA 4.0, Banafo / kroko.ai) | ~148 MB | [Banafo / kroko.ai](https://kroko.ai) | [csukuangfj/sherpa-onnx-streaming-zipformer-es-kroko-2025-08-06](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-es-kroko-2025-08-06) |
| Spanish (very light) | Moonshine Base Spanish int8 (light model, Moonshine Community License, non-commercial) | ~62 MB | Useful Sensors [moonshine](https://github.com/UsefulSensors/moonshine) (base, community fine-tune; our FLEURS check: 4.7% WER capped, 3.0% uncapped) | [csukuangfj2/sherpa-onnx-moonshine-base-es-quantized-2026-02-27](https://huggingface.co/csukuangfj2/sherpa-onnx-moonshine-base-es-quantized-2026-02-27) (non-commercial Moonshine Community License) |
| Ukrainian (light) | Moonshine Base Ukrainian int8 (light model, Moonshine Community License, non-commercial) | ~135 MB | Useful Sensors moonshine (base, community fine-tune; our FLEURS check: 21.0% WER capped; the only light dedicated Ukrainian entry, omnilingual covers uk too at ~349 MB but experimental) | [csukuangfj2/sherpa-onnx-moonshine-base-uk-quantized-2026-02-27](https://huggingface.co/csukuangfj2/sherpa-onnx-moonshine-base-uk-quantized-2026-02-27) (non-commercial Moonshine Community License) |
| Vietnamese (light) | Moonshine Base Vietnamese int8 (light model, Moonshine Community License, non-commercial) | ~135 MB | Useful Sensors moonshine (base, community fine-tune; our FLEURS check: 15.7% WER capped; the zipformer row below stays the smaller Vietnamese option at ~74 MB, permissively licensed) | [csukuangfj2/sherpa-onnx-moonshine-base-vi-quantized-2026-02-27](https://huggingface.co/csukuangfj2/sherpa-onnx-moonshine-base-vi-quantized-2026-02-27) (non-commercial Moonshine Community License) |
| Russian (light) | Zipformer Russian int8 (Vosk-based, sherpa) | ~69 MB | Vosk project (via sherpa-onnx re-export) | [csukuangfj/sherpa-onnx-zipformer-ru-int8-2025-04-20](https://huggingface.co/csukuangfj/sherpa-onnx-zipformer-ru-int8-2025-04-20) |
| Persian (Farsi) | Shenava Koochik v1.5 Persian (NeMo RNNT int8, Apache-2.0) | ~130 MB | [nvidia/stt_fa_fastconformer_hybrid_large](https://huggingface.co/nvidia/stt_fa_fastconformer_hybrid_large) fine-tuned by the [Shenava project](https://huggingface.co/Reza2kn) | [Reza2kn/Shenava-Koochik-v1.5-RNNT-sherpa-onnx](https://huggingface.co/Reza2kn/Shenava-Koochik-v1.5-RNNT-sherpa-onnx) (the author's own sherpa int8 export, downloaded directly) |
| Multilingual (24) | Orukeet multilingual int8 (Parakeet TDT v3 fine-tune, 24 languages incl. Italian, CC BY-SA 4.0) ([oruk/orukeet](https://huggingface.co/oruk/orukeet)) | ~640 MB | Parakeet TDT v3 fine-tune; Italian FLEURS 2.43 to 2.09 WER per the publisher's self-report (our eval refs are pseudo-refs, unverified) | none (pinned first-party export; an upstream re-publish breaks fresh imports until repinned) |
| 20 EU languages (small, low-RAM) | TranscribePsy-250M-EU (sherpa int8, CAFE adapters folded) | ~285 MB | [qvac/TranscribePsy-250M-EU](https://huggingface.co/qvac/TranscribePsy-250M-EU) (Parakeet v3 distill; our FLEURS sweep, 40 clips/locale: es 6.5, it 7.1, pt 9.4, de 10.4, nl 13.8, sk 15.8, ro/bg 18.3, fr 18.8, pl 19.2, fi 19.3, sv 19.7, cs 20.2, ru 20.4, en 21.0, da 23.3, lv 24.1, et 24.5, hu 25.9, hr 26.0; the model also claims el/lt/mt/sl/uk, which measured 30.7 to 70.3 and are deliberately not listed) | [pantinor/transcribepsy-250m-eu-sherpa](https://huggingface.co/pantinor/transcribepsy-250m-eu-sherpa) |
| Hindi | IndicConformer Hindi int8 (5.8% CER) | ~188 MB | [parismitaglobalsolutions/indicconformer-sherpa-onnx](https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx) (AI4Bharat per-language Conformer CTC (120M hybrid CTC+RNNT large, int8)) | none (pinned community re-export; shared tokens.txt at repo root) |
| Bengali | IndicConformer Bengali int8 (2.4% CER) | ~188 MB | [parismitaglobalsolutions/indicconformer-sherpa-onnx](https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx) (AI4Bharat per-language Conformer CTC (120M hybrid CTC+RNNT large, int8)) | none (pinned community re-export; shared tokens.txt at repo root) |
| Gujarati | IndicConformer Gujarati int8 (3.9% CER) | ~188 MB | [parismitaglobalsolutions/indicconformer-sherpa-onnx](https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx) (AI4Bharat per-language Conformer CTC (120M hybrid CTC+RNNT large, int8)) | none (pinned community re-export; shared tokens.txt at repo root) |
| Marathi | IndicConformer Marathi int8 (4.4% CER) | ~188 MB | [parismitaglobalsolutions/indicconformer-sherpa-onnx](https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx) (AI4Bharat per-language Conformer CTC (120M hybrid CTC+RNNT large, int8)) | none (pinned community re-export; shared tokens.txt at repo root) |
| Tamil | IndicConformer Tamil int8 (9.6% CER) | ~188 MB | [parismitaglobalsolutions/indicconformer-sherpa-onnx](https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx) (AI4Bharat per-language Conformer CTC (120M hybrid CTC+RNNT large, int8)) | none (pinned community re-export; shared tokens.txt at repo root) |
| Telugu | IndicConformer Telugu int8 (weaker on loanwords, 13.7% CER) | ~188 MB | [parismitaglobalsolutions/indicconformer-sherpa-onnx](https://huggingface.co/parismitaglobalsolutions/indicconformer-sherpa-onnx) (AI4Bharat per-language Conformer CTC (120M hybrid CTC+RNNT large, int8)) | none (pinned community re-export; shared tokens.txt at repo root) |
| Portuguese (BR) | Distil Whisper Large v3 Brazilian Portuguese (sherpa int8) | ~983 MB | [freds0/distil-whisper-large-v3-ptbr](https://huggingface.co/freds0/distil-whisper-large-v3-ptbr) (distil-large-v3 fine-tune; WER 6.35% on FLEURS pt_br, TASK-862) | [pantinor/distil-whisper-large-v3-ptbr-sherpa](https://huggingface.co/pantinor/distil-whisper-large-v3-ptbr-sherpa) |
| Turkish | Whisper Small Turkish (sherpa int8, fine-tune) | ~356 MB | [Tinlera/whisper-turkish-sherpa-onnx](https://huggingface.co/Tinlera/whisper-turkish-sherpa-onnx) (whisper-small fine-tune; WER 11.0% vs 15.5% baseline on FLEURS tr, TASK-861) | [pantinor/whisper-small-turkish-sherpa](https://huggingface.co/pantinor/whisper-small-turkish-sherpa) (byte-identical mirror, source-pinned) |
| Multilingual (all 99 languages, low-RAM) | Whisper Tiny multilingual (sherpa int8, low-RAM) | ~99 MB | [openai/whisper-tiny](https://huggingface.co/openai/whisper-tiny) | [csukuangfj/sherpa-onnx-whisper-tiny](https://huggingface.co/csukuangfj/sherpa-onnx-whisper-tiny) |
| 1600+ languages (uk/tr/pl filter-verified; experimental) | Omnilingual ASR 300M int8 (multilingual, experimental, Apache-2.0) | ~349 MB | [facebookresearch/omnilingual-asr](https://github.com/facebookresearch/omnilingual-asr) | [Hinotsuba/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12](https://huggingface.co/Hinotsuba/sherpa-onnx-omnilingual-asr-1600-languages-300M-ctc-int8-2025-11-12) |
| Chinese / English / Cantonese / Japanese / Korean | SenseVoice Small multilingual int8 (zh/en/yue/ja/ko) | ~227 MB | [FunAudioLLM/SenseVoiceSmall](https://huggingface.co/FunAudioLLM/SenseVoiceSmall) | [csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17](https://huggingface.co/csukuangfj/sherpa-onnx-sense-voice-zh-en-ja-ko-yue-2024-07-17) |
| English (small) | Parakeet TDT-CTC 110M (English) | ~131 MB | [nvidia/parakeet-tdt-ctc-110m](https://huggingface.co/nvidia/parakeet-tdt-ctc-110m) (CC-BY-4.0) | [VocaHQ/sherpa-onnx-nemo-parakeet-tdt-ctc-110m-en-int8](https://huggingface.co/VocaHQ/sherpa-onnx-nemo-parakeet-tdt-ctc-110m-en-int8) (int8 quant of the FP32-only upstream; vocaphone's measurement: 3.00 WER LibriSpeech test-clean, theirs not ours) |
| 19 Asian languages (zh/en/ja/ko/yue + hi/bn/ta/te/gu/pa/mr/as/ne/ur/th/vi/id/ms) | Dolphin Small (19 Asian languages) | ~250 MB | NVIDIA Dolphin (Apache-2.0) | [csukuangfj/sherpa-onnx-dolphin-small-ctc-multi-lang-int8-2025-04-02](https://huggingface.co/csukuangfj/sherpa-onnx-dolphin-small-ctc-multi-lang-int8-2025-04-02) |
| Vietnamese (small) | Zipformer Vietnamese | ~74 MB | VietASR zipformer, ~70k hours (Apache-2.0; publisher-reported level with PhoWhisper Large, theirs not ours) | [csukuangfj/sherpa-onnx-zipformer-vi-int8-2025-04-20](https://huggingface.co/csukuangfj/sherpa-onnx-zipformer-vi-int8-2025-04-20) (model trained on uppercase labels; the entry carries `text.lowercase`) |
| English / German / Spanish / French (small) | Canary Flash 180M (English), Canary Flash 180M (German), Canary Flash 180M (Spanish), Canary Flash 180M (French) | ~198 MB | [nvidia/canary-180m-flash](https://huggingface.co/nvidia/canary-180m-flash) | [csukuangfj/sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8) |
| Dutch | Canary 1B v2 Dutch int8 | ~1.3 GB | [nvidia/canary-1b-v2](https://huggingface.co/nvidia/canary-1b-v2) | [bnoordhuis/canary-1b-v2-sherpa-onnx-nl-int8](https://huggingface.co/bnoordhuis/canary-1b-v2-sherpa-onnx-nl-int8) (our FLEURS nl measurement: 6.08 WER vs Whisper Turbo's 8.29, and 2.4x faster; CC-BY-4.0) |
| English (Parakeet fine-tune) | Fritter English int8 (Parakeet TDT v2 fine-tune, CC-BY-NC) | ~661 MB | [danielrosehill/Fritter](https://huggingface.co/danielrosehill/Fritter) (Parakeet TDT v2 English fine-tune) | none (publisher CDN: the catalog entry points at [models.carrotcakeai.com's floating latest manifest](https://models.carrotcakeai.com/asr/fritter/latest/manifest.json), sha256 pins read at import time; non-commercial CC-BY-NC-4.0) |
| English (streaming) | Fritter Streaming English int8 (Nemotron 0.6B, CC-BY-NC) | ~661 MB | [danielrosehill/Fritter-Nemotron-0.6B-sherpa-onnx-int8](https://huggingface.co/danielrosehill/Fritter-Nemotron-0.6B-sherpa-onnx-int8) (streaming transducer, 560ms decode chunks) | none (publisher CDN: the catalog entry points at [models.carrotcakeai.com's floating latest streaming manifest](https://models.carrotcakeai.com/asr/fritter-streaming/latest/manifest.json) and carries the streaming flag, sha256 pins read at import time; non-commercial CC-BY-NC-4.0) |
| Hebrew / English | Sabich English-Hebrew int8 (Hebrish transducer, non-commercial) | ~661 MB | [Hebrish](https://hebrish.app) ([terms](https://hebrish.app/terms), non-commercial) | none (publisher CDN: the catalog entry points at [Hebrish's floating latest manifest](https://cdn.hebrish.app/models/sabich/latest.json), sha256 pins read at import time; non-commercial Hebrish terms) |

Notes on choosing:

- The full-size Whisper community fine-tunes (Arabic, Hebrew, German, Swiss German, Finnish) are quality picks for their languages; the "light" rows (67 to 148 MB) are streaming zipformers, cheaper and faster but less accurate.
- The Canary rows are NVIDIA's Canary family. One entry per language: canary has no auto-detection, the recognizer is conditioned on the language you import. The four Canary Flash entries share identical files, so importing a second flash language REPLACES the first (the app recognizes the same weights and re-points the single record at the new language): you keep one flash slot, not four. The Dutch 1B v2 entry carries DIFFERENT weights, so it lives beside a flash import as its own record. Both tiers need silence-aligned chunking, which the app enables automatically (VAD turns on when you select one of these models).
- The two German Whisper entries differ in purpose: the standard-German model for Hochdeutsch, the Swiss one for Swiss German dialects (it also accepts de and de-CH).
- SenseVoice emits English text in ALL CAPS without punctuation; that is the model's own output convention (verified against the upstream FunASR postprocessor, which does not change casing either). Chinese/Japanese/Korean output is unaffected. The app's Punctuation & Formatting pass (Gemma) adds punctuation automatically but preserves the original casing; readable mixed-case English would need a per-family casing exception, not yet implemented.
- Every catalog entry pins SHA-256 hashes for each file; the import verifies them before activation.
- The moonshine rows are the lightest dedicated entry for Spanish and Ukrainian (62 to 135 MB); for Vietnamese the zipformer row stays the lighter, permissively-licensed pick. Two caveats we verified ourselves: the non-English weights ship under the non-commercial Moonshine Community License (MIT covers code and the English models only), and the v2 `.ort` exports' old empty-above-~9.25s behavior was a sherpa bug (fixed in the app's sherpa 2.0.0 pin), so moonshine now chunks at the standard 30 seconds (per-language WER numbers live in the rows above).
