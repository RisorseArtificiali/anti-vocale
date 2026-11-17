#!/usr/bin/env python3
"""TASK-1001 lane 1, Cyrillic leg: the personal dictionary's hotwords ride
Cyrillic terms through GigaAM v3 (the transducer supplement path), mirroring
SherpaBackend.initOffline's armed construction exactly (beam 4 + hotwords +
modelingUnit bpe + bpeVocab).

The clip is an OmniVoice synthesis of a Russian sentence carrying surnames.
Arms: unarmed greedy (the default) vs armed at 1.5 and 3.0. The terms file is
one term per line, PersonalDictionary.toHotwordsFileLines's format.

Rebuilding the clip (/tmp wipes): OmniVoice TTS at loopback, no auth:
  curl -s -X POST http://127.0.0.1:3900/v1/audio/speech \
    -H "Content-Type: application/json" \
    -d '{"input":"Мой коллега Тимофей Щербаковский-Ковалевский поедет с Вячеславом Ксенофонтовым и Анастасией Ахмадуллиной в Санкт-Петербург на конференцию.","response_format":"wav"}' \
    -o /tmp/av-1017-clips/ru-hard.wav
(the GigaAM model itself downloads from pantinor/gigaam-v3 on HF; all five
sha256 are pinned in models_catalog.json).

Usage:
  eval/.venv/bin/python eval/biasing_nonlatin_bench.py [--clip ... --terms ...]
"""
from __future__ import annotations

import argparse
import os
import sys
import tempfile
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import run_baseline as rb  # noqa: E402
import sherpa_onnx  # noqa: E402

GIGAAM_DIR = rb.MODELS_ROOT / "gigaam-v3"
DEFAULT_CLIP = "/tmp/av-1017-clips/ru-hard.wav"
# The spoken (inflected) forms: with these, armed decoding at 1.5 AND 3.0
# restores the dropped hyphen and keeps every ending intact. Nominative-only
# terms instead eat the inflections (Ксенофонтовым -> Ксенофон at 1.5): for
# inflecting languages the dictionary wants the forms actually spoken.
DEFAULT_TERMS = "Щербаковский-Ковалевский, Ксенофонтовым, Ахмадуллиной"


def build(hotwords_file: str | None, score: float, bpe: str):
    armed = hotwords_file is not None
    return sherpa_onnx.OfflineRecognizer.from_transducer(
        tokens=str(GIGAAM_DIR / "gigaam_v3_e2e_rnnt_tokens.txt"),
        encoder=str(GIGAAM_DIR / "gigaam_v3_e2e_rnnt_encoder_int8.onnx"),
        decoder=str(GIGAAM_DIR / "gigaam_v3_e2e_rnnt_decoder.onnx"),
        joiner=str(GIGAAM_DIR / "gigaam_v3_e2e_rnnt_joint.onnx"),
        num_threads=rb.NUM_THREADS,
        provider="cpu",
        decoding_method="modified_beam_search" if armed else "greedy_search",
        max_active_paths=4 if armed else 1,
        hotwords_file=hotwords_file or "",
        hotwords_score=score if armed else 0.0,
        modeling_unit="bpe" if armed else "",
        bpe_vocab=bpe if armed else "",
        sample_rate=rb.SAMPLE_RATE,
        feature_dim=80,
        model_type="nemo_transducer",
    )


def run(rec, samples) -> str:
    t0 = time.perf_counter()
    stream = rec.create_stream()
    stream.accept_waveform(rb.SAMPLE_RATE, samples)
    rec.decode_stream(stream)
    dt = time.perf_counter() - t0
    return (getattr(stream.result, "text", "") or "").strip(), dt


def main() -> None:
    from audio_loader import load_audio

    ap = argparse.ArgumentParser()
    ap.add_argument("--clip", default=DEFAULT_CLIP)
    ap.add_argument("--terms", default=DEFAULT_TERMS)
    args = ap.parse_args()

    samples = load_audio(args.clip, sample_rate=rb.SAMPLE_RATE)
    with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False, encoding="utf-8") as tf:
        tf.write("\n".join(t.strip() for t in args.terms.split(", ")))
        hw = tf.name
    bpe = str(GIGAAM_DIR / "bpe.vocab")

    try:
        for name, h, s in [
            ("unarmed-greedy", None, 0.0),
            ("armed-1.5", hw, 1.5),
            ("armed-3.0", hw, 3.0),
        ]:
            rec = build(h, s, bpe)
            text, dt = run(rec, samples)
            print(f"RESULT {name}: {dt:.2f}s")
            print(f"  {text}")
    finally:
        os.unlink(hw)


if __name__ == "__main__":
    main()
