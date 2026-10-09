#!/usr/bin/env bash
# The ONE fossil-vs-maintainer-edit discriminator, sourced by the three
# recipe guards (sync-fdroid-mirror.sh, release-fdroid-references.sh,
# verify-github-workflow-before-recipe-push.sh). The 2026-10-09 lesson this
# encodes: hand-copied stanzas drifted within 48 hours (an unbound variable
# in one copy crashed the guard; awk braces diverged in two others), so the
# test lives HERE and the callers stay thin.
#
# Contract: caller sets FORK_CHECKOUT (repo root), GUARD_BRANCH (the branch
# whose origin-side recipe we compare), GUARD_RECIPE (recipe path relative to
# the checkout), and provides fail() (the caller's exit-with-message helper).
# Returns 0 normally; calls fail() when origin's branch is a fossil.
#
# The test, and why NOT block count: the fork's DEFAULT branch is a
# 1.8.1-era fossil nobody pushes since the checkupdates-bot flow started
# (the bot writes releases to fdroiddata master; the fork branch is written
# only at finalize, and only when the bot has not done the work). A fossil
# has an OLDER newest versionName than ours. A maintainer edit that REMOVES
# a build block (!47391-style; fdroid maintainers prune failing builds) has
# a lower block COUNT but the same newest versionName: count-based tests
# misclassify real maintainer deletions as fossils and would discard them.

fossil_guard() {
  local origin_newest our_newest
  origin_newest="$(git -C "$FORK_CHECKOUT" show "origin/$GUARD_BRANCH:$GUARD_RECIPE" 2>/dev/null \
    | grep -oE 'versionName: [0-9.]+' | awk '{print $2}' | sort -V | tail -1)"
  our_newest="$(git -C "$FORK_CHECKOUT" show "HEAD:$GUARD_RECIPE" 2>/dev/null \
    | grep -oE 'versionName: [0-9.]+' | awk '{print $2}' | sort -V | tail -1)"
  [ -n "$origin_newest" ] && [ -n "$our_newest" ] || return 0
  [ "$origin_newest" = "$our_newest" ] && return 0
  [ "$(printf '%s\n%s\n' "$origin_newest" "$our_newest" | sort -V | tail -1)" = "$our_newest" ] || return 0
  fail "origin/$GUARD_BRANCH's recipe is a FOSSIL (newest $origin_newest vs our $our_newest): the bot era never pushes it. Run scripts/release-fork-bootstrap.sh, then re-run Step 4 (new-fdroid-version.py) so the recipe commit rides the sanctioned lane; do NOT reset onto the fossil"
}
