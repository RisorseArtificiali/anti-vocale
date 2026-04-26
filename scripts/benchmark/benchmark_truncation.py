#!/usr/bin/env python3
"""Benchmark Parakeet with tail padding and blank_penalty to investigate truncation."""

import json
import time
import numpy as np
import sherpa_onnx
import soundfile as sf

MODEL_DIR = "/tmp/parakeet-bench/sherpa-onnx-nemo-parakeet-tdt-0.6b-v3-int8"
REFS_FILE = "/tmp/parakeet-bench/references.json"

def normalize(text):
    import re
    text = text.lower()
    text = re.sub(r"[.,;:!?\"()\-]", " ", text)
    text = re.sub(r"\s+", " ", text).strip()
    return text

def wer(reference: str, hypothesis: str) -> float:
    ref_words = normalize(reference).split()
    hyp_words = normalize(hypothesis).split()
    if not ref_words:
        return 0.0
    n, m = len(ref_words), len(hyp_words)
    dp = [[0]*(m+1) for _ in range(n+1)]
    for i in range(n+1): dp[i][0] = i
    for j in range(m+1): dp[0][j] = j
    for i in range(1, n+1):
        for j in range(1, m+1):
            if ref_words[i-1] == hyp_words[j-1]:
                dp[i][j] = dp[i-1][j-1]
            else:
                dp[i][j] = 1 + min(dp[i-1][j], dp[i][j-1], dp[i-1][j-1])
    return dp[n][m] / n

def create_recognizer(blank_penalty: float = 0.0):
    return sherpa_onnx.OfflineRecognizer.from_transducer(
        encoder=f"{MODEL_DIR}/encoder.int8.onnx",
        decoder=f"{MODEL_DIR}/decoder.int8.onnx",
        joiner=f"{MODEL_DIR}/joiner.int8.onnx",
        tokens=f"{MODEL_DIR}/tokens.txt",
        num_threads=4,
        decoding_method="greedy_search",
        blank_penalty=blank_penalty,
        model_type="nemo_transducer",
    )

def pad_audio(audio, sr, padding_seconds):
    if padding_seconds <= 0:
        return audio
    silence = np.zeros(int(sr * padding_seconds), dtype=np.float32)
    return np.concatenate([audio, silence])

def run_test(recognizer, samples, padding_seconds=0.0, label=""):
    print(f"\n{'='*70}")
    print(f"  {label}")
    print(f"{'='*70}")

    total_wer = 0
    total_time = 0
    total_duration = 0

    for i, sample in enumerate(samples):
        audio, sr = sf.read(sample["path"])
        if audio.ndim > 1:
            audio = audio[:, 0]
        audio = audio.astype("float32")
        if sr != 16000:
            new_len = int(len(audio) * 16000 / sr)
            indices = np.linspace(0, len(audio) - 1, new_len).astype(int)
            audio = audio[indices]
            sr = 16000

        original_duration = len(audio) / sr
        audio = pad_audio(audio, sr, padding_seconds)

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
        total_duration += original_duration

        ref_len = len(reference)
        hyp_len = len(hypothesis)
        trunc = "TRUNC" if hyp_len < ref_len * 0.8 else "ok   "

        print(f"  [{i}] {trunc} WER:{sample_wer:.1%} chars:{hyp_len:>3}/{ref_len:>3} "
              f"({original_duration:.1f}s +{padding_seconds}s pad in {elapsed:.2f}s)")
        if hyp_len < ref_len * 0.95:
            print(f"       REF: ...{reference[-60:]}")
            print(f"       HYP: ...{hypothesis[-60:]}")

    avg_wer = total_wer / len(samples)
    print(f"\n  AVG WER: {avg_wer:.1%}  total: {total_duration:.1f}s in {total_time:.1f}s")
    return avg_wer

def main():
    with open(REFS_FILE) as f:
        samples = json.load(f)

    print(f"Loaded {len(samples)} Italian samples")

    rec = create_recognizer(blank_penalty=0.0)
    baseline_wer = run_test(rec, samples, padding_seconds=0.0,
                            label="BASELINE: no padding, blank_penalty=0.0")

    rec2 = create_recognizer(blank_penalty=0.0)
    pad2_wer = run_test(rec2, samples, padding_seconds=2.0,
                        label="PAD 2s: 2 seconds silence appended, blank_penalty=0.0")

    rec3 = create_recognizer(blank_penalty=0.0)
    pad5_wer = run_test(rec3, samples, padding_seconds=5.0,
                        label="PAD 5s: 5 seconds silence appended, blank_penalty=0.0")

    rec4 = create_recognizer(blank_penalty=1.0)
    bp1_wer = run_test(rec4, samples, padding_seconds=0.0,
                       label="BLANK_PEN 1.0: no padding, blank_penalty=1.0")

    rec5 = create_recognizer(blank_penalty=1.0)
    both_wer = run_test(rec5, samples, padding_seconds=3.0,
                        label="COMBO: 3s padding + blank_penalty=1.0")

    print(f"\n\n{'='*70}")
    print(f"  TRUNCATION FIX SUMMARY")
    print(f"{'='*70}")
    results = [
        ("Baseline (no pad, no blank_penalty)", baseline_wer),
        ("+ 2s silence padding", pad2_wer),
        ("+ 5s silence padding", pad5_wer),
        ("+ blank_penalty=1.0", bp1_wer),
        ("+ 3s pad + blank_penalty=1.0", both_wer),
    ]
    for label, w in results:
        delta = w - baseline_wer
        arrow = "BETTER" if delta < -0.01 else "WORSE" if delta > 0.01 else "same"
        print(f"  {label:<42} WER: {w:.1%}  ({arrow} {delta:+.1%})")

if __name__ == "__main__":
    main()
