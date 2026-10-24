#!/usr/bin/env bash
# release-sanity-campaign.sh: TASK-683.3 AC3, the scratch-dispatch campaign that
# proves release-sanity goes RED when Build or tests fail (the 2026-09-26 live
# scenarios, replayed deliberately).
#
# Arm 1 (build-failure): a tag-mode dispatch pointing at a BOGUS tag. The build
#   job fails at checkout; release-sanity must FAIL with the "BUILD JOB" error.
#   Cost: the full matrix minutes (the parallel test job runs to its own end).
# Arm 2 (red-tests / 683.6): a scratch branch carrying one deliberately failing
#   unit test, dispatched in commit mode. The test job must FAIL while the
#   build stays green; release-sanity must FAIL on the build-test result too.
#   Cost: the complete fdroid release + tester-APK builds.
#
# Both arms are scratch: the bogus tag exists nowhere, the scratch branch is
# deleted after the campaign. Nothing here touches a real release or Play.
# Both pay full matrix minutes: test and build run in parallel with no
# needs-ordering (the test lane fetches the sherpa AAR and runs the fdroid
# suite + lint regardless of how the build lane dies).
#
# Usage:
#   scripts/release-sanity-campaign.sh arm1        # bogus-tag dispatch, then watch
#   scripts/release-sanity-campaign.sh arm2        # scratch branch + red test, then watch
#   scripts/release-sanity-campaign.sh watch <run-id>
#
# Env: REPO (default RisorseArtificiali/anti-vocale)

set -euo pipefail

REPO="${REPO:-RisorseArtificiali/anti-vocale}"
WF="android-release.yml"
SCRATCH_BRANCH="ci/red-test-scratch"

bogus_tag="v0.0.0-sanity-campaign-$(date +%s)"

# Capture THIS dispatch's run id (TASK-879): the dispatch instant (backdated
# 2s against clock skew) lower-bounds createdAt, --event excludes push/schedule
# runs, and a bounded poll rides out empty listings and transient gh errors
# (3 consecutive failures abort with their own message). Residual race,
# documented: two near-simultaneous dispatches by the SAME operator resolve to
# whichever run the listing shows as newest; this is a single-operator tool,
# and the old newest-run scrape raced EVERYONE.
capture_run_id() {
  local since="${1:?dispatch instant, UTC}"
  local run_id errors=0
  for _ in $(seq 1 30); do
    if run_id=$(gh run list --repo "${REPO}" --workflow "${WF}" \
        --event workflow_dispatch --created ">=${since}" --limit 1 \
        --json databaseId --jq '.[0].databaseId // empty'); then
      errors=0
    else
      errors=$((errors + 1))
      if [ "${errors}" -ge 3 ]; then
        echo "FAIL: gh run list failed ${errors} times in a row (auth/network?); the dispatched run's id is orphaned; find it at https://github.com/${REPO}/actions" >&2
        return 1
      fi
      sleep 2
      continue
    fi
    if [ -n "${run_id}" ]; then
      echo "${run_id}"
      return 0
    fi
    sleep 2
  done
  echo "FAIL: no workflow_dispatch run for ${WF} appeared within ~60s after ${since} (${REPO})" >&2
  return 1
}

# UTC instant 2s in the past, portable across BSD and GNU date (BSD -r epoch;
# GNU -d @epoch): the backdate absorbs clock skew so a local clock a beat
# ahead of GitHub's never filters our own run out of the capture.
capture_since() {
  local epoch
  epoch=$(( $(date -u +%s) - 2 ))
  date -u -r "${epoch}" +%Y-%m-%dT%H:%M:%SZ 2>/dev/null \
    || date -u -d "@${epoch}" +%Y-%m-%dT%H:%M:%SZ
}

dispatch_bogus_tag() {
  echo ">> dispatching tag-mode with bogus tag ${bogus_tag}"
  local since run_id
  since="$(capture_since)"
  # The runbook's dispatch path (gh workflow run) shapes the inputs correctly.
  gh workflow run "${WF}" --repo "${REPO}" --ref main -f "tag=${bogus_tag}"
  run_id=$(capture_run_id "${since}")
  echo ">> run: https://github.com/${REPO}/actions/runs/${run_id}"
  echo ">> expected: Build=FAILURE (checkout on a tag that does not exist),"
  echo ">>           Release sanity=FAILURE with 'BUILD JOB' in its log."
  echo ">> watch: scripts/release-sanity-campaign.sh watch ${run_id}"
}

