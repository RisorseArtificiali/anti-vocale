#!/usr/bin/env python3
"""TASK-1020 desktop bench: Reson8/Rumble-2-Flash (0.5B qwen3_asr, Apache 2.0)
on the 11-clip eval-it set AND the noisy set (agnese pink/white SNR 0/5/10),
same methodology as the TASK-244 qwen bench: per-clip WER/CER via
run_baseline's helpers, wall-time RTF, aggregate. The transformers leg (no
sherpa export exists yet): the card's own Qwen3ASRForConditionalGeneration
usage, float32 on CPU (the Mac MPS path is unverified for this arch; the
numbers here are quality anchors, the device RTF comes with the sherpa leg).

Anchors (same 11-clip set): Parakeet v3 int8 = 5.4% WER, qwen3 0.6B = 12.2%
(from the TASK-244 bench header; parakeet re-run in the same session for a
live anchor).
"""
import sys
import time
from pathlib import Path

import numpy as np
import torch
import librosa

sys.path.insert(0, str(Path(__file__).resolve().parent))
from run_baseline import normalize_it, wer, cer, repetition_loops  # noqa: E402
from audio_loader import load_audio  # noqa: E402

EVAL = Path(__file__).resolve().parent
MODEL_DIR = EVAL / "models" / "rumble-2-flash"
CLIPS = EVAL / "clips"
NOISY = EVAL / "clips_noisy"
TRANSCRIPTS = EVAL / "transcripts"

from transformers import AutoProcessor, Qwen3ASRForConditionalGeneration  # noqa: E402

torch.set_num_threads(4)

processor = AutoProcessor.from_pretrained(str(MODEL_DIR))
model = Qwen3ASRForConditionalGeneration.from_pretrained(
    str(MODEL_DIR), dtype=torch.float32
)


def transcribe(samples: np.ndarray, language: str = "Italian") -> str:
    inputs = processor.apply_transcription_request(
        audio=samples, language=language, return_tensors="pt"
    )
    t0 = time.time()
    with torch.no_grad():
        out = model.generate(**inputs, max_new_tokens=512)
    dt = time.time() - t0
    raw = processor.batch_decode(out, skip_special_tokens=True)[0]
    # The chat scaffold survives decode; the transcript is the <asr_text>
    # span (unclosed when generation hits the token cap, hence the lax end).
    import re as _re
    m = _re.search(r"<asr_text>(.*?)(?:</asr_text>|$)", raw, _re.S)
    text = (m.group(1) if m else raw).strip()
    return text, dt


def run_set(pairs, language, label):
    print(f"\n== {label} ==")
    print(f"{'clip':34s} {'WER':>7s} {'CER':>7s} {'RTF':>6s}  text")
    agg_w, agg_c, n_looped = [], [], 0
    for clip, ref in pairs:
        samples = load_audio(clip, sample_rate=16_000)
        dur = len(samples) / 16000
        hyp, dt = transcribe(samples, language)
        w = wer(normalize_it(ref.read_text()).split(), normalize_it(hyp).split()) * 100
        c = cer(normalize_it(ref.read_text()), normalize_it(hyp)) * 100
        if repetition_loops(hyp.split()):
            n_looped += 1
        agg_w.append(w)
        agg_c.append(c)
        print(f"{clip.name:34s} {w:6.2f}% {c:6.2f}% {dt/dur:5.2f}x  {hyp[:60]}")
    print(
        f"{label}: WER {np.mean(agg_w):.2f}%  CER {np.mean(agg_c):.2f}%  "
        f"looped {n_looped}/{len(pairs)}"
    )
    return np.mean(agg_w), np.mean(agg_c)


clean_pairs = sorted(
    [
        (c, TRANSCRIPTS / f"{c.stem}.txt")
        for c in CLIPS.iterdir()
        if c.suffix in (".wav", ".ogg")
    ],
    key=lambda x: x[0].name,
)
agnese_ref = TRANSCRIPTS / "agnese.txt"
noisy_pairs = sorted(
    [(c, agnese_ref) for c in NOISY.iterdir() if c.name.startswith("agnese__")],
    key=lambda x: x[0].name,
)

run_set(clean_pairs, "Italian", "clean-11")
if noisy_pairs:
    run_set(noisy_pairs, "Italian", "noisy-agnese")
