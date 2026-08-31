#!/bin/bash
#
# F-Droid build with optimistic artifact guard
#
# This script wraps the fdroid build command with a fast-fail check
# that verifies reference APKs exist before attempting the full build.
# This prevents wasting CI time when artifacts aren't ready yet.
#
# Optimistic guard: short per-URL HEAD checks (--max-time 30)
# Worst case: 3 URLs × 30s = 90s if network is slow but artifacts exist
# Fail-fast: if artifacts don't exist, answers 404 in ~1-2s per URL
#
# Usage: ./scripts/ci/fdroid-build-with-timeout.sh (called from GitLab CI)

set -euo pipefail

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# Get current version from recipe (we're in the fdroid-data build context)
TAG="v$(grep '^CurrentVersion: ' metadata/com.antivocale.app.yml | awk '{print $2}')"
REPO="RisorseArtificiali/anti-vocale"

echo "🔍 F-Droid Build with Artifact Guard"
echo "Tag: ${TAG}"
echo ""

echo "Step 1: Optimistic binary URL resolution check..."
echo "         (Fast-fail if artifacts not ready, ~30s worst case per URL)"
echo ""

# List of ABIs to check
ABIS=("armeabi-v7a" "arm64-v8a" "x86_64")

ALL_OK=true
FAILED_ABIS=()

for abi in "${ABIS[@]}"; do
  URL="https://github.com/${REPO}/releases/download/${TAG}/app-fdroid-${abi}-release.apk"

  echo -n "  Checking ${abi}... "

  # Use curl --max-time for per-URL timeout; -w '%{http_code}' always returns
  # the last status code (immune to redirect hops). 30s budget: enough for
  # slow networks but short enough to fail fast on missing artifacts.
  HTTP_CODE=$(curl -sIL --max-time 30 -o /dev/null -w '%{http_code}' "$URL" || echo "000")

  if [ "$HTTP_CODE" = "200" ]; then
    echo -e "${GREEN}✅ OK (${HTTP_CODE})${NC}"
  elif [ "$HTTP_CODE" = "404" ]; then
    echo -e "${RED}❌ NOT FOUND (404)${NC}"
    FAILED_ABIS+=("$abi")
    ALL_OK=false
  elif [ "$HTTP_CODE" = "000" ]; then
    echo -e "${YELLOW}⚠️  TIMEOUT (30s)${NC}"
    echo "     (Network error or no response)"
    FAILED_ABIS+=("$abi")
    ALL_OK=false
  else
    echo -e "${YELLOW}⚠️  UNEXPECTED (${HTTP_CODE})${NC}"
    echo "     (May be transient, continuing anyway)"
  fi
done

echo ""

if [ "$ALL_OK" = false ]; then
  echo -e "${RED}🔴 GUARD FAILED: Reference APKs not yet available${NC}"
  echo ""
  echo "Missing APKs for: ${FAILED_ABIS[*]}"
  echo ""
  echo "This indicates the GitHub Actions reproducible build is still running."
  echo "Expected wait time: 15-30 minutes from workflow dispatch."
  echo ""
  echo "Current GitHub Actions status:"
  echo "  gh run list --workflow=android-release.yml --limit 3"
  echo ""
  echo "⏸️  PAUSING: Retry this job when artifacts are ready"
  echo ""
  echo "To re-trigger this GitLab pipeline:"
  echo "  git push origin <branch>  # Force new pipeline run"
  echo ""
  exit 1
fi

echo -e "${GREEN}✅ Optimistic check passed - proceeding with fdroid build${NC}"
echo ""
echo "Step 2: Running fdroid build..."
echo ""

# Continue with normal fdroid build
exec fdroid build -v
