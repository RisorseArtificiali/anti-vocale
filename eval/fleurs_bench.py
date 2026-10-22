#!/usr/bin/env python3
"""FLEURS bench for catalog candidates (the CI-able eval harness).

Born from the 2026-10-21 tradition-gap campaign (TASK-860/861) where the
whole nl/pl/pt/tr/ko measurement ran on a dev Mac; this generalizes that
ad-hoc bench into one parametric script the CI (model-eval.yml) runs on
GitHub runners, so model probes stop occupying maintainer hardware.

Usage shapes:
  # an entry from our community catalog (single source of truth: files,
  # sha256 pins, family all ride the entry JSON):
  fleurs_bench.py --entry https://raw.githubusercontent.com/.../turkish.json \
      --langs tr_tr --clips 40 --baseline

  # a sherpa model on disk (local probes; whisper decodes with
  # tail_paddings=1000 to mirror the app's decode shape):
  fleurs_bench.py --langs tr_tr --kind whisper \
      --encoder enc.int8.onnx --decoder dec.int8.onnx --tokens tokens.txt

Metrics: normalized WER everywhere; for CJK languages (ko/zh/ja/yue) also
the space-stripped CER, the honest metric when a model does not emit
spacing (the TASK-861 Korean lesson: plain WER on unspaced output reads
as ~99% garbage while the text is nearly correct). The normalization is
jiwer-based and DIFFERS from run_baseline.py's stdlib stack (which
splits elisions and strips bracket tags): numbers from this script are
comparable within this script only.

Segmented mode (--mode segmented) concatenates the clips into one long
stream and chunks it with the field-tested rules the #162 reporter
(furiop) validated in their browser prototype: close a chunk after ~0.5s
of silence once >=6s of SPEECH accumulated (silence does not count toward
the floor), force the 30s cut at the chunk's quietest frame when it falls
in the final second (else a hard cut), discard chunks with no speech.
The segmentation derives from the audio alone (model-independent), so
candidate-vs-baseline inside this mode is apples-to-apples; the absolute
number is NOT comparable to clip mode or to published WERs.

Exit code 0 always unless a hard error: the numbers are the deliverable,
a quality verdict is the caller's decision.
"""

import argparse
import hashlib
import json
import os
import shutil
import sys
import time
import urllib.request
from pathlib import Path

# The CJK set that gets the space-stripped CER (code prefix match).
CJK_PREFIXES = ("ko", "zh", "ja", "yue")

CACHE_DIR = Path(__file__).parent / ".fleurs_cache"

# The whisper-small reference every candidate is quoted against, pinned
# like any catalog file (the yardstick must not drift): hashes computed
# locally from the downloaded artifacts, 2026-10-22.
BASELINE_REPO = "csukuangfj/sherpa-onnx-whisper-small"
BASELINE_FILES = {
    "encoder.int8.onnx": ("small-encoder.int8.onnx",
                          "4cbe7b22fa9026b843b60a68640c747de05bafb1a11b57edc0e66c232d9f33a9"),
    "decoder.int8.onnx": ("small-decoder.int8.onnx",
                          "acad50b5c782696e91b55914cc5ab4f756f1532f76e22aa6fc615f39fb69a8ee"),
    "tokens.txt": ("small-tokens.txt",
                   "b34b360dbb493e781e479794586d661700670d65564001f23024971d1f2fa126"),
}


def sha256_file(p: Path) -> str:
    h = hashlib.sha256()
    with open(p, "rb") as f:
        while chunk := f.read(1 << 20):
            h.update(chunk)
    return h.hexdigest()


