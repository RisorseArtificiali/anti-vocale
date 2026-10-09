#!/usr/bin/env bash
# One-command host bootstrap for the F-Droid release procedure (runbook Step -1).
#
# Why this exists (2026-10-09, the 1.14.0 release from the Mac): the whole
# recipe half of the procedure silently assumed bird's checkout state, and a
# fresh clone on a new host landed on the fork's DEFAULT branch
# (com.antivocale.app), which is a 1.8.1-era FOSSIL nobody has pushed since
# the checkupdates-bot flow started. The mirror-sync guard then fired with
# misleading advice ("reset onto it") that would have resurrected the fossil,
# and the Mac needed two more host-specific fixes discovered mid-flow (BSD wc
# padding, already fixed in check-fdroid-release.sh; the https mirror push
# rejected in favor of SSH). This script makes every one of those a no-op
# decision: run it on any host, get the sanctioned state.
#
# What "sanctioned" means and why (all derivable, nothing to remember):
#   - The fork checkout sits on a LOCAL-ONLY recipe branch that origin does
#     NOT have. The bot era writes releases to fdroiddata master; the fork
#     branch is only pushed at finalize, and only when the bot has not done
#     the work (runbook Step 6/7b). A branch origin lacks therefore passes
#     the sync guard vacuously, by design.
#   - The branch name convention is anti-vocale-1.10.0 (frozen since the
#     1.10.0 era; the scripts key on the CURRENT branch, not the name).
#   - The pre-push hook (signed-APK-URLs gate) is installed in the clone.
#   - The mirror checkout's push URL is SSH: GitHub https token auth is not
#     configured on every host, SSH is (the app repo pushes via SSH).
#
# Idempotent: re-running on a bootstrapped host verifies and exits 0.
# Env: FORK_CHECKOUT (default ~/data/repo/personal/fdroid-data),
#      MIRROR_CHECKOUT (default ~/data/repo/personal/fdroid-data-mirror),
#      APP_REPO (default ~/data/repo/personal/anti-vocale).

set -euo pipefail

FORK_CHECKOUT="${FORK_CHECKOUT:-$HOME/data/repo/personal/fdroid-data}"
MIRROR_CHECKOUT="${MIRROR_CHECKOUT:-$HOME/data/repo/personal/fdroid-data-mirror}"
APP_REPO="${APP_REPO:-$HOME/data/repo/personal/anti-vocale}"
# The fork is fdroid-data (HYPHENATED; 2026-10-09: paoloantinori/fdroiddata
# without the hyphen is a different, near-empty duplicate whose default
# branch is a 1.8.1-era fossil - cloning it put the whole release on the
# wrong remote and cost an hour of archaeology)
FORK_URL="https://gitlab.com/paoloantinori/fdroid-data.git"
UPSTREAM_URL="https://gitlab.com/fdroid/fdroiddata.git"
MIRROR_SSH_URL="git@github.com:paoloantinori/fdroid-data-mirror.git"
RECIPE_REL="metadata/com.antivocale.app.yml"
RECIPE_BRANCH="anti-vocale-1.10.0"

say()  { printf '%s\n' "== $*"; }
fail() { printf 'FAIL: %s\n' "$*" >&2; exit 1; }

# 1. fork checkout: clone (https, read-only is fine) if missing
if [ ! -d "$FORK_CHECKOUT/.git" ]; then
  say "cloning the fork (read-only https) to $FORK_CHECKOUT"
  git clone "$FORK_URL" "$FORK_CHECKOUT"
fi
cd "$FORK_CHECKOUT"

# 2. remotes: origin = the fork (set only when missing: an existing origin
#    may carry working push credentials this script must not downgrade),
#    upstream = fdroiddata proper
git remote get-url origin >/dev/null 2>&1 || git remote add origin "$FORK_URL"
git remote get-url upstream >/dev/null 2>&1 || git remote add upstream "$UPSTREAM_URL"
[ "$(git remote get-url upstream)" = "$UPSTREAM_URL" ] || git remote set-url upstream "$UPSTREAM_URL"

# 3. fetch both sides; origin's branches are what the sync guard compares against
say "fetching origin and upstream/master"
git fetch -q origin
git fetch -q upstream master

# 4. refuse the fossil trap: if the checkout is on a branch origin HAS and
#    that branch's recipe is materially older than upstream/master's, that is
#    the 1.8.1-era fossil, not a maintainer lane. Say so and move off it.
CUR_BRANCH="$(git branch --show-current)"
[ -n "$CUR_BRANCH" ] || fail "detached HEAD in $FORK_CHECKOUT; check out $RECIPE_BRANCH"
if git show-ref --verify --quiet "refs/remotes/origin/$CUR_BRANCH" \
   && [ "$CUR_BRANCH" != "$RECIPE_BRANCH" ]; then
  # newest-versionName test (fossil-guard.sh's rule, quoted inline because
  # this script runs before any checkout exists to source against): an
  # origin branch whose newest recipe version is older than upstream's
  ORIGIN_NEWEST="$(git show "origin/$CUR_BRANCH:$RECIPE_REL" 2>/dev/null | grep -oE 'versionName: [0-9.]+' | awk '{print $2}' | sort -V | tail -1)"
  MASTER_NEWEST="$(git show "upstream/master:$RECIPE_REL" 2>/dev/null | grep -oE 'versionName: [0-9.]+' | awk '{print $2}' | sort -V | tail -1)"
  if [ -n "$ORIGIN_NEWEST" ] && [ -n "$MASTER_NEWEST" ] && [ "$ORIGIN_NEWEST" != "$MASTER_NEWEST" ] \
     && [ "$(printf '%s\n%s\n' "$ORIGIN_NEWEST" "$MASTER_NEWEST" | sort -V | tail -1)" = "$MASTER_NEWEST" ]; then
    say "branch '$CUR_BRANCH' exists on origin but its recipe is a fossil (newest $ORIGIN_NEWEST vs $MASTER_NEWEST)"
    say "  the bot era never pushes it; moving to the local-only $RECIPE_BRANCH lane"
  fi
