#!/usr/bin/env python3
"""
TASK-816 A/B: the whisper degenerate-loop truncation guard
(app DegenerateRepetition.kt) measured on the eval harness.

The maintainer's ship condition for the guard: on clean audio the guard is
invisible (ZERO fired truncations, IDENTICAL WER/CER), and on the 792
degenerate clip the loop wall is gone. This script measures both.

Arms, in order:
1. CONFORMANCE (gate, runs first): the Python twin below must agree with
   eval/loop-guard-cases.json, the SAME case vector the Kotlin unit suite
   (DegenerateRepetitionTest) pins. A disagreement means the twin has
   drifted from the shipped algorithm and every measurement below would
   measure the wrong thing; the script refuses to continue.
2. CLEAN: every benchmark clip through distil_it and whisper_small (the
   whisper-family models the app gates) plus parakeet (ungated in the app;
   its output distribution is extra false-positive evidence), guard OFF vs
   ON: per-backend WER/CER must be IDENTICAL and the fire count zero. The
   reference transcripts also pass through the twin directly (no decode
   needed: the guard is a pure text function).
3. DEGENERATE: the maintainer's 792 clip (~/tmp-792/bug.oga) through
   distil_it, OFF vs ON, both texts recorded.

Run:  eval/.venv/bin/python eval/loop_guard_ab.py [--audio path/to/bug.oga]
Exit code 1 on any clean-arm fire or any WER/CER delta.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import unicodedata
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))

import run_baseline as rb  # noqa: E402  (same-dir import; pinned sherpa venv)
from audio_loader import load_audio  # noqa: E402

# ──────────────────────────────────────────────────────────────────────────────
# The Python twin of DegenerateRepetition.kt. Kept in lockstep with the
# Kotlin by the shared case vector (arm 1); do NOT "fix" one without the
# other. Constants mirror the Kotlin consts (pinned by the Kotlin test).
# ──────────────────────────────────────────────────────────────────────────────

WINDOW_WORDS = 24
MAX_DISTINCT_UNITS = 3
_WHITESPACE = re.compile(r"\s+")


def _is_letter_or_digit(c: str) -> bool:
    """Kotlin's Char.isLetterOrDigit: LETTER (L*) or DECIMAL_DIGIT (Nd)
    only. Python's str.isalnum is WIDER (accepts Nl/No numerals like 'Ⅷ'
    and '²'), which would drift the twin's loop sets from the Kotlin
    guard's (review F5)."""
    cat = unicodedata.category(c)
    return cat.startswith("L") or cat == "Nd"


def _token_key(word: str):
    """RepetitionCollapse.tokenKey: non-word chars stripped, lowercased;
    empty (None) for all-punctuation words."""
    key = "".join(c for c in word if c == "_" or _is_letter_or_digit(c)).lower()
    return key if key else None


def trim(text: str):
    """Returns (text, kept_words, dropped_words); dropped_words == 0 means
    no fire. Mirrors the Kotlin trim: window decides firing, the walk-back
    is unbounded, tandem loops drop in one call, and a trim that would
    empty the text falls back to the original (a whole-decode loop is not
    silence: the blank-chunk machinery and the silent-decode demotion must
    not see it)."""
    words = [w for w in _WHITESPACE.split(text) if w]
    current = list(words)
    dropped_total = 0
    while len(current) >= WINDOW_WORDS:
        loop_set = set()
        over = False
        for i in range(len(current) - WINDOW_WORDS, len(current)):
            loop_set.add(_token_key(current[i]))
            if len(loop_set) > MAX_DISTINCT_UNITS:
                over = True
                break
        if over:
            break
        keep = len(current)
        while keep > 0 and _token_key(current[keep - 1]) in loop_set:
            keep -= 1
        if keep == len(current):
            break
        dropped_total += len(current) - keep
        del current[keep:]
    if dropped_total == 0 or not current:
        return text, len(words), 0
    return _slice_through(text, len(current)), len(current), dropped_total


_REGEX_WS = " \t\n\x0b\x0c\r"


def _slice_through(text: str, kept: int) -> str:
    """The kept prefix as a SLICE of the original (never a rejoin), so
    surviving lead-in text keeps its line breaks and spacing (mirrors the
    Kotlin sliceThroughWord, review F3)."""
    seen = 0
    i, n = 0, len(text)
    while i < n:
        if text[i] in _REGEX_WS:
            i += 1
            continue
        j = i
        while j < n and text[j] not in _REGEX_WS:
            j += 1
        seen += 1
        if seen == kept:
            return text[:j]
        i = j
    return text


