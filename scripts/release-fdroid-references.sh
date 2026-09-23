#!/usr/bin/env bash
# One-command F-Droid reference flow, split in two phases around the ~3h
# build (the sherpa source compile for 3 ABIs measured 3h02m on v1.12.0 and
# 3h20m on v1.12.1; the old 25-50 min figures were stale). Every phase
# boundary is a gate that already exists as a script; this orchestrator only
# chains them and stops at the first red (exit nonzero).
#
#   prepare   mirror sync (fetches the fork; gate A validates local state) ->
#             gate A (pre-dispatch checker) -> stale-asset cleanup -> dispatch
#   finalize  gate C (job success, signed URLs, fork==mirror, clean tree) ->
#             fork push if local recipe commits are pending -> pipeline status
#             for THIS recipe SHA (+ retry if a write token is configured)
#
# Usage: scripts/release-fdroid-references.sh {prepare|finalize} vX.Y.Z [commit]
#   prepare vX.Y.Z <sha>   BUILD-FIRST (TASK-446, the default flow): dispatch
#                          `-f commit=<sha>` before any tag exists; the run
#                          uploads every release asset as artifacts. Afterwards
#                          scripts/release-create.sh vX.Y.Z <run-id> makes tag +
#                          release + binaries public in one act, then finalize.
#   prepare vX.Y.Z         LEGACY tag flow: dispatch `-f tag=vX.Y.Z` (the
#                          workflow creates the release itself). Kept as the
#                          fallback; the checkupdates bot can race it.
# Env:
#   DRY_RUN=1            print the side-effecting actions instead of running
#                        (exported to the sync script: its dry run is real too)
#   GL_TOKEN_WRITE=path  token file with Pipeline:Update scope; enables the
#                        GitLab retry. Without it the script prints the retry
#                        button URL and exits nonzero on a red pipeline.
#
# Why each gate (2026-08-31, all paid for once):
#   mirror     the workflow clones the MIRROR, not the fork; a mirror one
#              commit behind builds the wrong recipe
#   gate A     dispatching against a workflow whose NDK map lived only in the
#              working tree; the checker reads origin/main
#   cleanup    stale signed APKs left on the release become F-Droid's
#              binary: targets if anything reuses them
#   gate C     pushing the fork before the reference build finished is the
#              race that 404'd the fdroiddata pipeline
#   sha poll   a per-branch poll reads the PREVIOUS release's pipeline on a
#              long-lived recipe branch; the sha filter pins this release

set -euo pipefail

# TASK-633: this script's directory (the patterns module lives beside it).
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

HERE="$(cd "$(dirname "$0")" && pwd)"
APP_REPO="$(cd "$HERE/.." && pwd)"
REPO="RisorseArtificiali/anti-vocale"
GL_PROJECT="paoloantinori%2Ffdroid-data"
FORK_CHECKOUT="${FORK_CHECKOUT:-$HOME/data/repo/personal/fdroid-data}"
RECIPE_REL="metadata/com.antivocale.app.yml"
GL_TOKEN_READ="${GL_TOKEN_READ:-$HOME/.config/gl-token}"

say() { echo "== $*"; }
fail() { echo "FAIL: $*" >&2; exit 1; }
run() {
  if [ "${DRY_RUN:-0}" = "1" ]; then echo "DRY: $*"; else "$@"; fi
}
usage() { echo "usage: $0 {prepare vX.Y.Z [commit] | finalize vX.Y.Z}" >&2; exit 2; }

PHASE="${1:-}"
TAG="${2:-}"
COMMIT="${3:-}"
[ -n "$PHASE" ] && [ -n "$TAG" ] || usage
[ "$#" -le 3 ] || usage
case "$PHASE" in prepare | finalize) ;; *) usage ;; esac
# finalize takes no commit arg: by then tag + release exist (release-create.sh
# ran), and gate C resolves everything from the tag.
[ "$PHASE" = "finalize" ] && [ -n "$COMMIT" ] && usage
# A short hash fails the workflow's checkout-by-name two refspecs and burns a
# ~3h dispatch (the 1.13.0 first-dispatch incident): require the full SHA.
if [ -n "$COMMIT" ] && ! printf '%s' "$COMMIT" | grep -qE '^[0-9a-f]{40}$'; then
  echo "FAIL: commit must be a full 40-char SHA, got '$COMMIT'" >&2
  exit 1
