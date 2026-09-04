#!/usr/bin/env bash
# Pre-flight cross-checks between the app repo and the F-Droid recipe (TASK-420).
# Run from the app repo root BEFORE dispatching the reference build and before
# the checkupdates bot opens its MR. Exit non-zero on any mismatch.
#
# Catches the 2026-08-31 incident class: the recipe's 1.11.0 blocks carried the
# stale sherpa_onnx srclib pin (1.13.4) while the app builds against 1.13.5.
# The bot clones blocks verbatim, so a stale pin propagates silently, and the
# reproducibility check would have "passed" comparing two wrong builds.
#
# Usage: scripts/check-fdroid-release.sh [tag] [path-to-fdroid-data]
#   tag defaults to the versionName in app/build.gradle.kts
#   fdroid-data defaults to ~/data/repo/personal/fdroid-data
# Env:
#   EXPECT_COMMIT=<sha>  build-first mode (TASK-446): the tag does not exist
#                        yet; the recipe trio must point at this SHA instead of
#                        at a peeled tag. Use for gate A before a
#                        `-f commit=<sha>` dispatch.

set -euo pipefail

TAG="${1:-}"
FDROID_DATA="${2:-$HOME/data/repo/personal/fdroid-data}"
RECIPE="$FDROID_DATA/metadata/com.antivocale.app.yml"
REPO="RisorseArtificiali/anti-vocale"

fail() { echo "FAIL: $*" >&2; exit 1; }

[ -f "$RECIPE" ] || fail "recipe not found at $RECIPE (pass the fdroid-data path)"

# 1. version + codes
VERSION=$(grep -m1 'versionName = ' app/build.gradle.kts | grep -oE '[0-9]+\.[0-9]+\.[0-9]+')
BASE=$(grep -m1 'versionCode = ' app/build.gradle.kts | grep -oE '[0-9]+')
TAG="${TAG:-v$VERSION}"
echo "== app: $VERSION (base $BASE), checking tag $TAG"

# 2. tag must exist and its commit identified (peeled for annotated, direct
# for lightweight). Build-first (EXPECT_COMMIT): the tag does not exist yet;
# the dispatched SHA stands in for it, and if the tag DOES already exist it
# must agree (a mistaken re-check after publishing).
if [ -n "${EXPECT_COMMIT:-}" ]; then
  # Prefer the peeled line like the legacy branch below: for an annotated tag,
  # ls-remote lists the plain ref first, and head -1 would return the tag
  # OBJECT sha, not the commit.
  EXISTING=$(git ls-remote "https://github.com/$REPO" "refs/tags/$TAG^{}" | awk '{print $1}' | head -1)
  [ -n "$EXISTING" ] || EXISTING=$(git ls-remote "https://github.com/$REPO" "refs/tags/$TAG" | awk '{print $1}' | head -1)
  if [ -n "$EXISTING" ] && [ "$EXISTING" != "$EXPECT_COMMIT" ]; then
    fail "tag $TAG already exists at $EXISTING, not at EXPECT_COMMIT $EXPECT_COMMIT"
  fi
  TAG_COMMIT="$EXPECT_COMMIT"
  echo "== build-first: tag $TAG not required, recipe must point at $TAG_COMMIT"
else
  TAG_COMMIT=$(git ls-remote "https://github.com/$REPO" "refs/tags/$TAG^{}" | awk '{print $1}')
  [ -n "$TAG_COMMIT" ] || TAG_COMMIT=$(git ls-remote "https://github.com/$REPO" "refs/tags/$TAG" | awk '{print $1}')
  [ -n "$TAG_COMMIT" ] || fail "tag $TAG not found on origin"
  echo "== tag $TAG -> $TAG_COMMIT"
fi

