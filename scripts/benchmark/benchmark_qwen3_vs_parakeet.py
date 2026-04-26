#!/usr/bin/env python3
"""Head-to-head benchmark: Qwen3-ASR 0.6B vs Parakeet TDT on Italian audio.

Measures WER, RTF, and model size for both models on FLEURS test samples
and real WhatsApp voice messages.

Usage:
    # FLEURS benchmark (needs references.json)
    python benchmark_qwen3_vs_parakeet.py fleurs \\
        --parakeet-dir /tmp/parakeet-bench/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8 \\
        --qwen3-dir /tmp/parakeet-bench/sherpa-onnx-qwen3-asr-0.6B-int8 \\
        --references /tmp/parakeet-bench/references.json

    # Real audio benchmark (single file + manual reference)
    python benchmark_qwen3_vs_parakeet.py real-audio \\
        --parakeet-dir /tmp/parakeet-bench/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8 \\
        --qwen3-dir /tmp/parakeet-bench/sherpa-onnx-qwen3-asr-0.6B-int8 \\
        --audio /path/to/voice-message.wav \\
        --reference "manual transcription here"

    # Full comparison (FLEURS + real audio)
    python benchmark_qwen3_vs_parakeet.py full \\
        --parakeet-dir /tmp/parakeet-bench/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8 \\
        --qwen3-dir /tmp/parakeet-bench/sherpa-onnx-qwen3-asr-0.6B-int8 \\
        --references /tmp/parakeet-bench/references.json \\
        --audio /path/to/voice-message.wav \\
        --reference "manual transcription"
"""

import argparse
import json
import re
import time
from dataclasses import dataclass
from pathlib import Path

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


def _edit_distance(ref_words: list[str], hyp_words: list[str]) -> list[list[int]]:
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
    return dp


def wer(reference: str, hypothesis: str, normalized: bool = True) -> float:
    if normalized:
        ref_words = normalize(reference).split()
        hyp_words = normalize(hypothesis).split()
    else:
        ref_words = reference.lower().split()
        hyp_words = hypothesis.lower().split()
    if not ref_words:
        return 0.0
    dp = _edit_distance(ref_words, hyp_words)
    return dp[len(ref_words)][len(hyp_words)] / len(ref_words)


def wer_detail(reference: str, hypothesis: str) -> dict:
    """Return WER with insertion/deletion/substitution breakdown."""
    ref_words = normalize(reference).split()
    hyp_words = normalize(hypothesis).split()
    n, m = len(ref_words), len(hyp_words)
    if not ref_words:
        return {"wer": 0.0, "insertions": 0, "deletions": 0, "substitutions": 0, "ref_words": 0}
    dp = _edit_distance(ref_words, hyp_words)

    ins = subs = dels = 0
    i, j = n, m
    while i > 0 or j > 0:
        if i > 0 and j > 0 and ref_words[i - 1] == hyp_words[j - 1]:
            i -= 1
            j -= 1
        elif i > 0 and j > 0 and dp[i][j] == dp[i - 1][j - 1] + 1:
            subs += 1
            i -= 1
            j -= 1
        elif j > 0 and dp[i][j] == dp[i][j - 1] + 1:
            ins += 1
            j -= 1
        else:
            dels += 1
            i -= 1

    return {
        "wer": dp[n][m] / n,
        "insertions": ins,
        "deletions": dels,
        "substitutions": subs,
        "ref_words": n,
    }


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
# Model creation
# ---------------------------------------------------------------------------

def create_parakeet_recognizer(model_dir: str, num_threads: int = 4) -> sherpa_onnx.OfflineRecognizer:
    return sherpa_onnx.OfflineRecognizer.from_transducer(
        encoder=f"{model_dir}/encoder.int8.onnx",
        decoder=f"{model_dir}/decoder.int8.onnx",
        joiner=f"{model_dir}/joiner.int8.onnx",
        tokens=f"{model_dir}/tokens.txt",
        num_threads=num_threads,
        decoding_method="greedy_search",
        model_type="nemo_transducer",
    )


def create_qwen3_recognizer(model_dir: str, num_threads: int = 4) -> sherpa_onnx.OfflineRecognizer:
    return sherpa_onnx.OfflineRecognizer.from_qwen3_asr(
        conv_frontend=f"{model_dir}/conv_frontend.onnx",
        encoder=f"{model_dir}/encoder.int8.onnx",
        decoder=f"{model_dir}/decoder.int8.onnx",
        tokenizer=f"{model_dir}/tokenizer",
        num_threads=num_threads,
        max_new_tokens=2048,
    )


