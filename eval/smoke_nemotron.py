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

Single-decode-owner note (TASK-716): recognition runs through
run_baseline.recognize_online, the one online drain loop among the eval
scripts that import run_baseline (eval/smallclass/run_eval.py keeps its
own standalone probe by design: it does not import the harness). Whole-
clip feed, the 1.5s catalog tail pad, drain -> input_finished -> drain.
The previous private loop fed 0.5s chunks with no pad, the shape
run_baseline's own verification documents as garbling streaming output:
every test wav recovered trailing words when the shared loop landed
(en "co" to "cool", es/fr/de trailing phrases restored, recorded in
TASK-716). In the same retirement the recognizer construction and audio
loading became shared too (rb.build_recognizer; audio_loader.load_audio,
the ONE loader per TASK-461).

Each clip runs two ways:
  - forced: cfg language set to <lang>, removing auto-detection as a
    confound, isolating the att_context_size fix.
  - auto  : the catalog's "auto", the app's default mode.

Run:  eval/.venv/bin/python eval/smoke_nemotron.py
"""
from __future__ import annotations

import sys
import time
from pathlib import Path

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


def load_audio(path: Path):
    """The shared loader (TASK-461's ONE-loader rule): ffmpeg downmix plus
    resample, the same path run_baseline feeds the same recognizer."""
    from audio_loader import load_audio as shared_load
    return shared_load(path, sample_rate=SAMPLE_RATE)


def recognize(rec, samples, language=None):
    """The shared decode (TASK-716): run_baseline.recognize_online with the
    catalog cfg, language overridden for the forced arm."""
    cfg = dict(rb.BACKENDS["nemotron"])
    if language:
        cfg["language"] = language
    return rb.recognize_online(rec, samples, cfg)


def main() -> None:
    print(f"Model dir : {MODEL_DIR}")
    print(f"Loop thr  : {LOOP_THRESHOLD} consecutive identical tokens")
    print(f"Threads   : {rb.NUM_THREADS} | sample_rate={SAMPLE_RATE} | feature_dim=80\n")
    if not TEST_WAVS.is_dir():
        sys.exit(f"No test_wavs dir at {TEST_WAVS}")

    print("Loading OnlineRecognizer (rb.build_recognizer, the shared config) ...")
    rec, _ = rb.build_recognizer(rb.BACKENDS["nemotron"])
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
