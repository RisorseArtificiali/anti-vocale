#!/usr/bin/env python3
"""Shared audio loader for the eval harness (TASK-461).

Decodes any ffmpeg-readable container (opus/m4a/mp3/wav/ogg/flac) to mono
float32 at the model's sample rate, via an ffmpeg pipe read by soundfile:
no temp files, no librosa. run_baseline.py (--backends) and
postprocess_score.py (--transcribe) both go through load_audio so there is
exactly ONE loader to keep working; the previous split left run_baseline on
a librosa that was listed in requirements.txt but never installed in the
venv, which killed its --backends path at load_audio.

Importing this module needs only the stdlib (numpy appears just for type
checkers); soundfile is imported lazily by load_audio, which exits with a
pip hint when it is missing. ffmpeg must be on PATH.
"""

from __future__ import annotations

import io
import subprocess
from pathlib import Path
from typing import TYPE_CHECKING

if TYPE_CHECKING:
    import numpy as np


def load_audio(path: Path, sample_rate: int = 16_000) -> np.ndarray:
    """Decode `path` to float32 mono samples at `sample_rate`.

    ffmpeg does container decode + resample + downmix and writes wav into
    the pipe (nothing lands on disk); soundfile turns those bytes into a
    float32 ndarray in [-1, 1], which is what sherpa expects. Raises
    subprocess.CalledProcessError if ffmpeg rejects the file.
    """
    try:
        import soundfile as sf
    except ImportError:
        raise SystemExit(
            "soundfile not installed (needed to decode audio).\n"
            "Run:  pip install -r eval/requirements.txt"
        ) from None
    try:
        proc = subprocess.run(
            ["ffmpeg", "-v", "error", "-i", str(path),
             "-ar", str(sample_rate), "-ac", "1", "-f", "wav", "-"],
            capture_output=True, check=True,
        )
    except FileNotFoundError:
        raise SystemExit(
            "ffmpeg not found on PATH (needed to decode audio)."
        ) from None
    except subprocess.CalledProcessError as e:
        # ffmpeg's diagnostic lives in e.stderr; without it the failure names
        # the file but not the reason (review finding, TASK-461)
        detail = e.stderr.decode(errors="replace").strip().splitlines()
        tail = detail[-1] if detail else f"exit {e.returncode}"
        raise RuntimeError(f"ffmpeg failed on {path}: {tail}") from e
    samples, sr = sf.read(io.BytesIO(proc.stdout), dtype="float32")
    assert sr == sample_rate
    return samples
