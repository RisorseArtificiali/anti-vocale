#!/usr/bin/env python3
"""Windowed diarization + global speaker reconcile prototype (TASK-1060.2).

Two stages, so the expensive part runs once per window size and the cheap
reconcile sweep reruns freely:

  run    window the audio, run the app-config diarizer per window, embed every
         local cluster (mean of normalized titanet embeddings over its longest
         segments) and cache segments + cluster centroids to JSON.
  score  reconcile the cached window clusters globally (average-linkage
         agglomerative clustering on cosine distance, same-window cannot-link,
         clusters under the talk floor left unassigned) and compare against the
         whole-file reference from diarization_reference.py: many-to-one
         identity agreement, coverage, SPLIT and MERGE counts.

Usage:
  eval/.venv/bin/python -I eval/diarization_windowed.py run \
      --models eval/diarization-spike/models --window-min 10 --overlap-s 30 \
      --out eval/diarization-spike/windowed eval/diarization-spike/audio/X.mp3
  eval/.venv/bin/python -I eval/diarization_windowed.py score \
      --reference eval/diarization-spike/reference \
      --windowed eval/diarization-spike/windowed --thresholds 0.3,0.4,0.5
  eval/.venv/bin/python -I eval/diarization_windowed.py score-pieces \
      --reference eval/diarization-spike/gt --pieces eval/diarization-spike/comp-pieces-clip \
      --thresholds 0.8 --talk-floor 30
      (the app's absorption; --absorb-max-distance 2.0 is the ungated original,
      --absorb-floor-fraction 1 --absorb-floor-min 30 the absolute 30 s floor)

score-pieces defaults mirror WindowedDiarizationParams: the gated absorption
and the relative floor min(cap, max(min, fraction x talk)). TASK-1060.7 moved
its --talk-floor default (now the floor's cap) from 10 to 30, so a run that
relied on the old default must pass --talk-floor 10.
"""
from __future__ import annotations

import argparse
import json
import sys
import time
from collections import defaultdict
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).resolve().parent))
from audio_loader import load_audio  # noqa: E402
from diarization_reference import AUTO_THRESHOLD, build_engine  # noqa: E402

FRAME_S = 0.1
REAL_SPEAKER_FLOOR_S = 30.0  # reference clusters below this are the micro tail (TASK-1061)
SHARE_FLOOR = 0.10           # a counterpart counts for SPLIT/MERGE above this share...
SHARE_MIN_S = 30.0           # ...and this absolute overlap


# ---------------------------------------------------------------- run stage

def cluster_centroid(extractor, samples, rate, segs, max_segs=20, max_seg_s=10.0):
    usable = sorted((s for s in segs if s[1] - s[0] >= 1.0), key=lambda s: s[0] - s[1])[:max_segs]
    vecs = []
    for start, end in usable:
        end = min(end, start + max_seg_s)
        stream = extractor.create_stream()
        stream.accept_waveform(rate, samples[int(start * rate):int(end * rate)])
        stream.input_finished()
        if not extractor.is_ready(stream):
            continue
        v = np.asarray(extractor.compute(stream), dtype=np.float32)
        vecs.append(v / (np.linalg.norm(v) + 1e-9))
    if not vecs:
        return None
    c = np.mean(vecs, axis=0)
    return (c / (np.linalg.norm(c) + 1e-9)).tolist()


