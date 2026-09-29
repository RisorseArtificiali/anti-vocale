#!/usr/bin/env python3
"""
Anti-Vocale Italian voice-message eval harness.

Runs the same ONNX int8 models the app ships (via sherpa-onnx Python) on a local
set of real Italian voice messages + manual transcripts, and reports WER / CER /
repetition-loop counts per backend.

Step 1 of the self-fine-tuning plan: establish a measured baseline before training.
See eval/README.md for the clip/transcript format and metric definitions.

NOTE on the sherpa-onnx Python API: the recognizer config nesting below mirrors the
app's Kotlin config (greedy_search, tailPaddings=1000, modelType="" for Nemotron
online, etc.) for sherpa-onnx==1.13.8 (the shipped pin, .sherpa-version). If
check OfflineRecognizerConfig / OnlineRecognizerConfig field shapes first.
"""

from __future__ import annotations

import argparse
import csv
import sys
import time
from pathlib import Path
from statistics import mean

import numpy as np

try:
    import sherpa_onnx
except ImportError:
    sys.exit(
        "sherpa-onnx not installed. Run:  pip install -r eval/requirements.txt\n"
        "(pinned to sherpa-onnx==1.13.8 to match the shipped AAR)"
    )

# Audio decode: shared ffmpeg+soundfile loader (audio_loader.py, TASK-461),
# also used by postprocess_score --transcribe; ffmpeg must be on PATH. The
# missing-dep hint lives in the loader, not here.
from audio_loader import load_audio

# WER/CER use a built-in unit-cost Levenshtein (= S+D+I), so no jiwer dependency.


# ──────────────────────────────────────────────────────────────────────────────
# CONFIG — edit model paths / file names to match your local copies.
# These are the same ONNX int8 models the app downloads. The recognition params
# mirror the app's Kotlin backends (see file:line refs) so desktop ≈ on-device.
# ──────────────────────────────────────────────────────────────────────────────

SAMPLE_RATE = 16000          # all backends expect 16 kHz mono float
NUM_THREADS = 4              # matches SherpaConfig default in the app
LOOP_THRESHOLD = 4           # ≥ N consecutive identical tokens = 1 loop run (LocalAI-io def)

# Per-backend model roots + expected file names. Point MODELS_ROOT at a dir that
# contains the model subdirs below (or edit each path). Symlinks are fine.
MODELS_ROOT = Path(__file__).resolve().parent / "models"

BACKENDS = {
    # Whisper Distil Large V3 IT — our Italian leader.
    # App config: WhisperBackend.kt:82-106 (modelType="whisper", language="it",
    # tailPaddings=1000, greedy_search).
    "distil_it": {
        "kind": "whisper",
        "dir": MODELS_ROOT / "distil-large-v3-it",
        "encoder": "encoder.int8.onnx",
        "decoder": "decoder.int8.onnx",
        "tokens": "tokens.txt",
        "language": "it",
        "task": "transcribe",
        "tail_paddings": 1000,
        # Catalog flags.chunkDurationSeconds (overridden at import by
        # _apply_catalog_mirrors; this literal is the vendored fallback and
        # mirrors the shipped 29: sherpa's whisper decode cap is 2950 frames
        # = 29.5s, so a 30s window silently dropped its final ~0.48s,
        # TASK-718).
        "chunk_seconds": 29,
    },
    # Parakeet TDT 0.6b v3 — our default multilingual model.
    # App config: SherpaOnnxBackend.kt:101-107 (nemo_transducer, greedy_search).
    "parakeet": {
        "kind": "transducer",          # OfflineRecognizer transducer (NeMo TDT)
        "dir": MODELS_ROOT / "parakeet-tdt-0.6b-v3-int8",
        "encoder": "encoder.int8.onnx",
        "decoder": "decoder.int8.onnx",
        "joiner": "joiner.int8.onnx",
        "tokens": "tokens.txt",
        # REQUIRED: Parakeet TDT needs model_type="nemo_transducer" or sherpa exits 255 on
        # decoder init (no vocab_size metadata). Mirrors BackendConfig.SherpaOnnxConfig default.
        "model_type": "nemo_transducer",
        # Catalog flags.tailPadSeconds (vendored fallback; the catalog
        # override at import is authoritative). 1: variant-dependent effect
        # (stock-int8 pays 2.5pp, smoothquant gains 0.9pp; TASK-715/717),
        # entry-level flag, smoothquant default.
        "tail_pad_seconds": 1.0,
        # Catalog flags.chunkDurationSeconds: 60. Same windowing rule as
        # whisper below; the app caps parakeet chunks at 60s.
        "chunk_seconds": 60,
    },
    # Nemotron 3.5 streaming 0.6b (1120ms int8).
    # App config: NemotronStreamingBackend.kt:98-113 (OnlineRecognizer, modelType=""
    # empty, language="auto", greedy_search).
    "nemotron": {
        "kind": "online_transducer",   # OnlineRecognizer (streaming)
        "dir": MODELS_ROOT / "nemotron-3.5-asr-streaming-0.6b-1120ms-int8",
        "encoder": "encoder.int8.onnx",
        "decoder": "decoder.int8.onnx",
        "joiner": "joiner.int8.onnx",
        "tokens": "tokens.txt",
        "language": "auto",
        # Catalog flags.tailPadSeconds: 1.5s of silence AFTER the audio (the
        # streaming encoder needs a complete final chunk). TASK-714.
        "tail_pad_seconds": 1.5,
    },
}

