#!/usr/bin/env python3
"""Whole-file speaker diarization reference (TASK-1060.1 / 1060.2).

Mirrors the app's SpeakerDiarizer configuration: pyannote segmentation 3.0
int8 + NeMo titanet-small embeddings, FastClustering with the auto threshold
0.8 (num_clusters=-1). The whole-file output is the identity reference the
windowed spike (TASK-1060.2) is compared against; this script also prints the
sanity-floor summary (speaker count, segment count, per-speaker talk time).

Usage:
  eval/.venv/bin/python -I eval/diarization_reference.py \
      --models eval/diarization-spike/models \
      --out eval/diarization-spike/reference \
      eval/diarization-spike/audio/*.mp3
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from collections import defaultdict
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from audio_loader import load_audio  # noqa: E402

import sherpa_onnx  # noqa: E402

AUTO_THRESHOLD = 0.8  # SpeakerDiarizer.AUTO_THRESHOLD


def build_engine(models: Path, threads: int, threshold: float) -> sherpa_onnx.OfflineSpeakerDiarization:
    config = sherpa_onnx.OfflineSpeakerDiarizationConfig(
        segmentation=sherpa_onnx.OfflineSpeakerSegmentationModelConfig(
            pyannote=sherpa_onnx.OfflineSpeakerSegmentationPyannoteModelConfig(
                model=str(models / "sherpa-onnx-pyannote-segmentation-3-0" / "model.int8.onnx"),
            ),
            num_threads=threads,
        ),
        embedding=sherpa_onnx.SpeakerEmbeddingExtractorConfig(
            model=str(models / "titanet-small.onnx"),
            num_threads=threads,
        ),
        clustering=sherpa_onnx.FastClusteringConfig(
            num_clusters=-1, threshold=threshold, compute_confidence=True,
        ),
    )
    if not config.validate():
        raise SystemExit("invalid diarization config (check model paths)")
    return sherpa_onnx.OfflineSpeakerDiarization(config)


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--models", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--threads", type=int, default=4)
    ap.add_argument("--threshold", type=float, default=AUTO_THRESHOLD)
    ap.add_argument("audio", type=Path, nargs="+")
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)

    engine = build_engine(args.models, args.threads, args.threshold)
    rate = engine.sample_rate
    for path in args.audio:
        t0 = time.monotonic()
        samples = load_audio(path, rate)
        t1 = time.monotonic()
        result = engine.process(samples).sort_by_start_time()
        t2 = time.monotonic()
        segments = [
            {"start": round(s.start, 3), "end": round(s.end, 3), "speaker": s.speaker}
            for s in result
        ]
        talk = defaultdict(float)
        for s in segments:
            talk[s["speaker"]] += s["end"] - s["start"]
        audio_s = len(samples) / rate
        summary = {
            "file": path.name,
            "audio_s": round(audio_s, 1),
            "speakers": len(talk),
            "segments": len(segments),
            "talk_s_by_speaker": {str(k): round(v, 1) for k, v in sorted(talk.items(), key=lambda kv: -kv[1])},
            "decode_s": round(t1 - t0, 1),
            "diarize_s": round(t2 - t1, 1),
            "rtf": round((t2 - t1) / audio_s, 3),
        }
        (args.out / f"{path.stem}.json").write_text(
            json.dumps({"summary": summary, "segments": segments}, indent=1))
        print(json.dumps(summary), flush=True)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