def http_get(url: str, dest: Path, sha256: str | None = None) -> Path:
    """Fetch to dest, sha-verified when pinned. Downloads are atomic
    (.part + rename) so an interrupted fetch can never leave a partial
    file the caller mistakes for a good one."""
    if dest.is_file() and (sha256 is None or sha256_file(dest) == sha256):
        return dest
    dest.parent.mkdir(parents=True, exist_ok=True)
    part = dest.with_suffix(dest.suffix + ".part")
    print(f"downloading {url}")
    with urllib.request.urlopen(url) as r, open(part, "wb") as f:
        while chunk := r.read(1 << 20):
            f.write(chunk)
    if sha256 is not None:
        got = sha256_file(part)
        if got != sha256:
            part.unlink(missing_ok=True)
            raise SystemExit(f"sha256 mismatch for {dest.name}: {got} != {sha256}")
    os.replace(part, dest)
    return dest


def _entry_cache_stem(entry: str) -> str:
    """Namespaced per entry: two candidates can never adopt each other's
    files (the 2026-10-22 review's contamination class, where the turkish
    mirror and the baseline share the file names)."""
    return "entry-" + hashlib.sha256(entry.encode()).hexdigest()[:16]


def load_entry(entry: str) -> dict:
    """An entry JSON (URL or path): files download into the owner-
    namespaced cache dir, sha-verified; the entry itself is cached under
    its URL hash so a later entry can never re-measure an earlier one."""
    if entry.startswith("http"):
        ep = http_get(entry, CACHE_DIR / f"{_entry_cache_stem(entry)}.json")
    else:
        ep = Path(entry)
    spec = json.loads(ep.read_text())
    d = CACHE_DIR / "models" / _entry_cache_stem(entry)
    files = {}
    for f in spec["files"]:
        name = f["name"]
        if f["url"].startswith("http"):
            files[name] = str(http_get(f["url"], d / name, f.get("sha256")))
        else:
            files[name] = str(Path(entry).parent / name)
    return {"family": spec["family"], "name": spec.get("name", entry), "files": files}


def resolve_file(files: dict, role: str) -> str:
    """Role resolver: prefer the key carrying both the role and int8 (the
    shipped quantization), then any key containing the role. Raw-path mode
    passes canonical keys ("encoder"/"decoder"/"joiner"/"tokens"/"model")
    which match the role directly. Fails loudly listing what it had."""
    keys = list(files)
    for k in keys:
        if role in k and "int8" in k:
            return files[k]
    for k in keys:
        if role in k:
            return files[k]
    raise SystemExit(f"no {role} file in {keys}")


def build_recognizer(kind: str, files: dict, lang: str):
    import sherpa_onnx
    if kind == "whisper":
        # tail_paddings mirrors the app's decode shape (run_baseline's
        # lesson: measuring a different shape than the shipped one).
        return sherpa_onnx.OfflineRecognizer.from_whisper(
            encoder=resolve_file(files, "encoder"),
            decoder=resolve_file(files, "decoder"),
            tokens=resolve_file(files, "tokens"),
            language=lang, task="transcribe", tail_paddings=1000, num_threads=4)
    if kind == "transducer":
        # NOTE (run_baseline's TASK lesson): NeMo TDT decoders need
        # model_type="nemo_transducer" or the process exits 255; every
        # catalog TRANSDUCER entry today is zipformer, which wants no
        # model_type. Thread it here the day a TDT entry lands.
        return sherpa_onnx.OfflineRecognizer.from_transducer(
            tokens=resolve_file(files, "tokens"),
            encoder=resolve_file(files, "encoder"),
            decoder=resolve_file(files, "decoder"),
            joiner=resolve_file(files, "joiner"),
            num_threads=4)
    if kind == "sensevoice":
        return sherpa_onnx.OfflineRecognizer.from_sense_voice(
            model=resolve_file(files, "model"), tokens=resolve_file(files, "tokens"),
            language=lang, use_itn=True, num_threads=4)
    raise SystemExit(f"unsupported kind: {kind}")


def build_baseline(lang: str):
    """The pinned whisper-small reference (see BASELINE_FILES)."""
    url = f"https://huggingface.co/{BASELINE_REPO}/resolve/main"
    files = {
        canonical: str(http_get(f"{url}/{remote}", CACHE_DIR / "baseline" / canonical, sha))
        for canonical, (remote, sha) in BASELINE_FILES.items()
    }
    return build_recognizer("whisper", files, lang)


