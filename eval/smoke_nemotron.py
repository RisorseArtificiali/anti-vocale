#!/usr/bin/env python3
"""
Smoke test for the re-exported Nemotron 3.5 streaming model.

Background: k2-fsa/sherpa-onnx PR #3732 fixed the multilingual export's
encoder.att_context_size (70→56) and re-published the model on 2026-07-09.
This validates the re-export artifact — NOT a WER eval (no Italian reference
set exists yet; see eval/README.md). It confirms:

  1. The fixed model loads cleanly via sherpa-onnx (online transducer path).
  2. It produces sane, non-garbled, non-looping output on the multilingual
     test_wavs that ship with the repo (en/es/fr/de — Western-language
     proxies; no Italian test wav is shipped).

API note: run_baseline.py uses OnlineRecognizer.from_args, which does not exist
in sherpa-onnx 1.13.3 (the Online*Config classes aren't exposed in Python
either). The version-stable construction is the OnlineRecognizer.from_transducer
classmethod used here. Stream methods (create_stream / accept_waveform /
input_finished / decode_stream / set_option) and the pure metrics
(tokenize / repetition_loops) are reused from run_baseline for parity.

Each clip runs two ways:
  - forced: stream.set_option("language", <lang>) — removes auto-detection as a
    confound, isolating the att_context_size fix.
  - auto  : no language hint — the app's default mode, what users actually get.

Run:  eval/.venv/bin/python eval/smoke_nemotron.py
"""
from __future__ import annotations

import sys
import time
from pathlib import Path

import numpy as np
import sherpa_onnx
import soundfile as sf

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

# Reuse the harness's pure metrics + recognition params for parity.
import run_baseline as rb  # noqa: E402

MODEL_DIR = rb.BACKENDS["nemotron"]["dir"]
TEST_WAVS = MODEL_DIR / "test_wavs"
# en = sanity baseline (Nemotron EN should be near-perfect);
# es/fr/de = Romance/Western-language proxies closest to Italian.
LANGS = ["en", "es", "fr", "de"]
LOOP_THRESHOLD = rb.LOOP_THRESHOLD
SAMPLE_RATE = rb.SAMPLE_RATE  # 16000


def build_recognizer():
    """Construct the streaming transducer recognizer, mirroring the shipped
    NemotronStreamingBackend.kt config (greedy_search, empty modelType is the
    from_transducer default, cpu provider)."""
    d = MODEL_DIR
    return sherpa_onnx.OnlineRecognizer.from_transducer(
        tokens=str(d / "tokens.txt"),
        encoder=str(d / "encoder.int8.onnx"),
        decoder=str(d / "decoder.int8.onnx"),
        joiner=str(d / "joiner.int8.onnx"),
        num_threads=rb.NUM_THREADS,
        sample_rate=SAMPLE_RATE,
        feature_dim=80,
        decoding_method="greedy_search",
        provider="cpu",
    )


def load_audio(path: Path) -> np.ndarray:
    """Load any wav as 16 kHz mono float32. test_wavs are already 16k mono, but
    guard against a different sample rate by linear-resampling if needed."""
    data, sr = sf.read(str(path), dtype="float32", always_2d=False)
    if data.ndim > 1:
        data = data[:, 0]
    if sr != SAMPLE_RATE:
        # Simple linear resample — test_wavs are 16k so this rarely fires.
        n = int(round(len(data) * SAMPLE_RATE / sr))
        idx = np.linspace(0, len(data) - 1, n)
        data = np.interp(idx, np.arange(len(data)), data).astype(np.float32)
    return np.ascontiguousarray(data, dtype=np.float32)


def recognize(rec, samples, language=None):
    """Batch-via-online. Optional language hint isolates the att_context_size
    fix from auto-detection. Feeds 0.5s chunks, signals end-of-input, then runs
    the is_ready→decode_stream loop to drain (a single decode_stream only
    advances one step). Reads text via recognizer.get_result (1.13.3 API)."""
    stream = rec.create_stream()
    if language:
        stream.set_option("language", language)
    chunk = int(0.5 * SAMPLE_RATE)
    for i in range(0, len(samples), chunk):
        stream.accept_waveform(SAMPLE_RATE, samples[i:i + chunk])
    stream.input_finished()
    while rec.is_ready(stream):
        rec.decode_stream(stream)
    res = rec.get_result(stream)
    return res if isinstance(res, str) else (getattr(res, "text", "") or "")


def main() -> None:
    print(f"Model dir : {MODEL_DIR}")
    print(f"Loop thr  : {LOOP_THRESHOLD} consecutive identical tokens")
    print(f"Threads   : {rb.NUM_THREADS} | sample_rate={SAMPLE_RATE} | feature_dim=80\n")
    if not TEST_WAVS.is_dir():
        sys.exit(f"No test_wavs dir at {TEST_WAVS}")

    print("Loading OnlineRecognizer (from_transducer, mirrors NemotronStreamingBackend.kt) ...")
    rec = build_recognizer()
    print("  loaded OK\n")

    for lang in LANGS:
        wav = TEST_WAVS / f"{lang}.wav"
        if not wav.exists():
            print(f"[{lang}] MISSING {wav} — skip\n")
            continue
        samples = load_audio(wav)
        dur = len(samples) / SAMPLE_RATE

        print(f"[{lang}] {dur:4.1f}s audio")
        for label, lang_hint in (("force", lang), ("auto", None)):
            t0 = time.time()
            try:
                hyp = recognize(rec, samples, language=lang_hint)
            except Exception as e:  # noqa: BLE001
                print(f"   {label:5s} ERROR: {e}")
                continue
            dt = time.time() - t0
            toks = rb.tokenize(hyp)
            loops = rb.repetition_loops(toks, LOOP_THRESHOLD)
            flag = "  ⚠ LOOPS" if loops else ""
            print(f"   {label:5s} {dt:4.2f}s | loops={loops}{flag}")
            print(f"          «{hyp}»")
        print()


if __name__ == "__main__":
    main()
