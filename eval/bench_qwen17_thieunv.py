#!/usr/bin/env python3
"""TASK-244 watch-bench: thieunv/sherpa-onnx-qwen3-asr-1.7B-int8 on eval-it
(11 clips), same methodology as the 2026-09-24 solavr rejection run: per-clip
WER/CER via run_baseline's helpers, wall-time RTF per clip, aggregate.
Anchors: our qwen3 0.6B = 12.2% WER, Parakeet v3 int8 = 5.4% on this set.
"""
import sys, time, wave
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import numpy as np
import sherpa_onnx
from audio_loader import load_audio
from run_baseline import normalize_it, wer, cer, repetition_loops

PKG = Path.home() / "models" / "qwen3-1.7b-thieunv"
CLIPS = Path(__file__).resolve().parent / "clips"
TRANSCRIPTS = Path(__file__).resolve().parent / "transcripts"
NUM_THREADS = 4

rec = sherpa_onnx.OfflineRecognizer.from_qwen3_asr(
    conv_frontend=str(PKG / "conv_frontend.onnx"),
    encoder=str(PKG / "encoder.int8.onnx"),
    decoder=str(PKG / "decoder.int8.onnx"),
    # The tokenizer arg is the DIRECTORY (sherpa-onnx 1.13.8 loads
    # vocab.json+merges.txt from it; passing vocab.json fails with a
    # misleading "Failed to read vocab.json" naming the file you gave).
    tokenizer=str(PKG / "tokenizer"),
    num_threads=NUM_THREADS,
    decoding_method="greedy_search",
)


def load_wav(p: Path) -> np.ndarray:
    return load_audio(p, sample_rate=16_000)


pairs = sorted(
    [(c, TRANSCRIPTS / f"{c.stem}.txt") for c in CLIPS.iterdir() if c.suffix in (".wav", ".ogg")],
    key=lambda x: x[0].name,
)
print(f"{'clip':22s} {'WER':>7s} {'CER':>7s} {'RTF':>6s}  text")
agg_w, agg_c, n_looped = [], [], 0
for clip, ref in pairs:
    samples = load_wav(clip)
    dur = len(samples) / 16000
    t0 = time.time()
    stream = rec.create_stream()
    stream.accept_waveform(16000, samples)
    rec.decode_stream(stream)
    dt = time.time() - t0
    hyp = stream.result.text.strip()
    reference = normalize_it(ref.read_text())
    hypothesis = normalize_it(hyp)
    w = wer(reference.split(), hypothesis.split()) * 100
    c = cer(reference, hypothesis) * 100
    loops = repetition_loops(hyp.split())
    if loops:
        n_looped += 1
    agg_w.append(w)
    agg_c.append(c)
    print(f"{clip.name:22s} {w:6.2f}% {c:6.2f}% {dt / dur:6.2f}x  {hyp[:70]}")

print("-" * 80)
print(f"AGGREGATE (micro, unweighted): WER {sum(agg_w) / len(agg_w):.3f}%  "
      f"CER {sum(agg_c) / len(agg_c):.3f}%  looped clips: {n_looped}/{len(pairs)}")
print(f"anchors: qwen3 0.6B = 12.2% WER, parakeet v3 int8 = 5.4% WER on this set")
