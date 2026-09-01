#!/usr/bin/env bash
# One-command F-Droid reference flow, split in two phases around the ~45 min
# build. Every phase boundary is a gate that already exists as a script; this
# orchestrator only chains them and stops at the first red (exit nonzero).
#
#   prepare   mirror sync (with fork pull: gate A validates fresh state) ->
#             gate A (pre-dispatch checker) -> stale-asset cleanup -> dispatch
#   finalize  gate C (job success, signed URLs, fork==mirror, clean tree) ->
#             fork push if local recipe commits are pending -> pipeline status
#             for THIS recipe SHA (+ retry if a write token is configured)
#
# Usage: scripts/release-fdroid-references.sh {prepare|finalize} vX.Y.Z
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
usage() { echo "usage: $0 {prepare|finalize} vX.Y.Z" >&2; exit 2; }

[ $# -eq 2 ] || usage
PHASE="$1"
TAG="$2"
case "$PHASE" in prepare | finalize) ;; *) usage ;; esac

cd "$APP_REPO"

if [ "$PHASE" = "prepare" ]; then
  say "phase 1/4: mirror sync (pulls the fork first, so gate A sees fresh state)"
  DRY_RUN="${DRY_RUN:-0}" "$HERE/sync-fdroid-mirror.sh"

  say "phase 2/4: gate A (pre-dispatch checker, reads origin/main)"
  SKIP_BINARY_URLS=1 "$HERE/check-fdroid-release.sh" "$TAG" "$FORK_CHECKOUT"

  say "phase 3/4: stale-asset cleanup on release $TAG"
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

  say "phase 4/4: dispatch reference build"
  run gh workflow run android-release.yml -f tag="$TAG" -R "$REPO"
  say "monitor: https://github.com/$REPO/actions (reproducible job: 40-50 min)"
  say "when green: scripts/release-fdroid-references.sh finalize $TAG"
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
  run git -C "$FORK_CHECKOUT" push origin "$BR"
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