# The decode-shape numbers above (chunk_seconds / tail_pad_seconds /
# tail_paddings) are FALLBACKS: at import, _apply_catalog_mirrors() overwrites
# them from app/src/main/assets/models_catalog.json so the catalog stays the
# single source of truth (a flag flip there lands here with no second edit;
# the TASK-714 drift class cannot re-enter). The literals survive only for a
# vendored eval/ copy with no app/ tree, which gets a loud warning instead of
# a hard exit: decoding is this script's job, policing its own config is not.
_CATALOG_ENTRY_BY_BACKEND = {
    "distil_it": "whisper",
    "parakeet": "sherpa-onnx",
    "nemotron": "nemotron-streaming",
}


def _apply_catalog_mirrors() -> None:
    import json

    catalog_path = Path(__file__).resolve().parent.parent / "app/src/main/assets/models_catalog.json"
    try:
        entries = {m["id"]: m for m in json.loads(catalog_path.read_text(encoding="utf-8"))["models"]}
    except (OSError, ValueError, KeyError) as e:
        print(f"⚠ catalog mirrors NOT applied ({catalog_path}: {e}); "
              f"using the BACKENDS literals, which may not match the app", file=sys.stderr)
        return
    for name, entry_id in _CATALOG_ENTRY_BY_BACKEND.items():
        entry = entries.get(entry_id)
        if entry is None:
            print(f"⚠ catalog has no entry '{entry_id}' (backend '{name}' keeps its literals)",
                  file=sys.stderr)
            continue
        flags = entry.get("flags", {})
        BACKENDS[name]["chunk_seconds"] = flags.get("chunkDurationSeconds", 0)
        BACKENDS[name]["tail_pad_seconds"] = flags.get("tailPadSeconds", 0.0)
        if BACKENDS[name]["kind"] == "whisper":
            BACKENDS[name]["tail_paddings"] = flags.get("whisperTailPaddings", 0)


_apply_catalog_mirrors()

AUDIO_EXTS = {".opus", ".m4a", ".mp3", ".wav", ".flac", ".ogg", ".wma"}


# ──────────────────────────────────────────────────────────────────────────────
# Normalization (Italian)
# ──────────────────────────────────────────────────────────────────────────────

def normalize_it(text: str) -> str:
    """Normalize ref/hyp text for WER. Applied identically to both.

    - lowercase
    - strip bracketed annotations ([inaudible], [noise], [music])  ← FIRST, while brackets exist
    - drop punctuation (keeps accented Italian letters à è é ì ò ù — they're isalnum())
    - drop apostrophes (so l'acqua → l acqua → tokens l, acqua)
    - collapse whitespace
    Numbers are NOT expanded ("3" stays "3"). See README "Known limitations".
    """
    import re
    s = text.lower()
    s = re.sub(r"\[[^\]]*\]", " ", s)   # strip [annotations] before the char filter eats brackets
    out = []
    for ch in s:
        if ch.isalnum() or ch.isspace():
            out.append(ch)              # accented letters are alnum → kept
        elif ch == "'":
            out.append(" ")             # elision boundary: l'acqua → l acqua
        # else: drop punctuation / symbols
    s = "".join(out)
    s = re.sub(r"\s+", " ", s).strip()
    return s


def tokenize(text: str) -> list[str]:
    return normalize_it(text).split()


# ──────────────────────────────────────────────────────────────────────────────
# Metrics
# ──────────────────────────────────────────────────────────────────────────────