make_scratch_branch() {
  echo ">> creating scratch branch ${SCRATCH_BRANCH} with one failing test"
  # TASK-884: --prune (full fetch, no pathspec) keeps the scratch branch's
  # remote-tracking ref honest for the --force-with-lease below: a remote
  # branch deleted out of band would otherwise leave a stale tracking ref
  # that trips the lease with a raw git error instead of this campaign's
  # own recovery messaging.
  git fetch --prune --quiet origin
  local wt
  # /tmp is a symlink to /private/tmp on macOS, and git registers worktrees
  # under the symlink-resolved path: canonicalize FIRST or the collision
  # check false-fails on every reuse (TASK-879 round 2, verified on-device).
  wt="$(cd /tmp && pwd -P)/${SCRATCH_BRANCH//\//-}"
  # TASK-884 F1: a dir deleted under /tmp while the .git registration lives
  # (periodic cleanup) makes a bare 'git worktree add' die with a raw
  # 'missing but already registered' fatal BEFORE the recovery branches
  # below, which all require the dir to exist. Prune first so the stale
  # registration is gone and the add genuinely creates the worktree.
  git worktree prune
  # Reuse a leftover worktree from a previous campaign; a FAILED add must say
  # so (the old '2>/dev/null || true' swallowed fetch/permissions/corrupt
  # failures, TASK-879), and a leftover dir that is NOT our registered
  # worktree, or cannot be reset, names the recovery path instead of dying
  # mid-flow with a file-local error.
  if [ ! -d "$wt" ]; then
    git worktree add "$wt" origin/main
  elif ! git worktree list --porcelain | grep -qFx "worktree ${wt}"; then
    echo "FAIL: ${wt} exists but is not this repo's worktree (foreign collision); inspect it, then recover with: git worktree remove --force \"${wt}\"" >&2
    exit 1
  fi
  if ! checkout_out=$(git -C "$wt" checkout -B "$SCRATCH_BRANCH" origin/main 2>&1); then
    echo "FAIL: cannot reset the worktree at ${wt} to ${SCRATCH_BRANCH}. Causes: a dirty leftover tree, or ${SCRATCH_BRANCH} is checked out in another worktree. Git said:" >&2
    echo "${checkout_out}" >&2
    echo "recover: git worktree remove --force \"${wt}\" (and drop the stray checkout elsewhere if one exists)" >&2
    exit 1
  fi
  mkdir -p "$wt/app/src/test/java/com/antivocale/app/ci"
  cat > "$wt/app/src/test/java/com/antivocale/app/ci/ScratchRedTest.kt" <<'KOTLIN'
package com.antivocale.app.ci

import org.junit.Assert.assertTrue
import org.junit.Test

/** TASK-683.3 arm 2: a deliberately failing test. Never merge this branch. */
class ScratchRedTest {
    @Test fun `deliberately failing for the sanity campaign`() {
        assertTrue("intentional failure: release-sanity must go red", false)
    }
}
KOTLIN
  git -C "$wt" add app/src/test/java/com/antivocale/app/ci/ScratchRedTest.kt
  git -C "$wt" -c user.name="Paolo Antinori" -c user.email="pantinor@redhat.com" \
    commit -q -m "ci: scratch red test (TASK-683.3 arm 2; never merge)"
  # TASK-884 F2: checkout -B resets the branch onto a fresh origin/main tip
  # and the scratch commit above is created from scratch, so this push is a
  # non-fast-forward whenever the previous campaign's remote branch was not
  # deleted by hand. The branch is deliberately disposable, so force is the
  # correct semantics; --force-with-lease confines it to this branch (same
  # guard family as release-fdroid-references.sh and sync-fdroid-mirror.sh;
  # keep the sites aligned).
  git -C "$wt" push -q --force-with-lease origin "HEAD:${SCRATCH_BRANCH}"
  echo ">> pushed ${SCRATCH_BRANCH}"
}

dispatch_red_test() {
  make_scratch_branch
  # Worktrees share refs, so the branch tip the worktree just pushed is
  # resolvable locally and free; an ls-remote here costs a network round
  # trip to learn what rev-parse already knows (TASK-884).
  local sha
  sha=$(git rev-parse "${SCRATCH_BRANCH}")
  echo ">> dispatching commit mode from ${sha:0:12}"
  local since run_id
  since="$(capture_since)"
  gh workflow run "${WF}" --repo "${REPO}" --ref "${SCRATCH_BRANCH}" -f "commit=${sha}"
  run_id=$(capture_run_id "${since}")
  echo ">> run: https://github.com/${REPO}/actions/runs/${run_id}"
  echo ">> expected: tests=FAILURE, build-lane green until then, Release"
  echo ">>           sanity=FAILURE. Afterward delete the branch:"
  echo ">>           git push origin --delete ${SCRATCH_BRANCH}"
  echo ">> watch: scripts/release-sanity-campaign.sh watch ${run_id}"
}

watch_run() {
  local run_id="${1:?run id}"
  gh run watch "${run_id}" --repo "${REPO}" --exit-status || true
  # gh's bundled --jq only: an external jq is NOT a macOS default, and this
  # script must run on a stock maintainer box (TASK-884 review).
  echo "================ JOB RESULTS ================"
  gh run view "${run_id}" --repo "${REPO}" --json jobs --jq '.jobs[] | "\(.conclusion // .status)\t\(.name)"'
  echo "============================================="
  local sanity
  sanity=$(gh run view "${run_id}" --repo "${REPO}" --json jobs \
    --jq '.jobs[] | select(.name | startswith("Release sanity")) | .conclusion')
  echo ">> Release sanity conclusion: ${sanity:-<job never reported>}"
  # TASK-884 F4: branch on the conclusion; job-verdict-not-run-verdict per
  # release-run-verdict.sh (not reused: that script asserts success and
  # hardcodes the signing-job markers; this one asserts failure). Only a
  # concluded FAILURE proves AC3; cancelled/skipped/null (jq -r's literal
  # output for a conclusion that does not exist yet, e.g. a run still
  # executing when the view was taken)/empty all mean the job has not
  # reported, which is a rerun, not the green-lies bug, and telling the
  # operator to file one chases a phantom.
  case "${sanity}" in
    failure)
      echo ">> PASS: sanity went red as the campaign requires (AC3)." ;;
    cancelled|skipped|null|"")
      echo ">> INCONCLUSIVE: the run ended before Release sanity could report (${sanity:-no conclusion}). Re-run the arm; nothing is proven either way." ;;
    *)
      echo ">> FAIL: sanity concluded '${sanity}', not failure. If this is a green conclusion on a red arm, this is the green-lies bug alive; file it." ;;
  esac
}

case "${1:-}" in
  arm1) dispatch_bogus_tag ;;
  arm2) dispatch_red_test ;;
  watch) shift; watch_run "$@" ;;
  *) sed -n '2,/^$/p' "$0" | sed '$d'; exit 2 ;;
esac