fi

cd "$APP_REPO"

if [ "$PHASE" = "prepare" ]; then
  say "phase 1/4: mirror sync (fetches the fork; the remote is written only at finalize)"
  DRY_RUN="${DRY_RUN:-0}" "$HERE/sync-fdroid-mirror.sh"

  say "phase 2/4: gate A (pre-dispatch checker, reads origin/main)"
  # EXPECT_COMMIT set = build-first: the recipe trio must point at the
  # dispatched SHA; the tag is not required to exist (and must agree if it
  # does). Empty in the legacy flow; the prefix shadows any inherited value.
  SKIP_BINARY_URLS=1 EXPECT_COMMIT="${COMMIT:-}" "$HERE/check-fdroid-release.sh" "$TAG" "$FORK_CHECKOUT"

  say "phase 3/4: stale-asset cleanup on release $TAG"
  # Build-first normally has no release yet (gate A accepts an absent tag OR
  # one already at the dispatched SHA), so skip the asset probes in that mode.
  # Re-prepare AFTER publishing must go through release-create.sh guard 1,
  # which refuses an existing tag; the unconditional legacy cleanup below
  # covers re-dispatches of an already-published release.
  if [ -n "$COMMIT" ]; then
    say "build-first: skipping stale-asset probes (no release expected before the publish act)"
  else
    # one snapshot, and a loud failure if the listing itself breaks: a silent
    # empty list would skip the cleanup (the stale APKs would stay and become
    # F-Droid's binary: targets, the exact incident this phase exists for).
    # Match by pattern on the REAL names: an ABI added to the workflow must not
    # depend on a second list here being updated too.
    # A MISSING release is the normal first-dispatch state (the workflow creates
    # it via softprops/action-gh-release when uploading): nothing can be stale
    # on a release that does not exist yet. Distinguish by HTTP code so a broken
    # gh auth still fails loudly instead of masquerading as a fresh release
    # (found on v1.11.1's first dispatch).
    if ! ASSETS="$(gh release view "$TAG" -R "$REPO" --json assets --jq '.assets[].name' 2>/dev/null)"; then
      # capture first: under pipefail the api|grep pipeline would inherit gh's
      # exit 1 even when the grep matches.
      api_msg="$(gh api "repos/$REPO/releases/tags/$TAG" 2>&1 || true)"
      if grep -q "Not Found (HTTP 404)" <<<"$api_msg"; then
        say "release $TAG does not exist yet (first dispatch): nothing to clean"
      else
        fail "cannot list assets of release $TAG (gh auth/release problem)"
      fi
      ASSETS=""
    fi
    STALE="$(grep -E '^app-fdroid-.*-release(-unsigned)?\.apk$' <<<"$ASSETS" || true)"
    if [ -n "$STALE" ]; then
      while IFS= read -r asset; do
        run gh release delete-asset "$TAG" "$asset" -R "$REPO" --yes
      done <<<"$STALE"
    else
      say "no stale app-fdroid assets on $TAG"
    fi
  fi

  say "phase 4/4: dispatch reference build"
  if [ -n "$COMMIT" ]; then
    run gh workflow run android-release.yml -f commit="$COMMIT" -R "$REPO"
  else
    run gh workflow run android-release.yml -f tag="$TAG" -R "$REPO"
  fi
  say "monitor: https://github.com/$REPO/actions (reproducible job: ~3h, sherpa compiles from source for 3 ABIs)"
  if [ -n "$COMMIT" ]; then
    # Best-effort run-id capture for release-create.sh (its --run-id is
    # explicit because dispatch inputs are not queryable afterwards). The
    # listing can lag the dispatch; if the poll misses, the manual gh run
    # list below is the fallback. Skipped under DRY_RUN: no dispatch happened,
    # so the listing would return the PREVIOUS release's run id.
    if [ "${DRY_RUN:-0}" = "1" ]; then
      say "DRY: would poll for the dispatch run id (gh run list --event workflow_dispatch --limit 1)"
    else
      RUN_ID=""
      for _ in 1 2 3 4 5; do
        sleep 3
        RUN_ID=$(gh run list --workflow=android-release.yml --event workflow_dispatch \
          --limit 1 --json databaseId --jq '.[0].databaseId' 2>/dev/null || true)
        [ -n "$RUN_ID" ] && break
      done
      say "dispatch run id: ${RUN_ID:-NOT CAPTURED (gh run list --event workflow_dispatch --limit 1)}"
    fi
    say "when green: scripts/release-create.sh $TAG --run-id <id> --commit $COMMIT"
    say "then: scripts/release-fdroid-references.sh finalize $TAG"
  else
    say "when green: scripts/release-fdroid-references.sh finalize $TAG"
  fi
  exit 0
