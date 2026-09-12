# Scout Report: Google AI Edge Gallery (v1.0.14) & Ecosystem

**Date:** 2026-05-19
**Trigger:** Google AI Edge Gallery updated to v1.0.14 with Gemma 4 support
**Scope:** On-device ASR, transcription, and relevant ML techniques

---

## Executive Summary

Google's AI Edge ecosystem has rapidly evolved since its GitHub launch in April 2025. The Gallery app (v1.0.14, May 2026) now features **Audio Scribe** (on-device transcription via Gemma 3n/4), launched a separate **Eloquent** dictation app (iOS-only), and upgraded to **Gemma 4** with native audio input on the E2B/E4B edge variants. However, the LLM-based transcription approach has significant limitations compared to dedicated ASR models. **Bottom line: nothing worth copying for Anti-Vocale's core transcription pipeline, but two peripheral ideas merit attention.**

---

## What's New in the Google AI Edge Ecosystem

### 1. Google AI Edge Gallery v1.0.14 (May 2026)

Latest release adds:
- **Experimental MCP support** — Model Context Protocol for tool integration
- **New Agent Skills**: `create-calendar-event`, `read-calendar-events`, `schedule-notification`, `learn-something-new`
- **Hardware & performance enhancements** (details sparse)
- **Gemma 4 support** — full model family integration
- Available on Google Play Store (open beta) and GitHub (19 releases total)

### 2. Audio Scribe Feature

On-device transcription and translation workspace:
- Uses **Gemma 3n** audio encoder (Universal Speech Model / USM-based)
- Upload audio clips OR use device microphone
- Runs completely offline via MediaPipe LLM Inference API
- **Current limitation: 30-second clips maximum, batch mode only (no streaming)**
- Generates tokens every 160ms (~6 tokens/sec of audio)

### 3. Gemma 4 Audio Capabilities (NEW — April 2026)

Gemma 4 E2B and E4B add native audio input:
- Audio encoder is **50% smaller** than Gemma 3n's encoder
- Supports ASR (speech-to-text) and AST (speech-to-translated-text)
- Strong translation results for **English <> Spanish, French, Italian, Portuguese**
- Runs on-device: E2B needs ~1.5GB RAM (Q4), E4B needs ~5GB
- Same 30-second clip limitation as Gemma 3n
- 128K context window (edge models)
- Uses prompt-based transcription: "Transcribe the following speech segment in {LANGUAGE}..."

### 4. Google AI Edge Eloquent (NEW — April 2026)

Separate iOS-only dictation app:
- **Gemma-based ASR models** run on-device after download
- Real-time transcription with **automatic filler word removal** ("um", "ah", "uh")
- Text polishing on pause (structured/formal/short/long transforms)
- Optional cloud mode using Gemini for enhanced cleanup
- **Personal vocabulary dictionary** — imports names/jargon from Gmail
- Floating button for system-wide access (Android version referenced but not released)
- Free, no subscription, no usage caps

### 5. Box Fork (jegly/Box) — Community Reference

Independent fork of Google AI Edge Gallery:
- **Added whisper.cpp** alongside LiteRT for speech-to-text
- Voice mode (speech-to-speech AI chat)
- Encrypted chat history, biometric lock
- Supports GGUF model import
- Validates dedicated ASR models as complementary to LLM inference

---

## Relevance Assessment for Anti-Vocale

### LOW RELEVANCE — Core Transcription Pipeline

| Aspect | Google Edge Gallery | Anti-Vocale |
|--------|-------------------|-------------|
| ASR approach | LLM-based (Gemma audio encoder) | Dedicated ASR (Distil-Whisper via sherpa-onnx) |
| Italian quality | Unknown, likely worse (no published ASR benchmarks) | 4.3% WER (Distil IT, best available) |
| Clip length | 30 seconds max | Unlimited |
| Streaming | Not supported | Via VAD segmentation |
| Speed | LLM inference overhead | 17.6x faster than Distil via Parakeet |
| Model size | 1.5-5GB (E2B/E4B) | ~100-300MB (Distil-Whisper INT8) |