# 3. srclib pin must match .sherpa-version (issue #38 rule)
PIN_EXPECTED=$(grep -oE '[0-9a-f]{40}' .sherpa-version || true)
[ -n "$PIN_EXPECTED" ] || fail ".sherpa-version has no srclib commit"
# anchored (\$): a substring match would let a prerelease block poison the
# window (1.10.0-beta.2 matched a check for 1.10.0 and failed the trio count)
BLOCK_START=$(grep -n "versionName: $VERSION\$" "$RECIPE" | head -1 | cut -d: -f1 || true)
[ -n "$BLOCK_START" ] || fail "recipe has no block for $VERSION (generate it first: scripts/new-fdroid-version.py)"
# the version's TRIO spans from the first to the last of its blocks; +40 lines
# covers one block's pitch (38 measured) without reaching the fixed-offset
# fields of anything greppable outside the trio
LAST_SAME=$(grep -n "versionName: $VERSION\$" "$RECIPE" | tail -1 | cut -d: -f1 || true)
BLOCK_END=$((LAST_SAME + 40))
# Capture the trio ONCE and grep the variable below: piping sed straight into
# `grep -q` is a pipefail trap (grep -q exits on first match, sed then dies of
# SIGPIPE 141 and the pipeline reports failure even when the grep MATCHED; the
# flaky "missing versionCode" failures of 2026-09-01 were exactly this race).
TRIO="$(sed -n "${BLOCK_START},${BLOCK_END}p" "$RECIPE")"
PIN_COUNT=$(grep -cE 'sherpa_onnx@[0-9a-f]{40}' <<<"$TRIO" || true)
[ "$PIN_COUNT" = "3" ] || fail "expected 3 srclib pins in the $VERSION trio, found $PIN_COUNT"
BAD_PIN=$(grep -oE 'sherpa_onnx@[0-9a-f]{40}' <<<"$TRIO" | cut -d@ -f2 | grep -v "^$PIN_EXPECTED$" | head -1 || true)
[ -z "$BAD_PIN" ] || fail "srclib pin mismatch in the $VERSION trio: ${BAD_PIN:0:12}, .sherpa-version expects ${PIN_EXPECTED:0:12} (issue #38)"
echo "== srclib pin OK: all $PIN_COUNT blocks pin ${PIN_EXPECTED:0:12} (matches .sherpa-version)"

# 3b. the pin must be the sherpa release the AAR script fetches
SHERPA_VER=$(grep -oE 'v[0-9]+\.[0-9]+\.[0-9]+' .sherpa-version | head -1)
AAR_VER=$(grep -oE 'SHERPA_ONNX_VERSION="[0-9.]+"' scripts/fetch-sherpa-aar.sh | grep -oE '[0-9.]+')
echo "== sherpa $SHERPA_VER / AAR script $AAR_VER"
[ "v$AAR_VER" = "$SHERPA_VER" ] || fail "fetch-sherpa-aar.sh ($AAR_VER) != .sherpa-version ($SHERPA_VER)"

# 4. recipe commit must equal the tag commit (or, build-first, the dispatched SHA)
BAD_COMMIT=$(grep -oE 'commit: [0-9a-f]{40}' <<<"$TRIO" | awk '{print $2}' | grep -v "^$TAG_COMMIT$" | head -1 || true)
[ -z "$BAD_COMMIT" ] || fail "recipe commit mismatch in the $VERSION trio: $BAD_COMMIT != tag commit $TAG_COMMIT"
echo "== recipe commit OK"

# 5. vercodes must be base*10+{1,2,4} and CurrentVersionCode = MAX (x86_64 code;
# fdroiddata convention: master has always carried the highest, e.g. 374 for 1.10.0;
# licaon-corrected on MR 47391 after our runbook wrongly anchored arm64)
for ABI in 1 2 4; do
  EXPECTED=$((BASE * 10 + ABI))
  grep -q "versionCode: $EXPECTED" <<<"$TRIO" \
    || fail "recipe block missing versionCode $EXPECTED (expected base*10+$ABI)"
done
CVC=$(grep -m1 'CurrentVersionCode:' "$RECIPE" | awk '{print $2}' || true)
[ "$CVC" = "$((BASE * 10 + 4))" ] || fail "CurrentVersionCode $CVC != max code $((BASE * 10 + 4))"
echo "== vercodes OK ($((BASE*10+1))/$((BASE*10+2))/$((BASE*10+4)), CurrentVersionCode max)"

