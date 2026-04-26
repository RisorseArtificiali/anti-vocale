#!/usr/bin/env python3
"""Benchmark Parakeet on a real audio file with all config variations.

Usage:
    python benchmark_real_audio.py /path/to/audio.wav
"""

import sys
import numpy as np
import sherpa_onnx
import soundfile as sf
import time

MODEL_DIR = "/tmp/parakeet-bench/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"

def create_rec(decoding="greedy_search", blank_penalty=0.0, max_active_paths=4):
    return sherpa_onnx.OfflineRecognizer.from_transducer(
        encoder=f"{MODEL_DIR}/encoder.int8.onnx",
        decoder=f"{MODEL_DIR}/decoder.int8.onnx",
        joiner=f"{MODEL_DIR}/joiner.int8.onnx",
        tokens=f"{MODEL_DIR}/tokens.txt",
        num_threads=4,
        decoding_method=decoding,
        max_active_paths=max_active_paths,
        blank_penalty=blank_penalty,
        model_type="nemo_transducer",
    )

def transcribe(rec, audio, sr=16000):
    t0 = time.time()
    stream = rec.create_stream()
    stream.accept_waveform(sr, audio)
    rec.decode_stream(stream)
    elapsed = time.time() - t0
    return stream.result.text.strip(), elapsed

def main():
    if len(sys.argv) < 2:
        print(f"Usage: {sys.argv[0]} <audio.wav>")
        sys.exit(1)

    audio_path = sys.argv[1]

    audio, sr = sf.read(audio_path)
    if audio.ndim > 1:
        audio = audio[:, 0]
    audio = audio.astype(np.float32)
    duration = len(audio) / sr
    print(f"Audio: {duration:.1f}s, {len(audio)} samples, {sr}Hz\n")

    configs = [
        ("greedy_search, no pad",        "greedy_search",         0.0, 0.0, 4),
        ("greedy_search, +1s pad",       "greedy_search",         0.0, 1.0, 4),
        ("greedy_search, +2s pad",       "greedy_search",         0.0, 2.0, 4),
        ("greedy_search, +3s pad",       "greedy_search",         0.0, 3.0, 4),
        ("greedy, blank_penalty=0.5",    "greedy_search",         0.5, 0.0, 4),
        ("greedy, blank_penalty=1.0",    "greedy_search",         1.0, 0.0, 4),
        ("greedy, bp=1.0 + 2s pad",     "greedy_search",         1.0, 2.0, 4),
        ("beam_search(4), no pad",       "modified_beam_search",  0.0, 0.0, 4),
        ("beam_search(25), no pad",      "modified_beam_search",  0.0, 0.0, 25),
    ]

    print(f"{'Config':<35} {'Time':>6} {'Chars':>6}  Transcription")
    print(f"{'─'*100}")

    for label, decoding, bp, padding, paths in configs:
        rec = create_rec(decoding=decoding, blank_penalty=bp, max_active_paths=paths)

        if padding > 0:
            test_audio = np.concatenate([audio, np.zeros(int(sr * padding), dtype=np.float32)])
        else:
            test_audio = audio

        text, elapsed = transcribe(rec, test_audio)

        print(f"{label:<35} {elapsed:>5.1f}s {len(text):>6}  {text[:80]}")
        if len(text) > 80:
            remaining = text[80:]
            while remaining:
                print(f"{'':55}{remaining[:80]}")
                remaining = remaining[80:]
        print()

if __name__ == "__main__":
    main()