def _levenshtein(a: list[str], b: list[str]) -> int:
    """Unit-cost edit distance over token lists = (S + D + I)."""
    if len(a) < len(b):
        a, b = b, a
    if not b:
        return len(a)
    prev = list(range(len(b) + 1))
    for i, ca in enumerate(a, 1):
        cur = [i]
        for j, cb in enumerate(b, 1):
            cur.append(min(
                prev[j] + 1,            # deletion
                cur[j - 1] + 1,         # insertion
                prev[j - 1] + (ca != cb)  # substitution
            ))
        prev = cur
    return prev[-1]


def wer(ref_tokens: list[str], hyp_tokens: list[str]) -> float:
    """Word error rate = (S+D+I) / len(ref). May exceed 1.0 (heavy insertions)."""
    if not ref_tokens:
        return 0.0 if not hyp_tokens else float("inf")
    return _levenshtein(ref_tokens, hyp_tokens) / len(ref_tokens)


def cer(ref_text: str, hyp_text: str) -> float:
    ref_chars = list(ref_text.replace(" ", ""))
    hyp_chars = list(hyp_text.replace(" ", ""))
    if not ref_chars:
        return 0.0 if not hyp_chars else float("inf")
    return _levenshtein(ref_chars, hyp_chars) / len(ref_chars)


def repetition_loops(tokens: list[str], threshold: int = LOOP_THRESHOLD) -> int:
    """Count maximal runs where one token repeats >= threshold times consecutively.
    The hallucination proxy. e.g. [ciao]*4 + [mondo] -> 1 loop."""
    if not tokens or threshold < 2:
        return 0
    loops = 0
    run = 1
    for i in range(1, len(tokens)):
        if tokens[i] == tokens[i - 1]:
            run += 1
        else:
            if run >= threshold:
                loops += 1
            run = 1
    if run >= threshold:
        loops += 1
    return loops


# ──────────────────────────────────────────────────────────────────────────────
# Recognizer construction — one builder per backend kind, mirroring the app config
# ──────────────────────────────────────────────────────────────────────────────

def _missing(cfg: dict) -> list[str]:
    d = Path(cfg["dir"])
    names = [cfg["tokens"], cfg["encoder"], cfg["decoder"]]
    if "joiner" in cfg:
        names.append(cfg["joiner"])
    return [n for n in names if not (d / n).exists()]


def build_recognizer(cfg: dict):
    """Return (recognizer, is_online). Raises if model files are missing.

    sherpa-onnx 1.13.x removed the `from_args` entry points and does not expose the
    *Config classes in Python, so each backend is built with its version-stable
    `from_<kind>` classmethod (from_whisper / from_transducer — offline, and
    OnlineRecognizer.from_transducer — streaming). Recognition params mirror the
    app's Kotlin backends: greedy_search everywhere, Whisper tail_paddings=1000,
    Nemotron loaded as a streaming (online) transducer."""
    missing = _missing(cfg)
    if missing:
        raise FileNotFoundError(
            f"Missing model files in {cfg['dir']}: {missing}. "
            f"Edit run_baseline.py CONFIG / MODELS_ROOT to point at your local copy."
        )
    d = cfg["dir"]  # Path

    if cfg["kind"] == "whisper":
        # WhisperBackend.kt:82-106 (modelType="whisper", language="it", tailPaddings=1000).
        # sherpa-onnx 1.13.x removed OfflineRecognizer.from_args; use from_whisper
        # (kwargs verified against a real distil-it load + decode, 2026-07-09).
        return sherpa_onnx.OfflineRecognizer.from_whisper(
            tokens=str(d / cfg["tokens"]),
            encoder=str(d / cfg["encoder"]),
            decoder=str(d / cfg["decoder"]),
            language=cfg["language"],
            task=cfg["task"],
            tail_paddings=cfg["tail_paddings"],
            num_threads=NUM_THREADS,
            provider="cpu",
            decoding_method="greedy_search",
        ), False  # type: ignore[attr-defined]

    if cfg["kind"] == "transducer":
        # Parakeet TDT (SherpaOnnxBackend.kt:88-108). model_type="nemo_transducer" is REQUIRED:
        # without it sherpa uses the plain-transducer decoder-init path that expects vocab_size
        # metadata, which the NeMo TDT decoder lacks ('vocab_size does not exist in the metadata',
        # then native exit 255). Mirrors BackendConfig.SherpaOnnxConfig default
        # (TranscriptionBackend.kt:119). Verified load+decode 2026-07-09.
        return sherpa_onnx.OfflineRecognizer.from_transducer(
            tokens=str(d / cfg["tokens"]),
            encoder=str(d / cfg["encoder"]),
            decoder=str(d / cfg["decoder"]),
            joiner=str(d / cfg["joiner"]),
            num_threads=NUM_THREADS,
            provider="cpu",
            decoding_method="greedy_search",
            sample_rate=SAMPLE_RATE,
            feature_dim=80,
            model_type=cfg.get("model_type", "nemo_transducer"),
        ), False  # type: ignore[attr-defined]

    if cfg["kind"] == "online_transducer":
        # NemotronStreamingBackend.kt:98-113 (OnlineRecognizer, empty model_type).
        # NOTE: sherpa-onnx 1.13.x (verified through the pinned 1.13.8) does NOT
        # expose OnlineRecognizer.from_args or the Online*Config classes in Python.
        # The version-stable construction is the from_transducer classmethod.
        return sherpa_onnx.OnlineRecognizer.from_transducer(
            tokens=str(d / cfg["tokens"]),
            encoder=str(d / cfg["encoder"]),
            decoder=str(d / cfg["decoder"]),
            joiner=str(d / cfg["joiner"]),
            num_threads=NUM_THREADS,
            provider="cpu",
            decoding_method="greedy_search",
            sample_rate=SAMPLE_RATE,
            feature_dim=80,
        ), True  # type: ignore[attr-defined]

    raise ValueError(f"Unknown backend kind: {cfg['kind']}")


