#!/usr/bin/env python3
"""
Patch existing distil-large-v3-it ONNX encoder with missing tokenizer metadata.

The custom export script (export-distil-italian-onnx.py) used key names that
don't match what the current sherpa-onnx expects (e.g., "no_timestamps_id"
instead of "no_timestamps"). Newer sherpa-onnx versions call exit(-1) when
required keys are missing.

This script uses HARDCODED distil-specific values — it does NOT load the
Whisper model (which would give standard Whisper tokenizer values that are
WRONG for the distil variant). No re-export needed — runs in seconds.

Key insight: sherpa-onnx unconditionally appends the no_timestamps token
(from "no_timestamps" metadata) to the sot_sequence when constructing the
decoder prompt. So sot_sequence must be 3 tokens (sot, lang, task), and
no_timestamps is appended to make 4 tokens total.

Usage:
  # Download int8 from HuggingFace, patch, and upload:
  python3 patch-distil-metadata.py --download --upload

  # Patch an existing int8 encoder:
  python3 patch-distil-metadata.py --encoder ./distil-large-v3-it-encoder.int8.onnx
"""

import argparse
import subprocess
import sys
from pathlib import Path


def install(package: str):
    subprocess.run([sys.executable, "-m", "pip", "install", "-q", package],
                   check=True)


# Hardcoded distil-large-v3-it specific values.
# These differ from standard Whisper tokenizer values!
# Sources: original encoder metadata (commit bd874aa9efc5), reexport-decoder.py
DISTIL_VALUES = {
    # sot_sequence as 3 tokens (no no_timestamps — sherpa-onnx appends it).
    # Original was "50258,50274,50360,50364" (4 tokens incl. no_timestamps).
    # Standard export pattern is 3 tokens + sherpa-onnx appends no_timestamps.
    "sot_sequence": "50258,50274,50360",

    # Token IDs from the distil model's tokenizer (NOT standard Whisper).
    # Standard Whisper: sot=50258, eot=50256, transcribe=50358, translate=50359,
    #   no_timestamps=50363, no_speech=50484
    # Distil-large-v3-it: sot=50258, eot=50257, transcribe=50360, translate=50359,
    #   no_timestamps=50364
    "sot": "50258",
    "eot": "50257",
    "transcribe": "50360",
    "translate": "50359",
    "no_timestamps": "50364",       # original key was "no_timestamps_id"
    "blank_id": "50257",
    "no_speech": "50484",            # standard value, used for silence detection

    # Italian-only model — sherpa-onnx must NOT modify sot_sequence slots.
    # When 0, sherpa-onnx skips all_language_tokens/all_language_codes reads.
    "is_multilingual": "0",
}

# Keys in the original encoder that use WRONG key names for current sherpa-onnx.
# We add the correct key alongside the old one (old key is harmless).
RENAMED_KEYS = {
    "no_timestamps_id": "no_timestamps",  # old key -> new key name
}


