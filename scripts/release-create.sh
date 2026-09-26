#!/usr/bin/env bash
# Build-first publish act (TASK-446): creates tag + release + ALL release
# assets in ONE command, from the artifacts of a `-f commit=<sha>` dispatch.
#
# This is the step that makes the version public. Until it runs, no tag exists,
# so the fdroid checkupdates bot (UpdateCheckMode: Tags) cannot see anything;
# after it runs, every `binary:` URL resolves, so whenever the bot's sweep
# fires it finds complete binaries. The 2026-09-04 race (bot MR at 06:23 UTC,
# signed APKs attached ~07:20, pipeline 404) cannot recur through the ORDERING
# this path fixes. Residual, not ordering-related: if `gh release create` dies
# mid-upload the release is public but partial; the post-checks fail loudly and
# the completion is `gh release upload vX.Y.Z <remaining files>`.
#
# Usage: scripts/release-create.sh vX.Y.Z --run-id <id> --commit <sha> --notes-file F
#   --run-id      the dispatch run to download artifacts from (REQUIRED: run
#                 inputs are not queryable afterwards, so the id is explicit)
#   --commit      the future tag target: the bump commit the run was dispatched
#                 with (verified to be on origin/main and to carry the tag's
#                 versionName)
#   --notes-file  the curated GitHub release body (runbook Step 2.2)
# Env:
#   DRY_RUN=1     print the side-effecting actions instead of running them
#
# Exit codes: 0 published (or dry-run plan printed); 1 guard failed.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
APP_REPO="$(cd "$HERE/.." && pwd)"
REPO="RisorseArtificiali/anti-vocale"
REPO_URL="https://github.com/$REPO"

say() { echo "== $*"; }
fail() { echo "FAIL: $*" >&2; exit 1; }
run() {
  if [ "${DRY_RUN:-0}" = "1" ]; then echo "DRY: $*"; else "$@"; fi
}

# What commit does a tag point at? Peels annotated tags (prefers the ^{}
# line; ls-remote lists the plain ref first, and head -1 alone would return
# the tag OBJECT sha). Same semantics as new-fdroid-version.py peel_tag().
tag_commit() {
  local out peeled
  out=$(git ls-remote "$REPO_URL" "refs/tags/$1^{}" "refs/tags/$1" 2>/dev/null || true)
  # || true on both greps: a nonexistent tag means no match, and a failing
  # grep inside $() would kill the caller under set -e (found dry-running
  # guard 1 against an absent tag: the script died silently after the say).
  peeled=$(grep -F "refs/tags/$1^{}" <<<"$out" | awk '{print $1}' | head -1 || true)
  if [ -n "$peeled" ]; then echo "$peeled"; return; fi
  grep -F "refs/tags/$1" <<<"$out" | awk '{print $1}' | head -1 || true
}

TAG=""
RUN_ID=""
COMMIT=""
NOTES_FILE=""
while [ $# -gt 0 ]; do
  case "$1" in
    --run-id | --commit | --notes-file)
      [ $# -ge 2 ] || fail "$1 needs a value"
      case "$1" in
        --run-id) RUN_ID="$2" ;;
        --commit) COMMIT="$2" ;;
        --notes-file) NOTES_FILE="$2" ;;
      esac
      shift 2 ;;
    -h | --help) sed -n '2,/^$/p' "$0" | sed '$d'; exit 0 ;;
    -*) fail "unknown arg: $1" ;;
    *) [ -z "$TAG" ] || fail "unexpected second positional arg: $1"; TAG="$1"; shift ;;
  esac
done

