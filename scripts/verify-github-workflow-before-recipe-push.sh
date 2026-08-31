#!/bin/bash
#
# Pre-flight: Verify GitHub workflow completed before pushing recipe
#
# This script MUST be run before pushing the F-Droid recipe to prevent
# the race condition where GitLab CI tries to download artifacts that
# don't exist yet (incident 2026-08-31).
#
# Checks performed:
#   1. GitHub workflow android-release.yml completed successfully
#   2. The "reproducible" job succeeded (it reads the MIRROR recipe)
#   3. All three signed APKs (app-fdroid-<abi>-release.apk) exist in the release
#   4. The mirror recipe matches the fork recipe (drift = stale builds)
#
# Usage: ./scripts/verify-github-workflow-before-recipe-push.sh [TAG]
#   TAG: optional (defaults to CurrentVersion in the fork recipe)
#
# Exit codes:
#   0 = all checks passed, safe to push recipe
#   1 = checks failed (actionable message printed)

set -euo pipefail

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

FORK_CHECKOUT="${FORK_CHECKOUT:-$HOME/data/repo/personal/fdroid-data}"
MIRROR_CHECKOUT="${MIRROR_CHECKOUT:-$HOME/data/repo/personal/fdroid-data-mirror}"
REPO="RisorseArtificiali/anti-vocale"

# Get tag from argument or recipe
if [ -n "${1:-}" ]; then
  TAG="$1"
else
  TAG="v$(grep '^CurrentVersion: ' "$FORK_CHECKOUT/metadata/com.antivocale.app.yml" | awk '{print $2}')"
fi

fail() { echo -e "${RED}❌ $*${NC}"; exit 1; }
warn() { echo -e "${YELLOW}⚠️  $*${NC}"; }
ok()   { echo -e "${GREEN}✅ $*${NC}"; }

echo "🔍 Pre-push guard: GitHub workflow + artifacts for ${TAG}"
echo ""

# ---------------------------------------------------------------------------
# Check 1: find the latest workflow run and verify it completed
# ---------------------------------------------------------------------------
echo "Step 1: GitHub workflow status..."

# workflow_dispatch runs of android-release.yml, newest first. The displayTitle
# is the generic "Android CI/CD", so we cannot filter by tag: use the newest
# dispatched run and let the recipe-commit guard below catch a wrong tag.
RUN_ID=$(gh run list --workflow=android-release.yml \
  --event workflow_dispatch --status completed \
  --json databaseId,createdAt \
  --jq 'sort_by(.createdAt) | reverse | .[0].databaseId' 2>/dev/null || true)

# A run may still be in progress or queued: look for that FIRST, because a
# completed older run would otherwise mask it (2026-08-31: guard read the
# previous release's green run while the new one was still building).
# NOTE: gh's --status is a single-valued flag (repeating it is last-wins),
# so fetch unfiltered runs and select the non-terminal statuses client-side.
IN_PROGRESS_ID=$(gh run list --workflow=android-release.yml \
  --event workflow_dispatch --limit 20 \
  --json databaseId,status \
  --jq '[.[] | select(.status == "in_progress" or .status == "queued")][0].databaseId // empty' 2>/dev/null || true)

if [ -n "$IN_PROGRESS_ID" ]; then
  warn "Workflow run ${IN_PROGRESS_ID} is STILL IN PROGRESS"
  echo "   Monitor: gh run view ${IN_PROGRESS_ID}"
  echo "   The sherpa-onnx build takes 40-50 min from dispatch."
  fail "Do not push the recipe until the workflow completes."
fi

if [ -z "$RUN_ID" ]; then
  echo "   No completed dispatch run found."
  echo "   Dispatch first: gh workflow run android-release.yml -f tag=${TAG}"
  fail "No reference build exists for the recipe to point at."
fi