def get_clips(config: str, n: int):
    """FLEURS test clips + refs via the parquet endpoint (the datasets-server
    rows API fails on FLEURS: no page index; the parquet route always works).
    The manifest is reused only when its wav files all still exist; the
    parquet is read in batches so a 40-clip run never materializes the
    whole 700-row audio column."""
    import pyarrow.parquet as pq
    workdir = CACHE_DIR / "work" / config
    manifest_path = workdir / "manifest.json"
    if manifest_path.is_file():
        m = json.loads(manifest_path.read_text())
        if len(m) >= n and all(Path(e["wav"]).is_file() for e in m[:n]):
            return m[:n]
    parquet = CACHE_DIR / f"{config}.parquet"
    if not parquet.is_file():
        url = f"https://huggingface.co/api/datasets/google/fleurs/parquet/{config}/test/0.parquet"
        http_get(url, parquet)
    import subprocess
    clips_dir = workdir / "clips"
    clips_dir.mkdir(parents=True, exist_ok=True)
    entries = []
    table = pq.read_table(parquet, columns=["audio", "transcription"])
    for batch in table.to_batches(max_chunksize=64):
        for row in batch.to_pylist():
            if len(entries) >= n:
                break
            wav = clips_dir / f"{config}_{len(entries):02d}.wav"
            if not wav.is_file():
                raw = wav.with_suffix(".raw")
                raw.write_bytes(row["audio"]["bytes"])
                if subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(raw),
                                   "-ar", "16000", "-ac", "1", str(wav)]).returncode != 0:
                    raw.unlink(missing_ok=True)
                    continue
                raw.unlink(missing_ok=True)
            entries.append({"wav": str(wav), "ref": (row.get("transcription") or "").strip()})
        if len(entries) >= n:
            break
    manifest_path.write_text(json.dumps(entries, ensure_ascii=False))
    return entries


def decode(rec, wav: str) -> str:
    import soundfile as sf
    s = rec.create_stream()
    s.accept_waveform(16000, sf.read(wav, dtype="float32")[0])
    rec.decode_stream(s)
    return s.result.text.strip()


def normalize(t: str) -> str:
    import re
    return re.sub(r"[^\w\s]", "", t.lower()).strip()


def nospace(t: str) -> str:
    import re
    return re.sub(r"\s+", "", normalize(t))


def segment(pcm, sr=16000):
    """furiop's field-tested rules (#162): 0.5s of silence closes a chunk
    once >=6s of SPEECH accumulated (silence does not count toward the
    floor); the forced 30s cut lands at the chunk's quietest frame when it
    falls in the final second (else a hard cut at 30s); no-speech chunks
    are discarded. Energy-based, no external VAD. Units: samples
    throughout."""
    import numpy as np
    win = int(0.03 * sr)  # 30ms RMS frames
    rms = np.convolve(pcm ** 2, np.ones(win) / win, mode="same") + 1e-12
    rms = np.sqrt(rms)
    threshold = max(rms.mean() * 0.15, 1e-4)
    min_speech = int(6 * sr)
    max_len = int(30 * sr)
    sil_close = int(0.5 * sr)
    chunks, start, speech_samples, sil_run, in_speech = [], 0, 0, 0, False
    quietest_i, quietest_v = 0, float("inf")
    for i in range(len(pcm)):
        v = rms[i]
        if v < threshold:
            sil_run += 1
            if v < quietest_v:
                quietest_v, quietest_i = v, i
        else:
            sil_run = 0
            speech_samples += 1
            in_speech = True
        if (i - start) >= max_len:
            cut = quietest_i if i - sr < quietest_i <= i else i
            chunks.append((start, cut))
            start, speech_samples, sil_run, in_speech = cut + 1, 0, 0, False
            quietest_i, quietest_v = 0, float("inf")
        elif in_speech and speech_samples >= min_speech and sil_run >= sil_close:
            chunks.append((start, i - sil_run + int(0.1 * sr)))
            start, speech_samples, sil_run, in_speech = i + 1, 0, 0, False
            quietest_i, quietest_v = 0, float("inf")
    if speech_samples > int(0.5 * sr):
        chunks.append((start, len(pcm)))
    return [(a, b) for a, b in chunks if b - a > int(0.2 * sr)]


