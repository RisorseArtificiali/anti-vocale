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
        # The entry is 2KB and is the manifest of truth: fetched EVERY
        # run, never cached (a cached entry would silently re-measure the
        # previous model after an upstream edit at the same URL). The read
        # stays INSIDE the with: the response closes when the block exits.
        print(f"fetching entry {entry}")
        with urllib.request.urlopen(entry) as r:
            spec = json.load(r)
    else:
        with open(entry) as f:
            spec = json.load(f)
    d = CACHE_DIR / "models" / _entry_cache_stem(entry)
    files = {}
    for fd in spec["files"]:
        if fd["url"].startswith("http"):
            files[fd["name"]] = str(http_get(fd["url"], d / fd["name"], fd.get("sha256")))
        elif entry.startswith("http"):
            raise SystemExit(f"entry {entry} has a relative file url ({fd['url']}): "
                             "entries fetched by URL must carry absolute file urls")
        else:
            files[fd["name"]] = str(Path(entry).parent / fd["url"])
    # modelType and options are routing data the constructor arms need
    # (the CTC subfamily; the canary language pins): carried, not dropped.
    return {"family": spec["family"], "name": spec.get("name", entry), "files": files,
            "model_type": spec.get("modelType") or "", "options": spec.get("options") or {}}


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


def build_recognizer(kind: str, files: dict, lang: str,
                     model_type: str = "", options: dict | None = None):
    """All signatures probed against the pinned sherpa-onnx 1.13.8 wheel
    (inspect.signature, 2026-10-22): the CTC subfamily routes on the
    entry's modelType, and canary reads its language from the entry's
    options. The raw-file mode passes canonical keys."""
    options = options or {}
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
        # NeMo fastconformer/TDT exports (the TranscribePsy class, TASK-855)
        # need model_type="nemo_transducer": sherpa's nemo wrapper handles
        # the (B, feat, time) input layout and the TDT duration heads.
        # Without it sherpa exits 255 on decoder init (no vocab_size
        # metadata: the zipformer path; same lesson as run_baseline). No
        # feature_dim kwarg is needed: the nemo impl PostInit overrides the
        # caller's value from the encoder's feat_dim metadata (verified
        # against the pinned wheel, 2026-10-23 gate round).
        kwargs = {}
        if model_type == "nemo_transducer":
            kwargs["model_type"] = model_type
        return sherpa_onnx.OfflineRecognizer.from_transducer(
            tokens=resolve_file(files, "tokens"),
            encoder=resolve_file(files, "encoder"),
            decoder=resolve_file(files, "decoder"),
            joiner=resolve_file(files, "joiner"),
            num_threads=4, **kwargs)
    if kind == "sensevoice":
        return sherpa_onnx.OfflineRecognizer.from_sense_voice(
            model=resolve_file(files, "model"), tokens=resolve_file(files, "tokens"),
            language=lang, use_itn=True, num_threads=4)
    if kind == "ctc":
        # The subfamily is ENTRY data (modelType), never a filename guess:
        # nemo_ctc (IndicConformer, parakeet-110m), omnilingual_ctc, and
        # the plain zipformer-ctc exports each have their own constructor.
        if model_type == "nemo_ctc":
            return sherpa_onnx.OfflineRecognizer.from_nemo_ctc(
                model=resolve_file(files, "model"), tokens=resolve_file(files, "tokens"),
                num_threads=4)
        if model_type == "omnilingual_ctc":
            return sherpa_onnx.OfflineRecognizer.from_omnilingual_asr_ctc(
                model=resolve_file(files, "model"), tokens=resolve_file(files, "tokens"),
                num_threads=4)
        return sherpa_onnx.OfflineRecognizer.from_zipformer_ctc(
            model=resolve_file(files, "model"), tokens=resolve_file(files, "tokens"),
            num_threads=4)
    if kind == "canary":
        # src/tgt default to "en" in 1.13.8: a non-English canary without
        # its options pin silently measures in English (the wrong-number
        # class this harness exists to prevent).
        canary_lang = options.get("canary.language", "en")
        return sherpa_onnx.OfflineRecognizer.from_nemo_canary(
            encoder=resolve_file(files, "encoder"),
            decoder=resolve_file(files, "decoder"),
            tokens=resolve_file(files, "tokens"),
            src_lang=canary_lang, tgt_lang=canary_lang, num_threads=4)
    if kind == "moonshine":
        # The catalog's moonshine exports are the 3-file v2 shape
        # (encoder/decoder/tokens): from_moonshine_v2, not the v1
        # 5-file constructor.
        return sherpa_onnx.OfflineRecognizer.from_moonshine_v2(
            encoder=resolve_file(files, "encoder"),
            decoder=resolve_file(files, "decoder"),
            tokens=resolve_file(files, "tokens"), num_threads=4)
    if kind == "dolphin":
        return sherpa_onnx.OfflineRecognizer.from_dolphin_ctc(
            model=resolve_file(files, "model"), tokens=resolve_file(files, "tokens"),
            num_threads=4)
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
    parquet streams in batches (iter_batches) so a 40-clip run never
    materializes the whole 700-row audio column."""
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
    import soundfile as sf
    sys.path.insert(0, str(Path(__file__).parent))
    from audio_loader import load_audio  # the ONE shared loader (TASK-461)
    clips_dir = workdir / "clips"
    clips_dir.mkdir(parents=True, exist_ok=True)
    entries = []
    pf = pq.ParquetFile(parquet)
    for batch in pf.iter_batches(batch_size=64, columns=["audio", "transcription"]):
        for row in batch.to_pylist():
            if len(entries) >= n:
                break
            wav = clips_dir / f"{config}_{len(entries):02d}.wav"
            if not wav.is_file():
                # decode through the shared ffmpeg-pipe loader, persist the
                # 16k wav for manifest reuse; a decode failure skips the row
                # (a later row pulls in with its own ref: pairing holds).
                raw = wav.with_suffix(".raw")
                raw.write_bytes(row["audio"]["bytes"])
                try:
                    pcm = load_audio(str(raw), sample_rate=16000)
                    sf.write(str(wav), pcm, 16000)
                except Exception:
                    continue
                finally:
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
    are discarded. Energy-based, no external VAD. Evaluated per 30ms
    FRAME (every decision input is frame-granular), units: frames unless
    suffixed _smp."""
    import numpy as np
    hop = int(0.03 * sr)
    n_frames = len(pcm) // hop
    if n_frames == 0:
        return []
    framed = pcm[: n_frames * hop].reshape(n_frames, hop)
    rms = np.sqrt((framed ** 2).mean(axis=1) + 1e-12)
    threshold = max(rms.mean() * 0.15, 1e-4)
    min_speech_f = int(6 / 0.03)
    max_len_f = int(30 / 0.03)
    sil_close_f = int(0.5 / 0.03)
    chunks_f, start_f, speech_f, sil_f, in_speech = [], 0, 0, 0, False
    quietest_f, quietest_v = 0, float("inf")
    for i in range(n_frames):
        v = rms[i]
        if v < threshold:
            sil_f += 1
            if v < quietest_v:
                quietest_v, quietest_f = v, i
        else:
            sil_f = 0
            speech_f += 1
            in_speech = True
        if (i - start_f) >= max_len_f:
            cut = quietest_f if i - int(1 / 0.03) < quietest_f <= i else i
            chunks_f.append((start_f, cut))
            start_f, speech_f, sil_f, in_speech = cut + 1, 0, 0, False
            quietest_f, quietest_v = 0, float("inf")
        elif in_speech and speech_f >= min_speech_f and sil_f >= sil_close_f:
            chunks_f.append((start_f, i - sil_f + int(0.1 / 0.03)))
            start_f, speech_f, sil_f, in_speech = i + 1, 0, 0, False
            quietest_f, quietest_v = 0, float("inf")
    if speech_f > int(0.5 / 0.03):
        chunks_f.append((start_f, n_frames))
    min_len_f = int(0.2 / 0.03)
    return [((a) * hop, min((b + 1) * hop, len(pcm)))
            for a, b in chunks_f if (b - a) > min_len_f]


