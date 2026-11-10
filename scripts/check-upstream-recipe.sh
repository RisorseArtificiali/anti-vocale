#!/usr/bin/env bash
# Probe whether the checkupdates bot has already landed a version's blocks
# on UPSTREAM fdroiddata master, and when it has, run the full recipe
# cross-check against upstream's own file (TASK-976).
#
# Why (the v1.16.0 incident, 2026-11-10): after release-create attaches the
# signed assets, the bot picks the tag up within about a day and its MR
# lands on upstream. We built a fork lane ANYWAY (based on the fork's
# fossil master) and its pipeline failed on the weblate merge-conflict job
# because our lane duplicated changes that had landed upstream. The probe
# makes the bot's state a first-class gate: callers know BEFORE building a
# lane whether one is needed at all, and finalize skips the fork push once
# upstream carries the version.
#
# Usage: scripts/check-upstream-recipe.sh vX.Y.Z
# Exit codes (the code, not the text, is the verdict):
#   0  upstream carries the version AND the full cross-check passes
#   1  upstream carries the version but the cross-check FAILS (wrong
#      srclib pin, wrong commit or dead binary URLs; investigate)
#   3  upstream does NOT carry the version yet (normal within ~24h of the
#      tag; a fork lane is the only way to feed the reference build)
#   4  probe itself failed (network, non-200 or parse; never guess)
#
# Anonymous, no token: reads the raw file off gitlab.com's web endpoint and
# runs scripts/check-fdroid-release.sh against a materialized checkout.

set -euo pipefail

TAG="${1:-}"
[ -n "$TAG" ] || { echo "usage: $0 vX.Y.Z" >&2; exit 2; }
[[ "$TAG" =~ ^v[0-9]+\.[0-9]+\.[0-9]+$ ]] || { echo "FAIL: tag '$TAG' is not release-shaped" >&2; exit 2; }

HERE="$(cd "$(dirname "$0")" && pwd)"
VERSION="${TAG#v}"
RECIPE_REL="metadata/com.antivocale.app.yml"
# Anonymous API against fdroid/fdroiddata (no hyphen; the FORK is fdroid-data,
# upstream is fdroiddata): the /-/raw/ endpoint answers 403-sign_in to plain
# curl under bot detection, the API serves the same bytes anonymously; the
# route finalize's bot-first check already proved.
UPSTREAM_API="https://gitlab.com/api/v4/projects/fdroid%2Ffdroiddata/repository/files/$(printf '%s' "$RECIPE_REL" | sed 's|/|%2F|g')/raw?ref=master"

WORK="$(mktemp -d /tmp/av-upstream-recipe.XXXXXX)"
trap 'rm -rf "$WORK"' EXIT

# curl -f: a 404 body must never pose as a recipe (the checker's own rule).
if ! curl -fsSL --max-time 60 "$UPSTREAM_API" -o "$WORK/recipe.yml"; then
  echo "PROBE-FAIL: could not fetch the upstream recipe via $UPSTREAM_API (non-200 or network error)" >&2
  exit 4
fi

if ! grep -qE "versionName: ${VERSION}$" "$WORK/recipe.yml"; then
  echo "ABSENT: upstream fdroiddata master does not carry $VERSION yet (bot not landed; expected within ~24h of the tag)"
  exit 3
fi

echo "PRESENT: upstream fdroiddata master carries $VERSION (bot landed)"
mkdir -p "$WORK/fdroid-data/metadata"
mv "$WORK/recipe.yml" "$WORK/fdroid-data/metadata/com.antivocale.app.yml"

# The full cross-check, pointed at the materialized upstream file. The
# checker takes provenance from the tag's own files once the tag exists, so
# this validates upstream's blocks against the RELEASED truth (srclib pin,
# peeled-commit pins, vercodes, binary URLs, YAML).
if "$HERE/check-fdroid-release.sh" "$TAG" "$WORK/fdroid-data"; then
  echo "== upstream recipe for $TAG: ALL CHECKS PASSED"
  exit 0
else
  echo "FAIL: upstream carries $VERSION but the cross-check is red (see above)" >&2
  exit 1
fi
