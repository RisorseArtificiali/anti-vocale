#!/usr/bin/env python3
"""A/B test: linear interpolation vs Kaiser-windowed sinc resampler.

Decodes an OGG/Opus file to 48kHz PCM (simulating Android MediaCodec output),
then downsamples to 16kHz using both methods, transcribes with sherpa-onnx,
and compares the results.

Usage:
    python3 ab_resampler_test.py <input.ogg> [ground_truth.txt]
"""

import sys
import subprocess
import tempfile
import struct
import math
import numpy as np

try:
    import sherpa_onnx
except ImportError:
    print("ERROR: sherpa_onnx not installed. pip install sherpa-onnx")
    sys.exit(1)

# ── Resampler Implementations ──────────────────────────────────────────

def resample_linear(input_samples: np.ndarray, ratio: float) -> np.ndarray:
    """Linear interpolation resampler (previous implementation)."""
    output_size = int(len(input_samples) / ratio)
    output = np.zeros(output_size, dtype=np.float32)
    for i in range(output_size):
        src_pos = i * ratio
        src_idx = int(src_pos)
        if src_idx < len(input_samples) - 1:
            frac = src_pos - src_idx
            output[i] = input_samples[src_idx] * (1.0 - frac) + input_samples[src_idx + 1] * frac
        elif src_idx < len(input_samples):
            output[i] = input_samples[src_idx]
    return output


def _bessel_i0(x: float) -> float:
    """Modified Bessel function I_0(x) via Taylor series."""
    total = 1.0
    term = 1.0
    for k in range(1, 25):
        term *= (x / (2.0 * k)) ** 2
        total += term
        if term < 1e-12:
            break
    return total


def resample_sinc(input_samples: np.ndarray, ratio: float) -> np.ndarray:
    """Kaiser-windowed sinc resampler (new implementation)."""
    output_size = int(len(input_samples) / ratio)
    if output_size == 0:
        return np.zeros(0, dtype=np.float32)
    output = np.zeros(output_size, dtype=np.float64)

    cutoff = 1.0 / ratio if ratio > 1.0 else 1.0
    half_taps = 8
    kaiser_beta = 5.0
    bessel_denom = _bessel_i0(kaiser_beta)

    for i in range(output_size):
        src_pos = i * ratio
        center = int(src_pos)
        frac = src_pos - center

        total = 0.0
        for k in range(-half_taps, half_taps):
            idx = center + k
            if 0 <= idx < len(input_samples):
                x = k - frac
                # Sinc
                if abs(x) < 1e-10:
                    sinc_val = cutoff
                else:
                    sinc_val = cutoff * math.sin(math.pi * cutoff * x) / (math.pi * cutoff * x)
                # Kaiser window
                window_pos = x / half_taps
                kaiser_arg = kaiser_beta * math.sqrt(max(0.0, 1.0 - window_pos * window_pos))
                window_val = _bessel_i0(kaiser_arg) / bessel_denom
                total += input_samples[idx] * sinc_val * window_val
        output[i] = total

    return output.astype(np.float32)


# ── WER Computation ────────────────────────────────────────────────────

def compute_wer(hypothesis: str, reference: str) -> float:
    """Simple word error rate."""
    h_words = hypothesis.lower().split()
    r_words = reference.lower().split()
    if not r_words:
        return 0.0
    # Levenshtein distance on words
    n, m = len(r_words), len(h_words)
    dp = [[0] * (m + 1) for _ in range(n + 1)]
    for i in range(n + 1):
        dp[i][0] = i
    for j in range(m + 1):
        dp[0][j] = j
    for i in range(1, n + 1):
        for j in range(1, m + 1):
            if r_words[i-1] == h_words[j-1]:
                dp[i][j] = dp[i-1][j-1]
            else:
                dp[i][j] = 1 + min(dp[i-1][j], dp[i][j-1], dp[i-1][j-1])
    return dp[n][m] / n


# ── Main ───────────────────────────────────────────────────────────────

def decode_to_48k(ogg_path: str) -> tuple[np.ndarray, int]:
    """Decode OGG/Opus to 48kHz mono float32 PCM using ffmpeg."""
    cmd = [
        "ffmpeg", "-i", ogg_path, "-ar", "48000", "-ac", "1",
        "-f", "f32le", "-acodec", "pcm_f32le", "-v", "error", "-"
    ]
    result = subprocess.run(cmd, capture_output=True)
    if result.returncode != 0:
        print(f"ffmpeg error: {result.stderr.decode()}")
        sys.exit(1)
    samples = np.frombuffer(result.stdout, dtype=np.float32)
    return samples, 48000


