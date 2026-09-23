# Search Queries Reference

Search strategies for each scope area. Transport: the `crw` CLI (`crw search "<q>"`, `crw scrape <url>`; the mcp__crw__ MCP tools may not be registered, the CLI is the working form), curl for the JSON APIs, WebFetch for HTML pages. The agent file (`.claude/agents/model-scout.md`) carries curl equivalents of these queries; this reference file is the owner, fix drift here first.

## HuggingFace ASR Model Searches

Use `crw scrape` for model pages and `crw search` for discovery. Run all fetches in parallel.

### Firehose first (language-agnostic)

The single highest-value source: everything csukuangfj converts is sherpa-onnx ready, in every language. The pipeline-tag filter keeps the window ASR-only (unfiltered, ~80 percent of the top-30 window is TTS repos); sort by lastModified and diff against the previous report's inventory.

```
https://huggingface.co/api/models?author=csukuangfj&pipeline_tag=automatic-speech-recognition&sort=lastModified&direction=-1&limit=30
```

Some conversions carry no pipeline_tag (the mirror account csukuangfj2 among them), so keep the sherpa search below as the net for untagged ones. Verified: the two result sets are disjoint, do not merge them.

```
https://huggingface.co/api/models?search=sherpa-onnx+asr&sort=lastModified&direction=-1&limit=20
```

Caveat: this query AND-filters on the literal token "asr", so plain `sherpa-onnx-whisper-*` style repo names are missed; the firehose covers those.

On `full` scope only, also scrape the sherpa-onnx docs page listing supported pretrained models (multi-MB page; its unique value is the complete per-language inventory for gap analysis):

```
https://k2-fsa.github.io/sherpa/onnx/pretrained_models/index.html
```

### Family queries (API rows are the discovery mechanism)

```
# API discovery rows (structured, one per family)
https://huggingface.co/api/models?search=parakeet&sort=lastModified&direction=-1&limit=20
https://huggingface.co/api/models?search=qwen3+asr&sort=lastModified&direction=-1&limit=20
https://huggingface.co/api/models?search=whisper+distil&sort=lastModified&direction=-1&limit=15
https://huggingface.co/api/models?search=sense-voice&sort=lastModified&direction=-1&limit=15
https://huggingface.co/api/models?search=canary+asr&sort=lastModified&direction=-1&limit=15

# Web searches only for families with no API row above
"nemo transducer onnx int8"
"zipformer ctc onnx"
```

General ONNX landscape (one query, not several reorderings):

```
"onnx speech recognition"
```

### Per-language sweeps (only when the scope names languages)

For `languages:X,Y` scopes, and for the Italian reference sweep on `full` runs, use ONE API call plus ONE web search per language (verified against Italian: the old 5-query template returned 1, 0, and 0 results; this pair returns 15 relevant hits):

```
https://huggingface.co/api/models?search={language}&pipeline_tag=automatic-speech-recognition&sort=lastModified&direction=-1&limit=15
"{language} speech recognition"    # web: blog and GitHub signal only
```

Pick the sweep languages from: the scope argument, open GH issues and community-catalog requests (issues #69/#70), and the languages where the previous report flagged a coverage gap.

### Model Detail Scrapes (use `crw scrape`)

For each promising result from search, fetch details:

```
https://huggingface.co/{model_id}
```

When a model page is gated (e.g., NVIDIA permission requests), also scrape its `discussions/{n}`: they carry status updates from the model authors.

## HuggingFace LLM Model Searches

```
# API discovery rows
https://huggingface.co/api/models?search=multimodal+audio+small&sort=lastModified&direction=-1&limit=15
https://huggingface.co/api/models?search=gemma+gguf&sort=lastModified&direction=-1&limit=15

# Web search
"small multilingual llm gguf"
```

## Framework Release Queries

curl the GitHub API endpoints (the releases JSON carries the full release body; the HTML page below is only the rate-limit fallback):

```
# sherpa-onnx
https://api.github.com/repos/k2-fsa/sherpa-onnx/releases?per_page=10

# ONNX Runtime
https://api.github.com/repos/microsoft/onnxruntime/releases?per_page=5

# LiteRT-LM (the working Gemma audio path; its HTML releases page is the primary source, fetch with WebFetch)
https://github.com/google-ai-edge/LitERT-LM/releases
```

Rate-limit fallback (only when the API calls above fail):

```
https://github.com/k2-fsa/sherpa-onnx/releases
```

## Scope-Specific Query Selection

| Scope | Queries to run |
|-------|---------------|
| `full` | Firehose + family queries + Italian reference sweep + docs inventory page + all framework and LLM queries + landscape |
| `asr` | Firehose + all ASR family queries |
| `llm` | All LLM queries above |
| `frameworks` | All framework queries above |
| `parakeet` | Parakeet API row only |
| `whisper` | Whisper API row only |
| `qwen` | Qwen API row only |
| `languages:X,Y` | Per-language pair for X,Y plus the firehose |

## Tracked Items

The canonical tracked-items table lives in `.claude/skills/model-scout/SKILL.md` (Tracked Items section; it owns URL, why, and added-date). Each run: scrape every URL in that table with `crw scrape` and report status changes. For GitHub issue rows, also check linked PRs:

```
https://github.com/k2-fsa/sherpa-onnx/pulls?q={topic-from-the-row}
```

## Community Intake Threads

Catalog-candidate requests and language-coverage discussions land in the app's GH issues. Check these before per-language sweeps to prioritize languages users actually ask for:

```
https://github.com/RisorseArtificiali/anti-vocale/issues/69
https://github.com/RisorseArtificiali/anti-vocale/issues/70
```

## Landscape Research

Use `crw search` for broad discovery:
- Search: "on-device ASR 2026 mobile speech recognition new models"
- Search: "streaming ASR onnx android"

Use `crw scrape` for specific pages (sherpa-onnx releases belong to the framework section above; do not fetch them again here):
- https://huggingface.co/blog (ASR/speech posts)
- https://k2-fsa.github.io/sherpa/onnx/ (documentation for new model support)

## Why crw over curl for pages

- HuggingFace web pages redirect curl (302s); crw follows redirects natively
- File size metadata is often unavailable via API; crw scrapes the page content which includes file listings and sizes
- crw handles JavaScript-rendered pages that curl cannot
- Gated model discussion pages are accessible via crw but not via raw API calls
