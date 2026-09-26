#!/usr/bin/env python3
"""Ground-truth ONNX metadata fixtures for the bundled catalog (TASK-413, GH #68).

For every bundled catalog entry whose load path runs the metadata scan
(flags.skipMetadataCheck not set, mirroring SherpaBackend.loadModel), reads the
encoder ONNX of every variant from a local download cache and records the
encoder's actual metadata_props keys into small JSON fixtures under
app/src/test/resources/metadata-fixtures/. MetadataFixturesContractTest then
asserts that SherpaBackend.requiredMetadataKeys(entry) is a SUBSET of the
fixture keys, so the guard can never demand what the artifact does not carry
(the exact condition that shipped qwen3 broken for five releases).

Extraction method (honest description):
  ONNX ModelProto carries metadata_props as repeated StringStringEntryProto
  records (field 14, wire type 2: tag byte 0x72, varint length, then an inner
  message whose field 1, tag 0x0A, is the length-prefixed key). We read the
  LAST 2 MiB of the encoder, the same window as the production scanner
  (SherpaBackend.ONNX_METADATA_SCAN_LIMIT), and collect every byte sequence
  that parses as that record shape with a printable UTF-8 key. The pip `onnx`
  package is NOT assumed; this is a minimal, shape-validated protobuf read of
  the one field we need. Coincidental byte shapes could in theory fake a
  record, but every accepted record must carry consistent varint lengths end
  to end; review the generated diff (a handful of human-readable keys per
  model) when regenerating.

  The 2 MiB window is deliberately the PRODUCTION window, not a larger one:
  if a future export put required keys outside it, the fixture would not see
  them either, and the contract test would fail exactly where production
  would falsely reject the model at load time.

Cache layout: for variant files, both conventions are probed, in order:
  <cache>/<entry-id>__<variant-name>/<file>   (the TASK-661 pin-cache convention)
  <cache>/<variant-dir-name>/<file>           (the catalog dirName convention)
Entries (or variants) missing from the cache are LISTED and skipped with exit
0, so partial caches work; a variant missing anywhere skips the WHOLE entry
(a partial fixture would green-light partial ground truth). Every encoder
found is SHA-256 verified against the catalog pin first: fixtures are only
generated from the exact published artifact. An entry with NO fixture makes
MetadataFixturesContractTest fail with a regeneration hint; that is the
intended tripwire for catalog changes.

Regenerate on every model update (new pins, new variants, new entries):
  scripts/extract-metadata-fixtures.py --cache ~/tmp-pins
  (then commit the regenerated fixtures together with the catalog change)
"""

import argparse
import hashlib
import json
import sys
from pathlib import Path

# Mirrors SherpaBackend.ONNX_METADATA_SCAN_LIMIT (2 MiB).
SCAN_WINDOW_BYTES = 2 * 1024 * 1024

# Protobuf shapes: ModelProto.metadata_props record (field 14, wire type 2)
# and StringStringEntryProto.key (field 1, wire type 2).
METADATA_PROPS_TAG = 0x72
ENTRY_KEY_TAG = 0x0A


def repo_root() -> Path:
    return Path(__file__).resolve().parent.parent


def read_varint(buf: bytes, pos: int):
    """Protobuf varint at pos: (value, next_pos) or (None, pos)."""
    value = 0
    shift = 0
    while pos < len(buf):
        b = buf[pos]
        pos += 1
        value |= (b & 0x7F) << shift
        if not (b & 0x80):
            return value, pos
        shift += 7
        if shift > 63:
            return None, pos
    return None, pos


def extract_metadata_keys(tail: bytes):
    """metadata_props keys from the encoder tail (see module docstring)."""
    keys = set()
    pos = tail.find(METADATA_PROPS_TAG)
    while pos != -1:
        length, after = read_varint(tail, pos + 1)
        if length is not None and 1 <= length <= len(tail) - after:
            record = tail[after:after + length]
            if record and record[0] == ENTRY_KEY_TAG:
                key_len, key_pos = read_varint(record, 1)
                if key_len is not None and 1 <= key_len <= len(record) - key_pos:
                    try:
                        key = record[key_pos:key_pos + key_len].decode("utf-8")
                    except UnicodeDecodeError:
                        key = None
                    if key is not None and key.isprintable():
                        keys.add(key)
        pos = tail.find(METADATA_PROPS_TAG, pos + 1)
    return sorted(keys)


def encoder_file(variant: dict) -> dict:
    """The encoder file record, mirroring SherpaBackend.resolveRoles' first
    contains-match on 'encoder' over the variant's file names."""
    match = next((f for f in variant["files"] if "encoder" in f["name"]), None)
    if match is None:
        names = [f["name"] for f in variant["files"]]
        raise SystemExit(f"variant '{variant['name']}' has no encoder file: {names}")
    return match


