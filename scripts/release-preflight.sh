#!/usr/bin/env bash
# Release preflight: run BEFORE tagging and BEFORE every dispatch of the release
# workflow. Encodes every failure mode of the v1.10.0 release day (see
# docs/release-runbook.md and TASK-335). Exit code 0 = safe to proceed.
#
# Usage:
#   scripts/release-preflight.sh                 # full check (network: yes)
#   scripts/release-preflight.sh --tag v1.10.0   # also verify the fork recipe vs tag
#   scripts/release-preflight.sh --offline       # skip gh/api/curl checks
#
# Checks (each prints OK or FAIL; any FAIL exits non-zero at the end):
#  1. versionName/versionCode consistent; per-ABI codes = base*10+{1,2,4};
#     the `?: N` fallback literal matches the base code.
#  2. Latest release-notes section per locale is within the Play 500-char limit
#     (the extractor fails loudly on over-length, so this also fails the build).
#  3. fastlane changelogs/<base>.txt exist for en-US and it-IT, within 500 chars.
#  4. app/libs/sherpa-onnx.aar version equals scripts/fetch-sherpa-aar.sh version.
#  5. Fork recipe (anti-vocale-1.8.2): newest Builds entry commit == tag commit,
#     its versionCodes == base*10+{1,2,4}, CurrentVersionCode == base*10+4.
#  6. Fork recipe sherpa srclib pin == the k2-fsa/sherpa-onnx tag commit that
#     matches the AAR version (a stale pin ships the F-Droid build with native
#     bugs the GitHub build already fixed).
set -uo pipefail

REPO_DIR="$(cd "$(dirname "$0")/.." && pwd)"
FORK_DIR="${FORK_DATA_DIR:-$HOME/data/repo/personal/fdroid-data}"
# The recipe branch is whatever the fork checkout is on (the runbook
# Prerequisites mandate that); the literal here went stale after the branch
# moved off anti-vocale-1.8.2 and silently aimed these checks at a dead
# branch (found exercising the v1.11.1 preflight). Same convention as
# release-fdroid-references.sh and verify-github-workflow-before-recipe-push.sh.
FORK_BRANCH="$(git -C "$FORK_DIR" branch --show-current 2>/dev/null)"
RECIPE_REL="metadata/com.antivocale.app.yml"
OFFLINE=0
TAG=""

failures=0
ok()   { echo "OK   $*"; }
fail() { echo "FAIL $*"; failures=$((failures+1)); }

while [ $# -gt 0 ]; do
  case "$1" in
    --offline) OFFLINE=1 ;;
    --tag) shift; TAG="${1:-}" ;;
    *) echo "unknown arg: $1" >&2; exit 2 ;;
  esac
  shift
done
[ -n "$TAG" ] || TAG="$(git -C "$REPO_DIR" describe --tags --abbrev=0 2>/dev/null || true)"

# --- 1. Version consistency -------------------------------------------------
gradle="$REPO_DIR/app/build.gradle.kts"
base=$(grep -m1 'versionCode = ' "$gradle" | grep -oE '[0-9]+')
vname=$(grep -m1 'versionName = ' "$gradle" | grep -oE '"[^"]+"' | tr -d '"')
fallback=$(grep -m1 'defaultConfig.versionCode ?:' "$gradle" | grep -oE '\?: [0-9]+' | grep -oE '[0-9]+')
[ -n "$base" ] && [ -n "$vname" ] || { fail "could not read versionName/versionCode from app/build.gradle.kts"; exit 1; }
ok "version $vname (base code $base)"
[ "$base" = "$fallback" ] && ok "per-ABI fallback literal matches base ($fallback)" \
  || fail "per-ABI fallback literal is $fallback, base is $base: a fresh sync resolves wrong codes"

# --- 2. Play release notes within the extractor cap (fail-loud, 490) ----------
if python3 "$REPO_DIR/scripts/extract-release-notes.py" --output-dir /tmp/preflight-whatsnew >/dev/null 2>/tmp/preflight-notes.err; then
  for loc in en-US it-IT; do
    f="/tmp/preflight-whatsnew/whatsnew-$loc"
    [ -f "$f" ] && ok "notes $loc: $(python3 -c "print(len(open('$f').read()))") chars (<=490)" \
      || fail "notes $loc: file missing"
  done
else
  fail "release notes extraction: $(tail -1 /tmp/preflight-notes.err)"
fi

# --- 3. fastlane changelogs ---------------------------------------------------
for loc in en-US it-IT; do
  f="$REPO_DIR/fastlane/metadata/android/$loc/changelogs/$base.txt"
  if [ -f "$f" ]; then
    n=$(python3 -c "print(len(open('$f').read()))")
    [ "$n" -le 500 ] && ok "fastlane $loc/$base.txt: $n chars" \
      || fail "fastlane $loc/$base.txt is $n chars (F-Droid truncates at 500)"
  else
    fail "fastlane $loc/changelogs/$base.txt missing"
  fi
done