# ──────────────────────────────────────────────────────────────────────────────
# Recognition
# ──────────────────────────────────────────────────────────────────────────────

def _silence(seconds: float, dtype) -> np.ndarray:
    """A zeros buffer of `seconds` (TASK-714: the app's flags.tailPadSeconds).

    Cached by length: the per-backend pad is a constant, and the app's own
    TailSilenceBuffer never rewrites the buffer after allocation.
    """
    n = int(seconds * SAMPLE_RATE)
    buf = _SILENCE_CACHE.get((n, dtype))
    if buf is None:
        buf = np.zeros(n, dtype=dtype)
        _SILENCE_CACHE[(n, dtype)] = buf
    return buf


_SILENCE_CACHE: dict[tuple[int, object], np.ndarray] = {}


def recognize_offline(recognizer, samples: np.ndarray, cfg: dict) -> str:
    # Offline API: accept_waveform -> decode_stream -> stream.result.text. NO input_finished()
    # (that is online-only; OfflineStream has no input_finished in 1.13.x).
    # TASK-714 mirror, two parts:
    # 1. Windowing: the app chunks at flags.chunkDurationSeconds (whisper 30,
    #    parakeet 60) and joins chunk texts with a single space (orchestrator
    #    joinToString(" ")). CAUTION, verified at the pinned sherpa 1.13.8 tag
    #    (offline-recognizer-whisper-impl.h): the whisper decode cap is 2950
    #    frames = 29.5s, NOT 30s, so a full 30s window still loses its final
    #    ~0.48s of audio. The app's own windows are exactly 30s, so the
    #    harness mirrors the loss too; the app-side fix is tracked (TASK-718)
    #    and the harness follows the catalog when it lands.
    # 2. Per-window tail pad: each app chunk goes through the backend's own
    #    transcribeAudio, which appends flags.tailPadSeconds of silence as a
    #    SECOND accept (no padded copy, TASK-340 Fix 1b); pad per window here
    #    the same way. Whisper pads via its tail_paddings decode param instead
    #    (entry declares no tailPadSeconds).
    chunk_s = cfg.get("chunk_seconds", 0)
    if chunk_s > 0 and len(samples) > chunk_s * SAMPLE_RATE:
        step = int(round(chunk_s * SAMPLE_RATE))
        windows = [samples[i:i + step] for i in range(0, len(samples), step)]
    else:
        windows = [samples]
    tail = cfg.get("tail_pad_seconds", 0.0)
    texts = []
    for window in windows:
        stream = recognizer.create_stream()
        stream.accept_waveform(SAMPLE_RATE, window)
        if tail > 0:
            stream.accept_waveform(SAMPLE_RATE, _silence(tail, samples.dtype))
        recognizer.decode_stream(stream)
        texts.append((getattr(stream.result, "text", "") or "").strip())
    return " ".join(t for t in texts if t)


