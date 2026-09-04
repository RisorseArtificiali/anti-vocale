#!/usr/bin/env bash
# One-command F-Droid reference flow, split in two phases around the ~45 min
# build. Every phase boundary is a gate that already exists as a script; this
# orchestrator only chains them and stops at the first red (exit nonzero).
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
  say "monitor: https://github.com/$REPO/actions (reproducible job: 40-50 min)"
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

say "phase 2/3: fork push (only if local recipe commits are pending)"
BR="$(git -C "$FORK_CHECKOUT" branch --show-current)"
[ -n "$BR" ] || fail "fork checkout is on a detached HEAD; check out the recipe branch first"
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
