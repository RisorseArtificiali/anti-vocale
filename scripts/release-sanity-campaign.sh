#!/usr/bin/env bash
# release-sanity-campaign.sh: TASK-683.3 AC3, the scratch-dispatch campaign that
# proves release-sanity goes RED when Build or tests fail (the 2026-09-26 live
# scenarios, replayed deliberately).
#
# Arm 1 (build-failure): a tag-mode dispatch pointing at a BOGUS tag. The build
#   job fails at checkout; release-sanity must FAIL with the "BUILD JOB" error.
#   Cost: one CI run that dies fast at the checkout step (no models fetched).
# Arm 2 (red-tests / 683.6): a scratch branch carrying one deliberately failing
#   unit test, dispatched in commit mode. The test job must FAIL while the
#   build stays green; release-sanity must FAIL on the build-test result too.
#   Cost: one CI run up to the test step.
#
# Both arms are scratch: the bogus tag exists nowhere, the scratch branch is
# deleted after the campaign. Nothing here touches a real release or Play.
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
  git fetch origin main --quiet
  local wt
  # /tmp is a symlink to /private/tmp on macOS, and git registers worktrees
  # under the symlink-resolved path: canonicalize FIRST or the collision
  # check false-fails on every reuse (TASK-879 round 2, verified on-device).
  wt="$(cd /tmp && pwd -P)/${SCRATCH_BRANCH//\//-}"
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
  git -C "$wt" push -q origin "HEAD:${SCRATCH_BRANCH}"
  echo ">> pushed ${SCRATCH_BRANCH}"
}

dispatch_red_test() {
  make_scratch_branch
  local sha
  sha=$(git ls-remote origin "${SCRATCH_BRANCH}" | cut -f1)
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
  echo "================ JOB RESULTS ================"
  gh run view "${run_id}" --repo "${REPO}" --json jobs --jq '.jobs[] | "\(.conclusion // .status)\t\(.name)"'
  echo "============================================="
  local sanity
  sanity=$(gh run view "${run_id}" --repo "${REPO}" --json jobs \
    --jq '.jobs[] | select(.name | startswith("Release sanity")) | .conclusion')
  echo ">> Release sanity conclusion: ${sanity}"
  if [ "${sanity}" = "failure" ]; then
    echo ">> PASS: sanity went red as the campaign requires (AC3)."
  else
    echo ">> FAIL: sanity did NOT go red. This is the green-lies bug alive; file it."
  fi
}

case "${1:-}" in
  arm1) dispatch_bogus_tag ;;
  arm2) dispatch_red_test ;;
  watch) shift; watch_run "$@" ;;
  *) sed -n '2,22p' "$0"; exit 2 ;;
esac
