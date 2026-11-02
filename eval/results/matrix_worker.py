#!/usr/bin/env python3
"""TASK-923 per-language quality matrix driver (measurement pass 2026-11-01).

Drives eval/fleurs_bench.py functions in-process (its CLI language mapping
is config.split("_")[0], which cannot express cmn_hans_cn -> whisper zh).
Cells land as one JSON each under eval/results/cells-2026-11-01/ so a
crashed or restarted run converges: an existing valid cell is skipped.

Subcommands:
  download            fetch + sha-verify every matrix model file
  run --worker N --total K   run all cells for the languages of worker N
  assemble            merge cell files into quality-matrix-2026-11-01.json
"""

import argparse
import json
import subprocess
import sys
import time
from datetime import date, datetime, timezone
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
EVAL = REPO / "eval"
RESULTS = EVAL / "results"
CELLS = RESULTS / "cells-2026-11-01"
CATALOG = REPO / "app/src/main/assets/models_catalog.json"
sys.path.insert(0, str(EVAL))
import fleurs_bench  # noqa: E402

# Tradition set in measurement-priority order (fr/nl first per maintainer).
LANGS = [
    ("fr", "fr_fr"), ("nl", "nl_nl"), ("it", "it_it"), ("de", "de_de"),
    ("es", "es_419"), ("pt", "pt_br"), ("ru", "ru_ru"), ("tr", "tr_tr"),
    ("uk", "uk_ua"), ("pl", "pl_pl"), ("fi", "fi_fi"), ("el", "el_gr"),
    ("ar", "ar_eg"), ("hi", "hi_in"), ("vi", "vi_vn"), ("ko", "ko_kr"),
    ("zh", "cmn_hans_cn"), ("fa", "fa_ir"), ("he", "he_il"), ("en", "en_us"),
]
# FLEURS config -> whisper-style language code (only where prefix differs).
LANG_OVERRIDE = {"cmn_hans_cn": "zh"}
CLIPS = 40
CELL_DATE = "2026-11-01"

# Matrix rows: the models a user can install today. File roles resolved by
# substring from the catalog; sha256 pins ride the catalog untouched.
MODELS = [
    {"id": "parakeet-stock-int8", "catalog_id": "sherpa-onnx", "variant": "stock-int8",
     "kind": "transducer", "model_type": "nemo_transducer", "rank": 0,
     "roles": {"encoder": "encoder", "decoder": "decoder", "joiner": "joiner", "tokens": "tokens"}},
    {"id": "whisper-small-attn", "catalog_id": "whisper", "variant": "small",
     "kind": "whisper", "model_type": "", "rank": 1,
     "roles": {"encoder": "encoder", "decoder": "decoder", "tokens": "tokens"}},
    {"id": "whisper-turbo-attn", "catalog_id": "whisper", "variant": "turbo",
     "kind": "whisper", "model_type": "", "rank": 2,
     "roles": {"encoder": "encoder", "decoder": "decoder", "tokens": "tokens"}},
    {"id": "gigaam-v3", "catalog_id": "gigaam", "variant": "v3",
     "kind": "transducer", "model_type": "nemo_transducer", "rank": 3,
     "roles": {"encoder": "encoder", "decoder": "decoder", "joiner": "joint", "tokens": "tokens"}},
    # TASK-943: the distilled Italian variant, measured on its only language
    # with the -attn re-export (TASK-938/940; the catalog now names those
    # files, the pre-attn exports stay on the mirror unused).
    {"id": "whisper-distil-it-attn", "catalog_id": "whisper", "variant": "distil-large-v3-it",
     "kind": "whisper", "model_type": "", "rank": 4, "langs_only": ["it"],
     "roles": {"encoder": "encoder", "decoder": "decoder", "tokens": "tokens"}},
]

# parakeet declares 25 European languages; the tradition set minus these is unsupported.
PARAKEET_LANGS = {"de", "en", "es", "fr", "it", "nl", "pl", "pt", "ru", "tr", "uk", "el", "fi"}


def model_langs(m):
    if "langs_only" in m:
        return m["langs_only"]
    if m["catalog_id"] == "gigaam":
        return ["ru"]
    if m["catalog_id"] == "sherpa-onnx":
        return [l for l, _ in LANGS if l in PARAKEET_LANGS]
    return [l for l, _ in LANGS]