# 5b. every NDK pin in the recipe must be preinstallable by the reference
# workflow (2026-08-31: the 1.11.0 trio moved to ndk r28c while the workflow
# preinstalled only r27c; fdroidserver cannot download NDKs in that container
# and the reference build died ~40 min in, after the srclib compile).
# The check reads the workflow from origin/main, NOT the working tree: the
# dispatch executes origin/main, so a local-only NDK_MAP change must fail
# here ("push the workflow first"), not green-light an unpushed mapping.
WORKFLOW_LOCAL=".github/workflows/android-release.yml"
[ -f "$WORKFLOW_LOCAL" ] || fail "workflow not found at $WORKFLOW_LOCAL (run from the app repo root)"
WORKFLOW_REMOTE=$(git show origin/main:.github/workflows/android-release.yml 2>/dev/null || true)
if [ -z "$WORKFLOW_REMOTE" ]; then
  fail "cannot read .github/workflows/android-release.yml from origin/main (no remote branch?)"
fi
RECIPE_NDK_PINS=$(grep -E '^[[:space:]]+ndk: ' "$RECIPE" | awk '{print $2}' | sort -u || true)
[ -n "$RECIPE_NDK_PINS" ] || fail "no ndk pins found in the recipe"
for PIN in $RECIPE_NDK_PINS; do
  # herestring, not `echo | grep -q`: grep -q exits on first match and the
  # producer dies of SIGPIPE under pipefail, failing the check it just PASSED
  # (the flaky NDK-map misses of 2026-09-01 were exactly this race)
  grep -q "NDK_MAP=.*${PIN}=" <<<"$WORKFLOW_REMOTE" \
    || fail "recipe pins ndk $PIN but origin/main's NDK_MAP has no exact version for it (add '$PIN=<sdkmanager version>' to the NDK preinstall step and PUSH the workflow before dispatching)"
done
echo "== ndk pins OK: ${RECIPE_NDK_PINS} all mapped in origin/main's workflow"

# 6. binary URLs must resolve. Skippable pre-dispatch (SKIP_BINARY_URLS=1):
# on a fresh release the assets exist only AFTER the reference build, so the
# pre-push run of this checker must not demand them (the full green board is
# the post-build, pre-bot-MR gate).
if [ "${SKIP_BINARY_URLS:-0}" = "1" ]; then
  echo "== binary URLs SKIPPED (pre-dispatch run)"
else
for ABI in armeabi-v7a arm64-v8a x86_64; do
  ASSET_URL="https://github.com/$REPO/releases/download/$TAG/app-fdroid-$ABI-release.apk"
  STATUS=$(curl -sIL -o /dev/null -w '%{http_code}' --max-time 20 "$ASSET_URL" || echo 000)
  [ "$STATUS" = "200" ] || fail "binary URL not resolving ($STATUS): $ASSET_URL"
done
echo "== binary URLs OK (all 200)"
fi

# 7. YAML parses with no duplicate top-level keys
python3 - "$RECIPE" <<'PYEOF'
import sys, yaml, collections
recipe = sys.argv[1]
yaml.safe_load(open(recipe))
keys = [l.split(':')[0] for l in open(recipe) if l.strip() and not l[0].isspace() and l[0] != '#']
dupes = [k for k, c in collections.Counter(keys).items() if c > 1]
if dupes:
    sys.exit(f"duplicate top-level keys: {dupes}")
PYEOF
echo "== YAML OK"

# 8. No consecutive blank lines: fdroid's rewritemeta canonicalizes the recipe
# to single blank lines, so a recipe carrying a double blank fails the fork
# CI's `fdroid rewritemeta` job (proven on 1.11.1: the generator emitted a
# double blank at the block->tail junction, fixed in new-fdroid-version.py
# 2026-09-01; 1.11.0's recorded red was the NDK pin, a different cause).
# Catch the class at gate A, before it becomes someone else's red build. This
# awk is a PROJECTION of the CI predicate (byte equality with fdroidserver's
# writer), not the predicate itself: if fdroidserver is ever installed
# locally, prefer `fdroid rewritemeta -l` (the dry-run form) and keep this as
# the fallback; do not vendor the writer here to chase full equivalence.
awk '/^[[:space:]]*$/{ if (++b == 2) { print "  double blank line before recipe line " NR; exit 1 } } !/^[[:space:]]*$/{ b=0 }' "$RECIPE" \
  || fail "recipe has consecutive blank lines: the fork CI fdroid rewritemeta job would go red; regenerate via scripts/new-fdroid-version.py"
echo "== formatting OK (no double blank lines: the one drift class the generator can introduce)"

echo "ALL CHECKS PASSED for $VERSION / $TAG"