def run(args) -> int:
    import sherpa_onnx
    args.out.mkdir(parents=True, exist_ok=True)
    engine = build_engine(args.models, args.threads, AUTO_THRESHOLD)
    extractor = sherpa_onnx.SpeakerEmbeddingExtractor(sherpa_onnx.SpeakerEmbeddingExtractorConfig(
        model=str(args.models / "titanet-small.onnx"), num_threads=args.threads))
    rate = engine.sample_rate
    win = int(args.window_min * 60 * rate)
    ov = int(args.overlap_s * rate)
    for path in args.audio:
        samples = load_audio(path, rate)
        t0 = time.monotonic()
        windows = []
        start = 0
        while start < len(samples):
            end = min(len(samples), start + win)
            chunk = samples[start:end]
            off = start / rate
            # Core region: the part this window owns (overlap halves go to neighbours).
            core_lo = off + (args.overlap_s / 2 if start > 0 else 0)
            core_hi = end / rate - (args.overlap_s / 2 if end < len(samples) else 0)
            segs = [(s.start, s.end, s.speaker) for s in engine.process(chunk).sort_by_start_time()]
            by_local = defaultdict(list)
            for s0, s1, spk in segs:
                by_local[spk].append((s0, s1))
            clusters = {}
            for spk, ss in by_local.items():
                clusters[str(spk)] = {
                    "talk_s": round(sum(b - a for a, b in ss), 2),
                    "centroid": cluster_centroid(extractor, chunk, rate, ss),
                }
            windows.append({
                "offset_s": off, "core_lo": core_lo, "core_hi": core_hi,
                "segments": [{"start": round(off + a, 3), "end": round(off + b, 3), "local": str(spk)}
                             for a, b, spk in segs],
                "clusters": clusters,
            })
            if end == len(samples):
                break
            start = end - ov
        wall = time.monotonic() - t0
        audio_s = len(samples) / rate
        out = {"file": path.name, "audio_s": audio_s, "window_min": args.window_min,
               "overlap_s": args.overlap_s, "wall_s": round(wall, 1),
               "rtf": round(wall / audio_s, 3), "windows": windows}
        name = f"{path.stem}.w{args.window_min:g}.json"
        (args.out / name).write_text(json.dumps(out))
        print(json.dumps({"file": path.name, "window_min": args.window_min, "windows": len(windows),
                          "wall_s": out["wall_s"], "rtf": out["rtf"]}), flush=True)
    return 0


# ------------------------------------------- pieces stage (global clustering)

def embed_pieces(args) -> int:
    """Windows provide segmentation only: every core-region segment is cut into
    pieces of at most --piece-s seconds (a leftover under 1s joins the previous
    piece) and each piece is embedded, for clustering over the whole file."""
    import sherpa_onnx
    extractor = sherpa_onnx.SpeakerEmbeddingExtractor(sherpa_onnx.SpeakerEmbeddingExtractorConfig(
        model=str(args.models / "titanet-small.onnx"), num_threads=args.threads))
    rate = 16_000
    for wpath in args.windowed_files:
        win = json.loads(wpath.read_text())
        samples = load_audio(args.audio_dir / win["file"], rate)
        t0 = time.monotonic()
        pieces = []
        for w in win["windows"]:
            for s in w["segments"]:
                if args.clip_core:
                    # Clip to the core so a turn crossing the overlap is covered once.
                    s = {"start": max(s["start"], w["core_lo"]), "end": min(s["end"], w["core_hi"])}
                    if s["end"] <= s["start"]:
                        continue
                else:
                    mid = (s["start"] + s["end"]) / 2
                    if not (w["core_lo"] <= mid < w["core_hi"]):
                        continue
                cuts = list(np.arange(s["start"], s["end"], args.piece_s)) + [s["end"]]
                if len(cuts) > 2 and cuts[-1] - cuts[-2] < 1.0:
                    del cuts[-2]
                for a, b in zip(cuts, cuts[1:]):
                    if b - a < 0.5:
                        continue
                    stream = extractor.create_stream()
                    stream.accept_waveform(rate, samples[int(a * rate):int(b * rate)])
                    stream.input_finished()
                    if not extractor.is_ready(stream):
                        continue
                    v = np.asarray(extractor.compute(stream), dtype=np.float32)
                    pieces.append({"start": round(float(a), 3), "end": round(float(b), 3),
                                   "emb": (v / (np.linalg.norm(v) + 1e-9)).round(5).tolist()})
        out = {"file": win["file"], "audio_s": win["audio_s"], "window_min": win["window_min"],
               "segment_wall_s": win["wall_s"], "embed_wall_s": round(time.monotonic() - t0, 1),
               "piece_s": args.piece_s, "pieces": pieces}
        name = wpath.name.replace(".json", f".p{args.piece_s:g}{'.clip' if args.clip_core else ''}.pieces.json")
        (args.out / name).write_text(json.dumps(out))
        print(json.dumps({"file": win["file"], "window_min": win["window_min"], "pieces": len(pieces),
                          "embed_wall_s": out["embed_wall_s"]}), flush=True)
    return 0