def catalog_files(m):
    cat = json.loads(CATALOG.read_text())
    entry = next(x for x in cat["models"] if x["id"] == m["catalog_id"])
    variant = next(v for v in entry["variants"] if v["name"] == m["variant"])
    out = {}
    for f in variant["files"]:
        name = f["name"]
        role = next((r for r, pat in m["roles"].items() if pat in name), None)
        if role is None:
            continue
        assert role not in out, f"role clash {role} in {variant['dirName']}"
        out[role] = {
            "url": f"https://huggingface.co/{variant['source']['repo']}/resolve/main/{name}",
            "name": name, "sha256": f.get("sha256"),
        }
    missing = set(m["roles"]) - set(out)
    assert not missing, f"{m['id']}: unresolved roles {missing}"
    return out


def model_dir(m):
    d = fleurs_bench.CACHE_DIR / "models-qa" / m["id"]
    d.mkdir(parents=True, exist_ok=True)
    return d


def cmd_download(_args):
    for m in MODELS:
        d = model_dir(m)
        for role, f in catalog_files(m).items():
            t0 = time.time()
            p = fleurs_bench.http_get(f["url"], d / f["name"], f["sha256"])
            print(f"{m['id']}/{role}: {p} ({time.time() - t0:.0f}s)")


def cell_path(m, lang):
    return CELLS / f"{m['id']}__{lang}.json"


def run_cell(m, lang, config):
    files = {role: str(model_dir(m) / f["name"]) for role, f in catalog_files(m).items()}
    code = LANG_OVERRIDE.get(config, config.split("_")[0])
    rec = fleurs_bench.build_recognizer(m["kind"], files, code, m["model_type"])
    entries = fleurs_bench.get_clips(config, CLIPS)
    cjk = code.startswith(fleurs_bench.CJK_PREFIXES)
    out = fleurs_bench.run(m["id"], rec, entries, "clip", cjk)
    out.update({
        "lang": lang, "config": config, "lang_code": code,
        "clips_requested": CLIPS, "date": date.today().isoformat(),
        "harness_commit": HARNESS_COMMIT, "sherpa_version": SHERPA_VERSION,
        "catalog_files": {r: f["name"] for r, f in catalog_files(m).items()},
    })
    return out


def cmd_run(args):
    my_langs = [l for i, (l, _) in enumerate(LANGS) if i % args.total == args.worker]
    cells = [(lang, m) for lang in my_langs for m in
             sorted(MODELS, key=lambda x: x["rank"]) if lang in model_langs(m)]
    done = err = 0
    for lang, m in cells:
        cp = cell_path(m, lang)
        if cp.is_file():
            done += 1
            continue
        errp = cp.with_suffix(".error.json")
        if errp.is_file():
            if not args.retry_errors:
                err += 1
                continue
            errp.unlink()
        t0 = time.time()
        print(f"[w{args.worker}] {m['id']} {lang} start", flush=True)
        try:
            out = run_cell(m, lang, dict(LANGS)[lang])
            tmp = cp.with_suffix(".tmp")
            tmp.write_text(json.dumps(out, ensure_ascii=False, indent=1))
            tmp.rename(cp)
            print(f"[w{args.worker}] {m['id']} {lang} wer={out.get('wer')} "
                  f"({time.time() - t0:.0f}s)", flush=True)
        except Exception as e:
            errp.write_text(json.dumps({"model": m["id"], "lang": lang,
                                        "error": repr(e)}, ensure_ascii=False))
            print(f"[w{args.worker}] {m['id']} {lang} ERROR {e!r}", flush=True)
            err += 1
    print(f"[w{args.worker}] done: {done} cached, {err} errored", flush=True)


