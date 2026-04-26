# Parakeet TDT v3 Benchmark Scripts

Benchmarks for the Parakeet TDT v3 ASR model (`sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8`)
used by Anti-Vocale's `SherpaOnnxBackend`.

## Setup

```bash
pip install sherpa-onnx soundfile numpy
```

Download the model and extract:
```bash
MODEL_URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8.tar.bz2"
wget "$MODEL_URL" -O /tmp/parakeet-model.tar.bz2
tar xf /tmp/parakeet-model.tar.bz2 -C /tmp/
```

## Scripts

### `benchmark_decoding.py`
Compares decoding methods (`greedy_search` vs `modified_beam_search`) on Italian FLEURS samples.
Requires a `references.json` file with `[{"path": "file.wav", "reference": "transcription"}, ...]`.

### `benchmark_truncation.py`
Tests tail padding and `blank_penalty` to mitigate audio truncation.
Uses the same `references.json` format.

### `benchmark_real_audio.py`
Benchmarks all config variations on a single real audio file (e.g., WhatsApp voice message).
No reference JSON needed — outputs raw transcriptions for manual comparison.

## Usage

```bash
# Decoding comparison (needs references.json + model dir)
python benchmark_decoding.py

# Truncation/padding tests (needs references.json + model dir)
python benchmark_truncation.py

# Single real audio file (just needs model dir + wav file)
python benchmark_real_audio.py /path/to/audio.wav
```

Edit `MODEL_DIR` at the top of each script to point to the extracted model directory.
