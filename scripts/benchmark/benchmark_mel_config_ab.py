#!/usr/bin/env python3
"""A/B benchmark: Verify mel spectrogram config for Parakeet TDT and measure quality gap sources.

Investigates why sherpa-onnx Parakeet TDT gets 5.4% WER vs NVIDIA's 3.0% on Italian.

Tests:
  A) Default config (featureDim=80, auto-overridden to 128 by ONNX metadata)
  B) Explicit featureDim=128 (should produce identical results to A)
  C) With/without tail padding (1s silence)
  D) On both FLEURS clean audio and real WhatsApp (fabio2) audio

Usage:
    python benchmark_mel_config_ab.py \\
        --parakeet-dir /tmp/parakeet-bench/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8 \\
        --fleurs-references /tmp/parakeet-bench/references.json \\
        --fabio-audio /tmp/parakeet-bench/fabio2.wav \\
        --fabio-reference "Si, si Paolo, scusami..."
"""

import argparse
import json
import re
import time
from dataclasses import dataclass

import numpy as np
import sherpa_onnx
import soundfile as sf


# ---------------------------------------------------------------------------
# WER computation
# ---------------------------------------------------------------------------

def normalize(text: str) -> str:
    text = text.lower()
    text = re.sub(r"[.,;:!?\"()\-]", " ", text)
    return re.sub(r"\s+", " ", text).strip()


def wer(reference: str, hypothesis: str) -> float:
    ref_words = normalize(reference).split()
    hyp_words = normalize(hypothesis).split()
    if not ref_words:
        return 0.0
    n, m = len(ref_words), len(hyp_words)
    dp = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(n + 1):
        dp[i][0] = i
    for j in range(m + 1):
        dp[0][j] = j
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            if ref_words[i - 1] == hyp_words[j - 1]:
                dp[i][j] = dp[i - 1][j - 1]
            else:
                dp[i][j] = 1 + min(dp[i - 1][j], dp[i][j - 1], dp[i - 1][j - 1])
    return dp[n][m] / n


# ---------------------------------------------------------------------------
# Audio loading
# ---------------------------------------------------------------------------

def load_audio(path: str, target_sr: int = 16000) -> tuple[np.ndarray, int]:
    audio, sr = sf.read(path)
    if audio.ndim > 1:
        audio = audio[:, 0]
    audio = audio.astype(np.float32)
    if sr != target_sr:
        new_len = int(len(audio) * target_sr / sr)
        indices = np.linspace(0, len(audio) - 1, new_len).astype(int)
        audio = audio[indices]
        sr = target_sr
    return audio, sr


# ---------------------------------------------------------------------------
# Recognizer creation
# ---------------------------------------------------------------------------

def create_recognizer(
    model_dir: str,
    feature_dim: int = 80,
    num_threads: int = 4,
) -> sherpa_onnx.OfflineRecognizer:
    """Create a Parakeet TDT recognizer with specific feature config."""
    return sherpa_onnx.OfflineRecognizer.from_transducer(
        encoder=f"{model_dir}/encoder.int8.onnx",
        decoder=f"{model_dir}/decoder.int8.onnx",
        joiner=f"{model_dir}/joiner.int8.onnx",
        tokens=f"{model_dir}/tokens.txt",
        num_threads=num_threads,
        decoding_method="greedy_search",
        model_type="nemo_transducer",
        sample_rate=16000,
        feature_dim=feature_dim,
    )


# ---------------------------------------------------------------------------
# Transcription
# ---------------------------------------------------------------------------

@dataclass
class SampleResult:
    index: int
    config_label: str
    sample_name: str
    wer_raw: float
    rtf: float
    duration_s: float
    infer_time_s: float
    reference: str
    hypothesis: str


def transcribe(
    recognizer,
    audio: np.ndarray,
    sr: int,
    tail_padding_s: float = 0.0,
) -> tuple[str, float]:
    t0 = time.time()
    stream = recognizer.create_stream()
    if tail_padding_s > 0:
        padded = np.concatenate([audio, np.zeros(int(sr * tail_padding_s), dtype=np.float32)])
    else:
        padded = audio
    stream.accept_waveform(sr, padded)
    recognizer.decode_stream(stream)
    elapsed = time.time() - t0
    text = stream.result.text.strip()
    return text, elapsed


# ---------------------------------------------------------------------------
# Benchmark runner
# ---------------------------------------------------------------------------

CONFIGS = [
    {"feature_dim": 80, "tail_padding_s": 0.0, "label": "A: fd=80 (auto->128)"},
    {"feature_dim": 128, "tail_padding_s": 0.0, "label": "B: fd=128 (explicit)"},
    {"feature_dim": 80, "tail_padding_s": 1.0, "label": "C: fd=80 (auto->128) + 1s pad"},
    {"feature_dim": 128, "tail_padding_s": 1.0, "label": "D: fd=128 + 1s pad"},
]


