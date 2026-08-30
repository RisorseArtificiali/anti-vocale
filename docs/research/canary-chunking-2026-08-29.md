# Canary 180M Flash: duration ceiling and chunk-boundary sensitivity (TASK-408)

Date: 2026-08-29. Harness: `eval/.venv` (sherpa-onnx 1.13.5, the pinned `.sherpa-version`), `csukuangfj/sherpa-onnx-nemo-canary-180m-flash-en-es-de-fr-int8` (encoder.int8 + decoder.int8 + tokens, 207,170,046 bytes), CPU, 4 threads, greedy search, `from_nemo_canary(src_lang=tgt_lang)`.

These measurements drive two decisions in the app: the CANARY family's `maxChunkDurationSeconds = 10` and its `requiresVadAlignedChunking` flag (the orchestrator routes canary through VAD segmentation regardless of the user toggle; selecting a canary model also flips the VAD preference on).

## 1. Single-pass duration ceiling (JFK clip tiled, en)

| Input | Decode | Result |
|---|---|---|
| 6s | 1.7s | correct |
| 8s | 6.2s | correct |
| 10s | 1.7s | EMPTY (immediate EOT; intermittent) |
| 12s | 6.1s | correct |
| 16s | 19.6s | correct but slower than realtime |
| 20s | 11.5s | correct but slow |
| 24s | 31.9s | superlinear |
| 35s | 20.4s | degenerate: duplicated phrases, truncated |
| 58s | 26.2s | degenerate: 12 phrase-repeats for 10 present |

Decode time grows superlinearly with duration (the int8 transformer decoder carries per-step KV memory lists), and past roughly 10 seconds quality stops being trustworthy. On a phone (several times slower than this desktop), a >10s single decode would be both unusably slow and unreliable: hence the 10s cap, not whisper's 30s.

## 2. Chunk-boundary sensitivity (58s tile = the JFK phrase x10)

| Segmentation | Recovered | Empty chunks |
|---|---|---|
| Fixed 8s cuts | 5/10 phrases | 4/8 |
| Fixed 10s cuts | 2/10 phrases | 4/6 |
| Silence/phrase-aligned cuts (one phrase per chunk) | 10/10 phrases | 0/10 |

Fixed-position cuts that land mid-speech make canary emit an immediate EOT: the chunk decodes EMPTY. Half the content disappears. This is the measured reason canary gets silence-aligned segmentation (the app's VAD path) rather than the pipeline's fixed cuts; whisper tolerates mid-speech cuts, canary does not.

Aligned chunks also decode fast: 15.5s wall for the full 58s file (~3.7x realtime on desktop).

## 3. Language conditioning

`src_lang`/`tgt_lang` are constructor parameters (en/es/de/fr; no auto-detection): the recognizer is built per language. Verified: en wav decodes the JFK quote exactly; de wav decodes "Alles hat ein Ende, nur die Wurst hat zwei." exactly. This is why the catalog ships one entry per language and the import dialog's canary panel is a fixed four-language dropdown.

## 4. Quality: FLEURS WER across the four languages (2026-08-30)

10 validation clips per language, the same eval/smallclass material the small-class models were measured on (desktop, int8 export, greedy):

| Language | Canary 180M Flash int8 | Same-clip baselines (small streaming tier) |
|---|---|---|
| English | 9.7% | (none on these clips) |
| Spanish | 5.1% | bookbot zipformer-es: 100% (broken IPA output) |
| German | 3.7% | whisper-tiny-de: load OK, not scored |
| French | 10.1% | kroko streaming zipformer-fr: 22.5% |

Caveats: clean read speech, not voice-message conditions; 10 clips per language is a spot check, not a benchmark campaign. Even so the placement is clear: far above the current small streaming tier and in the same league as the big models on its languages, at ~4x realtime warm on desktop. Raw details: /tmp/canary_fleurs.json (session artifact).
