#!/usr/bin/env python3
"""TASK-585 gap 1: threshold re-measurement sweep for sub-40-token windows.

The shipped RepetitionLoopDetector measures only 40-token windows
(MIN_TOKENS=24 pre-check passes 24-39-token texts but they form no
window: a 4-word phrase x9 = 36 tokens is delivered undetected). Any fix
that shrinks the window must RE-MEASURE the thresholds at the new size
(the task's own rule); this script is that measurement.

Corpora (both machine-readable, both in this repo):
- CLEAN: eval/transcripts/*.txt - the human reference transcripts of the
  Italian eval clips (the acceptable distribution).
- LOOP: synthesized from the incident class (the "Muy bien!" x50 report,
  docs/research/2026-09-02) - phrase x N at phrase lengths 1..8 and
  repeats 4..60, covering the sub-40 token band densely.

For each candidate window size it reports the WORST (highest) clean
maxima and the BEST (lowest) loop maxima per arm: a window/threshold
pair separates iff clean_max < threshold <= loop_min for that arm (the
two-arm detector fires on either arm; the usable pair must separate on
at least one arm while keeping the clean side below BOTH thresholds).

Mirrors the Kotlin detector faithfully: whitespace tokenization, zlib
deflate ratio at the default level (Java Deflater DEFAULT_COMPRESSION
== zlib level 6), top-trigram dominance over window positions, window
step = W/2 with a tail-anchored final window.
"""
import json
import re
import sys
import zlib
from pathlib import Path

REPO = Path(__file__).resolve().parent.parent
TRANSCRIPTS = REPO / "eval" / "transcripts"
WINDOW_SIZES = [16, 20, 24, 28, 32, 36, 40]
STEP_DIVISOR = 2  # Kotlin: step 20 for window 40 == W/2
LOOPS = [
    ("phrase1", "Muy bien!"),
    ("phrase2", "no no no"),
    ("phrase3", "ecco la strada"),
    ("phrase4", "non lo so cosa dici"),
    ("phrase5", "adesso ti dico una cosa"),
    ("phrase6", "secondo me non va bene per niente"),
    ("phrase7", "guarda che questa cosa non mi piace affatto"),
    ("phrase8", "allora adesso ti racconto com'e andata la giornata"),
]
REPEATS = list(range(4, 61))
# Candidate thresholds to evaluate per arm (the shipped pair is 2.4 / 0.40).
COMPRESSION_CANDIDATES = [1.8, 2.0, 2.2, 2.4, 2.6]
NGRAM_CANDIDATES = [0.3, 0.4, 0.5, 0.6]


def tokenize(text: str):
    return [t for t in re.split(r"\s+", text) if t]


def windows(tokens, size):
    if len(tokens) < size:
        return []
    step = max(1, size // STEP_DIVISOR)
    last = len(tokens) - size
    starts = list(range(0, last + 1, step))
    if starts[-1] != last:
        starts.append(last)
    return [" ".join(tokens[s:s + size]) for s in starts]


def compression(window: str) -> float:
    raw = window.encode("utf-8")
    deflated = zlib.compress(raw, 6)
    if not deflated:
        return 1.0
    return len(raw) / len(deflated)


def ngram_dominance(tokens, start, until) -> float:
    positions = until - start - 2
    if positions <= 0:
        return 0.0
    counts = {}
    top = 0
    for i in range(start + 2, until):
        key = (tokens[i - 2], tokens[i - 1], tokens[i])
        c = counts.get(key, 0) + 1
        counts[key] = c
        if c > top:
            top = c
    return top / positions


def maxima(text: str, size: int):
    tokens = tokenize(text)
    if len(tokens) < size:
        return None  # text forms no window at this size
    mc, md = 0.0, 0.0
    for w, s in zip(windows(tokens, size),
                    [0] + list(range(0, len(tokens) - size + 1, max(1, size // STEP_DIVISOR)))):
        mc = max(mc, compression(w))
    # dominance over the same window spans (recompute spans to keep parity)
    spans = []
    step = max(1, size // STEP_DIVISOR)
    last = len(tokens) - size
    starts = list(range(0, last + 1, step))
    if starts[-1] != last:
        starts.append(last)
    for s in starts:
        spans.append((s, s + size))
    for s, e in spans:
        md = max(md, ngram_dominance(tokens, s, e))
    return mc, md


def main():
    clean_texts = {p.stem: p.read_text(encoding="utf-8").strip()
                   for p in sorted(TRANSCRIPTS.glob("*.txt"))}
    loop_texts = {}
    for name, phrase in LOOPS:
        for n in REPEATS:
            loop_texts[f"{name}x{n}"] = " ".join([phrase] * n)

    print(f"clean corpus: {len(clean_texts)} transcripts")
    print(f"loop corpus: {len(loop_texts)} synthesized texts")
    print()

    results = {}
    for size in WINDOW_SIZES:
        clean_rows = {n: maxima(t, size) for n, t in clean_texts.items()}
        loop_rows = {n: maxima(t, size) for n, t in loop_texts.items()}
        clean_seen = {n: r for n, r in clean_rows.items() if r}
        loop_seen = {n: r for n, r in loop_rows.items() if r}
        if not loop_seen:
            continue
        c_max_comp = max(r[0] for r in clean_seen.values()) if clean_seen else None
        c_max_dom = max(r[1] for r in clean_seen.values()) if clean_seen else None
        l_min_comp = min(r[0] for r in loop_seen.values())
        l_min_dom = min(r[1] for r in loop_seen.values())
        loops_forming = len(loop_seen)
        results[size] = {
            "clean_forming": len(clean_seen),
            "loops_forming": loops_forming,
            "clean_max_comp": round(c_max_comp, 4) if c_max_comp else None,
            "clean_max_dom": round(c_max_dom, 4) if c_max_dom else None,
            "loop_min_comp": round(l_min_comp, 4),
            "loop_min_dom": round(l_min_dom, 4),
        }
        print(f"window={size}: clean forming={len(clean_seen)} loops forming={loops_forming}")
        if clean_seen:
            print(f"  clean maxima:  comp={c_max_comp:.4f} ngram={c_max_dom:.4f}")
        print(f"  loop minima:   comp={l_min_comp:.4f} ngram={l_min_dom:.4f}")

    print()
    print("separating threshold pairs per window (clean_max < T <= loop_min, at least one arm):")
    for size, r in results.items():
        sep = []
        if r["clean_max_comp"] is not None:
            for t in COMPRESSION_CANDIDATES:
                if r["clean_max_comp"] < t <= r["loop_min_comp"]:
                    sep.append(f"comp>={t}")
            for t in NGRAM_CANDIDATES:
                if r["clean_max_dom"] < t <= r["loop_min_dom"]:
                    sep.append(f"ngram>={t}")
        print(f"  window={size}: {', '.join(sep) if sep else 'NO SEPARATION'}")

    out = REPO / "eval" / "loop_sweep_results.json"
    out.write_text(json.dumps(results, indent=2))
    print(f"\nresults -> {out.relative_to(REPO)}")


if __name__ == "__main__":
    sys.exit(main())
