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

dispatch_bogus_tag() {
  echo ">> dispatching tag-mode with bogus tag ${bogus_tag}"
  local run_id
  # The runbook's dispatch path (gh workflow run) shapes the inputs correctly.
  gh workflow run "${WF}" --repo "${REPO}" --ref main -f "tag=${bogus_tag}"
  sleep 5
  run_id=$(gh run list --repo "${REPO}" --workflow "${WF}" --limit 1 \
    --json databaseId,createdAt --jq '.[0].databaseId')
  echo ">> run: https://github.com/${REPO}/actions/runs/${run_id}"
  echo ">> expected: Build=FAILURE (checkout on a tag that does not exist),"
  echo ">>           Release sanity=FAILURE with 'BUILD JOB' in its log."
  echo ">> watch: scripts/release-sanity-campaign.sh watch ${run_id}"
}

make_scratch_branch() {
  echo ">> creating scratch branch ${SCRATCH_BRANCH} with one failing test"
  git fetch origin main --quiet
  local wt="/tmp/${SCRATCH_BRANCH//\//-}"
  git worktree add "$wt" origin/main 2>/dev/null || true
  git -C "$wt" checkout -B "$SCRATCH_BRANCH" origin/main --quiet
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
  local run_id
  gh workflow run "${WF}" --repo "${REPO}" --ref "${SCRATCH_BRANCH}" -f "commit=${sha}"
  sleep 5
  run_id=$(gh run list --repo "${REPO}" --workflow "${WF}" --limit 1 \
    --json databaseId --jq '.[0].databaseId')
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
  *) sed -n '2,30p' "$0"; exit 2 ;;
esac
