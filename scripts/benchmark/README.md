# Anti-Vocale ASR Benchmark Scripts

Benchmarks for the ASR models used by Anti-Vocale.

## Italian 4-Model Comparison (2026-04-26)

8 Italian FLEURS test samples, ~101.6s total audio, x86_64 desktop, 4 threads.

| Model | WER | RTF | Size |
|-------|-----|-----|------|
| **Distil Large V3 IT** | **4.3%** | 0.723x | 939MB |
| **Parakeet TDT** | 5.4% | **0.041x** | **640MB** |
| Whisper Turbo | 6.3% | 1.217x | 990MB |
| Qwen3-ASR 0.6B | 12.2% | 0.278x | 954MB |

### Per-Sample WER

| # | Parakeet | Qwen3 | Turbo | Distil IT | Best |
|---|---------|-------|-------|-----------|------|
| 0 | 4.3% | 8.7% | **0.0%** | **0.0%** | Turbo/Distil |
| 1 | **0.0%** | 3.4% | **0.0%** | **0.0%** | 3-way tie |
| 2 | 13.0% | 17.4% | **8.7%** | **8.7%** | Turbo/Distil |
| 3 | **0.0%** | 10.3% | 3.4% | **0.0%** | Parakeet/Distil |
| 4 | 0.0% | 0.0% | 0.0% | 0.0% | All tie |
| 5 | 0.0% | 0.0% | 0.0% | 0.0% | All tie |
| 6 | **21.7%** | 39.1% | 34.8% | **21.7%** | Parakeet/Distil |
| 7 | 3.7% | 18.5% | 3.7% | 3.7% | 3-way tie |

### Conclusion

**Parakeet TDT** is the best default for on-device Italian transcription — only 1.1% WER
behind the accuracy leader, but 17.6x faster and 300MB smaller.

**Distil Large V3 IT** is the accuracy champion (Italian-specific training) and could serve
as a "high quality" background re-processing option.

**Whisper Turbo** and **Qwen3-ASR** are worse than Parakeet on every dimension for Italian.

## Setup

```bash
pip install sherpa-onnx soundfile numpy
```

## Scripts

### `benchmark_qwen3_vs_parakeet.py`

Head-to-head comparison of any two models (Parakeet TDT vs Qwen3-ASR by default).
Supports three modes:

```bash
# FLEURS benchmark (needs references.json)
python benchmark_qwen3_vs_parakeet.py fleurs \
    --parakeet-dir /path/to/parakeet-model \
    --qwen3-dir /path/to/qwen3-model \
    --references references.json \
    --output results.json

# Real audio benchmark (single file + manual reference)
python benchmark_qwen3_vs_parakeet.py real-audio \
    --parakeet-dir /path/to/parakeet-model \
    --qwen3-dir /path/to/qwen3-model \
    --audio voice-message.wav \
    --reference "manual transcription"

# Full (FLEURS + real audio combined)
python benchmark_qwen3_vs_parakeet.py full \
    --parakeet-dir /path/to/parakeet-model \
    --qwen3-dir /path/to/qwen3-model \
    --references references.json \
    --audio voice-message.wav \
    --reference "manual transcription"
```

Outputs per-sample WER breakdown, error pattern analysis, and a final recommendation.

### `benchmark_decoding.py`
Compares decoding methods (`greedy_search` vs `modified_beam_search`) on Italian FLEURS samples.
Requires a `references.json` file with `[{"path": "file.wav", "reference": "transcription"}, ...]`.

### `benchmark_truncation.py`
Tests tail padding and `blank_penalty` to mitigate audio truncation.
Uses the same `references.json` format.

### `benchmark_real_audio.py`
Benchmarks all config variations on a single real audio file (e.g., WhatsApp voice message).
No reference JSON needed — outputs raw transcriptions for manual comparison.

## References JSON Format

All FLEURS-based scripts expect a JSON file:
```json
[
  {"path": "/path/to/sample.wav", "reference": "transcription text", "duration_s": 11.5},
  ...
]
```
