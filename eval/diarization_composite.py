#!/usr/bin/env python3
"""Synthetic multi-speaker composites with exact identity ground truth (TASK-1060.2).

The whole-file reference cannot adjudicate identity on real radio audio (its
own clusters sit 0.07-0.35 apart in titanet space), so the spike measures
against composites built from VoxPopuli Italian, where every utterance carries
a speaker_id. Utterances from different sessions keep natural intra-speaker
variability; each speaker recurs across the whole timeline so every window
boundary is exercised. Writes <name>.wav plus <name>.json ground truth in the
diarization_reference.py format ({"summary": {"audio_s"}, "segments"}).

Usage:
  eval/.venv/bin/python -I eval/diarization_composite.py \
      --parquet eval/diarization-spike/voxpopuli/it-test.parquet \
      --out eval/diarization-spike/composite --name comp6m --speakers 6 --seed 1
"""
from __future__ import annotations

import argparse
import io
import json
import random
from collections import defaultdict
from pathlib import Path

import numpy as np
import pyarrow.parquet as pq
import soundfile as sf

RATE = 16_000


def main() -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--parquet", type=Path, required=True)
    ap.add_argument("--out", type=Path, required=True)
    ap.add_argument("--name", required=True)
    ap.add_argument("--speakers", type=int, default=6)
    ap.add_argument("--gender", choices=["any", "male"], default="any")
    ap.add_argument("--seed", type=int, default=1)
    args = ap.parse_args()
    rng = random.Random(args.seed)

    d = pq.read_table(args.parquet, columns=["speaker_id", "gender", "audio"]).to_pydict()
    utts, gender = defaultdict(list), {}
    for spk, gen, a in zip(d["speaker_id"], d["gender"], d["audio"]):
        if spk in (None, "None"):
            continue
        x, sr = sf.read(io.BytesIO(a["bytes"]), dtype="float32")
        assert sr == RATE, sr
        if x.ndim > 1:
            x = x.mean(axis=1)
        utts[spk].append(x)
        gender[spk] = gen
    pool = [s for s in utts if args.gender == "any" or gender[s] == "male"]
    chosen = sorted(pool, key=lambda s: -sum(len(u) for u in utts[s]))[: args.speakers]
    queues = {s: rng.sample(utts[s], len(utts[s])) for s in chosen}

    # Turns of 1-3 consecutive utterances; the next speaker is drawn weighted
    # by remaining material so every speaker spans the whole timeline.
    pieces, segments, t, last = [], [], 0.0, None
    while any(queues.values()):
        live = [s for s in chosen if queues[s] and s != last] or [s for s in chosen if queues[s]]
        spk = rng.choices(live, weights=[len(queues[s]) for s in live])[0]
        for _ in range(rng.randint(1, 3)):
            if not queues[spk]:
                break
            u = queues[spk].pop()
            gap = np.zeros(int(rng.uniform(0.3, 1.0) * RATE), dtype=np.float32)
            pieces += [gap, u]
            t += len(gap) / RATE
            segments.append({"start": round(t, 3), "end": round(t + len(u) / RATE, 3), "speaker": chosen.index(spk)})
            t += len(u) / RATE
        last = spk

    audio = np.concatenate(pieces)
    args.out.mkdir(parents=True, exist_ok=True)
    sf.write(args.out / f"{args.name}.wav", audio, RATE)
    summary = {"file": f"{args.name}.wav", "audio_s": round(len(audio) / RATE, 1),
               "speakers": len(chosen), "segments": len(segments),
               "speaker_ids": {i: s for i, s in enumerate(chosen)},
               "genders": {i: gender[s] for i, s in enumerate(chosen)}}
    (args.out / f"{args.name}.json").write_text(json.dumps({"summary": summary, "segments": segments}, indent=1))
    print(json.dumps(summary))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
