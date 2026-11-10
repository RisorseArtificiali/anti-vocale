#!/usr/bin/env bash
# One command for the Play publish's gated deployment (TASK-976). The
# publish job parks on GitHub's production environment; approving it by
# hand meant remembering the pending-deployments API shape AND that the
# environment ids must be INTEGERS (a string id 422s; see the
# v1.14.1 incident note). This script reads the pending deployments for a run,
# shows them, and approves them with the ids the API itself returned.
#
# Usage: scripts/release-approve-play.sh <run-id> [--yes]
#   Without --yes it lists what WOULD be approved and exits 2 (nothing
#   sent); the approval mirrors the maintainer's release authorization,
#   so it never fires implicitly.

set -euo pipefail

RUN="${1:-}"
MODE="${2:-}"
[ -n "$RUN" ] || { echo "usage: $0 <run-id> [--yes]" >&2; exit 2; }
REPO="RisorseArtificiali/anti-vocale"

PENDING=""
if ! PENDING="$(gh api "repos/$REPO/actions/runs/$RUN/pending_deployments")"; then
  echo "FAIL: the pending-deployments probe failed (auth, network, or rate limit): never treat that as nothing pending" >&2
  exit 2
fi
if [ -z "$PENDING" ] || [ "$PENDING" = "[]" ]; then
  echo "== run $RUN has no pending deployment (already approved, rejected, or none was requested)"
  gh run view "$RUN" --json status,conclusion --jq '"run state: " + .status + "/" + (.conclusion // "pending")'
  exit 0
fi

IDS="$(printf '%s' "$PENDING" | jq -r '.[].environment.id')"
NAMES="$(printf '%s' "$PENDING" | jq -r '.[].environment.name' | paste -sd, -)"
echo "== run $RUN waits on environment(s): $NAMES (ids: $(echo "$IDS" | paste -sd, -))"

if [ "$MODE" != "--yes" ]; then
  echo "dry run: re-run with --yes to approve"
  exit 2
fi

# INTEGER ids straight from the API's own response, one --input payload;
# a string id is the documented 422 trap.
ID_JSON="$(printf '%s' "$PENDING" | jq -c '[.[].environment.id]')"
gh api -X POST "repos/$REPO/actions/runs/$RUN/pending_deployments" --input - <<EOF
{"environment_ids": $ID_JSON, "state": "approved", "comment": "publish approved via scripts/release-approve-play.sh"}
EOF
echo
echo "== approved; the publish job resumes"