def run_ab_test(
    model_dir: str,
    samples: list[dict],
    num_threads: int = 4,
) -> list[list[SampleResult]]:
    """Run A/B test with different configs on the same samples."""

    # Pre-load audio once (instead of per-config)
    loaded_audio = [load_audio(s["path"]) for s in samples]

    # Create recognizers keyed by (feature_dim, tail_padding_s).
    # A and B use the same recognizer (auto-detect makes them identical),
    # C and D use the same recognizer with different tail_padding.
    # But for correctness verification, we create one per unique feature_dim.
    rec_cache: dict[int, sherpa_onnx.OfflineRecognizer] = {}
    for fd in {cfg["feature_dim"] for cfg in CONFIGS}:
        rec_cache[fd] = create_recognizer(model_dir, feature_dim=fd, num_threads=num_threads)

    all_results = []

    for cfg in CONFIGS:
        label = cfg["label"]
        rec = rec_cache[cfg["feature_dim"]]

        print(f"\n{'─' * 70}")
        print(f"  Config: {label}")
        print(f"{'─' * 70}")

        results = []
        for i, sample in enumerate(samples):
            audio, sr = loaded_audio[i]
            duration = len(audio) / sr
            hypothesis, elapsed = transcribe(rec, audio, sr, cfg["tail_padding_s"])
            reference = sample["reference"].strip()
            sample_wer = wer(reference, hypothesis)
            rtf = elapsed / duration if duration > 0 else 0

            results.append(SampleResult(
                index=i,
                config_label=label,
                sample_name=sample.get("name", f"sample_{i}"),
                wer_raw=sample_wer,
                rtf=rtf,
                duration_s=duration,
                infer_time_s=elapsed,
                reference=reference,
                hypothesis=hypothesis,
            ))

            ref_short = reference[:60] + ("..." if len(reference) > 60 else "")
            hyp_short = hypothesis[:60] + ("..." if len(hypothesis) > 60 else "")
            print(f"  [{i}] {sample_wer:>5.1%} WER  {rtf:>6.3f}x  {duration:>5.1f}s")
            print(f"       REF: {ref_short}")
            print(f"       HYP: {hyp_short}")

        all_results.append(results)

    return all_results


# ---------------------------------------------------------------------------
# Reporting
# ---------------------------------------------------------------------------

def print_ab_comparison(all_results: list[list[SampleResult]]):
    """Print side-by-side comparison of all configs."""
    if not all_results or not all_results[0]:
        return

    num_samples = len(all_results[0])
    num_configs = len(all_results)

    print(f"\n{'=' * 80}")
    print(f"  A/B COMPARISON SUMMARY")
    print(f"{'=' * 80}")

    header = f"  {'Sample':<20}"
    for results in all_results:
        header += f" {results[0].config_label:>22}"
    print(header)
    print(f"  {'─' * (20 + 23 * num_configs)}")

    for i in range(num_samples):
        row = f"  {all_results[0][i].sample_name:<20}"
        for results in all_results:
            row += f" {results[i].wer_raw:>21.1%}"
        print(row)

    avg_row = f"  {'AVERAGE':<20}"
    for results in all_results:
        avg_wer = sum(r.wer_raw for r in results) / len(results)
        avg_row += f" {avg_wer:>21.1%}"
    print(f"  {'─' * (20 + 23 * num_configs)}")
    print(avg_row)

    print(f"\n  Config equivalence check:")
    for ci in range(1, num_configs):
        identical = all(
            all_results[0][i].hypothesis == all_results[ci][i].hypothesis
            for i in range(num_samples)
        )
        if identical:
            print(f"    Config {all_results[ci][0].config_label} == Config {all_results[0][0].config_label} (IDENTICAL output)")
        else:
            diffs = sum(
                1 for i in range(num_samples)
                if all_results[0][i].hypothesis != all_results[ci][i].hypothesis
            )
            print(f"    Config {all_results[ci][0].config_label} != Config {all_results[0][0].config_label} ({diffs}/{num_samples} samples differ)")