def locate(cache: Path, entry_id: str, variant: dict, file_name: str):
    for candidate in (
        cache / f"{entry_id}__{variant['name']}" / file_name,
        cache / variant["dirName"] / file_name,
    ):
        if candidate.is_file() and candidate.stat().st_size > 0:
            return candidate
    return None


def sha256_of(path: Path) -> str:
    digest = hashlib.sha256()
    with open(path, "rb") as fh:
        for chunk in iter(lambda: fh.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument(
        "--cache",
        type=Path,
        default=Path.home() / "tmp-pins",
        help="local model download cache (default: %(default)s)",
    )
    parser.add_argument(
        "--catalog",
        type=Path,
        default=repo_root() / "app/src/main/assets/models_catalog.json",
        help="models_catalog.json (default: %(default)s)",
    )
    parser.add_argument(
        "--out",
        type=Path,
        default=repo_root() / "app/src/test/resources/metadata-fixtures",
        help="fixture output dir (default: %(default)s)",
    )
    args = parser.parse_args()

    root = repo_root()
    catalog_path = args.catalog
    out_dir = args.out

    catalog = json.loads(catalog_path.read_text(encoding="utf-8"))
    # Production filter: SherpaBackend.loadModel scans every entry that does
    # not set skipMetadataCheck (whisper opts out; its exports carry no
    # vocab_size). This includes the non-TRANSDUCER qwen3-asr entry: its
    # load path runs the scan with the explicit empty key list.
    checked = [m for m in catalog["models"] if not m.get("flags", {}).get("skipMetadataCheck")]

    out_dir.mkdir(parents=True, exist_ok=True)
    written, missing = [], []
    for entry in checked:
        entry_id = entry["id"]
        variants_out = {}
        skipped_reason = None
        for variant in entry["variants"]:
            encoder = encoder_file(variant)
            file_name = encoder["name"]
            source = locate(args.cache, entry_id, variant, file_name)
            if source is None:
                skipped_reason = (
                    f"encoder not in cache: {file_name} "
                    f"(looked under {args.cache}/{entry_id}__{variant['name']}/ "
                    f"and {args.cache}/{variant['dirName']}/)"
                )
                break
            pin = encoder.get("sha256")
            actual = sha256_of(source)
            if pin and actual != pin:
                skipped_reason = (
                    f"sha256 mismatch for {source}: catalog pins {pin}, file is {actual}; "
                    "fixtures are only generated from the exact published artifact"
                )
                break
            with open(source, "rb") as fh:
                fh.seek(max(0, source.stat().st_size - SCAN_WINDOW_BYTES))
                tail = fh.read()
            variants_out[variant["name"]] = {
                "encoder": file_name,
                "sha256": actual,
                "keys": extract_metadata_keys(tail),
            }
        if skipped_reason:
            missing.append(f"{entry_id}: {skipped_reason}")
            # Remove any stale fixture so the contract test sees the gap.
            stale = out_dir / f"{entry_id}.json"
            if stale.exists():
                stale.unlink()
                print(f"  removed stale fixture: {stale.name}")
            continue
        fixture = {
            "entryId": entry_id,
            "modelType": entry.get("modelType", ""),
            "generatedBy": "scripts/extract-metadata-fixtures.py",
            "method": (
                "onnx metadata_props keys from the last 2 MiB of each pinned "
                "encoder (the production scan window)"
            ),
            # The production window this extraction used; the contract test
            # asserts it still equals SherpaBackend.ONNX_METADATA_SCAN_LIMIT.
            "scanWindowBytes": SCAN_WINDOW_BYTES,
            "variants": dict(sorted(variants_out.items())),
        }
        target = out_dir / f"{entry_id}.json"
        target.write_text(
            json.dumps(fixture, indent=2, sort_keys=True) + "\n", encoding="utf-8"
        )
        written.append(entry_id)
        key_sets = "; ".join(
            f"{name}={data['keys']}" for name, data in sorted(variants_out.items())
        )
        print(f"wrote {target.relative_to(root)} ({key_sets})")

    print()
    print(f"fixtures written: {len(written)} ({', '.join(written) or '-'})")
    if missing:
        print("MISSING (skipped, exit 0 so partial caches work):")
        for line in missing:
            print(f"  - {line}")
        print(
            "MetadataFixturesContractTest will fail for these entries until the "
            "cache holds their pinned encoders; regenerate after fetching them."
        )
    return 0


if __name__ == "__main__":
    sys.exit(main())