def patch_encoder(encoder_path: Path):
    """Patch encoder ONNX file with distil-specific tokenizer metadata."""

    try:
        import onnx
    except ImportError:
        print("Installing onnx...")
        install("onnx")
        import onnx

    print(f"Loading: {encoder_path}")
    onnx_model = onnx.load(str(encoder_path))

    existing = {p.key: p.value for p in onnx_model.metadata_props}
    print(f"  Existing metadata keys ({len(existing)}): {sorted(existing.keys())}")

    # Keys that MUST be overwritten even if present (known-wrong values).
    # sot_sequence in the original export was 4 tokens (incl. no_timestamps),
    # but sherpa-onnx unconditionally appends no_timestamps, creating a 5-token
    # prompt with a duplicate. We need exactly 3 tokens so sherpa-onnx makes 4.
    FORCE_OVERWRITE = {"sot_sequence"}

    # Build the set of keys to add/fix.
    to_add = {}

    for key, value in DISTIL_VALUES.items():
        if key not in existing:
            to_add[key] = value
            print(f"  ADD {key} = {value} (missing)")
        elif key in FORCE_OVERWRITE and existing[key] != value:
            to_add[key] = value
            print(f"  FIX {key}: {existing[key]} -> {value} (overwritten)")
        else:
            print(f"  KEEP {key} = {existing[key]} (already correct)")

    # Handle renamed keys: if old key exists but new key doesn't, add new key
    # with the value from the old key.
    for old_key, new_key in RENAMED_KEYS.items():
        if old_key in existing and new_key not in existing:
            to_add[new_key] = existing[old_key]
            print(f"  RENAME {old_key}={existing[old_key]} -> {new_key}")
        elif old_key in existing and new_key in existing:
            print(f"  SKIP rename {old_key} -> {new_key} (both exist)")

    # Apply additions/fixes
    # For keys in FORCE_OVERWRITE, remove old entries first to avoid duplicates.
    existing_keys_set = {p.key for p in onnx_model.metadata_props}
    new_props = [
        p for p in onnx_model.metadata_props
        if p.key not in to_add
    ]
    for k, v in to_add.items():
        new_props.append(onnx.StringStringEntryProto(key=k, value=str(v)))
    del onnx_model.metadata_props[:]
    onnx_model.metadata_props.extend(new_props)

    onnx.save(onnx_model, str(encoder_path))

    all_meta = {**existing, **to_add}
    print(f"\n  Patched! Total metadata keys: {len(all_meta)}")
    print(f"  Keys added: {sorted(to_add.keys())}")

    # Verify critical values
    critical = ["sot_sequence", "no_timestamps", "transcribe", "is_multilingual"]
    print(f"\n  Verification:")
    for k in critical:
        print(f"    {k} = {all_meta[k]}")

    return encoder_path


def main():
    parser = argparse.ArgumentParser(
        description="Patch distil ONNX encoder with correct metadata")
    parser.add_argument("--encoder",
                        default=None,
                        help="Path to encoder ONNX file (int8 or fp32)")
    parser.add_argument("--download",
                        action="store_true",
                        help="Download int8 encoder from HuggingFace first")
    parser.add_argument("--upload",
                        action="store_true",
                        help="Upload patched file to HuggingFace after patching")
    parser.add_argument("--hf-repo",
                        default="pantinor/sherpa-onnx-whisper-distil-large-v3-it",
                        help="HuggingFace repo name")
    args = parser.parse_args()

    HF_REPO = args.hf_repo
    HF_ENCODER_INT8 = "distil-large-v3-it-encoder.int8.onnx"

    # Determine encoder path
    if args.encoder:
        encoder_path = Path(args.encoder)
    elif args.download:
        encoder_path = Path(f"./{HF_ENCODER_INT8}")
        if not encoder_path.exists():
            print(f"Downloading {HF_ENCODER_INT8} from HuggingFace...")
            subprocess.run([
                "huggingface-cli", "download", HF_REPO, HF_ENCODER_INT8,
                "--local-dir", "."
            ], check=True)
    else:
        print("ERROR: Provide --encoder or --download")
        sys.exit(1)

    if not encoder_path.exists():
        print(f"ERROR: Encoder not found: {encoder_path}")
        sys.exit(1)

    # Patch
    patch_encoder(encoder_path)

    if args.upload:
        print(f"\nUploading to HuggingFace...")
        subprocess.run([
            "huggingface-cli", "upload", HF_REPO,
            encoder_path.name, str(encoder_path)
        ], check=True)
        print(f"  Uploaded {encoder_path.name} to {HF_REPO}")

    size_mb = encoder_path.stat().st_size / (1024 * 1024)
    print(f"\nDone! File: {encoder_path} ({size_mb:.1f} MB)")
    print(f"\nOn phone: delete old distil model, re-download from Model tab")


if __name__ == "__main__":
    main()