# whisper-small joins the built-in BACKENDS table and the CATALOG-MIRROR
# mechanism (the TASK-714 drift class: literals that cannot follow the
# catalog): registered BEFORE the mirror pass re-runs, so its windowing
# tracks the shipped catalog like distil_it's (review F4). Same config
# shape as distil_it (the app gates every whisper-family decode, built-in
# catalog and external import alike).
rb.BACKENDS["whisper_small"] = {
    "kind": "whisper",
    "dir": rb.MODELS_ROOT / "sherpa-onnx-whisper-small",
    "encoder": "small-encoder.int8.onnx",
    "decoder": "small-decoder.int8.onnx",
    "tokens": "small-tokens.txt",
    "language": "it",
    "task": "transcribe",
    "tail_paddings": 1000,
    "chunk_seconds": 29,
}
rb._CATALOG_ENTRY_BY_BACKEND["whisper_small"] = "whisper"
rb._apply_catalog_mirrors()
BACKENDS = dict(rb.BACKENDS)


def recognize_offline_windows(recognizer, samples, cfg) -> list:
    """The per-WINDOW texts of one offline decode, mirroring the app's
    boundary (review F1): the guard fires inside transcribeAudio, once per
    window, so the A/B must trim window texts, not the joined clip text
    (a clean clip whose non-final window legitimately ends in a tight
    repetitive tail is exactly the false-positive class this arm gates).
    Same windowing/padding as rb.recognize_offline."""
    import numpy as np
    chunk_s = cfg.get("chunk_seconds", 0)
    if chunk_s > 0 and len(samples) > chunk_s * rb.SAMPLE_RATE:
        step = int(round(chunk_s * rb.SAMPLE_RATE))
        windows = [samples[i:i + step] for i in range(0, len(samples), step)]
    else:
        windows = [samples]
    tail = cfg.get("tail_pad_seconds", 0.0)
    texts = []
    for window in windows:
        stream = recognizer.create_stream()
        stream.accept_waveform(rb.SAMPLE_RATE, window)
        if tail > 0:
            stream.accept_waveform(rb.SAMPLE_RATE, rb._silence(tail, samples.dtype))
        recognizer.decode_stream(stream)
        texts.append((getattr(stream.result, "text", "") or "").strip())
    return texts