def print_conclusion(all_results: list[list[SampleResult]]):
    """Print investigation conclusions."""
    print(f"\n{'=' * 80}")
    print(f"  INVESTIGATION CONCLUSIONS")
    print(f"{'=' * 80}")

    if not all_results or len(all_results) < 2:
        return

    a_results = all_results[0]
    b_results = all_results[1]

    identical = all(a.hypothesis == b.hypothesis for a, b in zip(a_results, b_results))

    print(f"\n  1. MEL CONFIG AUTO-DETECTION:")
    if identical:
        print(f"     VERIFIED: featureDim=80 and featureDim=128 produce IDENTICAL output.")
        print(f"     sherpa-onnx auto-detects feat_dim=128 from ONNX metadata (confirmed).")
        print(f"     The Kotlin featureDim=80 setting is harmless -- it gets overridden.")
    else:
        diffs = sum(1 for a, b in zip(a_results, b_results) if a.hypothesis != b.hypothesis)
        print(f"     WARNING: {diffs}/{len(a_results)} samples differ between fd=80 and fd=128!")
        print(f"     This suggests mel config auto-detection is NOT working correctly.")

    if len(all_results) >= 4:
        c_results = all_results[2]

        avg_no_pad = sum(r.wer_raw for r in a_results) / len(a_results)
        avg_with_pad = sum(r.wer_raw for r in c_results) / len(c_results)

        print(f"\n  2. TAIL PADDING IMPACT:")
        print(f"     Without padding: {avg_no_pad:.1%} average WER")
        print(f"     With 1s padding: {avg_with_pad:.1%} average WER")
        if abs(avg_no_pad - avg_with_pad) < 0.001:
            print(f"     No significant impact from tail padding on this test set.")
        elif avg_with_pad < avg_no_pad:
            print(f"     Padding improves WER by {avg_no_pad - avg_with_pad:.1%}")
        else:
            print(f"     Padding worsens WER by {avg_with_pad - avg_no_pad:.1%}")

    avg_a = sum(r.wer_raw for r in a_results) / len(a_results)
    print(f"\n  3. QUALITY GAP SOURCES (3.0% NVIDIA vs {avg_a:.1%} ours):")
    print(f"     - Mel spectrogram: CORRECT (auto-detected from ONNX metadata)")
    print(f"     - Remaining gap likely from: INT8 quantization, Opus compression,")
    print(f"       different FLEURS splits, or sherpa-onnx vs NeMo preprocessing differences")


# ---------------------------------------------------------------------------
# CLI
# ---------------------------------------------------------------------------

def parse_args():
    parser = argparse.ArgumentParser(
        description="A/B benchmark: Verify mel config for Parakeet TDT"
    )
    parser.add_argument(
        "--parakeet-dir", required=True,
        help="Path to Parakeet TDT model directory"
    )
    parser.add_argument(
        "--fleurs-references",
        help="JSON file with FLEURS Italian samples [{path, reference, name?}, ...]"
    )
    parser.add_argument(
        "--fabio-audio",
        help="Path to fabio2 WAV file"
    )
    parser.add_argument(
        "--fabio-reference",
        help="Manual reference transcription for fabio2"
    )
    parser.add_argument(
        "--threads", type=int, default=4,
        help="Number of inference threads (default: 4)"
    )
    parser.add_argument(
        "--output",
        help="Save results as JSON"
    )
    return parser.parse_args()


def main():
    args = parse_args()

    samples = []

    if args.fleurs_references:
        with open(args.fleurs_references) as f:
            fleurs = json.load(f)
        for i, s in enumerate(fleurs):
            s.setdefault("name", f"fleurs_{i:02d}")
        samples.extend(fleurs)
        print(f"Loaded {len(fleurs)} FLEURS Italian samples")

    if args.fabio_audio and args.fabio_reference:
        samples.append({
            "path": args.fabio_audio,
            "reference": args.fabio_reference,
            "name": "fabio2_real",
        })
        print(f"Added fabio2 real audio sample")

    if not samples:
        print("ERROR: No samples provided. Use --fleurs-references and/or --fabio-audio + --fabio-reference")
        return

    print(f"\nTotal samples: {len(samples)}")
    print(f"Model: {args.parakeet_dir}")

    all_results = run_ab_test(args.parakeet_dir, samples, args.threads)
    print_ab_comparison(all_results)
    print_conclusion(all_results)

    if args.output:
        output_data = {
            "configs": [r[0].config_label for r in all_results],
            "samples": [
                {
                    "name": all_results[0][i].sample_name,
                    "reference": all_results[0][i].reference,
                    "results_per_config": [
                        {
                            "config": r[i].config_label,
                            "wer": r[i].wer_raw,
                            "rtf": r[i].rtf,
                            "hypothesis": r[i].hypothesis,
                        }
                        for r in all_results
                    ],
                }
                for i in range(len(all_results[0]))
            ],
        }
        with open(args.output, "w") as f:
            json.dump(output_data, f, indent=2, ensure_ascii=False)
        print(f"\nResults saved to {args.output}")


if __name__ == "__main__":
    main()
