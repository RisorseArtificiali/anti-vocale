#!/usr/bin/env python3
"""TASK-1020 live anchor: Parakeet v3 int8 on the SAME clean-11 + noisy sets
as bench_rumble2flash.py, so the comparison is same-harness, not
cross-session. Sherpa leg (the app's actual runtime path)."""
import sys
import time
from pathlib import Path

import numpy as np
import sherpa_onnx

sys.path.insert(0, str(Path(__file__).resolve().parent))
from run_baseline import normalize_it, wer, cer, repetition_loops  # noqa: E402
from audio_loader import load_audio  # noqa: E402

EVAL = Path(__file__).resolve().parent
PKG = EVAL / "models" / "parakeet-tdt-0.6b-v3-int8"

rec = sherpa_onnx.OfflineRecognizer.from_transducer(
    encoder=str(PKG / "encoder.int8.onnx"),
    decoder=str(PKG / "decoder.int8.onnx"),
    joiner=str(PKG / "joiner.int8.onnx"),
    tokens=str(PKG / "tokens.txt"),
    num_threads=4,
    decoding_method="greedy_search",
    model_type="nemo_transducer",
)


def run_set(pairs, label):
    print(f"\n== {label} ==")
    agg_w, agg_c = [], []
    for clip, ref in pairs:
        samples = load_audio(clip, sample_rate=16_000)
        dur = len(samples) / 16000
        t0 = time.time()
        stream = rec.create_stream()
        stream.accept_waveform(16000, samples)
        rec.decode_stream(stream)
        dt = time.time() - t0
        hyp = stream.result.text.strip()
        w = wer(normalize_it(ref.read_text()).split(), normalize_it(hyp).split()) * 100
        c = cer(normalize_it(ref.read_text()), normalize_it(hyp)) * 100
        agg_w.append(w)
        agg_c.append(c)
        print(f"{clip.name:34s} {w:6.2f}% {c:6.2f}% {dt/dur:5.2f}x  {hyp[:60]}")
    print(f"{label}: WER {np.mean(agg_w):.2f}%  CER {np.mean(agg_c):.2f}%")


clean_pairs = sorted(
    [
        (c, EVAL / "transcripts" / f"{c.stem}.txt")
        for c in (EVAL / "clips").iterdir()
        if c.suffix in (".wav", ".ogg")
    ],
    key=lambda x: x[0].name,
)
agnese_ref = EVAL / "transcripts" / "agnese.txt"
noisy_pairs = sorted(
    [(c, agnese_ref) for c in (EVAL / "clips_noisy").iterdir() if c.name.startswith("agnese__")],
    key=lambda x: x[0].name,
)
run_set(clean_pairs, "clean-11-parakeet")
run_set(noisy_pairs, "noisy-agnese-parakeet")
