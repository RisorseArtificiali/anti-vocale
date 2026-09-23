---
name: model-scout
description: |
  Use this skill when the user asks to "scout for new models", "check for model updates", "find better ASR models", "check sherpa-onnx releases", "look for new Whisper models", "search HuggingFace for ASR models", "run model scout", "any new Parakeet models", "check framework updates", "/model-scout", "find ASR models for <language>", or when discussing improvements to on-device transcription quality for the Anti-Vocale app. Also triggers on periodic research requests about the ASR/LLM landscape for mobile speech recognition, and on questions about what languages the app could serve better.
version: 0.2.0
---

# Model Scout Skill

Periodic reconnaissance skill for discovering new ASR models, framework releases, and on-device transcription techniques relevant to Anti-Vocale (Android voice message transcription app, global user base).

## Purpose

Identify new or updated models and frameworks that could improve transcription quality in any language our users transcribe (Italian is the maintainer's reference benchmark, not the only axis), reduce model size, speed up inference on Android ARM64 devices, or extend language coverage. Coverage extends through TWO channels: the built-in catalog (app release required) and the external-model platform (community catalog plus user imports for the Transducer/Whisper/CTC/SenseVoice/Canary/Moonshine/Dolphin families (ExternalModels.kt declares seven); a catalog entry reaches users TODAY with no app release).

## Current Baseline

Verify against source files before each run:

- **Framework versions**: Read `app/build.gradle.kts` for sherpa-onnx and LiteRT-LM versions; read `.sherpa-version` at the repo root for the pinned sherpa-onnx tag
- **Model inventory**: Read `app/src/main/java/com/antivocale/app/transcription/*ModelManager.kt` and `*Downloader.kt` plus `data/ModelDownloader.kt` (the Gemma inventory), and `references/model-inventory.md`
- **Community catalog**: Read `app/src/main/assets/` + the CURRENT versioned index (TASK-643: `index-<versionName>.json`, derive the name from the highest-versioned index-*.json present; the unsuffixed index.json is the frozen legacy channel for <=1.13.x and never receives new entries) for the cataloged entries and their languages (dedupe and coverage judgments run against this, not against memory)
- **External families**: Read `app/src/main/java/com/antivocale/app/transcription/ModelFamilySupport.kt` for the import families and their constraints (featureDim, chunk caps)
- **Previous reports**: Check `docs/scout-reports/` for historical context

**Known baseline** (verify during run):
- ASR built-in: Parakeet TDT 0.6b v3 (640MB stock-int8, 862MB smoothquant default, 25 EU langs), Whisper Small/Turbo/Medium (101 langs), Whisper Distil Large V3 IT (938MB), Qwen3-ASR 0.6b (938MB, 59 langs), Nemotron 3.5 (streaming, OnlineRecognizer, 45 langs), GigaAM v3 (Russian)
- External families: Transducer / Whisper / CTC / SenseVoice / Canary / Moonshine / Dolphin (user-imported plus community catalog; seven families)
- LLM: Gemma 4 via `.litertlm` through `LlmTranscriptionBackend` (the working audio path; Gemma 4 E2B and E4B variants)
- Frameworks: sherpa-onnx (pinned via `.sherpa-version`), LiteRT-LM

**Important context for LLM/GGUF findings:** GGUF has NO path in the app at all (the llama-bro
backend was removed entirely, TASK-639 2026-09-23: fork deleted upstream and the exports are
text-only anyway); `.litertlm` via LiteRT-LM is the only Gemma runtime. When reporting Gemma GGUF
variants, always note: "no GGUF runtime in the app; relevant only if a .litertlm conversion appears."

## Scope Handling

Parse the user's request to determine scope. Default to `full` if unspecified:

| Scope | Focus Area |
|-------|-----------|
| `full` | All areas below, plus the Italian reference sweep |
| `asr` | ASR/transcription models only |
| `llm` | LLM models (litertlm, GGUF, multimodal) |
| `frameworks` | sherpa-onnx, ONNX Runtime, LiteRT-LM releases |
| `parakeet` | NVIDIA Parakeet family only |
| `whisper` | Whisper family only |
| `qwen` | Qwen ASR family only |
| `languages:X,Y` | Per-language sweep for the named languages (e.g. `languages:fa,uk,he`): find the best models per language, built-in or community-catalog, and report coverage gaps |

## Evaluation Criteria

Score every finding 1-5 on each, then compute weighted composite:

1. **Target-language quality impact** (weight: 3x): Will this improve WER/CER in a language our users transcribe? Name the languages the evidence covers and cite the benchmark or community report. Italian has our eval harness (eval/); for other languages rely on published WER, model cards, and community reports, and label the confidence honestly.
2. **Size efficiency** (weight: 2x): Under 1GB for ASR / 5GB for LLM with good quality-per-MB?
3. **On-device feasibility** (weight: 2x): Runs on Android ARM64 via sherpa-onnx or LiteRT-LM?
4. **Language coverage breadth** (weight: 1x): Which languages does the app serve today (built-in catalog sets, the streaming entry, and the community catalog) and which gaps does this fill? A model for a language with NO current good option scores high here.
5. **Community-catalog importability** (weight: 1x): Does it fit an external family (one of the seven import families: Transducer/Whisper/CTC/SenseVoice/Canary/Moonshine/Dolphin, sherpa-exportable)? A catalog candidate reaches users without an app release; pipeline = the community-model-conversion skill (proven on Whisper fine-tunes; for the other families flag conversion effort as unproven); intake threads = GH issues #69/#70.
6. **Maintenance health** (weight: 1x): Actively maintained with recent commits?

Composite = weighted sum / 5, reported as X/10 (weights sum to 10, criteria score 1-5).

## Execution Process

### Step 1: Verify Baseline

Read current source files to confirm framework versions and model inventory. Check `docs/scout-reports/` for previous findings to avoid repeating.

### Step 2: Launch Parallel Research

Run all applicable research areas in parallel. These are logical areas run as parallel bash groups in THIS session, not Agent-tool dispatches: do NOT dispatch the model-scout agent recursively (it executes the whole flow itself and would race on the report path). See **`references/search-queries.md`** for the exact queries; transport is the `crw` CLI (`crw search`, `crw scrape`) with curl as fallback (the mcp__crw__ MCP tools may not be registered).

**Area A: HuggingFace ASR Models**: language-agnostic firehose first (csukuangfj author feed with the ASR pipeline-tag filter), then family queries, then any per-language sweeps the scope requested (docs inventory page only on `full`).

**Area B: HuggingFace LLM Models**: search for small multilingual litertlm/GGUF models, Gemma variants, and multimodal audio models.

**Area C: Framework Releases**: curl the GitHub API endpoints for sherpa-onnx and ONNX Runtime releases; WebFetch the LiteRT-LM releases page (HTML is its primary source).

**Area D: Landscape Research**: `crw search` for broad discovery and `crw scrape` for HuggingFace blog posts and recent ASR developments.

### Step 3: Filter and Score

Apply exclusion filters (too large, no quantization, no quality evidence for any user-relevant language, abandoned) and inclusion signals (sherpa-onnx pre-converted, published WER in a covered or requested language, fits an external family). Score remaining findings against evaluation criteria.

### Step 4: Synthesize Report

Structure the report as specified in **`references/report-template.md`**. Include per-model cards with scores, the import path for each finding, prioritized recommendations, a language coverage section, and a watch list.

### Step 5: Save Report

Save to `docs/scout-reports/YYYY-MM-DD.md`. Create directory if needed.

## Behavioral Rules

1. **Evidence over speculation**: every finding must have a verifiable source URL. Never fabricate model names, sizes, or benchmarks.
2. **Honest confidence**: if quality in the claimed language cannot be verified, state so explicitly. Unverified quality is the norm for long-tail languages; say it, do not soften it.
3. **No marketing language**: use neutral, technical assessments. No "groundbreaking" or "state-of-the-art" without citing benchmarks.
4. **Size is contextual**: our ASR models range from ~300MB to 988MB built-in; community-catalog models may reasonably go larger if the family supports it. Don't arbitrarily label models "too large" without comparing to what we already ship or catalog.
5. **Integration realism**: account for the full path. For built-in candidates: model download, ONNX conversion, sherpa-onnx integration, JNI bridge, ProGuard rules, UI changes, testing. For catalog candidates: the community-model-conversion pipeline (export, validation, the REQUIRED device-validation pass, catalog entry, docs, GH close-out).
6. **Parallel execution**: always run CRW scrapes and searches concurrently to minimize wall-clock time.
7. **Historical awareness**: reference previous scout reports when available.
8. **No padding**: "No significant changes since last scout on YYYY-MM-DD" is a valid finding.

## Tracked Items

Items the scout should check for updates on each run. For each item, search for new commits, comments, or status changes and report progress.

| Item | URL | Why | Added |
|------|-----|-----|-------|
| VibeVoice TTS support in sherpa-onnx | https://github.com/k2-fsa/sherpa-onnx/issues/3106 | ONNX-exported VibeVoice models exist (FluffyBunnies/vibevoice-onnx-v2). If sherpa-onnx adds native support, could enable TTS features. Check for new comments, labels, or linked PRs. | 2026-04-30 |
| Cohere Transcribe int8 in sherpa-onnx | https://huggingface.co/CohereLabs/cohere-transcribe-03-2026 | First non-Whisper/non-Parakeet ASR in the sherpa-onnx ecosystem (2B params, 14 langs, Apache 2.0, 1.6GB int8). Check for: distilled/smaller variants, WER benchmarks per language, community quality reports. | 2026-05-02 |
| LiteRT-community TFLite ASR models | https://huggingface.co/litert-community | Qwen3-ASR 0.6B and Parakeet CTC 0.6b in TFLite with Qualcomm NPU builds. Check for: new model additions, TDT (not just CTC) conversions, broader Qualcomm SoC support, community benchmarks. | 2026-05-02 |

## Key Sources to Monitor

| Account/Org | Platform | Publishes |
|-------------|----------|-----------|
| csukuangfj | HuggingFace | sherpa-onnx pre-converted ASR models across ALL languages (the firehose; sort by lastModified) |
| k2-fsa | GitHub | sherpa-onnx releases with new model support |
| mrfakename | HuggingFace | Whisper ONNX conversions |
| nvidia | HuggingFace | Parakeet model family |
| litert-community | HuggingFace | TFLite ASR models (NPU builds) |
| OBLITERATUS | HuggingFace | Quantized GGUF models |
| microsoft | GitHub | ONNX Runtime releases |

## Error Handling

- HuggingFace rate limit: wait 2s and retry
- GitHub rate limit: WebFetch the HTML releases page instead
- crw or curl failure on one source: log and continue, never block the entire report
- No new findings: state clearly, do not pad the report

## Additional Resources

### Reference Files

- **`references/search-queries.md`**: CRW tool calls, search queries, and scope-specific search strategies
- **`references/report-template.md`**: full report structure with per-model card format and recommendation table layout
- **`references/model-inventory.md`**: detailed current model inventory with download URLs, file formats, and language coverage
