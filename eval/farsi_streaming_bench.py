#!/usr/bin/env python3
"""TASK-997 stage 3: the Persian streaming Nemotron on FLEURS fa_ir.

The stock streaming Nemotron's bench rides fleurs_bench.py, which builds
OfflineRecognizers only; the Farsi fine-tune is the SAME online-transducer
architecture, so this script reuses the harness's ONE pieces (run_baseline's
recognize_online drain loop and recognizer builder, audio_loader, fleurs_bench's
parquet clip loader) and adds nothing of its own decode-wise.

Baselines on the SAME clips (offline recognizers, the plain fleurs_bench path):
parakeet v3 int8 (our default) and whisper turbo. The number to beat is OUR
multilingual-set fa quality, not the card's 8.81.

Usage:
  eval/.venv/bin/python eval/farsi_streaming_bench.py [--clips 40]
"""
from __future__ import annotations

import argparse
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import run_baseline as rb  # noqa: E402
import sherpa_onnx  # noqa: E402

FARSI_DIR = rb.MODELS_ROOT / "nemotron-farsi-streaming-int8"


def build_farsi_recognizer():
    cfg = {
        "kind": "online_transducer",
        "dir": FARSI_DIR,
        "encoder": "encoder.int8.onnx",
        "decoder": "decoder.int8.onnx",
        "joiner": "joiner.int8.onnx",
        "tokens": "tokens.txt",
        # The fine-tune is fa-only with its own 1024-piece tokenizer: no
        # language conditioning (the multilingual setOption has nothing to
        # pick here; the card makes no per-language claim).
        "language": "",
        "tail_pad_seconds": 1.5,
    }
    return rb.build_recognizer(cfg)


def run_offline(rec, clips) -> tuple[list[str], float, float]:
    """Offline decode of the SAME 16k wavs via the plain recognizer API."""
    import soundfile as sf
    import numpy as np
    hyps = []
    total_audio_s = 0.0
    total_wall_s = 0.0
    for entry in clips:
        samples, sr = sf.read(entry["wav"], dtype="float32")
        assert sr == rb.SAMPLE_RATE
        total_audio_s += len(samples) / sr
        t0 = time.perf_counter()
        stream = rec.create_stream()
        stream.accept_waveform(sr, samples)
        rec.decode_stream(stream)
        hyps.append((getattr(stream.result, "text", "") or "").strip())
        total_wall_s += time.perf_counter() - t0
    return hyps, total_audio_s, total_wall_s


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--clips", type=int, default=40)
    ap.add_argument("--skip-farsi", action="store_true")
    args = ap.parse_args()

    from fleurs_bench import get_clips
    from audio_loader import load_audio

    clips = get_clips("fa_ir", args.clips)
    print(f"FLEURS fa_ir: {len(clips)} clips")

    rec, _ = build_farsi_recognizer()
    print("Farsi OnlineRecognizer loaded")

    cfg = {"language": "", "tail_pad_seconds": 1.5}
    hyps, refs = [], []
    total_audio_s = 0.0
    total_wall_s = 0.0
    for entry in clips:
        path, ref = entry["wav"], entry["ref"]
        samples = load_audio(path, sample_rate=rb.SAMPLE_RATE)
        total_audio_s += len(samples) / rb.SAMPLE_RATE
        t0 = time.perf_counter()
        hyp = rb.recognize_online(rec, samples, cfg)
        total_wall_s += time.perf_counter() - t0
        hyps.append(hyp)
        refs.append(ref)

    # Corpus-level, the fleurs_bench convention (jiwer over the full ref/hyp
    # lists; rb.wer is per-utterance tokens).
    import jiwer
    def norm(t: str) -> str:
        return " ".join(t.split())
    wer = jiwer.wer([norm(r) for r in refs], [norm(h) for h in hyps])
    cer = jiwer.cer([norm(r).replace(" ", "") for r in refs],
                    [norm(h).replace(" ", "") for h in hyps])
    rtf = total_wall_s / total_audio_s if total_audio_s else float("nan")
    print(f"\nRESULTS farsi-streaming-nemotron fa_ir ({len(clips)} clips)")
    print(f"  WER {wer:.2f}  CER {cer:.2f}  RTF {rtf:.3f} (Mac, {rb.NUM_THREADS} threads)")
    if args.skip_farsi:
        rec = None
        hyps, total_wall_s = [], 0.0
        refs = [e["ref"] for e in clips]
    import jiwer as _jw
    def _norm(t: str) -> str:
        return " ".join(t.split())
    def _report(tag: str, hyps2, wall=0.0, audio=total_audio_s):
        w = _jw.wer([_norm(r) for r in refs], [_norm(h) for h in hyps2])
        c = _jw.cer([_norm(r).replace(" ", "") for r in refs],
                    [_norm(h).replace(" ", "") for h in hyps2])
        rtf = wall / audio if audio else float("nan")
        print(f"RESULT {tag}: WER {w*100:.2f} CER {c*100:.2f} RTF {rtf:.3f}")
    if not args.skip_farsi:
        _report("farsi-streaming-nemotron", hyps, total_wall_s)
    # Baseline 1: parakeet v3 int8 (offline transducer, our default model),
    # through the harness's own builder (the working parameter set).
    try:
        pk, _ = rb.build_recognizer(rb.BACKENDS["parakeet"])
        h, a, wl = run_offline(pk, clips)
        _report("parakeet-v3-int8", h, wl, a)
    except Exception as e:
        print(f"parakeet baseline FAILED: {e}")
    for i, (ref, hyp) in enumerate(zip(refs, hyps)):
        print(f"  [{i}] ref: {ref[:90]}")
        print(f"      hyp: {hyp[:90]}")


if __name__ == "__main__":
    main()