def recognize_online(recognizer, samples: np.ndarray, cfg: dict) -> str:
    # Feed the WHOLE clip in one accept_waveform call, matching the app's
    # NemotronStreamingBackend.kt:158 (feeds the entire samples array at once).
    # Feeding sub-chunk slices (< the model's internal 1120ms chunk) creates
    # artificial boundary seams that degrade streaming-transducer output;
    # verified empirically: French test wav went from garbled to clean when
    # switched from 0.5s feed-chunks to whole-clip.
    stream = recognizer.create_stream()
    # TASK-714: the app conditions the multilingual model per stream
    # (stream.setOption("language", language); SherpaBackend online path).
    # "auto" is the untouched-default value the app passes when the user has
    # not pinned a language.
    stream.set_option("language", cfg.get("language", "auto"))
    stream.accept_waveform(SAMPLE_RATE, samples)
    tail = cfg.get("tail_pad_seconds", 0.0)
    if tail > 0:
        # Same order as the app: the pad is a second accept AFTER the audio
        # (TASK-340 Fix 1b), so the streaming encoder gets a complete final
        # chunk.
        stream.accept_waveform(SAMPLE_RATE, _silence(tail, samples.dtype))
    # Drain via is_ready -> decode_stream. A single decode_stream only advances ONE
    # step, so loop to drain. The app drains BEFORE inputFinished too,
    # then drains the trailing hypotheses EOF flushes; mirror both loops.
    while recognizer.is_ready(stream):
        recognizer.decode_stream(stream)
    stream.input_finished()
    while recognizer.is_ready(stream):
        recognizer.decode_stream(stream)
    # OnlineStream has no .result attribute; read via get_result.
    res = recognizer.get_result(stream)
    return res if isinstance(res, str) else (getattr(res, "text", "") or "")


# ──────────────────────────────────────────────────────────────────────────────
# Clip discovery
# ──────────────────────────────────────────────────────────────────────────────

def discover_pairs(clips_dir: Path, transcripts_dir: Path):
    """Return [(clip_id, audio_path, transcript_text), ...] and report orphans."""
    pairs = []
    audio = {}
    for p in sorted(clips_dir.glob("*")):
        if p.suffix.lower() in AUDIO_EXTS:
            audio.setdefault(p.stem, p)  # first ext wins if duplicate basenames
    transcripts = {p.stem: p for p in sorted(transcripts_dir.glob("*.txt"))}

    orphans_audio = sorted(set(audio) - set(transcripts))
    orphans_tx = sorted(set(transcripts) - set(audio))
    for cid in sorted(set(audio) & set(transcripts)):
        tx = transcripts[cid].read_text(encoding="utf-8").strip()
        pairs.append((cid, audio[cid], tx))
    return pairs, orphans_audio, orphans_tx


# ──────────────────────────────────────────────────────────────────────────────
# Main
# ──────────────────────────────────────────────────────────────────────────────

