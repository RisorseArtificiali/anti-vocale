#!/usr/bin/env bash
# TASK-853: fetch the prebuilt Cactus Needle engine (libneedle.a + needle.h)
# for the arm ABIs we ship. The engine ships as plain LFS files in the
# Cactus-Compute/needle3 Hugging Face repo; this script downloads them at a
# PINNED commit and verifies every artifact's sha256 (the pinned bytes are
# the ones the TASK-853 device E2E validated: arm64 f862e2d5..., armv7
# 722ff3bf...).
#
# History: the first version drove the cactus-needle pip package
# (`python -m needle.download`, then a download_engine fallback). Upstream
# reshuffled the package's entry points in 3.2.7 (module gone, attribute
# gone, a `needle fetch` CLI pointing at a different artifact line), and an
# unpinned pip install silently broke CI on a fresh runner. The direct
# pinned downloads have no version-skew class: bump NEEDLE3_SHA
# deliberately, and re-run the device E2E when the bytes change.
#
# Mirrors the fetch-sherpa-aar.sh policy: prebuilt binaries are fetched, not
# committed. Upstream ships no android-x86_64 build (the linux-x86_64 one
# links glibc, not bionic), so the x86_64 APK gets the stub bridge and the
# Whistle backend gates itself by ABI at runtime. The fdroid flavor builds
# the stub form for every ABI under -PwhistleEngineDisabled (TASK-854).
set -euo pipefail

NEEDLE3_SHA="3391a1007e7db31d2fe5c69c734bbe93a9c1ce79"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
DEST="$ROOT/app/libs/whistle"
HEADER_DEST="$ROOT/app/src/main/cpp/include"
BASE="https://huggingface.co/Cactus-Compute/needle3/resolve/${NEEDLE3_SHA}"
HEADER_SHA256="a27dac4b3239eead91d124b7f7dbce5495c13019903fd817b68c16d39b055a99"

fetch() { # <repo-path> <expected-sha256> <dest-file>
  local repo_path="$1" expected="$2" out="$3" tmp
  mkdir -p "$(dirname "$out")"
  tmp="$(mktemp "${out}.tmp.XXXXXX")"
  # Atomic install (the fetch-sherpa-aar.sh pattern): a failed download or
  # checksum must not leave poisoned bytes where CMake's existence-only
  # check would later accept them.
  if ! curl -fL --retry 3 -o "$tmp" "${BASE}/${repo_path}" \
     || ! echo "${expected}  ${tmp}" | shasum -a 256 -c -; then
    rm -f "$tmp"
    echo "fetch/verify failed: ${repo_path} (needle3 @ ${NEEDLE3_SHA})" >&2
    return 1
  fi
  # mktemp creates 0600; the old cp-based flow left world-readable files
  # and multi-user pipelines (fetch under one user, build under another)
  # depend on that.
  chmod 0644 "$tmp"
  mv -f "$tmp" "$out"
}

fetch android-arm64/libneedle.a \
  f862e2d58fbf4229c58678c492f73721d64ae4fdee023b91db155f04decae3f1 \
  "$DEST/arm64-v8a/libneedle.a"
fetch android-armv7/libneedle.a \
  722ff3bfbdc6eb55f4b4015a5cb23046da036558977c8cacb67520b512c47974 \
  "$DEST/armeabi-v7a/libneedle.a"
fetch android-arm64/needle.h "$HEADER_SHA256" "$HEADER_DEST/needle.h"

# The repo carries one needle.h per ABI dir. Verifying armv7's copy against
# the SAME pin is the divergence check: if the two dirs ever stop matching,
# this fails instead of silently blessing one of them.
armv7_header="$(mktemp)"
fetch android-armv7/needle.h "$HEADER_SHA256" "$armv7_header"
rm -f "$armv7_header"

echo "Done (needle3 @ ${NEEDLE3_SHA}). Header: $HEADER_DEST/needle.h"
