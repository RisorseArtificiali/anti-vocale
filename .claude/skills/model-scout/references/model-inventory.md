# Current Model Inventory

Detailed inventory of models currently used by Anti-Vocale. Built-ins below; the community catalog is a live channel, read it each run instead of relying on this file for it.

## Community Catalog (external-model platform)

- **Index:** `app/src/main/assets/external-catalog/index.json` (the single source of truth; read it each run)
- **Shape:** entries with `name`, `languages`, `family` (seven: Transducer/Whisper/CTC/SenseVoice/Canary/Moonshine/Dolphin), `entryUrl` (per-entry JSON)
- **Channel semantics:** a catalog entry reaches users without an app release; conversion pipeline = the community-model-conversion skill (proven on Whisper fine-tunes; other families unproven, flag effort accordingly)
- **Scope:** 14 entries at the time of writing (4 CANARY en/fr/de/es, 5 TRANSDUCER including the German and Spanish Kroko zipformers plus Persian Shenava, 4 WHISPER including Arabic dialectal and Swiss German, 1 SENSE_VOICE zh/en/yue/ja/ko; the index is the truth)

## ASR Models (sherpa-onnx / ONNX Runtime)

### Parakeet TDT 0.6b v3
- **Source:** pinned to the pantinor/* HuggingFace mirrors in models_catalog.json (sha256-managed per file); verify in the catalog
- **Size:** 640 MB stock-int8, 862 MB smoothquant (the default variant)
- **Format:** ONNX int8 (3 files: encoder, decoder, joiner + tokens.txt)
- **Languages:** 25 European languages
- **Architecture:** NVIDIA NeMo FastConformer-TDT (Token-and-Duration Transducer)
- **Config:** `nemo_transducer`, `greedy_search`, 1s tail padding
- **Italian WER:** ~10.1% on real WhatsApp audio, ~4.3% on clean FLEURS
- **Status:** Primary multilingual model

### Whisper Distil Large V3 IT
- **Source:** pantinor/* mirror (models_catalog.json)
- **Size:** 938 MB
- **Format:** ONNX int8 (encoder + decoder)
- **Languages:** Italian only
- **Architecture:** Distilled Whisper large-v3, Italian fine-tuned
- **Status:** Primary Italian-specific model

### Whisper Small int8
- **Source:** pantinor/* mirror (models_catalog.json)
- **Size:** 358 MB
- **Languages:** 101 languages

### Whisper Turbo int8
- **Source:** pantinor/* mirror (models_catalog.json)
- **Size:** 988 MB
- **Languages:** 101 languages

### Whisper Medium int8
- **Source:** pantinor/* mirror (models_catalog.json)
- **Size:** 903 MB
- **Languages:** 101 languages

### Qwen3-ASR 0.6b int8
- **Source:** pinned to the pantinor/* mirrors in models_catalog.json; verify in the catalog
- **Size:** 938 MB
- **Languages:** 59 languages
- **Status:** Recently added, evaluating

### Nemotron 3.5 (streaming)
- **Runtime:** OnlineRecognizer (the only streaming backend)
- **Languages:** omnilingual set; language conditioning via setOption at runtime
- **Status:** shipped; per-stream language option

### GigaAM v3 (Russian)
- **Runtime:** OfflineRecognizer
- **Languages:** Russian only
- **Notes:** punctuates natively; 30s chunk cap (quality-bounded, TASK-448)

## LLM Models (LiteRT-LM runtime; GGUF blocked)

### Gemma 4 E4B-it OBLITERATED
- **Source:** https://huggingface.co/OBLITERATUS/gemma-4-E4B-it-OBLITERATED
- **Size:** 4.9 GB (Q4_K_M), 5.3 GB (Q5_K_M), 7.4 GB (Q8_0)
- **Format:** GGUF
- **Languages:** 140+
- **License:** Apache 2.0

## Frameworks

### sherpa-onnx
- **Version:** v1.13.8 (the pin lives in `.sherpa-version` at the repo root: tag + srclib commit; the AAR is a local binary `libs/sherpa-onnx.aar`)
- **Format:** AAR bundled at `libs/sherpa-onnx.aar`
- **Purpose:** ONNX-based ASR inference runtime

### llama-bro
- **Version (fork):** v1.3.0-gemma4 (`com.github.paoloantinori:llama-bro:v1.3.0-gemma4`)
- **Version (upstream):** v1.2.3 (`com.github.whyisitworking:llama-bro`)
- **GitHub (fork):** https://github.com/paoloantinori/llama-bro
- **Purpose:** GGUF LLM inference on Android
- **Status:** **BLOCKED for Gemma 4 GGUF**: fork adds native `LLM_ARCH_GEMMA4` but the SDK profile layer (`ModelProfiles.GEMMA`) does not configure Gemma 4 correctly, and the GGUF download UI section was removed from ModelTab entirely.
- **Text-only limitation:** The OBLITERATUS GGUF models lack audio encoder. `Gemma4GgufBackend` hardcodes `supportsAudio = false`. Even with profile fix, GGUF can only do text generation, not audio transcription.

### LiteRT-LM (working Gemma path)
- **Backend:** `LlmTranscriptionBackend` → `LlmManager`
- **Format:** `.litertlm`
- **Audio support:** `supportsAudio = true` via `llmManager.generateFromAudio()`
- **Status:** Working path for Gemma audio transcription
- **Models:** GEMMA_4_E2B, GEMMA_4_E4B, GEMMA_3N_E2B, GEMMA_3N_E4B via `ModelDownloader`

## Source Files for Verification

- Framework pins: `.sherpa-version` (repo root), `app/build.gradle.kts`
- Community catalog: `app/src/main/assets/external-catalog/index.json`
- Model downloads: `app/src/main/java/com/antivocale/app/transcription/*Downloader.kt` and `app/src/main/java/com/antivocale/app/data/ModelDownloader.kt` (Gemma)
- Model management: `app/src/main/java/com/antivocale/app/transcription/*ModelManager.kt`
- External families and constraints: `app/src/main/java/com/antivocale/app/transcription/ModelFamilySupport.kt`
- Backend configs: `app/src/main/java/com/antivocale/app/transcription/TranscriptionBackend.kt`
- Language list: `app/src/main/java/com/antivocale/app/transcription/Language.kt`
