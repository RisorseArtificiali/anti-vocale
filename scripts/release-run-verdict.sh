#!/usr/bin/env bash
# Asserts a dispatched android-release run actually SIGNED the reference APKs.
#
# Why this exists (2026-10-09, the 1.14.0 first dispatch, run 37921765037):
# the run concluded SUCCESS while the reproducible signing job was SKIPPED
# (the TASK-683.8 decision job ran only on tag-carrying dispatches; a skipped
# need skips its dependents whatever their `if` says). `gh run watch
# --exit-status` exits 0 on that run: a skipped job is not a failure, so the
# run-level conclusion stays green. The only guards were a human reading the
# job list, and release-create's 12-artifact check hours later. This script
# is the deterministic early check: the job-level verdict, not the run-level.
#
# Jobs are matched by stable name MARKERS ("reference APKs" for the signing
# job, "Decide whether" for the skip decision), not byte-exact display names:
# a cosmetic workflow rename must not turn every green run into a FAIL (gate
# C matches by substring for the same reason).
#
# Usage:
#   scripts/release-run-verdict.sh <run-id> [--wait <max-min>] [--allow-skip]
#
# Exit codes:
#   0 = the run signs (job queued/in_progress/succeeded, or legitimately
#       skipped with --allow-skip, the post-publish dedup case)
#   1 = VERDICT FAIL: the run will not / did not sign (with remediation)
#   2 = undecided yet (signing job not created; re-invoke later)
#   3 = could not judge (usage error or gh read error; not a workflow fault)

set -euo pipefail

REPO="RisorseArtificiali/anti-vocale"
RUN_ID=""
WAIT_MIN=0
ALLOW_SKIP=0
while [ $# -gt 0 ]; do
  case "$1" in
    --wait) WAIT_MIN="${2:?--wait needs minutes}"; shift 2 ;;
    --allow-skip) ALLOW_SKIP=1; shift ;;
    -h|--help) sed -n '2,/^$/p' "$0" | sed '$d'; exit 0 ;;
    -*) echo "unknown flag: $1" >&2; exit 3 ;;
    *) if [ -n "$RUN_ID" ]; then echo "unexpected argument: $1" >&2; exit 3; fi
       RUN_ID="$1"; shift ;;
  esac
done
[ -n "$RUN_ID" ] || { echo "usage: $0 <run-id> [--wait <max-min>] [--allow-skip]" >&2; exit 3; }

# one gh round trip per poll: run status + both job cells, jq-extracted
VERDICT_JQ='
  .status as $rs
  | ([.jobs[] | select(.name | contains("reference APKs"))]) as $sign
  | ([.jobs[] | select(.name | contains("Decide whether"))]) as $dec
  | [$rs,
     ($sign[0].status // "absent"), ($sign[0].conclusion // "none"),
     ($dec[0].status // "absent"), ($dec[0].conclusion // "none")]
  | @tsv'

deadline=$(( $(date +%s) + WAIT_MIN * 60 ))
while :; do
  if ! fields="$(gh run view "$RUN_ID" -R "$REPO" --json status,conclusion,jobs --jq "$VERDICT_JQ" 2>/dev/null)"; then
    echo "FAIL: cannot read run $RUN_ID (gh error, auth, or not found)" >&2
    exit 3
  fi
  IFS=$'\t' read -r run_status signing_status signing_conclusion decision_status decision_conclusion <<<"$fields"

  case "$signing_status" in
    queued|in_progress)
      echo "VERDICT OK: run $RUN_ID signs the reference APKs (job $signing_status)"
      exit 0 ;;
    completed)
      case "$signing_conclusion" in
        success) echo "VERDICT OK: run $RUN_ID signed the reference APKs"; exit 0 ;;
        skipped)
          if [ "$ALLOW_SKIP" = 1 ]; then
            echo "VERDICT OK (allowed skip): post-publish dedup, assets already exist"; exit 0
          fi
          echo "VERDICT FAIL: run $RUN_ID concluded but the signing job was SKIPPED." >&2
          echo "  A green run-level conclusion does not mean every job ran (the 37921765037" >&2
          echo "  class: a skipped need skips dependents). The decision job reads" >&2
          echo "  $decision_status/$decision_conclusion; check the workflow's needs/ifs" >&2
          echo "  before re-dispatching." >&2
          exit 1 ;;
        *) echo "VERDICT FAIL: signing job concluded '$signing_conclusion' (read the job log)." >&2; exit 1 ;;
      esac ;;
    absent)
      if [ "$run_status" = "completed" ]; then
        echo "VERDICT FAIL: run completed without the signing job ever existing" >&2
        echo "  (green-run-without-signing class; decision job: $decision_status/$decision_conclusion)." >&2
        exit 1
      fi
      # run still going, job not created yet: the decision may still be pending
      ;;
  esac

  [ "$(date +%s)" -ge "$deadline" ] \
    && { echo "STILL UNDECIDED (signing: $signing_status, run: $run_status); re-invoke later" >&2; exit 2; }
  [ "$WAIT_MIN" -gt 0 ] && sleep 30
done