# --- 4. AAR version matches the fetch script ---------------------------------
aar_ver=$(grep -m1 -oE 'SHERPA_ONNX_VERSION="[0-9.]+"' "$REPO_DIR/scripts/fetch-sherpa-aar.sh" | grep -oE '[0-9.]+')
aar_actual=$(unzip -p "$REPO_DIR/app/libs/sherpa-onnx.aar classes.jar 2>/dev/null | true; echo")
# The AAR does not carry a version string; check size-vs-known-jar is unreliable, so
# verify the sha against the upstream release asset when online, else trust the fetch script.
if [ "$OFFLINE" -eq 0 ] && [ -n "$aar_ver" ]; then
  expected_size=$(curl -sIL "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$aar_ver/sherpa-onnx-$aar_ver.aar" | grep -i '^content-length' | tail -1 | tr -dc '0-9')
  local_size=$(stat -c%s "$REPO_DIR/app/libs/sherpa-onnx.aar" 2>/dev/null || echo 0)
  [ -n "$expected_size" ] && [ "$expected_size" = "$local_size" ] \
    && ok "sherpa AAR on disk matches upstream v$aar_ver (size $local_size)" \
    || fail "app/libs/sherpa-onnx.aar (size $local_size) differs from upstream v$aar_ver (size ${expected_size:-unknown}): re-run scripts/fetch-sherpa-aar.sh"
else
  ok "sherpa AAR fetch-script version: v$aar_ver (offline: size check skipped)"
fi

# --- 5 & 6. Fork recipe (needs --tag context; skipped offline) ---------------
if [ "$OFFLINE" -eq 0 ] && [ -n "$TAG" ]; then
  recipe="$FORK_DIR/$RECIPE_REL"
  if [ -f "$recipe" ]; then
    # Read the LOCAL recipe: under the ordering invariant (runbook Step 4/6)
    # the fork remote intentionally lags until finalize, so origin's copy is
    # stale by design at dispatch time and must not be checked or coached
    # here (the old origin-preferred read + "push the recipe BEFORE
    # dispatching" fail message prescribed exactly the banned mid-flow push).
    tag_commit=$(git -C "$REPO_DIR" rev-parse "$TAG^{commit}" 2>/dev/null) || tag_commit=$(git ls-remote "https://github.com/RisorseArtificiali/anti-vocale" "refs/tags/$TAG" 2>/dev/null | awk '{print $1}')
    newest_commit=$(awk '/^[[:space:]]+commit:/{c=$2} END{print c}' "$recipe")
    [ "$newest_commit" = "$tag_commit" ] && ok "recipe newest entry commit == $TAG (local; origin catches up at finalize)" \
      || fail "recipe newest commit $newest_commit != tag $TAG ($tag_commit): run Step 4 (new-fdroid-version.py) locally before dispatching; the fork is pushed only at finalize"
    newest_codes=$(awk '/^[[:space:]]+versionCode:/{print $2}' "$recipe" | tail -3 | tr '\n' ' ')
    expected_codes="$((base*10+1)) $((base*10+2)) $((base*10+4)) "
    [ "$newest_codes" = "$expected_codes" ] && ok "recipe vercodes: $newest_codes" \
      || fail "recipe newest vercodes '$newest_codes' != expected '$expected_codes'"
    cvc=$(awk '/^CurrentVersionCode:/{print $2}' "$recipe")
    [ "$cvc" = "$((base*10+4))" ] && ok "CurrentVersionCode $cvc == max (base*10+4)" \
      || fail "CurrentVersionCode is $cvc, expected $((base*10+4))"
    pin=$(awk '/^[[:space:]]+- sherpa_onnx@/{print $2}' "$recipe" | tail -1 | sed 's/sherpa_onnx@//')
    [ -n "$pin" ] || fail "could not read sherpa_onnx srclib pin from the recipe"
    if [ -n "$aar_ver" ] && [ -n "$pin" ]; then
      want_pin=$(gh api "repos/k2-fsa/sherpa-onnx/git/ref/tags/v$aar_ver" --jq '.object.sha' 2>/dev/null)
      # annotated tags: peel to commit
      want_commit=$(gh api "repos/k2-fsa/sherpa-onnx/tags" --jq ".[] | select(.name==\"v$aar_ver\") | .commit.sha" 2>/dev/null)
      want="${want_commit:-$want_pin}"
      [ -n "$want" ] && [ "$pin" = "$want" ] && ok "recipe sherpa srclib pin == v$aar_ver tag commit" \
        || fail "recipe sherpa srclib pin $pin != v$aar_ver commit ${want:-unknown}: F-Droid would build with a different native stack than the shipped AAR"
    fi
  else
    fail "fork recipe not found at $recipe (set FORK_DATA_DIR if the fork lives elsewhere)"
  fi
fi

echo
if [ "$failures" -eq 0 ]; then
  echo "PREFLIGHT PASS ($TAG)"
  echo "Reminders: never 'gh release upload --clobber' canonical asset names;"
  echo "dispatch with -f tag only rebuilds references; Play rejects a re-upload"
  echo "of an already-uploaded versionCode (fix notes in console instead)."
else
  echo "PREFLIGHT FAIL: $failures check(s) failed ($TAG)"
fi
exit "$([ "$failures" -eq 0 ] && echo 0 || echo 1)"