fi

# ------------------------------ finalize -----------------------------------

say "phase 1/3: gate C (job success, signed URLs, fork==mirror, clean tree)"
"$HERE/verify-github-workflow-before-recipe-push.sh" "$TAG"

# BOT-FIRST (TASK-525, the v1.12.1 lesson): with build-first ordering the
# tag and its signed assets appear atomically, so fdroiddata's checkupdates
# bot can land this release's recipe on master directly (it did: commit
# 97972ae793 for 1.12.1). When master's recipe content already equals ours,
# the fork push and the MR are a no-op: skip them instead of pushing a
# branch nobody needs to merge. A real difference (typically a stale srclib
# pin copied forward by the bot after a sherpa bump) falls through to the
# normal push+MR path as the correction.
#
# The comparison is ONE raw-file GET (30KB), not a git fetch of fdroiddata
# master into this checkout: finalize gets re-run while watching pipelines,
# and each re-run would otherwise pay a full-repo catch-up fetch.
#
# Normalization: the two inert lines new-fdroid-version.py strips from NEW
# blocks (the sdkmanager r27c prebuild line and the dead `zip` apt entry)
# are cosmetic, and the bot clones blocks VERBATIM, so a bot-landed master
# keeps carrying them while our generated blocks do not. A raw diff would
# then never match again; stripping both known-cosmetic patterns from BOTH
# sides keeps the skip reachable without widening it to version fields or
# any build-relevant line.
normalize_recipe() {
  # TASK-633: the inert-line list has one owner, scripts/fdroid_recipe_patterns.py
  # (shared with new-fdroid-version.py); a divergence there breaks this
  # comparison silently. NOTE -c (not a heredoc): a `python3 - <<PY` heredoc
  # makes the SCRIPT the stdin, so sys.stdin.read() returns empty and both
  # sides normalize to "" (the comparison would always match, green no-op).
  python3 -c "
import re, sys
sys.path.insert(0, '${SCRIPT_DIR}')
from fdroid_recipe_patterns import INERT_LINE_PATTERNS, INERT_LITERAL_REPLACES
text = sys.stdin.read()
for _, pattern in INERT_LINE_PATTERNS:
    text = re.sub(pattern, '', text, flags=re.M)
for _, old_lit, new_lit in INERT_LITERAL_REPLACES:
    text = text.replace(old_lit, new_lit)
sys.stdout.write(text)
"
}
MASTER_RAW=""
# Anonymous API against fdroid/fdroiddata (note: no hyphen; the fork is
# fdroid-data, the upstream is fdroiddata): the /-/raw/ endpoint answers
# 403-sign_in to plain curl (bot detection), while the API serves the same
# bytes anonymously, so no token is needed for this read.
MASTER_RAW="$(curl -sfL --max-time 30 \
  "https://gitlab.com/api/v4/projects/fdroid%2Ffdroiddata/repository/files/$(printf '%s' "$RECIPE_REL" | sed 's|/|%2F|g')/raw?ref=master" \
  || true)"