def transcribe(samples: np.ndarray, sample_rate: int, model_dir: str) -> str:
    """Transcribe audio using sherpa-onnx Whisper Turbo."""
    recognizer = sherpa_onnx.OfflineRecognizer.from_whisper(
        encoder=f"{model_dir}/turbo-encoder.int8.onnx",
        decoder=f"{model_dir}/turbo-decoder.int8.onnx",
        tokens=f"{model_dir}/turbo-tokens.txt",
        num_threads=4,
        provider="cpu",
    )
    stream = recognizer.create_stream()
    stream.accept_waveform(sample_rate, samples.tolist())
    recognizer.decode_stream(stream)
    return stream.result.text.strip()


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)

    ogg_path = sys.argv[1]
    ground_truth = sys.argv[2] if len(sys.argv) > 2 else None

    model_dir = "model-export/output/sherpa-onnx-whisper-turbo"
    import os
    script_dir = os.path.dirname(os.path.abspath(__file__))
    project_root = os.path.dirname(os.path.dirname(script_dir))
    model_dir = os.path.join(project_root, model_dir)

    print(f"=== A/B Resampler Test ===")
    print(f"Input: {ogg_path}")
    print(f"Model: {model_dir}")
    print()

    # Decode to 48kHz (simulates Android Opus decoder output)
    print("Decoding OGG to 48kHz PCM...")
    pcm_48k, sr = decode_to_48k(ogg_path)
    duration = len(pcm_48k) / sr
    print(f"  {len(pcm_48k)} samples, {sr}Hz, {duration:.1f}s")
    print()

    # Resample both ways
    ratio = 48000.0 / 16000.0  # 3.0
    print(f"Resampling 48kHz → 16kHz (ratio={ratio})...")
    linear_16k = resample_linear(pcm_48k, ratio)
    sinc_16k = resample_sinc(pcm_48k, ratio)
    print(f"  Linear: {len(linear_16k)} samples")
    print(f"  Sinc:   {len(sinc_16k)} samples")
    print()

    # Spectral comparison
    # Measure energy above 8kHz in the original 48kHz signal
    fft_48k = np.abs(np.fft.rfft(pcm_48k))
    freqs_48k = np.fft.rfftfreq(len(pcm_48k), 1.0/sr)
    above_8k_mask = freqs_48k > 8000
    energy_above_8k = np.sum(fft_48k[above_8k_mask]**2) / np.sum(fft_48k**2) * 100
    print(f"Spectral analysis of 48kHz input:")
    print(f"  Energy above 8kHz: {energy_above_8k:.1f}%")
    print()

    # Transcribe both
    print("Transcribing with linear resampled audio...")
    text_linear = transcribe(linear_16k, 16000, model_dir)
    print(f"  LINEAR: {text_linear}")
    print()

    print("Transcribing with sinc resampled audio...")
    text_sinc = transcribe(sinc_16k, 16000, model_dir)
    print(f"  SINC:   {text_sinc}")
    print()

    # WER comparison if ground truth provided
    if ground_truth:
        with open(ground_truth) as f:
            ref = f.read().strip()
        wer_linear = compute_wer(text_linear, ref)
        wer_sinc = compute_wer(text_sinc, ref)
        print(f"=== WER Results ===")
        print(f"  Ground truth: {ref[:100]}...")
        print(f"  Linear WER: {wer_linear*100:.1f}%")
        print(f"  Sinc WER:   {wer_sinc*100:.1f}%")
        improvement = (wer_linear - wer_sinc) * 100
        print(f"  Improvement: {improvement:+.1f}% {'(better)' if improvement > 0 else '(worse)' if improvement < 0 else '(same)'}")
    else:
        print("No ground truth provided. Provide as 2nd argument for WER comparison.")

    # Show diff
    if text_linear != text_sinc:
        print()
        print("=== Transcription Diff ===")
        import difflib
        diff = difflib.unified_diff(
            text_linear.splitlines(), text_sinc.splitlines(),
            fromfile="linear", tofile="sinc", lineterm=""
        )
        for line in diff:
            print(line)
    else:
        print()
        print("Transcriptions are identical.")


if __name__ == "__main__":
    main()