# ---------------------------------------------------------------------------
# Transcription
# ---------------------------------------------------------------------------

@dataclass
class SampleResult:
    index: int
    wer_raw: float
    wer_detail: dict
    rtf: float
    duration_s: float
    infer_time_s: float
    reference: str
    hypothesis: str
    model_label: str


def transcribe_sample(recognizer, audio: np.ndarray, sr: int, model_type: str) -> tuple[str, float]:
    t0 = time.time()
    stream = recognizer.create_stream()
    if model_type == "qwen3":
        stream.set_option("language", "Italian")
    stream.accept_waveform(sr, audio)
    recognizer.decode_stream(stream)
    elapsed = time.time() - t0
    text = stream.result.text.strip()
    return text, elapsed


def benchmark_model(
    recognizer, samples: list[dict], model_label: str, model_type: str,
    tail_padding_s: float = 0.0,
) -> list[SampleResult]:
    results = []
    for i, sample in enumerate(samples):
        audio, sr = load_audio(sample["path"])
        duration = len(audio) / sr

        if tail_padding_s > 0:
            audio = np.concatenate([audio, np.zeros(int(sr * tail_padding_s), dtype=np.float32)])

        hypothesis, elapsed = transcribe_sample(recognizer, audio, sr, model_type)
        reference = sample["reference"].strip()
        sample_wer = wer(reference, hypothesis)
        detail = wer_detail(reference, hypothesis)
        rtf = elapsed / duration if duration > 0 else 0

        results.append(SampleResult(
            index=i, wer_raw=sample_wer, wer_detail=detail, rtf=rtf,
            duration_s=duration, infer_time_s=elapsed,
            reference=reference, hypothesis=hypothesis, model_label=model_label,
        ))

    return results


# ---------------------------------------------------------------------------
# Model size measurement
# ---------------------------------------------------------------------------

def model_size_mb(model_dir: str) -> float:
    total = 0
    for f in Path(model_dir).rglob("*"):
        if f.is_file() and not f.is_symlink():
            total += f.stat().st_size
    return total / (1024 * 1024)


def _aggregate(results: list[SampleResult]) -> dict:
    """Compute summary statistics from a list of sample results."""
    if not results:
        return {"avg_wer": 0.0, "total_time": 0.0, "total_duration": 0.0, "avg_rtf": 0.0}
    total_time = sum(r.infer_time_s for r in results)
    total_duration = sum(r.duration_s for r in results)
    return {
        "avg_wer": sum(r.wer_raw for r in results) / len(results),
        "total_time": total_time,
        "total_duration": total_duration,
        "avg_rtf": total_time / total_duration if total_duration > 0 else 0.0,
    }


# ---------------------------------------------------------------------------
# Reporting
# ---------------------------------------------------------------------------

def print_sample_results(results: list[SampleResult]):
    print(f"\n  {'#':>2}  {'WER':>6}  {'RTF':>7}  {'Dur':>5}  {'Time':>5}  Ref / Hyp")
    print(f"  {'─' * 80}")
    for r in results:
        ref_short = r.reference[:40] + ("..." if len(r.reference) > 40 else "")
        hyp_short = r.hypothesis[:40] + ("..." if len(r.hypothesis) > 40 else "")
        print(f"  {r.index:>2}  {r.wer_raw:>5.1%}  {r.rtf:>6.3f}x  {r.duration_s:>4.1f}s  {r.infer_time_s:>4.2f}s")
        print(f"      REF: {ref_short}")
        print(f"      HYP: {hyp_short}")


