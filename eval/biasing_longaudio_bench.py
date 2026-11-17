#!/usr/bin/env python3
"""TASK-1004 lane 4, desktop leg: biasing at high scores on LONG audio.

The 10.4-min Italian clip (40 FLEURS segments + 5 OmniVoice sentences carrying
the dictionary terms, /tmp/av-1017-clips/it-long.wav). Arms: unarmed greedy,
armed 1.5, armed 4.0. Checks: (a) the term sentences' fate (do Boncompagni /
Chiaramonte / Guicciardini stay correct, and does the unarmed baseline miss
any so the fix direction is visible); (b) no runaway degradation of the FLEURS
segments (WER of the full transcript against the reconstructed interleaved
reference, per-arm); (c) sane wall time (no leak-class slowdown as audio grows).

Rebuilding the inputs (the clip lives in /tmp and the FLEURS cache is
gitignored; the bench crashes with FileNotFoundError when either is gone):
- The FLEURS side regenerates itself: fleurs_bench.get_clips("it_it", 40)
  downloads the parquet on demand into eval/.fleurs_cache.
- The 5 term sentences come from OmniVoice TTS (loopback, no auth):
  curl -s -X POST http://127.0.0.1:3900/v1/audio/speech \
    -H "Content-Type: application/json" \
    -d '{"input":"<TERM_SENTENCES entry>","response_format":"wav"}' -o term-N.wav
  then resample to 16k mono (ffmpeg -i term-N.wav -ar 16000 -ac 1).
- The long clip = the interleaving below (7 FLEURS wavs, 1 term sentence,
  0.7s silence between pieces), concatenated at 16kHz mono.

Usage:
  eval/.venv/bin/python eval/biasing_longaudio_bench.py
"""
from __future__ import annotations

import json
import os
import sys
import tempfile
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import jiwer  # noqa: E402
import run_baseline as rb  # noqa: E402
import sherpa_onnx  # noqa: E402
from biasing_nonregression_bench import build_parakeet  # noqa: E402

CLIP = Path("/tmp/av-1017-clips/it-long.wav")
TERMS = "Boncompagni, Chiaramonte, Guicciardini"
MANIFEST = HERE / ".fleurs_cache" / "work" / "it_it" / "manifest.json"
# The exact sentences the clip interleaves every 7 FLEURS segments (the
# reconstruction recipe in the module docstring builds them).
TERM_SENTENCES = [
    "Il conte Boncompagni ricevette una lettera dal duca.",
    "A Chiaramonte si terrà la riunione di domani.",
    "Guicciardini scrisse la storia d'Italia.",
    "La famiglia Boncompagni abitava vicino a Chiaramonte.",
    "Secondo Guicciardini, la fortuna guida metà delle nostre azioni.",
]


def expected_transcript() -> str:
    """The interleaved reference: each group of 7 FLEURS refs followed by one
    term sentence, matching the clip's construction order."""
    refs = [e["ref"] for e in json.load(open(MANIFEST))[:40]]
    seq: list[str] = []
    for gi, i in enumerate(range(0, len(refs), 7)):
        seq.extend(refs[i : i + 7])
        if gi < len(TERM_SENTENCES):
            seq.append(TERM_SENTENCES[gi])
    return " ".join(seq)


def main() -> None:
    from audio_loader import load_audio

    samples = load_audio(str(CLIP), sample_rate=rb.SAMPLE_RATE)
    expected = expected_transcript()
    with tempfile.NamedTemporaryFile("w", suffix=".txt", delete=False, encoding="utf-8") as tf:
        tf.write("\n".join(t.strip() for t in TERMS.split(", ")))
        hw = tf.name
    bpe = str(rb.BACKENDS["parakeet"]["dir"] / "bpe.vocab")

    n = lambda t: " ".join(t.split())  # noqa: E731
    try:
        for name, kw in [
            ("unarmed-greedy", dict(hotwords_file=None, hotwords_score=0.0)),
            ("armed-1.5", dict(hotwords_file=hw, hotwords_score=1.5, bpe_vocab=bpe)),
            ("armed-4.0", dict(hotwords_file=hw, hotwords_score=4.0, bpe_vocab=bpe)),
        ]:
            rec = build_parakeet(**kw)
            t0 = time.perf_counter()
            text = rb.recognize_offline(rec, samples, rb.BACKENDS["parakeet"]).strip()
            wall = time.perf_counter() - t0
            Path(f"/tmp/av-1017-clips/long-{name}.txt").write_text(text, encoding="utf-8")
            terms_ok = {t: text.count(t) for t in TERMS.split(", ")}
            wer = jiwer.wer(n(expected), n(text))
            print(f"RESULT {name}: wall {wall:.0f}s ({len(samples)/rb.SAMPLE_RATE/wall:.2f}x realtime)")
            print(f"  WER (interleaved ref): {wer*100:.2f}")
            print(f"  term hits: {terms_ok}")
            print(f"  tail: ...{text[-160:]}")
    finally:
        os.unlink(hw)


if __name__ == "__main__":
    main()