# Mirrors of WindowedDiarizationParams (PieceClustering.kt) defaults.
ABSORB_FLOOR_S = 30.0
ABSORB_FLOOR_FRACTION = 0.02
ABSORB_FLOOR_MIN_S = 10.0
ABSORB_MAX_DISTANCE = 0.45


def absorb_floor(talk_s, cap_s, fraction, min_s):
    """WindowedDiarizationParams.absorbFloorFor: the fraction of total talk,
    no lower than min_s and no higher than cap_s."""
    return min(cap_s, max(min_s, fraction * talk_s))


def absorb_small(labels, durations, emb, floor_s, max_distance=ABSORB_MAX_DISTANCE):
    """TASK-1061 absorption with the TASK-1060.7 gate (PieceClustering.absorbSmall):
    a cluster under floor_s talk joins the nearest large cluster centroid when
    its own normalized centroid lies within max_distance cosine distance,
    else its pieces become -1 (unassigned). max_distance 2.0 is the ungated
    original. Returns (labels, absorbed, dropped) cluster counts."""
    labels = np.asarray(labels)
    talk = defaultdict(float)
    for l, d in zip(labels, durations):
        talk[int(l)] += d
    big = [c for c, t in talk.items() if t >= floor_s]
    if not big:
        return labels, 0, 0
    cent = np.stack([emb[labels == c].sum(axis=0) for c in big])
    cent /= np.linalg.norm(cent, axis=1, keepdims=True) + 1e-9
    out = labels.copy()
    absorbed = dropped = 0
    for c, t in talk.items():
        if t < floor_s:
            idx = labels == c
            own = emb[idx].sum(axis=0)
            own /= np.linalg.norm(own) + 1e-9
            sims = cent @ own
            nearest = int(np.argmax(sims))
            if 1.0 - sims[nearest] <= max_distance:
                out[idx] = big[nearest]
                absorbed += 1
            else:
                out[idx] = -1
                dropped += 1
    return out, absorbed, dropped


def score_pieces(args) -> int:
    import sherpa_onnx
    for ppath in sorted(args.pieces.glob("*.pieces.json")):
        pc = json.loads(ppath.read_text())
        stem = Path(pc["file"]).stem
        ref = json.loads((args.reference / f"{stem}.json").read_text())
        emb = np.asarray([p["emb"] for p in pc["pieces"]], dtype=np.float32)
        dur = [p["end"] - p["start"] for p in pc["pieces"]]
        for t in [float(x) for x in args.thresholds.split(",")]:
            t0 = time.monotonic()
            labels = sherpa_onnx.FastClustering(
                sherpa_onnx.FastClusteringConfig(num_clusters=-1, threshold=t))(emb)
            cluster_s = time.monotonic() - t0
            for absorb in (False, True):
                floor = absorb_floor(sum(dur), args.talk_floor, args.absorb_floor_fraction,
                                     args.absorb_floor_min) if absorb else 0.0
                lab, absorbed, dropped = (absorb_small(labels, dur, emb, floor, args.absorb_max_distance)
                                          if absorb else (np.asarray(labels), 0, 0))
                hyp = [(p["start"], p["end"], int(l)) for p, l in zip(pc["pieces"], lab)]
                r = compare(ref_segments(ref), hyp, ref["summary"]["audio_s"])
                print(json.dumps({"file": stem, "window_min": pc["window_min"], "piece_s": pc["piece_s"],
                                  "threshold": t, "absorb_floor": round(floor, 2),
                                  "absorb_max_distance": args.absorb_max_distance if absorb else None,
                                  "absorbed": absorbed, "dropped": dropped,
                                  "cluster_s": round(cluster_s, 2), "pieces": len(dur), **r}), flush=True)
    return 0