def print_error_analysis(results: list[SampleResult], label: str):
    print(f"\n  Error analysis for {label}:")
    total_ins = sum(r.wer_detail["insertions"] for r in results)
    total_del = sum(r.wer_detail["deletions"] for r in results)
    total_sub = sum(r.wer_detail["substitutions"] for r in results)
    total_ref = sum(r.wer_detail["ref_words"] for r in results)
    if total_ref == 0:
        return
    print(f"    Insertions:      {total_ins:>4} ({total_ins / total_ref:.1%})")
    print(f"    Deletions:       {total_del:>4} ({total_del / total_ref:.1%})")
    print(f"    Substitutions:   {total_sub:>4} ({total_sub / total_ref:.1%})")

    # Show worst samples
    worst = sorted(results, key=lambda r: r.wer_raw, reverse=True)[:3]
    if worst and worst[0].wer_raw > 0:
        print(f"\n    Worst samples:")
        for r in worst:
            if r.wer_raw == 0:
                break
            ref_w = normalize(r.reference).split()
            hyp_w = normalize(r.hypothesis).split()
            # Find first differing word
            errors = []
            for j in range(max(len(ref_w), len(hyp_w))):
                rw = ref_w[j] if j < len(ref_w) else "<missing>"
                hw = hyp_w[j] if j < len(hyp_w) else "<missing>"
                if rw != hw:
                    errors.append(f"{rw}→{hw}")
                    if len(errors) >= 5:
                        break
            print(f"      [{r.index}] WER:{r.wer_raw:.0%}  errors: {', '.join(errors)}")


def print_comparison_summary(
    parakeet_results: list[SampleResult],
    qwen3_results: list[SampleResult],
    parakeet_size_mb: float,
    qwen3_size_mb: float,
):
    p = _aggregate(parakeet_results)
    q = _aggregate(qwen3_results)

    print(f"\n{'=' * 80}")
    print(f"  HEAD-TO-HEAD COMPARISON SUMMARY")
    print(f"{'=' * 80}")
    print(f"  {'Metric':<25} {'Parakeet TDT':>15} {'Qwen3-ASR':>15} {'Winner':>12}")
    print(f"  {'─' * 70}")
    print(f"  {'Average WER':<25} {p['avg_wer']:>14.1%} {q['avg_wer']:>14.1%} {'← Parakeet' if p['avg_wer'] <= q['avg_wer'] else '← Qwen3':>12}")
    print(f"  {'Average RTF':<25} {p['avg_rtf']:>13.3f}x {q['avg_rtf']:>13.3f}x {'← Parakeet' if p['avg_rtf'] <= q['avg_rtf'] else '← Qwen3':>12}")
    print(f"  {'Total inference (s)':<25} {p['total_time']:>15.1f} {q['total_time']:>15.1f}")
    print(f"  {'Total audio (s)':<25} {p['total_duration']:>15.1f} {q['total_duration']:>15.1f}")
    print(f"  {'Model size (MB)':<25} {parakeet_size_mb:>15.0f} {qwen3_size_mb:>15.0f} {'← Qwen3' if qwen3_size_mb <= parakeet_size_mb else '← Parakeet':>12}")
    print(f"  {'Samples':<25} {len(parakeet_results):>15} {len(qwen3_results):>15}")

    # Per-sample breakdown
    if len(parakeet_results) == len(qwen3_results):
        print(f"\n  Per-sample WER comparison:")
        print(f"  {'#':>2}  {'Parakeet':>8}  {'Qwen3':>8}  {'Delta':>8}  Better")
        print(f"  {'─' * 45}")
        for pr, qr in zip(parakeet_results, qwen3_results):
            delta = qr.wer_raw - pr.wer_raw
            better = "← Qwen3" if delta < -0.005 else "← Parakeet" if delta > 0.005 else "tie"
            print(f"  {pr.index:>2}  {pr.wer_raw:>7.1%}  {qr.wer_raw:>7.1%}  {delta:>+7.1%}  {better}")


