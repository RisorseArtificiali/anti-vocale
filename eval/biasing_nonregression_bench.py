#!/usr/bin/env python3
"""TASK-1002: the biasing feature's non-regression on the Italian FLEURS corpus.

The app's semantics (SherpaBackend.initOffline, 1.17):
- dictionary EMPTY  -> greedy_search, NO hotwords keys at all (the shipped
  default path, byte-identical to pre-feature);
- dictionary ARMED  -> modified_beam_search(4) + hotwords file + hotwords score.

So the honest corpus question is not "does arming change nothing" (the decoder
changes by design) but "does arming with NEUTRAL terms damage the corpus".
Arms:
  a. unarmed greedy                      (the default users keep)
  b. armed, neutral terms, score 1.5     (the feature's default knob)
  c. armed, neutral terms, score 3.0     (an aggressive-but-not-max knob)

Neutral terms are words FREQUENT IN THE REFS themselves: a user whose dictionary
names things the audio says correctly. Damage here = the feature hurting
correct transcripts, the class TASK-994 promised not to introduce.

Usage:
  eval/.venv/bin/python eval/biasing_nonregression_bench.py [--clips 40]
"""
from __future__ import annotations

import argparse
import os
import re
import sys
import tempfile
import time
from collections import Counter
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import jiwer  # noqa: E402
import run_baseline as rb  # noqa: E402
import sherpa_onnx  # noqa: E402


def build_parakeet(hotwords_file: str | None, hotwords_score: float, bpe_vocab: str = "", beam_only: bool = False):
    """Mirrors SherpaBackend.initOffline's armed/unarmed construction:
    armed = modified_beam_search(4) + hotwords + modelingUnit bpe + bpeVocab;
    unarmed = greedy, none of those keys (the shipped default path).
    beam_only isolates the decoder switch from the hotwords effect."""
    cfg = rb.BACKENDS["parakeet"]
    d = cfg["dir"]
    beam = hotwords_file is not None or beam_only
    armed = hotwords_file is not None
    return sherpa_onnx.OfflineRecognizer.from_transducer(
        tokens=str(d / cfg["tokens"]),
        encoder=str(d / cfg["encoder"]),
        decoder=str(d / cfg["decoder"]),
        joiner=str(d / cfg["joiner"]),
        num_threads=rb.NUM_THREADS,
        provider="cpu",
        decoding_method="modified_beam_search" if beam else "greedy_search",
        max_active_paths=4 if beam else 1,
        hotwords_file=hotwords_file or "",
        hotwords_score=hotwords_score if armed else 0.0,
        modeling_unit="bpe" if armed else "",
        bpe_vocab=bpe_vocab if armed else "",
        sample_rate=rb.SAMPLE_RATE,
        feature_dim=80,
        model_type=cfg.get("model_type", "nemo_transducer"),
    )


def neutral_terms(refs: list[str], k: int = 10) -> list[str]:
    """The k most frequent ref words of length >= 6: things the audio already
    says correctly, the neutral-dictionary user shape."""
    words = Counter()
    for r in refs:
        for w in re.findall(r"[A-Za-zÀ-ÿ]{6,}", r.lower()):
            words[w] += 1
    return [w for w, _ in words.most_common(k)]


def run_arm(rec, clips):
    from audio_loader import load_audio
    hyps, wall = [], 0.0
    for entry in clips:
        samples = load_audio(entry["wav"], sample_rate=rb.SAMPLE_RATE)
        t0 = time.perf_counter()
        stream = rec.create_stream()
        stream.accept_waveform(rb.SAMPLE_RATE, samples)
        rec.decode_stream(stream)
        wall += time.perf_counter() - t0
        hyps.append((getattr(stream.result, "text", "") or "").strip())
    return hyps, wall


def wer(refs, hyps):
    n = lambda t: " ".join(t.split())  # noqa: E731
    return jiwer.wer([n(r) for r in refs], [n(h) for h in hyps])


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--clips", type=int, default=40)
    args = ap.parse_args()

    from fleurs_bench import get_clips

    clips = get_clips("it_it", args.clips)
    refs = [e["ref"] for e in clips]
    terms = neutral_terms(refs)
    print(f"FLEURS it_it: {len(clips)} clips; neutral terms: {terms}")

    with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False, encoding="utf-8") as tf:
        tf.write("\n".join(terms))
        hw_path = tf.name

    bpe = str(rb.BACKENDS["parakeet"]["dir"] / "bpe.vocab")
    arms = [
        ("unarmed-greedy", dict(hotwords_file=None, hotwords_score=0.0)),
        ("beam-only", dict(hotwords_file=None, hotwords_score=0.0, beam_only=True)),
        ("armed-1.5", dict(hotwords_file=hw_path, hotwords_score=1.5, bpe_vocab=bpe)),
        ("armed-3.0", dict(hotwords_file=hw_path, hotwords_score=3.0, bpe_vocab=bpe)),
    ]
    results = {}
    for name, kw in arms:
        rec = build_parakeet(**kw)
        hyps, wall = run_arm(rec, clips)
        results[name] = hyps
        w = wer(refs, hyps)
        print(f"RESULT {name}: WER {w*100:.2f}  wall {wall:.1f}s")

    base = results["unarmed-greedy"]
    for other in ("beam-only", "armed-1.5", "armed-3.0"):
        d = sum(1 for x, y in zip(base, results[other]) if x != y)
        print(f"DIFF unarmed vs {other}: {d}/{len(base)} clips differ")
    try:
        for i in range(len(base)):
            marks = [n for n in ("beam-only", "armed-1.5", "armed-3.0") if results[n][i] != base[i]]
            if marks:
                print(f"  [{i}] greedy  : {base[i][:88]}")
                for n in marks:
                    print(f"      {n:9s}: {results[n][i][:88]}")
    finally:
        os.unlink(hw_path)


if __name__ == "__main__":
    main()