def run(name, rec, entries, mode, cjk):
    import jiwer, soundfile as sf, numpy as np
    refs, hyps = [], []
    t0 = time.time()
    if mode == "clip":
        for e in entries:
            hyps.append(decode(rec, e["wav"]))
            refs.append(e["ref"])
    else:  # segmented: one long stream, furiop's chunking
        pcm = np.concatenate([sf.read(e["wav"], dtype="float32")[0] for e in entries])
        hyp_parts = []
        for a, b in segment(pcm):
            s = rec.create_stream()
            s.accept_waveform(16000, pcm[a:b])
            rec.decode_stream(s)
            hyp_parts.append(s.result.text.strip())
        hyps = [" ".join(hyp_parts)]
        refs = [" ".join(e["ref"] for e in entries)]
    wall = time.time() - t0
    out = {"model": name, "mode": mode, "clips": len(entries), "wall_s": round(wall, 1)}
    if refs and hyps:
        out["wer"] = round(jiwer.wer([normalize(r) for r in refs],
                                     [normalize(h) for h in hyps]) * 100, 2)
        if cjk:
            out["cer_stripped"] = round(jiwer.cer([nospace(r) for r in refs],
                                                  [nospace(h) for h in hyps]) * 100, 2)
        out["sample"] = hyps[0][:110]
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--entry", help="catalog entry JSON (URL or path)")
    ap.add_argument("--kind", choices=["whisper", "transducer", "sensevoice"],
                    help="model kind when passing raw files")
    for f in ("encoder", "decoder", "joiner", "tokens", "model"):
        ap.add_argument(f"--{f}", help=f"path or URL of the {f} file")
    ap.add_argument("--langs", required=True, help="FLEURS configs, comma separated (tr_tr,ko_kr)")
    ap.add_argument("--clips", type=int, default=40)
    ap.add_argument("--mode", choices=["clip", "segmented"], default="clip")
    ap.add_argument("--baseline", action="store_true", help="also run the whisper-small reference")
    ap.add_argument("--out", help="write results JSON here (also stdout)")
    args = ap.parse_args()

    spec = None
    if args.entry:
        spec = load_entry(args.entry)
        kind = {"WHISPER": "whisper", "TRANSDUCER": "transducer",
                "SENSE_VOICE": "sensevoice"}.get(spec["family"], spec["family"].lower())
        files = spec["files"]
        name = spec["name"]
    elif args.kind:
        kind = args.kind
        files = {k: v for k, v in {
            "encoder": args.encoder, "decoder": args.decoder, "joiner": args.joiner,
            "tokens": args.tokens, "model": args.model}.items() if v}
        name = "model-on-disk"
    else:
        ap.error("one of --entry or --kind is required")

    # One construction site: the recognizer is rebuilt per language on
    # purpose (whisper and sensevoice bake the language at build).
    results = []
    for config in [c.strip() for c in args.langs.split(",") if c.strip()]:
        lang = config.split("_")[0]
        cjk = lang.startswith(CJK_PREFIXES)
        entries = get_clips(config, args.clips)
        results.append(run(name, build_recognizer(kind, files, lang), entries, args.mode, cjk))
        if args.baseline:
            results.append(run("whisper-small baseline", build_baseline(lang), entries, args.mode, cjk))
    print(json.dumps(results, ensure_ascii=False, indent=1))
    if args.out:
        Path(args.out).write_text(json.dumps(results, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