def print_recommendation(
    parakeet_results: list[SampleResult],
    qwen3_results: list[SampleResult],
    parakeet_size_mb: float,
    qwen3_size_mb: float,
):
    if not parakeet_results or not qwen3_results:
        return

    p = _aggregate(parakeet_results)
    q = _aggregate(qwen3_results)

    print(f"\n{'=' * 80}")
    print(f"  RECOMMENDATION")
    print(f"{'=' * 80}")

    wer_delta = q["avg_wer"] - p["avg_wer"]
    rtf_ratio = q["avg_rtf"] / p["avg_rtf"] if p["avg_rtf"] > 0 else float("inf")
    size_ratio = qwen3_size_mb / parakeet_size_mb if parakeet_size_mb > 0 else float("inf")

    if wer_delta < -0.02:
        qual_verdict = "Qwen3-ASR is significantly more accurate"
    elif wer_delta < -0.005:
        qual_verdict = "Qwen3-ASR is slightly more accurate"
    elif wer_delta > 0.02:
        qual_verdict = "Parakeet TDT is significantly more accurate"
    elif wer_delta > 0.005:
        qual_verdict = "Parakeet TDT is slightly more accurate"
    else:
        qual_verdict = "Both models have similar accuracy"

    if rtf_ratio < 0.8:
        speed_verdict = "Qwen3-ASR is faster"
    elif rtf_ratio > 1.2:
        speed_verdict = "Parakeet TDT is faster"
    else:
        speed_verdict = "Both have similar speed"

    if size_ratio < 0.8:
        size_verdict = "Qwen3-ASR is smaller"
    elif size_ratio > 1.2:
        size_verdict = "Parakeet TDT is smaller"
    else:
        size_verdict = "Both have similar size"

    print(f"  Quality:  {qual_verdict} (ΔWER: {wer_delta:+.1%})")
    print(f"  Speed:    {speed_verdict} (RTF ratio: {rtf_ratio:.2f}x)")
    print(f"  Size:     {size_verdict} ({qwen3_size_mb:.0f}MB vs {parakeet_size_mb:.0f}MB)")


    if wer_delta > 0.02 and rtf_ratio > 1.2:
        print(f"\n  → Parakeet TDT wins clearly: better quality AND faster")
    elif wer_delta < -0.02 and rtf_ratio < 0.8:
        print(f"\n  → Qwen3-ASR wins clearly: better quality AND faster")
    elif wer_delta < -0.02 and rtf_ratio > 1.2:
        print(f"\n  → Trade-off: Qwen3-ASR is more accurate but slower. Consider if quality matters more than speed for Italian.")
    elif wer_delta > 0.02 and rtf_ratio < 0.8:
        print(f"\n  → Trade-off: Parakeet TDT is more accurate but slower (unusual).")
    else:
        print(f"\n  → No clear winner. Consider model size and language coverage as tiebreakers.")
        print(f"    Qwen3-ASR supports 52 languages; Parakeet TDT supports 25 EU languages.")


# ---------------------------------------------------------------------------
# CLI & main
# ---------------------------------------------------------------------------

def parse_args():
    parser = argparse.ArgumentParser(
        description="Head-to-head benchmark: Qwen3-ASR 0.6B vs Parakeet TDT on Italian audio"
    )
    subparsers = parser.add_subparsers(dest="command", required=True)

    common = argparse.ArgumentParser(add_help=False)
    common.add_argument("--parakeet-dir", required=True, help="Path to Parakeet TDT model directory")
    common.add_argument("--qwen3-dir", required=True, help="Path to Qwen3-ASR model directory")
    common.add_argument("--threads", type=int, default=4, help="Number of inference threads (default: 4)")
    common.add_argument("--output", help="Save results as JSON to this file")

    # fleurs subcommand
    fleurs_parser = subparsers.add_parser("fleurs", parents=[common],
                                          help="Benchmark on Italian FLEURS test samples")
    fleurs_parser.add_argument("--references", required=True,
                               help="JSON file with [{path, reference}, ...]")

    # real-audio subcommand
    real_parser = subparsers.add_parser("real-audio", parents=[common],
                                        help="Benchmark on a real audio file")
    real_parser.add_argument("--audio", required=True, help="Path to WAV audio file")
    real_parser.add_argument("--reference", required=True, help="Manual reference transcription")

    # full subcommand
    full_parser = subparsers.add_parser("full", parents=[common],
                                        help="Run both FLEURS and real-audio benchmarks")
    full_parser.add_argument("--references", required=True, help="FLEURS references JSON")
    full_parser.add_argument("--audio", help="Real audio WAV file")
    full_parser.add_argument("--reference", help="Manual transcription for real audio")

    return parser.parse_args()


def load_fleurs_samples(references_path: str) -> list[dict]:
    with open(references_path) as f:
        return json.load(f)