[ -n "$TAG" ] || fail "usage: $0 vX.Y.Z --run-id <id> --commit <sha> --notes-file F (see --help)"
[ -n "$RUN_ID" ] || fail "--run-id is required (the dispatch run carries the artifacts; its inputs cannot be recovered afterwards)"
[ -n "$COMMIT" ] || fail "--commit is required (the tag target)"
[ -n "$NOTES_FILE" ] || fail "--notes-file is required (runbook Step 2.2, the curated GitHub release body)"
[ -f "$NOTES_FILE" ] || fail "notes file not found: $NOTES_FILE"
[[ "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail "tag must look like vX.Y.Z, got '$TAG'"
[[ "$COMMIT" =~ ^[0-9a-f]{40}$ ]] || fail "--commit must be a full 40-hex SHA, got '$COMMIT'"

# The asset manifest, derived from the app's gradle abiCode when-map (the
# single owner, TASK-525): one list, three uses below (presence check, count
# tripwires, the create call itself).
GRADLE_ABI_MAP="$(sed -n '/val abiCode = when/,/else -> 0/p' "$APP_REPO/app/build.gradle.kts" \
  | grep -oE '"[^"]+" -> [0-9]+' || true)"
[ -n "$GRADLE_ABI_MAP" ] || { echo "::error::cannot parse the abiCode when-map from app/build.gradle.kts (the asset manifest needs the ABI list)"; exit 1; }
mapfile -t ABI_LIST < <(awk -F'"' '{print $2}' <<<"$GRADLE_ABI_MAP")
ASSETS=()
for ABI in "${ABI_LIST[@]}"; do
  ASSETS+=(
    "app-fdroid-$ABI-release-unsigned.apk"
    "app-fdroid-$ABI-release.apk"
    "antivocale-debug-$ABI.apk"
    "antivocale-fdroid-testsigned-$ABI.apk"
  )
done

cd "$APP_REPO"

# --- guards: this script PUBLISHES, so every input is verified first --------

say "guard 1/5: tag $TAG must not exist yet"
EXISTING=$(tag_commit "$TAG")
[ -z "$EXISTING" ] || fail "tag $TAG already exists at $EXISTING (already published? re-dispatch instead)"

say "guard 2/5: commit $COMMIT is pushed and on origin/main"
git fetch -q origin main
git merge-base --is-ancestor "$COMMIT" origin/main \
  || fail "$COMMIT is not an ancestor of origin/main (push the bump commit first; a release tag must not point at an unpushed or side SHA)"

say "guard 3/5: the bump commit carries the tag's version"
VERSION="${TAG#v}"
# || true: a missing/garbled versionName would die silently under set -e
# before the friendly fail below could name the cause.
COMMIT_NAME=$(git show "$COMMIT:app/build.gradle.kts" | grep -m1 'versionName = ' | grep -oE '[0-9]+\.[0-9]+\.[0-9]+' || true)
[ -n "$COMMIT_NAME" ] || fail "could not read versionName from app/build.gradle.kts at $COMMIT (wrong SHA?)"
[ "$COMMIT_NAME" = "$VERSION" ] \
  || fail "app/build.gradle.kts at $COMMIT says versionName '$COMMIT_NAME', expected $VERSION (wrong commit for $TAG?)"

say "guard 4/5: dispatch run $RUN_ID completed green, from the bump commit (reproducible job included)"
RUN_JSON=$(gh run view "$RUN_ID" -R "$REPO" --json status,conclusion,headSha,jobs) \
  || fail "cannot read run $RUN_ID (gh auth or wrong id?)"
[ "$(echo "$RUN_JSON" | jq -r .status)" = "completed" ] || fail "run $RUN_ID is not completed yet"
[ "$(echo "$RUN_JSON" | jq -r .conclusion)" = "success" ] || fail "run $RUN_ID conclusion is not success"
# Bind the run to this release: asset names carry no version, so a stale run
# id (the previous release's dispatch) would otherwise pass every check below
# and publish the WRONG binaries under the new tag. headSha is the branch head
# at dispatch time, which equals the bump SHA in runbook order (Step 3 pushes
# the bump, Step 5 dispatches immediately); a main push in between fails here
# loudly, which is the safe direction.
RUN_HEAD=$(echo "$RUN_JSON" | jq -r .headSha)
# Micro-release order (release/vX.Y.Z branch, runbook's v1.11.3 pattern) merges
# the branch into main AROUND the dispatch, so headSha can legitimately be a
# main tip that is not the bump SHA. The equivalent binding proof for that
# flow: the workflow's own recipe-commit guard step ran and succeeded inside
# THIS run (it fails the job fast when the recipe's commit: is not the
# dispatched SHA, so its success pins the built tree to $COMMIT).
RECIPE_GUARD_OK=$(echo "$RUN_JSON" | jq -r '
  [.jobs[].steps[]? | select(.name | test("Verify recipe commit"))] |
  (length > 0) and all(.conclusion == "success")')
# Review F8: the step succeeding only proves the run validated ITS OWN
# dispatch input; a stale run of a DIFFERENT build-first dispatch would
# pass that alone. Bind materially: the run's own log must contain the
# expected commit SHA at the guard step (the comparison prints both SHAs).
if [ "$RECIPE_GUARD_OK" = "true" ]; then
  if ! gh run view "$RUN_ID" -R "$REPO" --log 2>/dev/null |
      grep -A 3 "Verify recipe commit" | grep -q "$COMMIT"; then
    RECIPE_GUARD_OK=false
  fi
fi
if [ "$RUN_HEAD" != "$COMMIT" ] && [ "$RECIPE_GUARD_OK" != "true" ]; then
  fail "run $RUN_ID headSha is $RUN_HEAD, expected the bump commit $COMMIT, and the run's recipe-commit guard step did not succeed (stale run id? re-check: gh run list --event workflow_dispatch --limit 3)"
fi
# Same job selector as verify-github-workflow-before-recipe-push.sh (twice:
# the reference-run picker and the in-progress check); three copies total,
# plus the workflow's needs/release-sanity references to the job id and the
# runbook's by-name mentions (job walkthrough, Step 5 list, the proof
# command). If the reproducible job is ever renamed, change all of them
# (keep aligned).
REPRO=$(echo "$RUN_JSON" | jq -r '[.jobs[] | select(.name | contains("reproducible"))][0].conclusion // empty')
[ "$REPRO" = "success" ] || fail "run $RUN_ID reproducible job conclusion: '${REPRO:-absent}' (was this a -f commit=<sha> dispatch?)"

say "guard 5/5: artifacts carry the full ${#ASSETS[@]}-asset set"
STAGE=$(mktemp -d)
trap 'rm -rf "$STAGE"' EXIT
# ONE artifact per call, deliberately: a single -n downloads FLAT into -D
# (verified against gh 2.97.0 on a live run), while multiple -n in one call
# nest each artifact under $STAGE/<name>/ and break the flat manifest check
# below. Do not batch these.
gh run download "$RUN_ID" -R "$REPO" -n app-fdroid-release -D "$STAGE" \
  || fail "artifact 'app-fdroid-release' missing from run $RUN_ID"
gh run download "$RUN_ID" -R "$REPO" -n tester-apks -D "$STAGE" \
  || fail "artifact 'tester-apks' missing from run $RUN_ID (was the dispatch a -f commit=<sha> one?)"
gh run download "$RUN_ID" -R "$REPO" -n fdroid-signed-references -D "$STAGE" \
  || fail "artifact 'fdroid-signed-references' missing from run $RUN_ID"
MISSING=0
for NAME in "${ASSETS[@]}"; do
  [ -f "$STAGE/$NAME" ] || { echo "   missing: $NAME"; MISSING=1; }
done
[ "$MISSING" -eq 0 ] || fail "artifact set incomplete (listed above); re-dispatch the build"
COUNT=$(ls "$STAGE" | wc -l)
[ "$COUNT" -eq "${#ASSETS[@]}" ] || fail "expected exactly ${#ASSETS[@]} files in the artifact set, found $COUNT"

# --- the publish act: ONE gh release create makes tag + release + binaries ---

say "publishing $TAG at $COMMIT (${#ASSETS[@]} assets)"
run gh release create "$TAG" \
  --repo "$REPO" \
  --target "$COMMIT" \
  --title "Anti-Vocale $TAG" \
  --notes-file "$NOTES_FILE" \
  "${ASSETS[@]/#/$STAGE/}"

if [ "${DRY_RUN:-0}" = "1" ]; then
  say "DRY: publish skipped; no post-checks"
  exit 0
fi

# --- post-checks: the invariant this script exists to guarantee -------------

PUBLISHED_COMMIT=$(tag_commit "$TAG")
[ "$PUBLISHED_COMMIT" = "$COMMIT" ] || fail "tag $TAG resolved to $PUBLISHED_COMMIT, expected $COMMIT"
ASSET_COUNT=$(gh release view "$TAG" -R "$REPO" --json assets --jq '.assets | length')
[ "$ASSET_COUNT" -eq "${#ASSETS[@]}" ] || fail "release $TAG carries $ASSET_COUNT assets, expected ${#ASSETS[@]}"
say "OK: $TAG published at $COMMIT with $ASSET_COUNT assets; every binary: URL now resolves"
say "next: scripts/release-fdroid-references.sh finalize $TAG"