if [ -n "$MASTER_RAW" ]; then
  if diff -q <(normalize_recipe <<<"$MASTER_RAW") \
             <(normalize_recipe < "$FORK_CHECKOUT/$RECIPE_REL") >/dev/null; then
    say "fdroiddata master already carries this recipe content (the bot landed it): no fork push, no MR needed"
    say "watch the fdroiddata master pipeline for the build of $TAG"
    exit 0
  fi
else
  say "warning: cannot fetch fdroiddata master's recipe for the bot-first check; continuing with the normal push path"
fi

say "phase 2/3: fork push (only if local recipe commits are pending)"
BR="$(git -C "$FORK_CHECKOUT" branch --show-current)"
[ -n "$BR" ] || fail "fork checkout is on a detached HEAD; check out the recipe branch first"
# 2026-09-21 incident: the checkout sat on another app's branch (cookies-
# extractor-1.0.0) and finalize happily force-pushed this release's recipe
# onto that app's MR. The lane rule is the app PREFIX, not the exact
# version: an anti-vocale-<X.Y.Z> branch of a DIFFERENT version is the
# documented state while an MR is still open when the next version drops
# (fdroid maintainers ask for the newer bump pushed to the SAME branch;
# docs/research/2026-09-01-fdroiddata-branch-conventions.md section 4), so
# only non-anti-vocale lanes are refused. No remediation command printed:
# creating the branch here would cut it from whatever HEAD the drifted
# checkout sits on; the runbook (step 4) owns the reset-onto-upstream dance.
EXPECTED_BR="anti-vocale-${TAG#v}"
case "$BR" in
  anti-vocale-*) ;;
  *) fail "fork checkout is on branch '$BR' but this release pushes an anti-vocale-* lane (expected '$EXPECTED_BR' or the open-MR branch); see docs/release-runbook.md" ;;
esac
git -C "$FORK_CHECKOUT" fetch -q origin
LOCAL_SHA="$(git -C "$FORK_CHECKOUT" rev-parse HEAD)"
REMOTE_SHA="$(git -C "$FORK_CHECKOUT" rev-parse -q --verify "origin/$BR" || true)"
if [ -n "$REMOTE_SHA" ] && [ "$LOCAL_SHA" = "$REMOTE_SHA" ]; then
  say "fork branch $BR already pushed, nothing to do"
else
  # The recipe branch is reset onto fdroid/master every release (runbook
  # Step 4), and fdroiddata SQUASH-merges MRs, so origin's tip is never an
  # ancestor of the rebuilt branch: this push is NON-FF by design and needs
  # the lease. Ordering invariant (2026-09-01 incident): the fork remote is
  # written ONLY here, after gate C proved the signed APKs exist. Two guards
  # keep the force safe:
  #  - refuse when origin's recipe carries lines this checkout lacks
  #    (a maintainer's edits, !47391-style, anywhere in the file). Directional
  #    on content, not commits: squash-merges break ancestry (an ancestor test
  #    would deadlock the next release on the already-merged old tip); new local
  #    blocks never appear as origin-side additions (the CurrentVersion fields
  #    are machine-managed and exempt)
  #  - --force-with-lease covers the fetch-to-push race (origin moving in
  #    between), the one window the content check above cannot see
  #  Same guard as sync-fdroid-mirror.sh and gate C; keep the copies aligned.
  FILTER_AWK='/^CurrentVersion(Code)?:/{next} 1'
  # capture, then grep: under pipefail the old `diff | grep -q` form could
  # NEVER fire (a real origin-side difference makes diff exit 1 and pipefail
  # surfaces that regardless of grep's match: the guard was dead code and a
  # maintainer edit would have sailed through). Capturing reads to EOF, so
  # only the grep verdict decides.
  ORIGIN_EXTRA="$(diff -u \
    <(git -C "$FORK_CHECKOUT" show "HEAD:$RECIPE_REL" | awk "$FILTER_AWK") \
    <(git -C "$FORK_CHECKOUT" show "origin/$BR:$RECIPE_REL" 2>/dev/null | awk "$FILTER_AWK"))" || true
  if grep -qE '^\+[^+]' <<<"$ORIGIN_EXTRA"; then
    fail "origin/$BR's recipe has content this checkout lacks (maintainer edits?): reset onto it and re-run scripts/new-fdroid-version.py; pushing now would discard it"
  fi
  run git -C "$FORK_CHECKOUT" push --force-with-lease origin "$BR"
  if [ "${DRY_RUN:-0}" = "1" ]; then
    say "DRY: push skipped; the pipeline state below is PRE-PUSH"
  else
    say "pushed $BR; the fdroiddata pipeline starts from this push"
  fi
