#!/usr/bin/env bash
# Post-release state check (TASK-976): one deterministic verdict over the
# rules that were verified by eye across cycles, several of which were
# violated for real (v1.11.x: release branches never merged back, main sat
# two versions behind; the index rename rule exists because a version was
# shipped masquerading as the previous one).
#
# Usage: scripts/check-postrelease.sh vX.Y.Z
# What it asserts, each with the rule's origin:
#   1. the tag is fully merged into origin/main (no commits on the tag
#      that main lacks) - the same-day merge-back rule;
#   2. main's versionName is a SNAPSHOT and is NOT the released version
#      (unreleased builds self-identify in the About row);
#   3. exactly one versioned catalog index file, and it is NOT the
#      released version's (the index is REPLACED, not copied alongside,
#      at the snapshot bump);
#   4. the upstream fdroiddata recipe carries the version and the full
#      cross-check passes (bot landed; absent is a WARNING, not a failure:
#      the bot lands within ~a day of the tag).
#
# Exit 0 all green; 1 any hard failure; 2 usage.

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"
APP_REPO="$(cd "$HERE/.." && pwd)"
TAG="${1:-}"
[ -n "$TAG" ] || { echo "usage: $0 vX.Y.Z" >&2; exit 2; }
[[ "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "FAIL: tag '$TAG' is not release-shaped" >&2; exit 2; }
VERSION="${TAG#v}"

cd "$APP_REPO"
# No --tags: moving tags the repo also carries (nightly) would clobber and
# fail the fetch; this check needs origin/main and the ONE release tag.
git fetch -q origin main
if ! git fetch -q origin "+refs/tags/$TAG:refs/tags/$TAG"; then
  fail "the tag fetch failed (a locally diverged $TAG would refuse the update): fetch --force the tag or delete the stale local one, then re-run"
fi

FAILURES=0
WARNINGS=0
fail() { echo "FAIL: $*" >&2; FAILURES=$((FAILURES + 1)); }
ok() { echo "OK: $*"; }

# 1. tag fully merged into main.
if [ -z "$(git log "$TAG" --not origin/main --oneline)" ]; then
  ok "tag $TAG is fully merged into origin/main"
else
  fail "tag $TAG has commits not on origin/main (same-day merge-back rule): run git merge $TAG into main"
fi

# 2. main carries a SNAPSHOT versionName that is not the released one.
VN="$(grep -m1 'versionName = "' app/build.gradle.kts | sed 's/.*"\(.*\)".*/\1/')"
if [[ "$VN" == *-SNAPSHOT ]] && [ "$VN" != "$VERSION-SNAPSHOT" ] && [ "${VN%-SNAPSHOT}" != "$VERSION" ]; then
  ok "main versionName is '$VN' (snapshot, past the released $VERSION)"
else
  fail "main versionName is '$VN': after releasing $VERSION it must be the NEXT planned version with a -SNAPSHOT suffix"
fi

# 3. exactly one index-*.json, named for the next version, not the released one.
INDEXES="$(ls app/src/main/assets/external-catalog/index-*.json 2>/dev/null | xargs -r -n1 basename | tr '\n' ' ')"
COUNT="$(printf '%s' "$INDEXES" | wc -w | tr -d ' ')"
if [ "$COUNT" = "1" ] && ! printf '%s' "$INDEXES" | grep -qF "index-$VERSION.json"; then
  ok "catalog index is $INDEXES(exactly one, not the released version's)"
else
  fail "catalog index state: [$INDEXES] (want exactly one index-*.json, NOT index-$VERSION.json; the snapshot bump REPLACES the file)"
fi

# 4. upstream recipe: present+green, or warn (bot window). The exit code
# is captured explicitly: check-upstream-recipe.sh's contract is the code.
set +e
"$HERE/check-upstream-recipe.sh" "$TAG" >/dev/null 2>&1
UP_RC=$?
set -e
case "$UP_RC" in
  0) ok "upstream fdroiddata carries $VERSION and the cross-check passes" ;;
  3) WARNINGS=$((WARNINGS + 1)); echo "WARN: upstream does not carry $VERSION yet (bot lands within ~a day of the tag; re-run later)" ;;
  *) fail "upstream probe exit $UP_RC for $VERSION; inspect scripts/check-upstream-recipe.sh $TAG" ;;
esac

echo
if [ "$FAILURES" = "0" ] && [ "$WARNINGS" = "0" ]; then
  echo "POST-RELEASE STATE OK for $TAG"
  exit 0
fi
if [ "$FAILURES" = "0" ]; then
  echo "POST-RELEASE STATE PENDING for $TAG (upstream leg unverified; re-run this script once the bot lands; OK is the only pass)" >&2
else
  echo "POST-RELEASE STATE: $FAILURES failure(s) for $TAG" >&2
fi
exit 1