def main():
    ap = argparse.ArgumentParser(description="Anti-Vocale Italian voice-message eval baseline.")
    here = Path(__file__).resolve().parent
    ap.add_argument("--clips", default=str(here / "clips"))
    ap.add_argument("--transcripts", default=str(here / "transcripts"))
    ap.add_argument("--results", default=str(here / "results"))
    ap.add_argument("--backends", default="distil_it,parakeet,nemotron",
                    help="Comma-separated subset of: " + ",".join(BACKENDS))
    ap.add_argument("--loop-threshold", type=int, default=LOOP_THRESHOLD)
    ap.add_argument("--dry-run", action="store_true",
                    help="List discovered clips + planned backends; load no models.")
    args = ap.parse_args()


    clips_dir, transcripts_dir, results_dir = (Path(args.clips), Path(args.transcripts), Path(args.results))
    pairs, orphans_a, orphans_t = discover_pairs(clips_dir, transcripts_dir)

    chosen = [b.strip() for b in args.backends.split(",") if b.strip()]
    unknown = [b for b in chosen if b not in BACKENDS]
    if unknown:
        sys.exit(f"Unknown backend(s): {unknown}. Known: {list(BACKENDS)}")


    print(f"Clips dir:    {clips_dir}")
    print(f"Transcripts:  {transcripts_dir}")
    print(f"Discovered:   {len(pairs)} paired clip(s)")
    if orphans_a:
        print(f"  ⚠ {len(orphans_a)} audio clip(s) without a transcript: {orphans_a[:5]}{'…' if len(orphans_a) > 5 else ''}")
    if orphans_t:
        print(f"  ⚠ {len(orphans_t)} transcript(s) without audio: {orphans_t[:5]}{'…' if len(orphans_t) > 5 else ''}")
    print(f"Backends:     {chosen}")
    print(f"Loop thr:     {args.loop_threshold} consecutive identical tokens")

    if not pairs:
        sys.exit("\nNo paired clips found. Add audio to clips/ and transcripts to transcripts/ "
                 "(see eval/README.md).")

    if args.dry_run:
        print("\n--dry-run: not loading models. Pairing looks good? Run without --dry-run.")
        return

    # Build recognizers (skip a backend if its files are missing/won't construct).
    # The bundle carries its cfg: recognition params travel with the recognizer
    # instead of being re-fetched from the module dict at each call site.
    recognizers = {}
    for name in chosen:
        cfg = BACKENDS[name]
        try:
            rec, is_online = build_recognizer(cfg)
            recognizers[name] = (rec, is_online, cfg)
            print(f"  ✓ loaded {name} ({cfg['kind']})")
        except Exception as e:  # noqa: BLE001
            print(f"  ✗ skip {name}: {e}")

    if not recognizers:
        sys.exit("\nNo recognizers loaded. Check MODELS_ROOT / model file names in run_baseline.py.")

    # Run.
    rows = []
    for cid, audio_path, ref_text in pairs:
        try:
            samples = load_audio(audio_path, sample_rate=SAMPLE_RATE)
        except Exception as e:  # noqa: BLE001
            print(f"  ✗ {cid}: audio load failed ({e})")
            continue
        ref_tokens = tokenize(ref_text)
        ref_norm = normalize_it(ref_text)
        for name, (rec, is_online, cfg) in recognizers.items():
            t0 = time.time()
            try:
                hyp = (recognize_online(rec, samples, cfg) if is_online
                       else recognize_offline(rec, samples, cfg))
            except Exception as e:  # noqa: BLE001
                print(f"  ✗ {cid} / {name}: recognition failed ({e})")
                hyp = ""
            dt = time.time() - t0
            hyp_tokens = tokenize(hyp)
            loops = repetition_loops(hyp_tokens, args.loop_threshold)
            rows.append({
                "clip_id": cid,
                "backend": name,
                "wer": round(wer(ref_tokens, hyp_tokens), 4),
                "cer": round(cer(ref_norm, normalize_it(hyp)), 4),
                "loops": loops,
                "has_loop": int(loops > 0),
                "decode_s": round(dt, 2),
                "ref": ref_text.replace("\n", " "),
                "hyp": hyp.replace("\n", " "),
            })
        print(f"  • {cid} done ({len(recognizers)} backend(s))")

    # Write per-clip CSV.
    results_dir.mkdir(parents=True, exist_ok=True)
    stamp = time.strftime("%Y%m%d-%H%M%S")
    per_clip_path = results_dir / f"per_clip_{stamp}.csv"
    with per_clip_path.open("w", newline="", encoding="utf-8") as f:
        w = csv.DictWriter(f, fieldnames=["clip_id", "backend", "wer", "cer", "loops", "has_loop", "decode_s", "ref", "hyp"])
        w.writeheader()
        w.writerows(rows)

    # Summary.
    by_backend = {}
    for r in rows:
        by_backend.setdefault(r["backend"], []).append(r)
    summary_lines = [
        f"# Eval summary — {stamp}",
        f"",
        f"- Clips: {len(pairs)} paired",
        f"- Backends run: {', '.join(by_backend)}",
        f"- Loop threshold: {args.loop_threshold} consecutive identical tokens",
        f"",
        f"| Backend | n | mean WER | mean CER | clips w/ loops | loop rate |",
        f"|---|---|---|---|---|---|",
    ]
    for name, rs in by_backend.items():
        wers = [r["wer"] for r in rs if r["wer"] != float("inf")]
        cers = [r["cer"] for r in rs if r["cer"] != float("inf")]
        n_loop = sum(r["has_loop"] for r in rs)
        summary_lines.append(
            f"| {name} | {len(rs)} | {mean(wers):.3f} | {mean(cers):.3f} | "
            f"{n_loop} | {n_loop / len(rs):.0%} |"
        )
    summary_lines += ["", f"Per-clip detail: `{per_clip_path.name}`", ""]
    summary_path = results_dir / f"summary_{stamp}.md"
    summary_path.write_text("\n".join(summary_lines), encoding="utf-8")

    print(f"\nWrote {per_clip_path}")
    print(f"Wrote {summary_path}")
    print("\n" + "\n".join(summary_lines))


if __name__ == "__main__":
    main()