fi

say "phase 3/3: fdroiddata pipeline status (filtered by this recipe SHA)"
if [ ! -f "$GL_TOKEN_READ" ]; then
  fail "read token $GL_TOKEN_READ missing (pipeline polling)"
fi
# sha-pinned poll: the recipe branch is long-lived across releases, so a
# per-branch poll would return the PREVIOUS release's pipeline (including a
# stale green "flow complete") until GitLab registers the new push
PIPE_JSON="$(curl -sS --max-time 20 \
  --header "PRIVATE-TOKEN: $(cat "$GL_TOKEN_READ")" \
  "https://gitlab.com/api/v4/projects/$GL_PROJECT/pipelines?ref=$BR&sha=$LOCAL_SHA&per_page=1")" \
  || fail "gitlab.com unreachable (pipeline poll)"
# GitLab answers an error OBJECT (not an array) on 403/404: detect it before
# jq's .[0] indexing dies with a type error that names neither token nor scope
if echo "$PIPE_JSON" | jq -e 'type == "array"' >/dev/null; then
  PL_ID="$(echo "$PIPE_JSON" | jq -r '.[0].id // empty')"
  PL_ST="$(echo "$PIPE_JSON" | jq -r '.[0].status // empty')"
  PL_URL="$(echo "$PIPE_JSON" | jq -r '.[0].web_url // empty')"
else
  fail "GitLab API error: $(echo "$PIPE_JSON" | jq -r '.message // .') (check $GL_TOKEN_READ and project access)"
fi

if [ -z "$PL_ID" ]; then
  say "no pipeline yet for $BR @ ${LOCAL_SHA:0:9} (it starts from the fork push above)"
  exit 0
fi
echo "   pipeline $PL_ID: $PL_ST"
echo "   $PL_URL"

case "$PL_ST" in
  success)
    say "pipeline green; F-Droid release flow complete (merge is the admins')"
    ;;
  running | pending | created | manual | preparing)
    say "pipeline still $PL_ST; re-run finalize later, or watch $PL_URL"
    ;;
  *)
    if [ -n "${GL_TOKEN_WRITE:-}" ] && [ -f "$GL_TOKEN_WRITE" ]; then
      if [ "${DRY_RUN:-0}" = "1" ]; then
        say "DRY: would retry pipeline $PL_ID"
      else
        RETRY_JSON="$(curl -sS --max-time 20 --request POST \
          --header "PRIVATE-TOKEN: $(cat "$GL_TOKEN_WRITE")" \
          "https://gitlab.com/api/v4/projects/$GL_PROJECT/pipelines/$PL_ID/retry")" \
          || fail "retry request failed (network)"
        echo "$RETRY_JSON" | jq -e 'has("id")' >/dev/null \
          && say "retry sent for pipeline $PL_ID; re-run finalize to watch it" \
          || fail "GitLab refused the retry: $(echo "$RETRY_JSON" | jq -r '.message // .')"
      fi
    else
      say "pipeline $PL_ST; API retry needs GL_TOKEN_WRITE (Pipeline:Update scope)"
      echo "   Retry button: $PL_URL"
      exit 1
    fi
    ;;
esac