def cmd_assemble(_args):
    cells = {}
    for p in sorted(CELLS.glob("*.json")):
        if p.name.endswith(".error.json") or p.name.endswith(".tmp"):
            continue
        c = json.loads(p.read_text())
        cells.setdefault(c["model"], {})[c["lang"]] = c
    errors = [{"model": p.name.split("__")[0].removesuffix(".error"),
               "lang": p.name.split("__")[1].removesuffix(".error.json"),
               "error": json.loads(p.read_text()).get("error")}
              for p in sorted(CELLS.glob("*.error.json"))]
    meta = {
        "task": "TASK-923 quality matrix, measurement pass",
        "date": CELL_DATE,
        "clips_per_cell": CLIPS,
        "harness_commit": HARNESS_COMMIT,
        "harness_script": "eval/fleurs_bench.py (unmodified; driven in-process)",
        "sherpa_version": SHERPA_VERSION,
        "machine": MACHINE,
        "mode": "clip",
        "metrics_note": "normalized jiwer WER; space-stripped CER for zh; "
                        "comparable within this script only (differs from run_baseline.py)",
        "measured": cells,
        "failed_cells": errors,
        "not_measured": not_measured(cells),
    }
    out = RESULTS / f"quality-matrix-{CELL_DATE}.json"
    tmp = out.with_suffix(".tmp")
    tmp.write_text(json.dumps(meta, ensure_ascii=False, indent=1))
    tmp.rename(out)
    total = sum(len(v) for v in cells.values())
    print(f"assembled {total} measured cells -> {out}")
    for nm in meta["not_measured"]:
        print(f"  NOT MEASURED: {nm['model']} langs={','.join(nm['langs'])}: {nm['reason']}")


def not_measured(cells):
    have = set(cells)
    all_langs = [l for l, _ in LANGS]
    rows = []
    qwen_langs = [l for l in all_langs if "qwen3-asr" not in have or l not in cells["qwen3-asr"]]
    rows.append({"model": "qwen3-asr-0.6b-int8", "langs": qwen_langs,
                 "reason": "harness fleurs_bench.py has no qwen3 kind; sherpa-onnx 2.0.1 "
                           "exposes OfflineRecognizer.from_qwen3_asr but wiring a new backend "
                           "into the harness is out of scope for this measurement pass"})
    nem_langs = [l for l in all_langs if "nemotron-streaming" not in have or l not in cells["nemotron-streaming"]]
    rows.append({"model": "nemotron-streaming-1120ms-int8", "langs": nem_langs,
                 "reason": "streaming backend (catalog runtime: online, OnlineRecognizer); "
                           "the harness decode path drives OfflineRecognizer only, same "
                           "out-of-scope rule as qwen3"})
    para = set(model_langs(next(m for m in MODELS if m["id"] == "parakeet-stock-int8")))
    rows.append({"model": "parakeet-stock-int8", "langs": [l for l in all_langs if l not in para],
                 "reason": "model does not declare the language (catalog languages are 25 "
                           "European configs; hi/fa/he/vi/ko/zh/ar absent)"})
    return rows


def _env_facts():
    global HARNESS_COMMIT, SHERPA_VERSION, MACHINE
    HARNESS_COMMIT = subprocess.run(
        ["git", "-C", str(REPO), "log", "-1", "--format=%H", "--", "eval/fleurs_bench.py"],
        capture_output=True, text=True).stdout.strip()
    import sherpa_onnx
    SHERPA_VERSION = sherpa_onnx.__version__
    MACHINE = (subprocess.run(["sysctl", "-n", "machdep.cpu.brand_string"],
                          capture_output=True, text=True).stdout.strip()
           if sys.platform == "darwin"
           else subprocess.run(["sh", "-c", "grep -m1 'model name' /proc/cpuinfo "
                               "| cut -d: -f2 | xargs"],
                               capture_output=True, text=True).stdout.strip()
           or "unknown")


HARNESS_COMMIT = SHERPA_VERSION = MACHINE = ""
_env_facts()

if __name__ == "__main__":
    ap = argparse.ArgumentParser()
    sub = ap.add_subparsers(dest="cmd", required=True)
    sub.add_parser("download")
    r = sub.add_parser("run")
    r.add_argument("--worker", type=int, required=True)
    r.add_argument("--total", type=int, required=True)
    r.add_argument("--retry-errors", action="store_true")
    sub.add_parser("assemble")
    args = ap.parse_args()
    CELLS.mkdir(parents=True, exist_ok=True)
    {"download": cmd_download, "run": cmd_run, "assemble": cmd_assemble}[args.cmd](args)