def run_benchmark_set(
    parakeet_dir: str, qwen3_dir: str, samples: list[dict],
    num_threads: int, parakeet_padding: float = 1.0,
) -> tuple[list[SampleResult], list[SampleResult]]:
    print(f"\nLoading Parakeet TDT from {parakeet_dir}...")
    parakeet_rec = create_parakeet_recognizer(parakeet_dir, num_threads)
    print(f"Loading Qwen3-ASR from {qwen3_dir}...")
    qwen3_rec = create_qwen3_recognizer(qwen3_dir, num_threads)

    print(f"\nRunning benchmark on {len(samples)} samples...")

    print(f"\n{'=' * 80}")
    print(f"  PARAKEET TDT (greedy_search + {parakeet_padding}s tail padding)")
    print(f"{'=' * 80}")
    parakeet_results = benchmark_model(parakeet_rec, samples, "Parakeet TDT", "parakeet",
                                       tail_padding_s=parakeet_padding)
    print_sample_results(parakeet_results)
    print_error_analysis(parakeet_results, "Parakeet TDT")

    print(f"\n{'=' * 80}")
    print(f"  QWEN3-ASR 0.6B (language=Italian)")
    print(f"{'=' * 80}")
    qwen3_results = benchmark_model(qwen3_rec, samples, "Qwen3-ASR", "qwen3")
    print_sample_results(qwen3_results)
    print_error_analysis(qwen3_results, "Qwen3-ASR")

    return parakeet_results, qwen3_results


def results_to_json(
    parakeet_results: list[SampleResult],
    qwen3_results: list[SampleResult],
    parakeet_size_mb: float,
    qwen3_size_mb: float,
    samples: list[dict],
) -> dict:
    def _serialize(results: list[SampleResult]) -> list[dict]:
        return [
            {
                "index": r.index,
                "wer": r.wer_raw,
                "rtf": r.rtf,
                "duration_s": r.duration_s,
                "infer_time_s": r.infer_time_s,
                "reference": r.reference,
                "hypothesis": r.hypothesis,
                "errors": r.wer_detail,
            }
            for r in results
        ]

    p = _aggregate(parakeet_results)
    q = _aggregate(qwen3_results)

    return {
        "parakeet": {
            "avg_wer": p["avg_wer"],
            "model_size_mb": parakeet_size_mb,
            "results": _serialize(parakeet_results),
        },
        "qwen3": {
            "avg_wer": q["avg_wer"],
            "model_size_mb": qwen3_size_mb,
            "results": _serialize(qwen3_results),
        },
        "num_samples": len(samples),
        "timestamp": time.strftime("%Y-%m-%dT%H:%M:%S"),
    }


def main():
    args = parse_args()

    parakeet_size = model_size_mb(args.parakeet_dir)
    qwen3_size = model_size_mb(args.qwen3_dir)
    print(f"Model sizes: Parakeet={parakeet_size:.0f}MB, Qwen3-ASR={qwen3_size:.0f}MB")

    all_parakeet = []
    all_qwen3 = []
    all_samples = []

    if args.command in ("fleurs", "full"):
        print(f"\n{'#' * 80}")
        print(f"  FLEURS BENCHMARK")
        print(f"{'#' * 80}")
        samples = load_fleurs_samples(args.references)
        print(f"Loaded {len(samples)} Italian FLEURS samples from {args.references}")

        parakeet_results, qwen3_results = run_benchmark_set(
            args.parakeet_dir, args.qwen3_dir, samples, args.threads,
        )
        print_comparison_summary(parakeet_results, qwen3_results, parakeet_size, qwen3_size)
        print_recommendation(parakeet_results, qwen3_results, parakeet_size, qwen3_size)

        all_parakeet.extend(parakeet_results)
        all_qwen3.extend(qwen3_results)
        all_samples.extend(samples)

    if args.command in ("real-audio", "full") and args.audio:
        print(f"\n{'#' * 80}")
        print(f"  REAL AUDIO BENCHMARK")
        print(f"{'#' * 80}")
        samples = [{"path": args.audio, "reference": args.reference or ""}]
        print(f"Audio: {args.audio}")

        parakeet_results, qwen3_results = run_benchmark_set(
            args.parakeet_dir, args.qwen3_dir, samples, args.threads,
        )
        print_comparison_summary(parakeet_results, qwen3_results, parakeet_size, qwen3_size)

        all_parakeet.extend(parakeet_results)
        all_qwen3.extend(qwen3_results)
        all_samples.extend(samples)

    if all_parakeet and all_qwen3:
        print_comparison_summary(all_parakeet, all_qwen3, parakeet_size, qwen3_size)
        print_recommendation(all_parakeet, all_qwen3, parakeet_size, qwen3_size)

    if args.output:
        data = results_to_json(all_parakeet, all_qwen3, parakeet_size, qwen3_size, all_samples)
        with open(args.output, "w") as f:
            json.dump(data, f, indent=2, ensure_ascii=False)
        print(f"\nResults saved to {args.output}")


if __name__ == "__main__":
    main()