# -------------------------------------------------------------- score stage

def reconcile(windows, threshold, talk_floor):
    """Average-linkage agglomerative clustering of window clusters by cosine
    distance; clusters from the same window never merge (cannot-link)."""
    items = []  # (window_idx, local, centroid)
    for wi, w in enumerate(windows):
        for local, c in w["clusters"].items():
            if c["centroid"] is not None and c["talk_s"] >= talk_floor:
                items.append((wi, local, np.asarray(c["centroid"], dtype=np.float32)))
    n = len(items)
    if n == 0:
        return {}
    emb = np.stack([it[2] for it in items])
    dist = 1.0 - emb @ emb.T
    groups = [[i] for i in range(n)]
    gwin = [{items[i][0]} for i in range(n)]
    while True:
        best = None
        for a in range(len(groups)):
            for b in range(a + 1, len(groups)):
                if gwin[a] & gwin[b]:
                    continue
                d = float(dist[np.ix_(groups[a], groups[b])].mean())
                if d < threshold and (best is None or d < best[0]):
                    best = (d, a, b)
        if best is None:
            break
        _, a, b = best
        groups[a] += groups[b]
        gwin[a] |= gwin[b]
        del groups[b], gwin[b]
    assign = {}
    for gid, g in enumerate(groups):
        for i in g:
            assign[(items[i][0], items[i][1])] = gid
    return assign


def frames_from(segments, n_frames):
    """Frame label array: -1 silence, -2 overlap (excluded), else speaker id."""
    lab = np.full(n_frames, -1, dtype=np.int64)
    for start, end, spk in segments:
        lo, hi = int(start / FRAME_S), min(n_frames, int(end / FRAME_S))
        region = lab[lo:hi]
        clash = (region != -1) & (region != spk)
        region[(region == -1)] = spk
        region[clash] = -2
    return lab


def compare(ref_segments, hyp_segments, audio_s):
    """Many-to-one identity agreement, coverage, SPLIT and MERGE counts of
    hypothesis segments against reference segments (both (start, end, id);
    hypothesis ids < 0 mean unassigned)."""
    n_frames = int(audio_s / FRAME_S) + 1
    talk = defaultdict(float)
    for start, end, spk in ref_segments:
        talk[spk] += end - start
    real = {k for k, v in talk.items() if v >= REAL_SPEAKER_FLOOR_S}
    ref_lab = frames_from(ref_segments, n_frames)
    pro_lab = frames_from(hyp_segments, n_frames)

    ref_real = np.isin(ref_lab, list(real))
    both = ref_real & (pro_lab >= 0)
    coverage = both.sum() / max(1, ref_real.sum())

    pairs = defaultdict(int)
    for r, p in zip(ref_lab[both], pro_lab[both]):
        pairs[(int(r), int(p))] += 1
    by_gid, by_ref = defaultdict(dict), defaultdict(dict)
    for (r, p), c in pairs.items():
        by_gid[p][r] = c
        by_ref[r][p] = c
    correct = sum(max(d.values()) for d in by_gid.values())
    agreement = correct / max(1, both.sum())

    def counterparts(d):
        tot = sum(d.values())
        return [k for k, c in d.items() if c / tot >= SHARE_FLOOR and c * FRAME_S >= SHARE_MIN_S]

    merges = sum(1 for d in by_gid.values() if len(counterparts(d)) >= 2)
    splits = sum(len(counterparts(d)) - 1 for d in by_ref.values() if len(counterparts(d)) >= 2)
    n_hyp = len({p for p in pro_lab[pro_lab >= 0].tolist()})
    return {"agreement": round(agreement, 4), "coverage": round(float(coverage), 4),
            "merges": merges, "splits": splits, "hyp_ids": n_hyp, "ref_real": len(real)}


