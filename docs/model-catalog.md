# Model catalog

Every model Anti-Vocale can transcribe with, on one page: download sizes, supported languages, speed notes, and links to the original models behind our ports. The same information drives the in-app model picker, but this page is meant to be browsed from a desktop before you commit to a download on your phone.

Bundled models are downloaded on demand inside the app. Community models are one-tap imports (**Model tab > Advanced > Import from catalog**) and the list grows without app updates. Any other sherpa-onnx model can also be imported by URL or from a folder ([import formats](external-models.md)).

Speed context for everything below: measured by us on an Italian voice-message test set with the desktop eval harness (2026-05), your device and your language will differ. The in-app benchmark tab measures the models you have installed on your own hardware.

## Bundled models

### Parakeet TDT 0.6B v3 (default)

Transducer (NVIDIA NeMo, TDT decoding). The app default: the best speed/accuracy balance we have measured, about 18x faster than the Distil-Italian model at 5.4% vs 4.3% word error rate on our Italian set.

- Variants: int8 (640 MB), SmoothQuant (862 MB)
- Languages: 25 European, including Italian, German, French, Spanish, English, Russian, and the Nordics, Baltics, and most of Central Europe
- Audio limit: 400 s per segment (a hard cap in NVIDIA's checkpoint), split automatically for longer input
- Original model: [nvidia/parakeet-tdt-0.6b-v3](https://huggingface.co/nvidia/parakeet-tdt-0.6b-v3)
- Downloads from: [csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8) (int8), [pantinor/parakeet-tdt-0.6b-v3-smoothquant](https://huggingface.co/pantinor/parakeet-tdt-0.6b-v3-smoothquant) (SmoothQuant)

### Whisper (OpenAI, encoder-decoder)

Four sizes of the classic. Whisper Turbo is the strong multilingual all-rounder at 6.3% WER on our Italian set; medium and small trade accuracy for download size; the Distil variant below is the Italian specialist.

| Variant | Size | Languages | Original |
|---|---|---|---|
| turbo | 988 MB | 99 | [openai/whisper-large-v3-turbo](https://huggingface.co/openai/whisper-large-v3-turbo) |
| medium | 903 MB | 99 | [openai/whisper-medium](https://huggingface.co/openai/whisper-medium) |
| small | 358 MB | 99 | [openai/whisper-small](https://huggingface.co/openai/whisper-small) |
| distil-large-v3-it | 938 MB | Italian | [bofenghuang/whisper-large-v3-distil-it-v0.2](https://huggingface.co/bofenghuang/whisper-large-v3-distil-it-v0.2) |

- Audio limit: 30 s per segment, split automatically
- Distil-large-v3-it scored the best Italian accuracy of everything we tested (4.3% WER), at roughly a seventeenth of Parakeet's speed
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
- Original model: [nvidia/nemotron-3.5-asr-streaming-0.6b](https://huggingface.co/nvidia/nemotron-3.5-asr-streaming-0.6b)
- Downloads from: [csukuangfj2/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-1120ms-int8-2026-06-11](https://huggingface.co/csukuangfj2/sherpa-onnx-nemotron-3.5-asr-streaming-0.6b-1120ms-int8-2026-06-11)

### GigaAM v3

Russian specialist transducer from SberDevices.

- Size: 326 MB; audio limit 200 s per segment (a rotary positional table in the NeMo export), split automatically at 30 s (quality, not the crash limit: measured degradation above ~60 s per pass)
- Original model: [ai-sage/GigaAM-v3](https://huggingface.co/ai-sage/GigaAM-v3)
- Downloads from: [pantinor/gigaam-v3](https://huggingface.co/pantinor/gigaam-v3)

### Gemma (LLM)

A full language model rather than a dedicated transcriber: the only bundled model that can also summarize, reformat, and post-process the transcript, driven by a customizable prompt in Settings. Runs via LiteRT-LM; see the [user guide](user-guide/) for prompting.

## Community catalog

One-tap imports from the in-app catalog. Where we publish our own sherpa-onnx re-export of someone else's fine-tune, both links are listed: the original model is the one to read for training data, benchmarks, and credit; our mirror is just the packaging the app downloads.

| Language | Catalog entry (searchable name in the app) | Size | Original model | Our mirror |
|---|---|---|---|---|
| Arabic (dialectal) | Whisper Large v3 Turbo Arabic Dialectal (sherpa int8) | ~1.0 GB | [oddadmix/whisper-large-v3-turbo-arabic-dialectal-v2](https://huggingface.co/oddadmix/whisper-large-v3-turbo-arabic-dialectal-v2) (sherpa export: [dmouayad](https://huggingface.co/dmouayad/sherpa-onnx-whisper-large-v3-turbo-arabic-dialectal-v2)) | [pantinor/whisper-arabic-dialectal-sherpa](https://huggingface.co/pantinor/whisper-arabic-dialectal-sherpa) |
| German | Whisper v3 Turbo German int8 (sherpa, primeline fine-tune) | ~1.0 GB | [primeline/whisper-large-v3-turbo-german](https://huggingface.co/primeline/whisper-large-v3-turbo-german) (WER 2.6% on their German eval mix) | [pantinor/whisper-large-v3-turbo-german-sherpa](https://huggingface.co/pantinor/whisper-large-v3-turbo-german-sherpa) |
| German (Swiss) | Whisper v3 Turbo Swiss German int8 (sherpa, Flurin17 fine-tune) | ~1.0 GB | [Flurin17/whisper-large-v3-turbo-swiss-german](https://huggingface.co/Flurin17/whisper-large-v3-turbo-swiss-german) | [pantinor/whisper-large-v3-turbo-swiss-german-sherpa](https://huggingface.co/pantinor/whisper-large-v3-turbo-swiss-german-sherpa) |
| German (light) | Kroko Community Zipformer German (streaming, CC-BY-SA 4.0, Banafo / kroko.ai) | ~67 MB | [Banafo / kroko.ai](https://kroko.ai) | [csukuangfj/sherpa-onnx-streaming-zipformer-de-kroko-2025-08-06](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-de-kroko-2025-08-06) |
| Spanish (light) | Kroko Community Zipformer Spanish (streaming, CC-BY-SA 4.0, Banafo / kroko.ai) | ~148 MB | [Banafo / kroko.ai](https://kroko.ai) | [csukuangfj/sherpa-onnx-streaming-zipformer-es-kroko-2025-08-06](https://huggingface.co/csukuangfj/sherpa-onnx-streaming-zipformer-es-kroko-2025-08-06) |
| Russian (light) | Zipformer Russian int8 (Vosk-based, sherpa) | ~69 MB | Vosk project (via sherpa-onnx re-export) | [csukuangfj/sherpa-onnx-zipformer-ru-int8-2025-04-20](https://huggingface.co/csukuangfj/sherpa-onnx-zipformer-ru-int8-2025-04-20) |
| English / German / Spanish / French (small) | Canary Flash 180M (English), Canary Flash 180M (German), Canary Flash 180M (Spanish), Canary Flash 180M (French) | ~207 MB | [nvidia/canary-180m-flash](https://huggingface.co/nvidia/canary-180m-flash) | [csukuangfj/sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8](https://huggingface.co/csukuangfj/sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8) |

Notes on choosing:

- The Whisper-family community models (~1 GB) are quality picks for their languages; the "light" rows (67 to 148 MB) are streaming zipformers, cheaper and faster but less accurate.
- The Canary Flash rows are NVIDIA's Canary 180M Flash. One entry per language: canary has no auto-detection, the recognizer is conditioned on the language you import. Because the four entries share identical files, importing a second language REPLACES the first (the app recognizes the same weights and re-points the single record at the new language): you keep one canary slot, not four. At ~207 MB it is the smallest multilingual tier; it needs silence-aligned chunking, which the app enables automatically (VAD turns on when you select one of these models).
- The two German Whisper entries differ in purpose: the standard-German model for Hochdeutsch, the Swiss one for Swiss German dialects (it also accepts de and de-CH).
- Every catalog entry pins SHA-256 hashes for each file; the import verifies them before activation.
