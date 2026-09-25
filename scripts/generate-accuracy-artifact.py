#!/usr/bin/env python3
"""Accuracy-artifact generator (TASK-658 / GH #120).

Reads the single sources table scripts/accuracy-sources.csv and emits
app/src/main/assets/model-accuracy.json, the artifact the app's
PerformanceStatsDialog renders as its Accuracy section. The csv is the
editable input; the asset is generated so the numbers can never drift
between docs, code, and UI without this script regenerating them.

Validation (exit 1 with every problem listed; nothing is written on failure):
  1. exact header model_id,variant,language,metric,value,corpus,date
  2. every row carries all seven fields; value parses as a number
  3. language is a two-letter code, metric WER or CER, date YYYY-MM
  4. model_id is a built-in catalog id or "external" (a missing model or
     language on a row fails loudly, never silently)
  5. built-in variants must be real catalog dirNames; external variants
     must equal the importer-sanitized name of an external-catalog entry
     (the dialog joins external rows by that prefix)
  6. no duplicate (model_id, variant, language, metric) row
"""

import csv
import json
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
SOURCES = ROOT / "scripts" / "accuracy-sources.csv"
ASSET = ROOT / "app/src/main/assets/model-accuracy.json"
BUILTIN_CATALOG = ROOT / "app/src/main/assets/models_catalog.json"
EXTERNAL_CATALOG_DIR = ROOT / "app/src/main/assets/external-catalog"

HEADER = ["model_id", "variant", "language", "metric", "value", "corpus", "date"]
EXTERNAL_MODEL_ID = "external"
METRICS = {"WER", "CER"}


def sanitize_dir_name(name: str) -> str:
    """Mirror of ExternalModelImporter.sanitizeDirName (Kotlin): the dir an
    external-catalog entry lands in is this string plus a random 6-hex
    suffix, and the dialog matches rows by that prefix. If the importer's
    rule ever changes, this mirror and ModelAccuracyTest's tripwire fail."""
    s = re.sub(r"[^A-Za-z0-9._-]", "-", name)
    s = re.sub(r"-+", "-", s)
    s = s.strip("-.")
    return s or "model"


def builtin_dirnames() -> dict[str, set[str]]:
    catalog = json.loads(BUILTIN_CATALOG.read_text(encoding="utf-8"))
    return {
        m["id"]: {v["dirName"] for v in m.get("variants", [])}
        for m in catalog["models"]
    }


def external_variants() -> set[str]:
    out = set()
    for path in sorted(EXTERNAL_CATALOG_DIR.glob("*.json")):
        if path.name.startswith("index"):
            continue
        entry = json.loads(path.read_text(encoding="utf-8"))
        out.add(sanitize_dir_name(entry["name"]))
    return out


def main() -> int:
    problems: list[str] = []
    with SOURCES.open(newline="", encoding="utf-8") as f:
        reader = csv.reader(f)
        try:
            header = next(reader)
        except StopIteration:
            sys.exit(f"FAIL: empty sources file {SOURCES}")
        if header != HEADER:
            sys.exit(f"FAIL: header must be {','.join(HEADER)}, got {','.join(header)}")

        builtin = builtin_dirnames()
        external = external_variants()
        seen: set[tuple[str, str, str, str]] = set()
        rows: list[dict] = []
        for lineno, row in enumerate(reader, start=2):
            if not row or row == [""]:
                continue
            where = f"line {lineno}"
            if len(row) != len(HEADER):
                problems.append(f"{where}: expected {len(HEADER)} fields, got {len(row)}")
                continue
            model_id, variant, language, metric, value, corpus, date = row
            if not model_id:
                problems.append(f"{where}: missing model_id")
            if not variant:
                problems.append(f"{where}: missing variant")
            if not re.fullmatch(r"[a-z]{2}", language):
                problems.append(f"{where}: language must be a two-letter code, got {language!r}")
            if metric not in METRICS:
                problems.append(f"{where}: metric must be WER or CER, got {metric!r}")
            try:
                numeric = float(value)
            except ValueError:
                problems.append(f"{where}: value is not a number: {value!r}")
                numeric = None
            if numeric is not None and numeric < 0:
                problems.append(f"{where}: value must be >= 0, got {value!r}")
            if not corpus:
                problems.append(f"{where}: missing corpus")
            if not re.fullmatch(r"\d{4}-\d{2}", date):
                problems.append(f"{where}: date must be YYYY-MM, got {date!r}")
            if model_id and model_id != EXTERNAL_MODEL_ID:
                if model_id not in builtin:
                    problems.append(
                        f"{where}: unknown model_id {model_id!r} (built-in ids: {sorted(builtin)})")
                elif variant and variant not in builtin[model_id]:
                    problems.append(
                        f"{where}: variant {variant!r} is not a {model_id} catalog dirName")
            if model_id == EXTERNAL_MODEL_ID and variant and variant not in external:
                problems.append(
                    f"{where}: external variant {variant!r} matches no "
                    "external-catalog entry name after sanitization")
            key = (model_id, variant, language, metric)
            if key in seen:
                problems.append(f"{where}: duplicate row {key}")
            seen.add(key)
            rows.append({
                "modelId": model_id,
                "variant": variant,
                "language": language,
                "metric": metric,
                "value": numeric,
                "corpus": corpus,
                "date": date,
            })

    if problems:
        print("FAIL: accuracy-sources.csv is invalid:")
        for p in problems:
            print("  - " + p)
        return 1
    if not rows:
        sys.exit(f"FAIL: no measurement rows in {SOURCES}")

    artifact = {
        "schemaVersion": 1,
        "generatedFrom": "scripts/generate-accuracy-artifact.py",
        "measurements": rows,
    }
    ASSET.write_text(json.dumps(artifact, indent=2) + "\n", encoding="utf-8")
    print(f"wrote {ASSET.relative_to(ROOT)} with {len(rows)} measurements")
    return 0


if __name__ == "__main__":
    sys.exit(main())