**Verdict:** Gemma-based ASR is a demo/showcase, not a production-quality transcription solution. Anti-Vocale's dedicated ASR models deliver better quality at a fraction of the cost. The Box fork's decision to **add** whisper.cpp validates that dedicated ASR models remain superior for transcription.

### MODERATE RELEVANCE — Post-Processing Ideas

1. **Filler word removal** (from Eloquent): Anti-Vocale could add an optional post-processing step that strips Italian filler words ("ehm", "cioè", "praticamente") from transcriptions. This is a text-only operation, no ML needed.

2. **Text polishing transforms** (from Eloquent): Offer users options to clean up transcriptions — remove hesitation markers, fix sentence boundaries, normalize formatting. Could be done with simple heuristics or an LLM pass if the user enables it.

### LOW RELEVANCE — Speech Translation

Gemma 4's strong EN<>IT speech translation could theoretically add a "transcribe and translate" mode. But:
- Would require bundling a 1.5-5GB model
- Use case is tangential to Anti-Vocale's core mission
- Translation quality for Italian specifically is unverified at the edge model sizes

### NOTABLE REFERENCE — Architecture Validation

The Box fork (jegly/Box) is the most relevant artifact. It explicitly chose to add whisper.cpp to Google's LLM-centric gallery app, confirming that **dedicated ASR models (Whisper family via native inference) outperform LLM-based transcription for practical use cases**. This validates Anti-Vocale's architecture choice.

---

## Key Technical Details

### Gemma 4 Audio Architecture
- Audio encoder converts raw speech to embeddings (log-mel spectrograms → tokens)
- Input: mono-channel, 16kHz, float32 waveforms in [-1, 1]
- 160ms per audio token (~6 tokens/sec)
- Prompt-based transcription (no dedicated ASR decoder)
- Per-Layer Embeddings (PLE) for memory efficiency
- MatFormer architecture for compute flexibility

### Eloquent Pipeline
1. Record audio via microphone
2. On-device Gemma ASR model transcribes in real-time
3. On pause: automatic filler word filtering
4. Optional: cloud-based Gemini cleanup
5. Output: clean text with optional formatting transforms

---

## Actionable Takeaways

1. **No changes needed to core ASR pipeline** — Anti-Vocale's sherpa-onnx + Distil-Whisper Italian setup outperforms Google's LLM-based approach for the transcription use case.

2. **Consider adding filler word removal** — Simple regex/heuristic pass on Italian transcriptions. Words to target: "ehm", "um", "cioè" (when used as filler), "praticamente", "diciamo", "eccetera". Low effort, high UX value.

3. **Watch Eloquent's Android release** — When Google ports Eloquent to Android, its personal vocabulary feature (importing custom words/names) could inspire a similar feature in Anti-Vocale's whitelist system.

4. **No immediate Gemma integration needed** — The 30-second clip limitation and LLM-inference overhead make Gemma audio impractical for Anti-Vocale's voice message transcription use case.

---

## Sources

- Google AI Edge Gallery GitHub: https://github.com/google-ai-edge/gallery/releases
- Gemma 4 audio docs: https://ai.google.dev/gemma/docs/capabilities/audio
- Gemma 3n developer guide: https://developers.googleblog.com/en/introducing-gemma-3n-developer-guide/
- Google AI Edge Gallery audio blog: https://developers.googleblog.com/google-ai-edge-gallery-now-with-audio-and-on-google-play/
- TechCrunch Eloquent coverage: https://techcrunch.com/2026/04/07/google-quietly-releases-an-offline-first-ai-dictation-app-on-ios/
- Box fork (whisper.cpp integration): https://github.com/jegly/Box
- Gemma 4 audio encoder analysis: https://www.mindstudio.ai/blog/gemma-4-audio-encoder-e2b-e4b-speech-recognition/
- Gemma 4 announcement: https://blog.google/innovation-and-ai/technology/developers-tools/gemma-4/