fi

if [ "$CUR_BRANCH" != "$RECIPE_BRANCH" ]; then
  say "moving off branch '$CUR_BRANCH' to the sanctioned recipe lane $RECIPE_BRANCH (nothing is lost: branches stay)"
fi

# 5. the sanctioned lane: a LOCAL-ONLY branch based on upstream/master. Never
#    push it here: finalize owns the fork remote (runbook invariant). The
#    reset moves the branch FORWARD only: a branch carrying commits
#    upstream/master lacks is the legitimate pre-finalize state (the recipe
#    commit waiting for its push) and must NEVER be reset. Verified the hard
#    way on 2026-10-09: the first version of this script reset exactly that
#    state away and the commit had to come back from the reflog.
if git show-ref --verify --quiet "refs/heads/$RECIPE_BRANCH"; then
  git checkout "$RECIPE_BRANCH" 2>/dev/null || fail "cannot check out $RECIPE_BRANCH (uncommitted conflicting changes?): commit or stash in $FORK_CHECKOUT first"
  if [ -n "$(git status --porcelain)" ]; then
    say "$RECIPE_BRANCH tree is dirty: left exactly as-is (no reset)"
  elif git merge-base --is-ancestor "$RECIPE_BRANCH" upstream/master; then
    git reset --hard upstream/master >/dev/null
    say "$RECIPE_BRANCH was at or behind upstream/master: moved forward to it"
  else
    say "$RECIPE_BRANCH carries commits upstream/master lacks (pre-finalize recipe work?): left untouched"
  fi
else
  # If the current branch carries commits upstream/master lacks (a Step-4
  # recipe commit stranded on the fossil branch), the lane starts THERE so
  # nothing is stranded; otherwise it starts at upstream/master.
  if git merge-base --is-ancestor HEAD upstream/master; then
    git checkout -b "$RECIPE_BRANCH" upstream/master >/dev/null 2>&1
    say "created local-only $RECIPE_BRANCH at upstream/master (origin does not have it: intended)"
  else
    git checkout -b "$RECIPE_BRANCH" >/dev/null 2>&1
    say "created $RECIPE_BRANCH AT THE CURRENT COMMITS (they are ahead of upstream/master: mid-flow work carried over, not stranded)"
  fi
fi

# 6. pre-push hook (the chokepoint for premature recipe pushes): installed
#    whenever the file is missing or does not exec our script verbatim
H="$FORK_CHECKOUT/.git/hooks/pre-push"
HOOK_BODY="$(printf '#!/bin/sh\nexec "%s/scripts/fdroid-recipe-pre-push.sh" "$@"\n' "$APP_REPO")"
if [ "$(cat "$H" 2>/dev/null || true)" != "$HOOK_BODY" ]; then
  [ -f "$H" ] && cp "$H" "$H.bak.$(date +%s)"
  printf '%s\n' "$HOOK_BODY" > "$H"
  chmod +x "$H"
  say "pre-push hook installed"
else
  say "pre-push hook present"
fi

# 7. mirror checkout: exists, on av1100-slim, and pushes via SSH
if [ ! -d "$MIRROR_CHECKOUT/.git" ]; then
  if [ -e "$MIRROR_CHECKOUT" ]; then
    fail "$MIRROR_CHECKOUT exists but is not a git checkout (a stale dir would make the clone nest inside it); remove or rename it first"
  fi
  say "cloning the mirror (what the reproducible workflow builds from)"
  MIRROR_TMP="${MIRROR_CHECKOUT}.tmp.$$"
  git clone -b av1100-slim "$MIRROR_SSH_URL" "$MIRROR_TMP" \
    || git clone -b av1100-slim https://github.com/paoloantinori/fdroid-data-mirror.git "$MIRROR_TMP"
  mv "$MIRROR_TMP" "$MIRROR_CHECKOUT"
fi
MIRROR_BR="$(git -C "$MIRROR_CHECKOUT" branch --show-current)"
[ "$MIRROR_BR" = "av1100-slim" ] || fail "mirror is on '${MIRROR_BR:-detached}', not av1100-slim"
git -C "$MIRROR_CHECKOUT" remote set-url --push origin "$MIRROR_SSH_URL"
say "mirror on av1100-slim, push URL re-pointed to SSH (https tokens are not configured on every host)"

say "bootstrap complete: run scripts/new-fdroid-version.py and scripts/sync-fdroid-mirror.sh from $APP_REPO"
