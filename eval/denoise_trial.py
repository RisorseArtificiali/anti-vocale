#!/usr/bin/env python3
"""
TASK-669: gtcrn/dpdfnet speech-denoiser pre-pass trial, measured on the
Italian real-message eval set before any claim (the campaign honesty rule).

Three questions, three sections of the report:
  A. CLEAN regression: does the pre-pass HURT the clean set the app's
     baseline was measured on? (WER/CER before vs after, per backend.)
  B. NOISY benefit: does it HELP? A synthetic noisy subset (white + pink
     noise at SNR 0/5/10 dB over four real clips) stands in for the
     kitchen/street/pocket classes. The SNR labels are NOMINAL: the noise
     scales against the whole-clip mean power, silence included, so speech
     regions sit a few dB dirtier than the label. Within-cell comparisons
     stay exact (both arms score the same array). CAVEAT, direction matters: stationary
     Gaussian noise is the FLATTERING regime for a spectral denoiser; the
     real classes are non-stationary babble and transients where denoisers
     do worse. For the REJECT verdict this proxy is safe (failing on the
     flattering regime implies failing on harder noise); a future re-run
     showing a marginal WIN here would overstate real-world benefit and
     must add a non-stationary condition before any adoption claim.
  C. COST + VAD interaction: the denoise RTF (part of the verdict, not an
     afterthought) and the silero VAD segment counts before/after (a
     denoiser changes the signal VAD sees: suppressed false segments are a
     win, dropped quiet speech is a regression), measured on BOTH the clean
     set and the noisy subset (the regime where suppression is in play).

Measurement structure (each quantity computed at its own layer, once):
  - every noisy condition is materialized ONCE with a per-condition seed
    and written once; all arms score the same arrays;
  - the base (no-denoiser) ASR pass is backend-dependent but
    denoiser-independent: computed once per backend, stored at the report
    top level;
  - the denoised waveform is denoiser-dependent but backend-independent:
    denoised once per (denoiser, clip) with the RTF timers accumulated
    there, reused by every backend scoring loop and by the VAD pass.

The recognizer construction, decoding, normalization, and WER/CER are
run_baseline's own functions (app-faithful; sherpa-onnx pinned to the
shipped AAR version). The denoisers are the same gtcrn/dpdfnet the AAR
ships.

Usage:
  .venv/bin/python denoise_trial.py [--backends parakeet,distil_it]
                                    [--noisy-clips 4] [--snrs 0,5,10]
                                    [--seed 669]
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import zlib
from datetime import date
from pathlib import Path

import numpy as np
import sherpa_onnx
import soundfile as sf

from run_baseline import (
    BACKENDS,
    MODELS_ROOT,
    NUM_THREADS,
    SAMPLE_RATE,
    build_recognizer,
    cer,
    discover_pairs,
    normalize_it,
    recognize_offline,
    recognize_online,
    tokenize,
    wer,
)
from audio_loader import load_audio

EVAL_DIR = Path(__file__).resolve().parent
DENOISER_MODELS = {
    "gtcrn": MODELS_ROOT / "gtcrn" / "gtcrn_simple.onnx",
    "dpdfnet": MODELS_ROOT / "gtcrn" / "dpdfnet2.onnx",
}
VAD_MODEL = MODELS_ROOT / "gtcrn" / "silero_vad.onnx"
NOISY_DIR = EVAL_DIR / "clips_noisy"
RESULTS_DIR = EVAL_DIR / "results"


def build_denoiser(which: str) -> sherpa_onnx.OfflineSpeechDenoiser:
    mc = sherpa_onnx.OfflineSpeechDenoiserModelConfig(num_threads=NUM_THREADS)
    if which == "gtcrn":
        mc.gtcrn = sherpa_onnx.OfflineSpeechDenoiserGtcrnModelConfig(model=str(DENOISER_MODELS["gtcrn"]))
    elif which == "dpdfnet":
        mc.dpdfnet = sherpa_onnx.OfflineSpeechDenoiserDpdfNetModelConfig(model=str(DENOISER_MODELS["dpdfnet"]))
    else:
        raise SystemExit(f"unknown denoiser {which}")
    return sherpa_onnx.OfflineSpeechDenoiser(sherpa_onnx.OfflineSpeechDenoiserConfig(model=mc))


def denoise(dn, samples: np.ndarray) -> tuple[np.ndarray, float]:
    """Returns (denoised samples, wall seconds). The output can be a few
    hundred samples SHORTER than the input (denoiser framing); ASR re-segments
    so transcript alignment is unaffected."""
    t0 = time.perf_counter()
    out = np.asarray(dn.run(samples, sample_rate=SAMPLE_RATE).samples, dtype=np.float32)
    return out, time.perf_counter() - t0


def transcribe(bundle, samples: np.ndarray) -> str:
    # run_baseline's own main bundles the same triple: the params travel
    # with the recognizer instead of being re-fetched at each call site.
    rec, is_online, cfg = bundle
    if is_online:
        return recognize_online(rec, samples, cfg)
    return recognize_offline(rec, samples, cfg)


def score(ref_text: str, hyp: str) -> dict:
    ref = normalize_it(ref_text)
    h = normalize_it(hyp)
    return {"wer": wer(tokenize(ref), tokenize(h)), "cer": cer(ref, h)}


def vad_segments(samples: np.ndarray) -> list[tuple[int, int]]:
    """Silero VAD speech segments in samples, the model class the app's
    VAD-aligned chunking drives. A fresh detector per call keeps state
    between clips from leaking. Windowed feeding (512 samples) is the
    documented pattern: a single whole-clip accept_waveform under-detects
    (measured: 0.31s speech on a 2.1s clip vs 2.01s windowed)."""
    mc = sherpa_onnx.VadModelConfig()
    mc.silero_vad.model = str(VAD_MODEL)
    mc.sample_rate = SAMPLE_RATE
    vad = sherpa_onnx.VoiceActivityDetector(mc)
    for i in range(0, len(samples), 512):
        vad.accept_waveform(samples[i : i + 512])
    vad.flush()
    out = []
    while not vad.empty():
        seg = vad.front
        out.append((seg.start, seg.start + len(seg.samples)))
        vad.pop()
    return out


def _pink_noise(n: int, rng: np.random.Generator) -> np.ndarray:
    """FFT-domain 1/f approximation (vectorized; the per-sample IIR form
    measured 4x slower for no perceptual gain at noise-floor duty)."""
    white = rng.standard_normal(n)
    spectrum = np.fft.rfft(white)
    freqs = np.fft.rfftfreq(n, d=1.0)
    freqs[0] = freqs[1]
    shaped = spectrum / np.sqrt(freqs)
    return np.fft.irfft(shaped, n=n)


def make_noisy(samples: np.ndarray, noise_kind: str, snr_db: float, rng: np.random.Generator) -> np.ndarray:
    speech_power = float((samples**2).mean())
    if noise_kind == "white":
        noise = rng.standard_normal(len(samples)).astype(np.float32)
    else:
        noise = _pink_noise(len(samples), rng).astype(np.float32)
    noise_power = float((noise**2).mean())
    scale = np.sqrt(speech_power / (noise_power * (10 ** (snr_db / 10))))
    return (samples + noise * scale).astype(np.float32)


def condition_rng(seed: int, tag: str) -> np.random.Generator:
    """One deterministic generator per (clip, noise, snr) condition: every
    arm scores the SAME noise realization, and re-runs reproduce it."""
    return np.random.default_rng(seed + zlib.crc32(tag.encode()))


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--backends", default="parakeet,distil_it")
    ap.add_argument("--noisy-clips", type=int, default=4)
    ap.add_argument("--snrs", default="0,5,10")
    ap.add_argument("--seed", type=int, default=669)
    args = ap.parse_args()

    pairs, _, _ = discover_pairs(EVAL_DIR / "clips", EVAL_DIR / "transcripts")
    if not pairs:
        raise SystemExit("no clip/transcript pairs found")
    print(f"{len(pairs)} clean pairs")

    backends: dict[str, tuple] = {}
    for key in args.backends.split(","):
        key = key.strip()
        if key not in BACKENDS:
            raise SystemExit(f"unknown backend {key}; known: {sorted(BACKENDS)}")
        try:
            backends[key] = (*build_recognizer(BACKENDS[key]), BACKENDS[key])  # (rec, is_online, cfg)
        except (FileNotFoundError, RuntimeError) as e:
            # run_baseline's rule: skip backends whose models are absent
            # instead of killing the whole trial.
            print(f"skip {key}: {e}", file=sys.stderr)
    if not backends:
        raise SystemExit("no usable backends")

    # Clean waveforms loaded once; the raw side of every later comparison.
    clean = [(clip.name, load_audio(clip), transcript) for _, clip, transcript in pairs]

    # Noisy conditions materialized ONCE, before any arm: written to disk as
    # eval assets and scored as the same arrays by every arm.
    subset = clean[: args.noisy_clips]
    NOISY_DIR.mkdir(exist_ok=True)
    for stale in NOISY_DIR.glob("*.wav"):
        stale.unlink()  # conditions from earlier grids must not outlive their report
    conditions = []  # (tag, snr, transcript, noisy_samples); the snr travels
    # with the condition instead of being re-parsed from the tag (a parsed
    # tag cannot survive values >= 100 or fractional SNRs).
    for name, samples, transcript in subset:
        for kind in ("white", "pink"):
            for snr in (float(s) for s in args.snrs.split(",")):
                tag = f"{Path(name).stem}__{kind}_snr{snr:g}.wav"
                noisy = make_noisy(samples, kind, snr, condition_rng(args.seed, tag))
                sf.write(NOISY_DIR / tag, noisy, SAMPLE_RATE)
                conditions.append((tag, snr, transcript, noisy))

    # --- Base pass: backend-dependent, denoiser-independent. Once. ---
    report = {
        "date": str(date.today()),
        "backends": list(backends),
        "seed": args.seed,
        "noisy_clips": args.noisy_clips,
        "snrs": [float(x) for x in args.snrs.split(",")],
        "base": {},
        "denoisers": {},
    }
    for name, rec in backends.items():
        report["base"][name] = {
            "clean": [
                {"clip": clip_name, **score(transcript, transcribe(rec, samples))}
                for clip_name, samples, transcript in clean
            ],
            "noisy": [
                {"clip": tag, "snr": snr, **score(transcript, transcribe(rec, noisy))}
                for tag, snr, transcript, noisy in conditions
            ],
        }

    # Raw-side VAD rows: denoiser-independent, computed once (the detector
    # is deterministic, but each quantity belongs to its own layer).
    vad_raw_clean = {name: vad_segments(samples) for name, samples, _ in clean}
    vad_raw_noisy = {tag: vad_segments(noisy) for tag, _snr, _tr, noisy in conditions}

    # --- Per-denoiser arms: denoise once per waveform, timers there. ---
    for which in DENOISER_MODELS:
        dn = build_denoiser(which)

        den_clean: dict[str, np.ndarray] = {}
        den_noisy: dict[str, np.ndarray] = {}
        denoise_s = 0.0
        audio_s = 0.0
        for clip_name, samples, _ in clean:
            den, dt = denoise(dn, samples)
            den_clean[clip_name] = den
            denoise_s += dt
            audio_s += len(samples) / SAMPLE_RATE
        for tag, _snr, _transcript, noisy in conditions:
            den, dt = denoise(dn, noisy)
            den_noisy[tag] = den
            denoise_s += dt
            audio_s += len(noisy) / SAMPLE_RATE

        arm = {
            "denoise_rtf": round(denoise_s / audio_s, 4) if audio_s else None,
            "denoise_seconds": round(denoise_s, 2),
            "audio_seconds": round(audio_s, 1),
            "clean": {},
            "noisy": {},
            "vad": {
                "clean": [],
                "noisy": [],
            },
        }
        for name, rec in backends.items():
            arm["clean"][name] = [
                {"clip": clip_name, "denoised": score(transcript, transcribe(rec, den_clean[clip_name]))}
                for clip_name, _, transcript in clean
            ]
            arm["noisy"][name] = [
                {
                    "clip": tag,
                    "snr": snr,
                    "denoised": score(transcript, transcribe(rec, den_noisy[tag])),
                }
                for tag, snr, transcript, _ in conditions
            ]

        # VAD interaction on both regimes (raw rows hoisted above).
        for clip_name, _, _ in clean:
            den = vad_segments(den_clean[clip_name])
            arm["vad"]["clean"].append(vad_row(clip_name, vad_raw_clean[clip_name], den))
        for tag, _snr, _transcript, _noisy in conditions:
            den = vad_segments(den_noisy[tag])
            arm["vad"]["noisy"].append(vad_row(tag, vad_raw_noisy[tag], den))

        report["denoisers"][which] = arm

    RESULTS_DIR.mkdir(exist_ok=True)
    # Timestamped, not date-only: same-day re-runs must not silently
    # overwrite the previous measurement (the run_baseline convention).
    from datetime import datetime

    out = RESULTS_DIR / f"denoise_trial_{datetime.now():%Y%m%d-%H%M%S}.json"
    out.write_text(json.dumps(report, indent=1))
    print(f"\nwrote {out}")

    # Console digest: clean means, noisy per SNR cell (a pre-pass that helps
    # at 0 dB but hurts at 10 dB averages into a false wash in one number).
    def finite_mean(vals: list[float]) -> float:
        # run_baseline's rule: an empty-normalized reference yields inf and
        # must not poison the aggregate (per-clip rows keep their value).
        finite = [v for v in vals if np.isfinite(v)]
        return float(np.mean(finite)) if finite else float("nan")

    for name in report["base"]:
        b = finite_mean([r["wer"] for r in report["base"][name]["clean"]])
        print(f"CLEAN base {name}: WER {b:.3f}")
    for which, arm in report["denoisers"].items():
        for name in arm["clean"]:
            d = finite_mean([r["denoised"]["wer"] for r in arm["clean"][name]])
            print(f"CLEAN {which:8s} {name}: WER -> {d:.3f}")
        for name in arm["noisy"]:
            base_rows = [r for r in report["base"][name]["noisy"]]
            for snr in sorted({r["snr"] for r in base_rows}):
                b = finite_mean([r["wer"] for r in base_rows if r["snr"] == snr])
                d = finite_mean([r["denoised"]["wer"] for r in arm["noisy"][name] if r["snr"] == snr])
                print(f"NOISY {which:8s} {name} snr{snr:g}: WER {b:.3f} -> {d:.3f}")
        print(f"      {which} RTF {arm['denoise_rtf']} ({arm['denoise_seconds']}s over {arm['audio_seconds']}s)")


def vad_row(tag: str, raw: list, den: list) -> dict:
    return {
        "clip": tag,
        "raw_segments": len(raw),
        "raw_speech_s": round(sum(e - s for s, e in raw) / SAMPLE_RATE, 2),
        "den_segments": len(den),
        "den_speech_s": round(sum(e - s for s, e in den) / SAMPLE_RATE, 2),
    }


if __name__ == "__main__":
    main()
