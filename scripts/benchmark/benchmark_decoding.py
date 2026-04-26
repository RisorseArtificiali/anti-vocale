#!/usr/bin/env python3
"""Benchmark Parakeet TDT v3 on Italian audio with different decoding configs."""

import json
import time
import sherpa_onnx
import soundfile as sf

MODEL_DIR = "/tmp/parakeet-bench/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"
REFS_FILE = "/tmp/parakeet-bench/references.json"

def wer(reference: str, hypothesis: str) -> float:
    """Simple word error rate."""
    ref_words = reference.lower().split()
    hyp_words = hypothesis.lower().split()
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

def create_recognizer(decoding_method: str, max_active_paths: int = 4):
    """Create a sherpa-onnx recognizer with the specified decoding config."""
    recognizer = sherpa_onnx.OfflineRecognizer.from_transducer(
        encoder=f"{MODEL_DIR}/encoder.int8.onnx",
        decoder=f"{MODEL_DIR}/decoder.int8.onnx",
        joiner=f"{MODEL_DIR}/joiner.int8.onnx",
        tokens=f"{MODEL_DIR}/tokens.txt",
        num_threads=4,
        decoding_method=decoding_method,
        max_active_paths=max_active_paths,
        model_type="nemo_transducer",
    )
    return recognizer

def run_benchmark(recognizer, samples, label):
    """Run benchmark and print results."""
    print(f"\n{'='*70}")
    print(f"  CONFIG: {label}")
    print(f"{'='*70}")

    total_wer = 0
    total_time = 0
    total_duration = 0
    results = []

    for i, sample in enumerate(samples):
        audio, sr = sf.read(sample["path"])
        if audio.ndim > 1:
            audio = audio[:, 0]
        audio = audio.astype("float32")

        if sr != 16000:
            import numpy as np
            new_len = int(len(audio) * 16000 / sr)
            indices = np.linspace(0, len(audio) - 1, new_len).astype(int)
            audio = audio[indices]
            sr = 16000

        duration = len(audio) / sr

        t0 = time.time()
        stream = recognizer.create_stream()
        stream.accept_waveform(sr, audio)
        recognizer.decode_stream(stream)
        result = stream.result
        elapsed = time.time() - t0

        hypothesis = result.text.strip()
        reference = sample["reference"].strip()
        sample_wer = wer(reference, hypothesis)

        total_wer += sample_wer
        total_time += elapsed
        total_duration += duration
        rtf = elapsed / duration if duration > 0 else 0

        results.append({
            "sample": i,
            "wer": sample_wer,
            "rtf": rtf,
            "duration_s": duration,
            "infer_time_s": elapsed,
        })

        print(f"\n  [{i}] WER: {sample_wer:.1%}  RTF: {rtf:.3f}x  ({duration:.1f}s in {elapsed:.2f}s)")
        print(f"    REF: {reference[:100]}")
        print(f"    HYP: {hypothesis[:100]}")

    avg_wer = total_wer / len(samples) if samples else 0
    avg_rtf = total_time / total_duration if total_duration > 0 else 0

    print(f"\n  AVERAGE WER: {avg_wer:.1%}")
    print(f"  AVERAGE RTF: {avg_rtf:.3f}x  (total: {total_duration:.1f}s in {total_time:.1f}s)")

    return {"label": label, "avg_wer": avg_wer, "avg_rtf": avg_rtf, "results": results}

def main():
    with open(REFS_FILE) as f:
        samples = json.load(f)

    print(f"Loaded {len(samples)} Italian audio samples")

    configs = [
        ("greedy_search", 4, "greedy_search"),
        ("modified_beam_search", 4, "modified_beam_search (paths=4)"),
        ("modified_beam_search", 25, "modified_beam_search (paths=25)"),
    ]

    all_results = []
    for method, paths, label in configs:
        recognizer = create_recognizer(method, paths)
        result = run_benchmark(recognizer, samples, label)
        all_results.append(result)

    print(f"\n\n{'='*70}")
    print(f"  COMPARISON SUMMARY")
    print(f"{'='*70}")
    print(f"  {'Config':<55} {'WER':>6} {'RTF':>6}")
    print(f"  {'─'*67}")
    for r in all_results:
        print(f"  {r['label']:<55} {r['avg_wer']:>5.1%} {r['avg_rtf']:>5.3f}x")

if __name__ == "__main__":
    main()
