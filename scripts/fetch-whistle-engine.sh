#!/usr/bin/env bash
# TASK-853: fetch the prebuilt Cactus Needle engine (libneedle.a + needle.h)
# for the arm ABIs we ship. The engine is Apache-2.0 (Cactus-Compute/needle3
# on Hugging Face); the x86_64 APK gets no engine (upstream ships no
# android-x86_64 build; the linux-x86_64 one links glibc, not bionic), so
# the Whistle backend must gate itself by ABI at runtime.
#
# Mirrors the fetch-sherpa-aar.sh policy: prebuilt binaries are fetched, not
# committed. The version pin lives below; bump NEEDLE_VERSION together with
# the whistle.cact catalog pin (the engine reads the .cact container
# version-tolerantly today, but a pinned pair is the tested shape).
set -euo pipefail

NEEDLE_VERSION="3.2.6"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/libs/whistle"
HEADER_DEST="$ROOT/app/src/main/cpp/include"

command -v python3 >/dev/null || { echo "python3 required (pip install cactus-needle provides the downloader)"; exit 1; }
PYTHON="${PYTHON:-python3}"

for ABI in arm64-v8a armeabi-v7a; do
  TARGET_DIR="$DEST/$ABI"
  mkdir -p "$TARGET_DIR"
  UPSTREAM_ABI="${ABI/arm64-v8a/android-arm64}"
  UPSTREAM_ABI="${UPSTREAM_ABI/armeabi-v7a/android-armv7}"
  TMP_DIR="$(mktemp -d)"
  (cd "$TMP_DIR" && "$PYTHON" -m needle.download "$UPSTREAM_ABI" >/dev/null 2>&1) || {
    # The module entry point varies by package version; fall back to the CLI.
    (cd "$TMP_DIR" && "$PYTHON" -c "import needle, os; needle.download_engine('$UPSTREAM_ABI')" >/dev/null 2>&1)
  }
  FOUND="$(find "$TMP_DIR" -name libneedle.a | head -1)"
  [ -n "$FOUND" ] || { echo "libneedle.a not found for $UPSTREAM_ABI"; exit 1; }
  cp "$FOUND" "$TARGET_DIR/libneedle.a"
  HEADER="$(find "$TMP_DIR" -name needle.h | head -1)"
  [ -n "$HEADER" ] && mkdir -p "$HEADER_DEST" && cp "$HEADER" "$HEADER_DEST/needle.h"
  rm -rf "$TMP_DIR"
  echo "$ABI: $(wc -c < "$TARGET_DIR/libneedle.a") bytes (needle $NEEDLE_VERSION)"
done
echo "Done. Header: $HEADER_DEST/needle.h"