def run(name, rec, entries, mode, cjk):
    import jiwer, soundfile as sf, numpy as np
    refs, hyps = [], []
    audio_s = 0.0
    t0 = time.time()
    if mode == "clip":
        for e in entries:
            hyps.append(decode(rec, e["wav"]))
            refs.append(e["ref"])
            audio_s += sf.info(e["wav"]).duration
    else:  # segmented: one long stream, furiop's chunking
        pcm = np.concatenate([sf.read(e["wav"], dtype="float32")[0] for e in entries])
        audio_s = len(pcm) / 16000
        hyp_parts = []
        for a, b in segment(pcm):
            s = rec.create_stream()
            s.accept_waveform(16000, pcm[a:b])
            rec.decode_stream(s)
            hyp_parts.append(s.result.text.strip())
        hyps = [" ".join(hyp_parts)]
        refs = [" ".join(e["ref"] for e in entries)]
    wall = time.time() - t0
    out = {"model": name, "mode": mode, "clips": len(entries),
           "audio_s": round(audio_s, 1), "wall_s": round(wall, 1)}
    if audio_s > 0:
        out["rtf"] = round(wall / audio_s, 3)
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
    ap.add_argument("--kind", choices=["whisper", "transducer", "sensevoice", "ctc",
                    "canary", "moonshine", "dolphin"],
                    help="model kind when passing raw files")
    for f in ("encoder", "decoder", "joiner", "tokens", "model"):
        ap.add_argument(f"--{f}", help=f"path or URL of the {f} file")
    ap.add_argument("--langs", required=True, help="FLEURS configs, comma separated (tr_tr,ko_kr)")
    ap.add_argument("--clips", type=int, default=40)
    ap.add_argument("--mode", choices=["clip", "segmented"], default="clip")
    ap.add_argument("--baseline", action="store_true", help="also run the whisper-small reference")
    ap.add_argument("--out", help="write results JSON here (also stdout)")
    args = ap.parse_args()
    if args.clips <= 0:
        ap.error("--clips must be positive")

    spec = None
    if args.entry:
        spec = load_entry(args.entry)
        kind = {"WHISPER": "whisper", "TRANSDUCER": "transducer",
                "SENSE_VOICE": "sensevoice", "CTC": "ctc", "CANARY": "canary",
                "MOONSHINE": "moonshine", "DOLPHIN": "dolphin",
                }.get(spec["family"], spec["family"].lower())
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
        results.append(run(name, build_recognizer(kind, files, lang,
                                                  spec.get("model_type", "") if spec else "",
                                                  spec.get("options") if spec else None),
                           entries, args.mode, cjk))
        if args.baseline and lang == "yue":
            # The pinned whisper-small predates Cantonese: no <|yue|> token.
            # Skip the reference for yue rather than fail the run after the
            # candidate already decoded.
            print("note: no whisper-small baseline for yue (not in its vocab)")
        elif args.baseline:
            results.append(run("whisper-small baseline", build_baseline(lang), entries, args.mode, cjk))
    print(json.dumps(results, ensure_ascii=False, indent=1))
    if args.out:
        Path(args.out).write_text(json.dumps(results, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    main()