def conformance_gate(cases_path: Path):
    cases = json.loads(cases_path.read_text(encoding="utf-8"))
    failures = 0
    for case in cases:
        out, _kept, dropped = trim(case["text"])
        expected = case.get("expected")
        if expected is None:
            if dropped != 0 or out != case["text"]:
                print(f"  ✗ '{case['name']}': twin fires on clean text")
                failures += 1
        elif out != expected or dropped != case["dropped"]:
            print(f"  ✗ '{case['name']}': twin '{out!r}' (dropped {dropped}) "
                  f"!= vector '{expected!r}' (dropped {case['dropped']})")
            failures += 1
    return failures, len(cases)


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[1])
    ap.add_argument("--audio", default=str(Path.home() / "tmp-792" / "bug.oga"),
                    help="the degenerate clip (792 incident archive default)")
    ap.add_argument("--backends", default="distil_it,whisper_small,parakeet")
    args = ap.parse_args()

    # ── Arm 1: conformance ────────────────────────────────────────────────
    cases_path = HERE / "loop-guard-cases.json"
    failures, n = conformance_gate(cases_path)
    print(f"[1] CONFORMANCE: {n - failures}/{n} vector cases agree "
          f"({'PASS' if failures == 0 else 'FAIL'})")
    if failures:
        return 1

    # ── Arm 2: clean corpus ───────────────────────────────────────────────
    pairs, orphans_a, orphans_t = rb.discover_pairs(HERE / "clips", HERE / "transcripts")
    print(f"[2] CLEAN: {len(pairs)} paired clips; backends: {args.backends}")
    if orphans_a or orphans_t:
        print(f"    orphans: audio={orphans_a} transcripts={orphans_t}")

    chosen = [b.strip() for b in args.backends.split(",") if b.strip()]
    unknown = [b for b in chosen if b not in BACKENDS]
    if unknown:
        sys.exit(f"Unknown backend(s): {unknown}. Known: {list(BACKENDS)}")

    failures = 0
    summary = []
    recognizers = {}
    for name in chosen:
        cfg = BACKENDS[name]
        try:
            rec, is_online = rb.build_recognizer(cfg)
            recognizers[name] = (rec, is_online, cfg)
        except Exception as e:  # noqa: BLE001
            # A skipped backend silently shrinks the evidence matrix and the
            # run would still print PASSED (review F4): count it.
            print(f"    ✗ skip {name}: {e}")
            failures += 1
    if not pairs:
        print("    ✗ no paired clips: the clean arm measured nothing")
        failures += 1

    for name, (rec, is_online, cfg) in recognizers.items():
        if not pairs:
            continue  # counted above; nothing to average (review F6)
        fires = 0
        wer_off, wer_on, cer_off, cer_on = [], [], [], []
        for cid, audio_path, ref_text in pairs:
            samples = load_audio(audio_path, sample_rate=rb.SAMPLE_RATE)
            if is_online:
                window_texts = [rb.recognize_online(rec, samples, cfg)]
            else:
                window_texts = recognize_offline_windows(rec, samples, cfg)
            # The app trims each window's text at the decode boundary and
            # the orchestrator joins the trimmed texts: both arms derive
            # from the SAME window texts (review F1).
            hyp = " ".join(t for t in window_texts if t)
            trimmed = [trim(t) for t in window_texts]
            clip_fires = [t for t in trimmed if t[2] > 0]
            if clip_fires:
                fires += len(clip_fires)
                for t in clip_fires:
                    print(f"    ✗ FIRE on clean clip {cid}/{name} (one window): dropped {t[2]} words")
            out = " ".join(t[0] for t in trimmed if t[0])
            ref_tokens = rb.tokenize(ref_text)
            ref_norm = rb.normalize_it(ref_text)
            wer_off.append(rb.wer(ref_tokens, rb.tokenize(hyp)))
            wer_on.append(rb.wer(ref_tokens, rb.tokenize(out)))
            cer_off.append(rb.cer(ref_norm, rb.normalize_it(hyp)))
            cer_on.append(rb.cer(ref_norm, rb.normalize_it(out)))
        identical = wer_off == wer_on and cer_off == cer_on
        if fires or not identical:
            failures += 1
        summary.append((name, len(pairs), fires,
                        sum(wer_off) / len(wer_off), sum(wer_on) / len(wer_on),
                        sum(cer_off) / len(cer_off), sum(cer_on) / len(cer_on), identical))
    print(f"    {'backend':<14} {'n':>3} {'fires':>5} {'WER off':>8} {'WER on':>8} {'CER off':>8} {'CER on':>8}  identical")
    for name, n, fires, wo, wn, co, cn, identical in summary:
        flag = "OK" if fires == 0 and identical else "FAIL"
        print(f"    {name:<14} {n:>3} {fires:>5} {wo:>8.4f} {wn:>8.4f} {co:>8.4f} {cn:>8.4f}  {flag}")

    # The human reference transcripts, straight through the twin.
    ref_fires = 0
    for tx in sorted((HERE / "transcripts").glob("*.txt")):
        _out, _kept, dropped = trim(tx.read_text(encoding="utf-8"))
        if dropped:
            ref_fires += 1
            print(f"    ✗ FIRE on reference transcript {tx.name}")
    print(f"    reference transcripts through the twin: {ref_fires} fires "
          f"({'PASS' if ref_fires == 0 else 'FAIL'})")
    if ref_fires:
        failures += 1

    # ── Arm 3: the degenerate clip ────────────────────────────────────────
    print(f"[3] DEGENERATE: {args.audio}")
    audio = Path(args.audio)
    if not audio.is_file():
        print(f"    ✗ clip not found: the degenerate arm measured nothing")
        failures += 1
    else:
        samples = load_audio(audio, sample_rate=rb.SAMPLE_RATE)
        rec, is_online, cfg = recognizers.get("distil_it", (None, None, None))
        if rec is None:
            print("    ✗ distil_it not loaded: the degenerate arm measured nothing")
            failures += 1
        else:
            hyp = rb.recognize_offline(rec, samples, cfg)
            out, kept, dropped = trim(hyp)
            print(f"    OFF ({len(hyp)} chars): {hyp[:200]!r}")
            print(f"    ON  ({len(out)} chars, kept {kept} words, dropped {dropped}): {out[:200]!r}")
            if not dropped:
                print("    ✗ the guard did NOT fire on the degenerate clip")
                failures += 1

    if failures:
        print("\nA/B FAILED: a clean-arm false positive or delta, or a degenerate no-fire.")
        return 1
    print("\nA/B PASSED: zero clean fires, identical WER/CER, degenerate wall removed.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
