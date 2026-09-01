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
#   5. origin's recipe adds nothing the local recipe lacks (the finalize
#      force-push discards nothing)
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
  TAG="v$(grep '^CurrentVersion: ' "$FORK_CHECKOUT/metadata/com.antivocale.app.yml" 2>/dev/null | awk '{print $2}' || true)"
  [ -n "${TAG#v}" ] || fail "cannot read CurrentVersion from the fork recipe; pass the tag explicitly"
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

# One listing serves both the in-progress check and the reference-run pick.
# NOTE: gh's --status is a single-valued flag (repeating it is last-wins),
# so fetch unfiltered runs and select statuses client-side.
RUNS_JSON=$(gh run list --workflow=android-release.yml \
  --event workflow_dispatch --limit 20 \
  --json databaseId,status,conclusion,createdAt 2>/dev/null || true)

# A run may still be in progress or queued: look for that FIRST, because a
# completed older run would otherwise mask it (2026-08-31: guard read the
# previous release's green run while the new one was still building).
IN_PROGRESS_ID=$(echo "$RUNS_JSON" | jq -r \
  '[.[] | select(.status == "in_progress" or .status == "queued")][0].databaseId // empty')

if [ -n "$IN_PROGRESS_ID" ]; then
  warn "Workflow run ${IN_PROGRESS_ID} is STILL IN PROGRESS"
  echo "   Monitor: gh run view ${IN_PROGRESS_ID}"
  echo "   The sherpa-onnx build takes 40-50 min from dispatch."
  fail "Do not push the recipe until the workflow completes."
fi

# Reference run = the newest completed dispatch whose reproducible job RAN.
# A Play-only dispatch (play-store-track) skips that job: binding to the
# newest completed run unconditionally would fail this gate on a "skipped"
# conclusion with a wrong diagnosis, and re-running finalize never clears it.
RUN_ID=""
REPROD_CONCLUSION=""
for CAND in $(echo "$RUNS_JSON" | jq -r \
  '[.[] | select(.status == "completed")] | sort_by(.createdAt) | reverse | .[0:5][].databaseId'); do
  CAND_CONCLUSION=$(gh run view "$CAND" --json jobs 2>/dev/null \
    | jq -r '[.jobs[] | select(.name | contains("reproducible"))][0].conclusion // empty')
  if [ -n "$CAND_CONCLUSION" ] && [ "$CAND_CONCLUSION" != "skipped" ]; then
    RUN_ID="$CAND"
    REPROD_CONCLUSION="$CAND_CONCLUSION"
    break
  fi
done

if [ -z "$RUN_ID" ]; then
  echo "   No completed dispatch run with a reproducible job found."
  echo "   Dispatch first: gh workflow run android-release.yml -f tag=${TAG}"
  fail "No reference build exists for the recipe to point at."
fi

if [ "$REPROD_CONCLUSION" != "success" ]; then
  echo "   Reference run ${RUN_ID}: reproducible job conclusion: ${REPROD_CONCLUSION}"
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

# The diff below compares WORKING TREES, but the fork push (and the pipeline
# it triggers) operate on COMMITS: a dirty fork tree would pass while the
# push ships the older committed recipe. Gate that here so the manual path
# is protected too, not just the orchestrator.
if [ -n "$(git -C "$FORK_CHECKOUT" status --porcelain -- metadata/com.antivocale.app.yml)" ]; then
  fail "fork recipe has UNCOMMITTED changes; commit first (this gate certifies what the push will send)"
fi

if ! diff -q "$FORK_CHECKOUT/metadata/com.antivocale.app.yml" \
             "$MIRROR_CHECKOUT/metadata/com.antivocale.app.yml" >/dev/null; then
  echo "   Fork and mirror recipes DIFFER:"
  diff "$FORK_CHECKOUT/metadata/com.antivocale.app.yml" \
       "$MIRROR_CHECKOUT/metadata/com.antivocale.app.yml" | head -20 || true
  fail "Sync the mirror first: scripts/sync-fdroid-mirror.sh (from the app repo)."
fi
ok "Fork and mirror recipes are identical (fork tree clean)"

# ---------------------------------------------------------------------------
# Check 4: origin's copy of the recipe carries nothing a push would discard
# ---------------------------------------------------------------------------
echo ""
echo "Step 4: fork branch vs its remote (recipe content guard)..."
CURRENT_BRANCH=$(git -C "$FORK_CHECKOUT" branch --show-current)
[ -n "$CURRENT_BRANCH" ] || fail "fork checkout is on a detached HEAD; check out the recipe branch first"
git -C "$FORK_CHECKOUT" fetch -q origin
# Same Builds-section content guard as sync-fdroid-mirror.sh and the finalize
# push (fdroiddata squash-merges MRs, so commit ancestry cannot express
# "already merged upstream"; the check is directional on Builds content):
# origin holding recipe lines this checkout lacks means the push would discard
# them (!47391-style maintainer edits on our branch).
FILTER_AWK='/^CurrentVersion(Code)?:/{next} 1'
# capture, then grep: under pipefail the old `diff | grep -q` form could
# NEVER fire (a real origin-side difference makes diff exit 1 and pipefail
# surfaces that regardless of grep's match: the guard was dead code and a
# maintainer edit would have sailed through). Capturing reads to EOF, so
# only the grep verdict decides.
ORIGIN_EXTRA="$(diff -u \
  <(git -C "$FORK_CHECKOUT" show "HEAD:metadata/com.antivocale.app.yml" | awk "$FILTER_AWK") \
  <(git -C "$FORK_CHECKOUT" show "origin/$CURRENT_BRANCH:metadata/com.antivocale.app.yml" 2>/dev/null | awk "$FILTER_AWK"))" || true
if grep -qE '^\+[^+]' <<<"$ORIGIN_EXTRA"; then
  fail "origin/$CURRENT_BRANCH's recipe has content this checkout lacks (maintainer edits?): reset onto it and re-run scripts/new-fdroid-version.py"
fi
ok "origin's recipe adds nothing this checkout lacks"

echo ""
ok "ALL CHECKS PASSED; safe to push the recipe"
echo ""
echo "Next steps:"
echo "  one command: scripts/release-fdroid-references.sh finalize ${TAG}"
echo "    (runs this gate, pushes the fork if pending, polls the pipeline)"
echo "  manual fork push (non-FF by design after the Step 4 master reset; the"
echo "   fork checkout's pre-push hook blocks the recipe branch until the"
echo "   signed APK URLs resolve):"
echo "    git -C ${FORK_CHECKOUT} push --force-with-lease origin ${CURRENT_BRANCH}"
exit 0
