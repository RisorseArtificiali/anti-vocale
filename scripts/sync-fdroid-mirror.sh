#!/usr/bin/env bash
# Syncs the fork's F-Droid recipe to the GitHub mirror (av1100-slim), the
# repo the reproducible workflow actually clones. The 2026-08-31 incident:
# the mirror was one commit behind the fork (CurrentVersionCode 382 vs 384)
# and only luck in timing kept the reference build on the right recipe.
#
# "In sync" means the REMOTE branch matches: local working trees can agree
# while an unpushed (or wrong-branch) commit leaves origin/av1100-slim stale,
# and the 45-min reference build would clone the wrong recipe (2026-08-31
# postmortem class). Hence the branch guard and the remote-parity checks.
#
# Usage: scripts/sync-fdroid-mirror.sh   (from anywhere)
# Env:   FORK_CHECKOUT   default ~/data/repo/personal/fdroid-data
#        MIRROR_CHECKOUT default ~/data/repo/personal/fdroid-data-mirror
#        DRY_RUN=1       suppress mutating actions (commit/push/cp); the
#                        ff-only pulls and the diff always run so the dry
#                        run observes the same state the real run would

set -euo pipefail

FORK_CHECKOUT="${FORK_CHECKOUT:-$HOME/data/repo/personal/fdroid-data}"
MIRROR_CHECKOUT="${MIRROR_CHECKOUT:-$HOME/data/repo/personal/fdroid-data-mirror}"
MIRROR_URL="https://github.com/paoloantinori/fdroid-data-mirror.git"
MIRROR_BRANCH="av1100-slim"
RECIPE_REL="metadata/com.antivocale.app.yml"

fail() { echo "FAIL: $*" >&2; exit 1; }
run() {
  if [ "${DRY_RUN:-0}" = "1" ]; then echo "DRY: $*"; else "$@"; fi
}

[ -f "$FORK_CHECKOUT/$RECIPE_REL" ] \
  || fail "fork recipe not found at $FORK_CHECKOUT/$RECIPE_REL"

if [ ! -d "$MIRROR_CHECKOUT" ]; then
  if [ "${DRY_RUN:-0}" = "1" ]; then
    fail "mirror checkout missing at $MIRROR_CHECKOUT; run once without DRY_RUN to create it"
  fi
  echo "== mirror checkout missing, cloning once"
  run git clone -b "$MIRROR_BRANCH" "$MIRROR_URL" "$MIRROR_CHECKOUT"
fi

MIRROR_BR="$(git -C "$MIRROR_CHECKOUT" branch --show-current)"
[ "$MIRROR_BR" = "$MIRROR_BRANCH" ] \
  || fail "mirror checkout is on '${MIRROR_BR:-detached HEAD}', not $MIRROR_BRANCH; check out $MIRROR_BRANCH first (a sync from the wrong branch would push a stale av1100-slim and report success)"

echo "== refreshing both checkouts (ff-only; runs in DRY too: read-only)"
if [ -n "$(git -C "$FORK_CHECKOUT" status --porcelain -- "$RECIPE_REL")" ]; then
  fail "fork recipe has UNCOMMITTED changes; commit first (the mirror commit must cite a fork SHA that actually contains the synced content)"
fi
git -C "$FORK_CHECKOUT" pull --ff-only \
  || fail "fork pull diverged from origin: rebase your local recipe commits (see git -C $FORK_CHECKOUT status), then re-run"
git -C "$MIRROR_CHECKOUT" pull --ff-only \
  || fail "mirror pull diverged from origin: inspect $MIRROR_CHECKOUT and re-sync by hand before re-running"

mirror_pushed() {
  # local branch clean and identical to its remote: what the workflow clones
  [ -z "$(git -C "$MIRROR_CHECKOUT" status --porcelain -- "$RECIPE_REL")" ] \
    && [ "$(git -C "$MIRROR_CHECKOUT" rev-parse "$MIRROR_BRANCH")" \
         = "$(git -C "$MIRROR_CHECKOUT" rev-parse "origin/$MIRROR_BRANCH")" ]
}

if mirror_pushed && diff -q "$FORK_CHECKOUT/$RECIPE_REL" "$MIRROR_CHECKOUT/$RECIPE_REL" >/dev/null; then
  echo "== mirror already in sync with the fork (remote verified)"
  exit 0
fi

echo "== recipes differ (or mirror ahead of its remote), syncing:"
diff "$FORK_CHECKOUT/$RECIPE_REL" "$MIRROR_CHECKOUT/$RECIPE_REL" | head -10 || true

FORK_SHA="$(git -C "$FORK_CHECKOUT" rev-parse --short HEAD)"
run cp "$FORK_CHECKOUT/$RECIPE_REL" "$MIRROR_CHECKOUT/$RECIPE_REL"
run git -C "$MIRROR_CHECKOUT" add "$RECIPE_REL"
run git -C "$MIRROR_CHECKOUT" commit -m "Sync recipe with fdroiddata branch ($FORK_SHA)"
run git -C "$MIRROR_CHECKOUT" push origin "$MIRROR_BRANCH"
if [ "${DRY_RUN:-0}" != "1" ]; then
  mirror_pushed || fail "push reported success but origin/$MIRROR_BRANCH still differs from local $MIRROR_BRANCH; investigate before dispatching"
  echo "== mirror synced (av1100-slim at recipe of fork $FORK_SHA)"
fi
