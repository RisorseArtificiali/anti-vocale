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

# 2. tag must exist and its commit identified (peeled for annotated, direct for lightweight)
TAG_COMMIT=$(git ls-remote "https://github.com/$REPO" "refs/tags/$TAG^{}" | awk '{print $1}')
[ -n "$TAG_COMMIT" ] || TAG_COMMIT=$(git ls-remote "https://github.com/$REPO" "refs/tags/$TAG" | awk '{print $1}')
[ -n "$TAG_COMMIT" ] || fail "tag $TAG not found on origin"
echo "== tag $TAG -> $TAG_COMMIT"

# 3. srclib pin must match .sherpa-version (issue #38 rule)
PIN_EXPECTED=$(grep -oE '[0-9a-f]{40}' .sherpa-version || true)
[ -n "$PIN_EXPECTED" ] || fail ".sherpa-version has no srclib commit"
BLOCK_START=$(grep -n "versionName: $VERSION" "$RECIPE" | head -1 | cut -d: -f1)
[ -n "$BLOCK_START" ] || fail "recipe has no block for $VERSION (generate it first: scripts/new-fdroid-version.py)"
# the version's TRIO spans from the first to the last of its blocks; +40 lines
# covers one block's pitch (38 measured) without reaching the fixed-offset
# fields of anything greppable outside the trio
LAST_SAME=$(grep -n "versionName: $VERSION" "$RECIPE" | tail -1 | cut -d: -f1)
BLOCK_END=$((LAST_SAME + 40))
PIN_COUNT=$(sed -n "${BLOCK_START},${BLOCK_END}p" "$RECIPE" | grep -cE 'sherpa_onnx@[0-9a-f]{40}')
[ "$PIN_COUNT" = "3" ] || fail "expected 3 srclib pins in the $VERSION trio, found $PIN_COUNT"
BAD_PIN=$(sed -n "${BLOCK_START},${BLOCK_END}p" "$RECIPE" | grep -oE 'sherpa_onnx@[0-9a-f]{40}' | cut -d@ -f2 | grep -v "^$PIN_EXPECTED$" | head -1 || true)
[ -z "$BAD_PIN" ] || fail "srclib pin mismatch in the $VERSION trio: ${BAD_PIN:0:12}, .sherpa-version expects ${PIN_EXPECTED:0:12} (issue #38)"
echo "== srclib pin OK: all $PIN_COUNT blocks pin ${PIN_EXPECTED:0:12} (matches .sherpa-version)"

# 3b. the pin must be the sherpa release the AAR script fetches
SHERPA_VER=$(grep -oE 'v[0-9]+\.[0-9]+\.[0-9]+' .sherpa-version | head -1)
AAR_VER=$(grep -oE 'SHERPA_ONNX_VERSION="[0-9.]+"' scripts/fetch-sherpa-aar.sh | grep -oE '[0-9.]+')
echo "== sherpa $SHERPA_VER / AAR script $AAR_VER"
[ "v$AAR_VER" = "$SHERPA_VER" ] || fail "fetch-sherpa-aar.sh ($AAR_VER) != .sherpa-version ($SHERPA_VER)"

# 4. recipe commit must equal the tag commit
BAD_COMMIT=$(sed -n "${BLOCK_START},${BLOCK_END}p" "$RECIPE" | grep -oE 'commit: [0-9a-f]{40}' | awk '{print $2}' | grep -v "^$TAG_COMMIT$" | head -1 || true)
[ -z "$BAD_COMMIT" ] || fail "recipe commit mismatch in the $VERSION trio: $BAD_COMMIT != tag commit $TAG_COMMIT"
echo "== recipe commit OK"

# 5. vercodes must be base*10+{1,2,4} and CurrentVersionCode = arm64
for ABI in 1 2 4; do
  EXPECTED=$((BASE * 10 + ABI))
  sed -n "${BLOCK_START},${BLOCK_END}p" "$RECIPE" | grep -q "versionCode: $EXPECTED" \
    || fail "recipe block missing versionCode $EXPECTED (expected base*10+$ABI)"
done
CVC=$(grep -m1 'CurrentVersionCode:' "$RECIPE" | awk '{print $2}')
[ "$CVC" = "$((BASE * 10 + 2))" ] || fail "CurrentVersionCode $CVC != arm64 code $((BASE * 10 + 2))"
echo "== vercodes OK ($((BASE*10+1))/$((BASE*10+2))/$((BASE*10+4)), CurrentVersionCode arm64)"

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

echo "ALL CHECKS PASSED for $VERSION / $TAG"
