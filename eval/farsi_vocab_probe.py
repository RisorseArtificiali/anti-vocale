#!/usr/bin/env python3
"""TASK-1007: prove the Farsi streaming model's hotwords mechanism is live
with the DERIVED bpe.vocab (extracted from mehdi-hf's .nemo tokenizer, 1024
pieces, aligned 1:1 with mentisialog's tokens.txt; only <blk> appended).

Mirrors SherpaBackend.initOnline's armed construction (bpeVocab +
modelingUnit=bpe at construction) and the per-request createStream(csv)
hotwords. The clip: an OmniVoice Persian synthesis carrying surnames.

Arms: unarmed (no vocab) vs armed with the CORRECT surnames vs armed with a
DELIBERATELY MISSPELLED variant: the Parakeet lesson (the flip experiment
proves the mechanism precisely by biasing toward a WRONG spelling) applies
here too, and it is the strongest liveness signal.

Usage:
  eval/.venv/bin/python eval/farsi_vocab_probe.py
"""
from __future__ import annotations

import os
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import run_baseline as rb  # noqa: E402
import sherpa_onnx  # noqa: E402

FA_DIR = rb.MODELS_ROOT / "nemotron-farsi-streaming-int8"
VOCAB = Path.home() / "data" / "farsi-vocab" / "27e8a96fcb4744ffbb3ea9b619427a35_tokenizer.vocab"
CLIP = Path("/tmp/av-1017-clips/fa-names.wav")


def build(bpe_vocab: str | None):
    d = FA_DIR
    armed = bpe_vocab is not None
    return sherpa_onnx.OnlineRecognizer.from_transducer(
        tokens=str(d / "tokens.txt"),
        encoder=str(d / "encoder.int8.onnx"),
        decoder=str(d / "decoder.int8.onnx"),
        joiner=str(d / "joiner.int8.onnx"),
        num_threads=rb.NUM_THREADS,
        provider="cpu",
        model_type="",  # Nemotron online: empty modelType
        decoding_method="modified_beam_search",
        max_active_paths=4,
        modeling_unit="bpe" if armed else "cjkchar",
        bpe_vocab=bpe_vocab or "",
    )


def run(rec, samples, hotwords: str) -> str:
    t0 = time.perf_counter()
    stream = rec.create_stream(hotwords)
    stream.accept_waveform(rb.SAMPLE_RATE, samples)
    stream.accept_waveform(rb.SAMPLE_RATE, __import__("numpy").zeros(int(rb.SAMPLE_RATE * 1.5), dtype="float32"))
    while rec.is_ready(stream):
        rec.decode_stream(stream)
    text = (rec.get_result(stream) or "").strip()
    dt = time.perf_counter() - t0
    return text, dt


def main() -> None:
    from audio_loader import load_audio
    import numpy as np  # noqa: F401 (referenced via __import__ in run)

    samples = load_audio(str(CLIP), sample_rate=rb.SAMPLE_RATE)
    vocab = str(VOCAB)

    arms = [
        ("unarmed", None, ""),
        ("armed-correct", vocab, "رضا قنبری، محمدی، احمدی، پارس"),
        ("armed-misspelled", vocab, "رضا غنبری، محمّدی، اهمدی، بارز"),
    ]
    for name, bpe, hw in arms:
        rec = build(bpe)
        text, dt = run(rec, samples, hw)
        print(f"RESULT {name}: {dt:.2f}s")
        print(f"  {text}")


if __name__ == "__main__":
    main()
