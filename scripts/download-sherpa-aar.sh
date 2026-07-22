#!/usr/bin/env bash
# Fetch the prebuilt sherpa-onnx AAR into app/libs/ for local development builds.
#
# The AAR is NOT committed (it's a build artifact). F-Droid builds it from source
# via the recipe's build: block; for local dev we fetch the upstream release AAR.
#
# Usage: ./scripts/download-sherpa-aar.sh
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_LIBS="$(cd "$SCRIPT_DIR/../app/libs" 2>/dev/null && pwd || true)"
PROJECT_LIBS="$SCRIPT_DIR/../app/libs"

mkdir -p "$PROJECT_LIBS"

SHERPA_VERSION="1.13.4"
URL="https://github.com/k2-fsa/sherpa-onnx/releases/download/v${SHERPA_VERSION}/sherpa-onnx-${SHERPA_VERSION}.aar"
DEST="$PROJECT_LIBS/sherpa-onnx.aar"

if [ -f "$DEST" ]; then
    echo "sherpa-onnx.aar already present at app/libs/ — skipping."
    exit 0
fi

echo "Downloading sherpa-onnx ${SHERPA_VERSION} AAR to app/libs/sherpa-onnx.aar"
curl -fL -o "$DEST" "$URL"
echo "Done."