def windowed_segments(win, threshold, talk_floor):
    assign = reconcile(win["windows"], threshold, talk_floor)
    out = []
    for wi, w in enumerate(win["windows"]):
        for s in w["segments"]:
            mid = (s["start"] + s["end"]) / 2
            if w["core_lo"] <= mid < w["core_hi"]:
                out.append((s["start"], s["end"], assign.get((wi, s["local"]), -3)))
    return out


def ref_segments(ref):
    return [(s["start"], s["end"], s["speaker"]) for s in ref["segments"]]


def score(args) -> int:
    thresholds = [float(t) for t in args.thresholds.split(",")]
    for wpath in sorted(args.windowed.glob("*.w*.json")):
        win = json.loads(wpath.read_text())
        stem = wpath.name.split(".w")[0]
        ref = json.loads((args.reference / f"{stem}.json").read_text())
        for t in thresholds:
            r = compare(ref_segments(ref), windowed_segments(win, t, args.talk_floor),
                        ref["summary"]["audio_s"])
            print(json.dumps({"file": stem, "window_min": win["window_min"], "threshold": t,
                              "wall_s": win["wall_s"], **r}), flush=True)
    if args.whole:
        for wpath in sorted(args.whole.glob("*.json")):
            ref_path = args.reference / wpath.name
            if not ref_path.exists():
                continue
            ref, hyp = json.loads(ref_path.read_text()), json.loads(wpath.read_text())
            r = compare(ref_segments(ref), ref_segments(hyp), ref["summary"]["audio_s"])
            print(json.dumps({"file": wpath.stem, "arm": "whole-file", **r}), flush=True)
    return 0


def main() -> int:
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    r = sub.add_parser("run")
    r.add_argument("--models", type=Path, required=True)
    r.add_argument("--out", type=Path, required=True)
    r.add_argument("--window-min", type=float, default=10.0)
    r.add_argument("--overlap-s", type=float, default=30.0)
    r.add_argument("--threads", type=int, default=4)
    r.add_argument("audio", type=Path, nargs="+")
    s = sub.add_parser("score")
    s.add_argument("--reference", type=Path, required=True)
    s.add_argument("--windowed", type=Path, required=True)
    s.add_argument("--thresholds", default="0.3,0.4,0.5,0.6")
    s.add_argument("--talk-floor", type=float, default=10.0)
    s.add_argument("--whole", type=Path, help="also score whole-file outputs in this dir")
    e = sub.add_parser("embed")
    e.add_argument("--models", type=Path, required=True)
    e.add_argument("--audio-dir", type=Path, required=True)
    e.add_argument("--out", type=Path, required=True)
    e.add_argument("--piece-s", type=float, default=3.0)
    e.add_argument("--clip-core", action="store_true", help="clip segments to the window core instead of midpoint ownership")
    e.add_argument("--threads", type=int, default=4)
    e.add_argument("windowed_files", type=Path, nargs="+")
    sp = sub.add_parser("score-pieces")
    sp.add_argument("--reference", type=Path, required=True)
    sp.add_argument("--pieces", type=Path, required=True)
    sp.add_argument("--thresholds", default="0.6,0.7,0.8,0.9")
    sp.add_argument("--talk-floor", type=float, default=ABSORB_FLOOR_S, help="the absorption floor's cap")
    sp.add_argument("--absorb-floor-fraction", type=float, default=ABSORB_FLOOR_FRACTION)
    sp.add_argument("--absorb-floor-min", type=float, default=ABSORB_FLOOR_MIN_S)
    sp.add_argument("--absorb-max-distance", type=float, default=ABSORB_MAX_DISTANCE,
                    help="2.0 reproduces the ungated TASK-1061 absorption")
    args = ap.parse_args()
    return {"run": run, "score": score, "embed": embed_pieces, "score-pieces": score_pieces}[args.cmd](args)


if __name__ == "__main__":
    raise SystemExit(main())