echo "   Latest completed run: ${RUN_ID}"
RUN_STATUS=$(mktemp) || fail "mktemp failed"
trap 'rm -f "$RUN_STATUS"' EXIT
gh run view "$RUN_ID" --json status,conclusion,jobs > "$RUN_STATUS"

# Check the reproducible job specifically
REPROD_CONCLUSION=$(jq -r '.jobs[]
  | select(.name | contains("reproducible"))
  | .conclusion // "missing"' "$RUN_STATUS")

if [ -z "$REPROD_CONCLUSION" ]; then
  fail "No *reproducible* job found in run ${RUN_ID}. Jobs: $(jq -r '.jobs[].name' "$RUN_STATUS" | tr '\n' ' ')"
fi

if [ "$REPROD_CONCLUSION" != "success" ]; then
  echo "   Reproducible job conclusion: ${REPROD_CONCLUSION}"
  echo "   Logs: gh run view ${RUN_ID} --log"
  fail "Reference build did not succeed; the binary: URLs would 404."
fi
ok "Reproducible job succeeded (run ${RUN_ID})"

# ---------------------------------------------------------------------------
# Check 2: signed APKs exist in the GitHub release (via the full checker)
# ---------------------------------------------------------------------------
echo ""
echo "Step 2: signed reference APKs in release ${TAG}..."

# The full checker validates URLs, vercodes, srclib pin, and YAML. It fails
# fast if any invariant drifted since Step 5 completed. Anchor the call to
# this script's directory so the guard works from any cwd, and pin
# SKIP_BINARY_URLS=0 so an inherited env var cannot silently skip the URL
# checks this gate exists to run.
CHECKER="$(dirname "$0")/check-fdroid-release.sh"
[ -f "$CHECKER" ] || fail "checker not found at $CHECKER"
if ! SKIP_BINARY_URLS=0 "$CHECKER" "$TAG" "$FORK_CHECKOUT"; then
  fail "URL or invariant check failed (the gate always runs the URL checks)."
fi
ok "All signed APKs exist and invariants hold"

# ---------------------------------------------------------------------------
# Check 3: fork recipe and mirror recipe are identical (drift check)
# ---------------------------------------------------------------------------
echo ""
echo "Step 3: mirror recipe drift check..."

if [ ! -d "$MIRROR_CHECKOUT" ]; then
  warn "Mirror checkout not found at ${MIRROR_CHECKOUT}"
  echo "   The reproducible job clones the MIRROR (av1100-slim), not the fork."
  echo "   If the mirror is stale, the reference build targets the WRONG version"
  echo "   (2026-08-31 incident: guard refused to sign 1.10.0 APKs as 1.11.0)."
  echo "   Create it once:"
  echo "     git clone -b av1100-slim https://github.com/paoloantinori/fdroid-data-mirror.git ~/data/repo/personal/fdroid-data-mirror"
  fail "Set MIRROR_CHECKOUT or clone the mirror before pushing."
fi

if ! diff -q "$FORK_CHECKOUT/metadata/com.antivocale.app.yml" \
             "$MIRROR_CHECKOUT/metadata/com.antivocale.app.yml" >/dev/null; then
  echo "   Fork and mirror recipes DIFFER:"
  diff "$FORK_CHECKOUT/metadata/com.antivocale.app.yml" \
       "$MIRROR_CHECKOUT/metadata/com.antivocale.app.yml" | head -20 || true
  fail "Sync the mirror first (runbook Step 4): cp + commit + push av1100-slim."
fi
ok "Fork and mirror recipes are identical"

echo ""
ok "ALL CHECKS PASSED; safe to push the recipe"
echo ""
echo "Next steps:"
CURRENT_BRANCH=$(git -C "$FORK_CHECKOUT" branch --show-current)
echo "  cd ${FORK_CHECKOUT}"
echo "  git add metadata/com.antivocale.app.yml"
echo "  git commit -m 'Update to ${TAG}'"
echo "  git push origin ${CURRENT_BRANCH}"
exit 0
