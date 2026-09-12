#!/usr/bin/env bash
# Fetch the sherpa-onnx AAR into app/libs/.
#
# The AAR is NOT committed to the repo (removed in v1.8.3 to align with the
# F-Droid source-build direction); every environment needs this script once
# before Gradle can resolve the runtime classpath (app/build.gradle.kts:
# implementation(files("libs/sherpa-onnx.aar"))). CI calls it from both the
# Unit tests and Build jobs (.github/workflows/android-release.yml).
#
# THIS SCRIPT IS THE SINGLE SOURCE for the pinned sherpa-onnx version: bump
# SHERPA_ONNX_VERSION here (and the k2-fsa release must carry the .aar asset).

set -euo pipefail

SHERPA_ONNX_VERSION="1.13.8"
# SYNC: when bumping, also update .sherpa-version (repo root) and the
# SRCLIB PIN comment in app/build.gradle.kts (issue #38).

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
TARGET="$PROJECT_DIR/app/libs/sherpa-onnx.aar"

URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${SHERPA_ONNX_VERSION}/sherpa-onnx-${SHERPA_ONNX_VERSION}.aar"

mkdir -p "$PROJECT_DIR/app/libs"

# Skip when an identical AAR is already in place (idempotent for local re-runs).
TMP="$(mktemp)"
echo "Fetching sherpa-onnx v${SHERPA_ONNX_VERSION}..."
curl -fL --retry 3 -o "$TMP" "$URL"

# Sanity: a valid AAR is a zip whose first bytes are the local-file header.
if ! head -c 4 "$TMP" | grep -q "PK"; then
    echo "ERROR: downloaded file is not a zip/AAR (URL: $URL)" >&2
    rm -f "$TMP"
    exit 1
fi

if [ -f "$TARGET" ] && cmp -s "$TMP" "$TARGET"; then
    echo "app/libs/sherpa-onnx.aar already up to date ($(stat -c%s "$TARGET") bytes)"
    rm -f "$TMP"
else
    mv "$TMP" "$TARGET"
    echo "Installed app/libs/sherpa-onnx.aar ($(stat -c%s "$TARGET") bytes)"
fi
